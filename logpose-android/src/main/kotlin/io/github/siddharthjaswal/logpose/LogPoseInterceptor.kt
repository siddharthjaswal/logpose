package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.emit.EventEmitter
import io.github.siddharthjaswal.logpose.emit.emit
import io.github.siddharthjaswal.logpose.internal.BodyCapture
import io.github.siddharthjaswal.logpose.mock.LogPoseRuntime
import io.github.siddharthjaswal.logpose.mock.MockRegistry
import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import io.github.siddharthjaswal.logpose.wire.Request as WireRequest
import io.github.siddharthjaswal.logpose.wire.Response as WireResponse

/**
 * Drop-in OkHttp interceptor that emits one structured [Transaction] per HTTP
 * exchange for the LogPose IDE plugin to render.
 *
 * Add it as the LAST application interceptor (so it sees the final request and the
 * decoded response):
 *
 * ```kotlin
 * OkHttpClient.Builder()
 *     .addInterceptor(LogPoseInterceptor(LogPoseConfig(enabled = BuildConfig.DEBUG)))
 *     .build()
 * ```
 *
 * Unlike OkHttp's `HttpLoggingInterceptor`, this builds the whole exchange in memory
 * and emits it atomically, so concurrent requests never interleave or mismatch
 * their bodies.
 */
class LogPoseInterceptor @JvmOverloads constructor(
    private val config: LogPoseConfig = LogPoseConfig(),
    private val emitter: EventEmitter = LogcatEmitter(config),
) : Interceptor {

    private val patchJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!config.enabled) return chain.proceed(request)

        // Announce this process to the IDE (package + current mock revision) so it can target
        // the mock broadcast and detect a wiped registry after a restart.
        LogPoseRuntime.emitHelloOnFirstIntercept(config)

        val id = UUID.randomUUID().toString().substring(0, 8)
        val startedAt = System.currentTimeMillis()
        val startNs = System.nanoTime()

        val wireRequest = WireRequest(
            method = request.method,
            url = request.url.toString(),
            host = request.url.host,
            path = request.url.encodedPath,
            headers = BodyCapture.headersToMap(request.headers, config),
            body = runCatching { BodyCapture.captureRequest(request, config) }.getOrNull(),
        )

        // The trace to file this row under: a LogPoseTrace tag on the request (set at call time,
        // it survives the hop to this OkHttp thread) wins; otherwise the ambient trace, which is
        // only set here for a synchronous call made inside withTrace on this same thread.
        val traceId = request.tag(LogPoseTrace::class.java)?.traceId ?: LogPose.currentTraceId()

        // Mock short-circuit: if an active rule matches, serve it instead of hitting the
        // network (replace mode), or let the real response through and patch it (patch mode).
        // Either way the transaction is emitted flagged mocked=true, so the timeline never
        // lies about what the app actually received.
        if (config.mocksEnabled && MockRegistry.hasRules) {
            val rule = MockRegistry.match(
                method = request.method,
                path = request.url.encodedPath,
                query = request.url.queryParameterNames
                    .associateWith { request.url.queryParameter(it).orEmpty() },
                headers = rawHeaders(request),
                // Body matching reuses the copy BodyCapture already buffered — reading the body a
                // second time would break a one-shot/streaming request. Such a body is never
                // buffered (it comes back as a placeholder with an unknown size), so it stays null
                // here and MockRegistry fails the match closed rather than guessing.
                body = if (MockRegistry.needsBody) {
                    wireRequest.body?.takeIf { it.sizeBytes >= 0 }?.text
                } else null,
            )
            if (rule != null) {
                return if (rule.mode == MockRule.MODE_PATCH)
                    servePatch(rule, chain, request, wireRequest, id, startedAt, startNs, traceId)
                else
                    serveMock(rule, request, wireRequest, id, startedAt, startNs, traceId)
            }
        }

        // Emit a "pending" event (request only, no response) the instant the call starts,
        // so the IDE can show the in-flight request with a live timer. The completed event
        // below shares the same id and replaces it.
        if (config.emitPending) {
            emitter.emit(Transaction(id = id, startedAtMillis = startedAt, request = wireRequest), traceId)
        }

        // Catch *any* failure, not just IOException: a downstream interceptor (auth, error
        // mapping) can throw a RuntimeException after OkHttp produced a response, and OkHttp's
        // own connection failures surface as IOException. Either way we emit the error-shaped
        // transaction (so it never stays stuck "pending") and rethrow unchanged.
        val response: Response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            emitter.emit(
                Transaction(
                    id = id,
                    startedAtMillis = startedAt,
                    request = wireRequest,
                    error = t.toString(),
                    durationMillis = elapsedMs(startNs),
                ),
                traceId,
            )
            throw t
        }

        emitter.emit(
            Transaction(
                id = id,
                startedAtMillis = startedAt,
                durationMillis = elapsedMs(startNs),
                request = wireRequest,
                response = WireResponse(
                    code = response.code,
                    message = response.message,
                    headers = BodyCapture.headersToMap(response.headers, config),
                    body = runCatching { BodyCapture.captureResponse(response, config) }.getOrNull(),
                ),
            ),
            traceId,
        )
        return response
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    /**
     * Request headers as sent, for rule matching — deliberately **not**
     * [BodyCapture.headersToMap], whose values are redacted for emission: a rule matching on an
     * `Authorization` value has to see the real one. These never leave the process.
     */
    private fun rawHeaders(request: okhttp3.Request): Map<String, String> {
        val headers = request.headers
        val out = LinkedHashMap<String, String>(headers.size)
        for (i in 0 until headers.size) out[headers.name(i)] = headers.value(i)
        return out
    }

    /**
     * What a matched rule actually serves on this hit: its own response fields, or — when it
     * carries a [MockRule.responses] sequence — the step for the current hit count. Resolved
     * **before** the serve is recorded, so hit 0 gets step 0.
     */
    private class Served(
        val status: Int,
        val body: String?,
        val headers: Map<String, String>,
        val contentType: String,
        val latencyMillis: Long,
        val behavior: String,
    )

    private fun served(rule: MockRule): Served {
        val step = MockRegistry.stepFor(rule)
            ?: return Served(
                rule.status, rule.body, rule.headers,
                rule.contentType, rule.latencyMillis, rule.behavior,
            )
        return Served(
            step.status, step.body, step.headers,
            step.contentType, step.latencyMillis, step.behavior,
        )
    }

    /**
     * Serves [rule] without touching the network: applies its latency, then either returns a
     * synthetic [Response] or throws the exception its behavior calls for. Emits a
     * `mocked = true` transaction (or an error-shaped one) so the exchange still shows up in
     * the IDE, clearly flagged as mocked.
     *
     * What it serves comes from [served] — the rule's own fields, or the step this hit has
     * reached in its [MockRule.responses] sequence.
     */
    private fun serveMock(
        rule: MockRule,
        request: okhttp3.Request,
        wireRequest: WireRequest,
        id: String,
        startedAt: Long,
        startNs: Long,
        traceId: String?,
    ): Response {
        val serve = served(rule)
        MockRegistry.recordServe(rule)
        // Show the request in flight while its latency plays out — otherwise a slow mock (the way
        // you reproduce a timeout-during-X race) has no visible in-flight window, only a final row.
        if (config.emitPending) {
            emitter.emit(Transaction(id = id, startedAtMillis = startedAt, request = wireRequest), traceId)
        }
        if (serve.latencyMillis > 0) {
            runCatching { Thread.sleep(serve.latencyMillis) }
                .onFailure { Thread.currentThread().interrupt() }
        }

        // Failure behaviors: emit an error-shaped transaction (mocked) and throw, exactly as a
        // real network failure would surface to the caller.
        val failure: IOException? = when (serve.behavior) {
            MockRule.BEHAVIOR_TIMEOUT -> SocketTimeoutException("LogPose mock: simulated timeout")
            MockRule.BEHAVIOR_CONNECTION_FAILURE -> ConnectException("LogPose mock: simulated connection failure")
            else -> null
        }
        if (failure != null) {
            emitter.emit(
                Transaction(
                    id = id,
                    startedAtMillis = startedAt,
                    request = wireRequest,
                    error = failure.toString(),
                    durationMillis = elapsedMs(startNs),
                    mocked = true,
                ),
                traceId,
            )
            throw failure
        }

        val bodyText = serve.body ?: ""
        val mediaType = serve.contentType.toMediaTypeOrNull()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(serve.status)
            .message(httpReason(serve.status))
            .apply { serve.headers.forEach { (k, v) -> header(k, v) } }
            .body(bodyText.toByteArray(Charsets.UTF_8).toResponseBody(mediaType))
            .build()

        emitter.emit(
            Transaction(
                id = id,
                startedAtMillis = startedAt,
                durationMillis = elapsedMs(startNs),
                request = wireRequest,
                response = WireResponse(
                    code = response.code,
                    message = response.message,
                    headers = BodyCapture.headersToMap(response.headers, config),
                    body = runCatching { BodyCapture.captureResponse(response, config) }.getOrNull(),
                ),
                mocked = true,
            ),
            traceId,
        )
        return response
    }

    /**
     * Patch mode: hit the real network, then deep-merge the rule's JSON [MockRule.body] into
     * the response body — override existing keys, add new ones, keep everything else the
     * backend sent. Non-JSON responses (or an unparseable patch) pass through unchanged.
     *
     * With a [MockRule.responses] sequence, each step's body is the patch for that hit — so a
     * field can change per call while the rest stays backend-generated.
     */
    private fun servePatch(
        rule: MockRule,
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        wireRequest: WireRequest,
        id: String,
        startedAt: Long,
        startNs: Long,
        traceId: String?,
    ): Response {
        val serve = served(rule)
        MockRegistry.recordServe(rule)
        if (config.emitPending) {
            emitter.emit(Transaction(id = id, startedAtMillis = startedAt, request = wireRequest), traceId)
        }
        if (serve.latencyMillis > 0) {
            runCatching { Thread.sleep(serve.latencyMillis) }
                .onFailure { Thread.currentThread().interrupt() }
        }

        val real: Response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            emitter.emit(
                Transaction(
                    id = id, startedAtMillis = startedAt, request = wireRequest,
                    error = t.toString(), durationMillis = elapsedMs(startNs), mocked = true,
                ),
                traceId,
            )
            throw t
        }

        val realBody = real.body
        val mediaType = realBody?.contentType()
        val original = runCatching { realBody?.string() ?: "" }.getOrDefault("")
        val mergedText = runCatching {
            val base = patchJson.parseToJsonElement(original)
            val patch = patchJson.parseToJsonElement(serve.body ?: "{}")
            patchJson.encodeToString(JsonElement.serializer(), mergeJson(base, patch))
        }.getOrDefault(original) // not JSON / bad patch → leave the real body untouched

        val response = real.newBuilder()
            // Body length/encoding change, so drop the stale framing headers.
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(mergedText.toByteArray(Charsets.UTF_8).toResponseBody(mediaType))
            .build()

        emitter.emit(
            Transaction(
                id = id,
                startedAtMillis = startedAt,
                durationMillis = elapsedMs(startNs),
                request = wireRequest,
                response = WireResponse(
                    code = response.code,
                    message = response.message,
                    headers = BodyCapture.headersToMap(response.headers, config),
                    body = runCatching { BodyCapture.captureResponse(response, config) }.getOrNull(),
                ),
                mocked = true,
            ),
            traceId,
        )
        return response
    }

    /**
     * Deep-merges [patch] into [base]: objects recurse by key; arrays recurse element-wise by
     * index (so you can override one field inside `data[0]` without replacing the whole array —
     * extra base elements are kept, extra patch elements appended); scalars and type-mismatches
     * replace.
     */
    private fun mergeJson(base: JsonElement, patch: JsonElement): JsonElement {
        if (base is JsonObject && patch is JsonObject) {
            val out = LinkedHashMap<String, JsonElement>(base)
            for ((key, value) in patch) {
                val current = out[key]
                out[key] = if (current != null) mergeJson(current, value) else value
            }
            return JsonObject(out)
        }
        if (base is JsonArray && patch is JsonArray) {
            val out = base.toMutableList()
            patch.forEachIndexed { i, value ->
                if (i < out.size) out[i] = mergeJson(out[i], value) else out.add(value)
            }
            return JsonArray(out)
        }
        return patch
    }

    private fun httpReason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        504 -> "Gateway Timeout"; else -> "Mock"
    }
}
