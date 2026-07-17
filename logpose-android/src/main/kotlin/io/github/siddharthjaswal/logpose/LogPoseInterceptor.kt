package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.emit.TransactionEmitter
import io.github.siddharthjaswal.logpose.internal.BodyCapture
import io.github.siddharthjaswal.logpose.mock.LogPoseRuntime
import io.github.siddharthjaswal.logpose.mock.MockRegistry
import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.Transaction
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
    private val emitter: TransactionEmitter = LogcatEmitter(config),
) : Interceptor {

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

        // Mock short-circuit: if an active rule matches, serve it instead of hitting the
        // network. The transaction is still emitted (flagged mocked=true) so the timeline
        // never lies about what the app actually received.
        if (config.mocksEnabled) {
            val rule = MockRegistry.match(request.method, request.url.encodedPath)
            if (rule != null) return serveMock(rule, request, wireRequest, id, startedAt, startNs)
        }

        // Emit a "pending" event (request only, no response) the instant the call starts,
        // so the IDE can show the in-flight request with a live timer. The completed event
        // below shares the same id and replaces it.
        if (config.emitPending) {
            emitter.emit(Transaction(id = id, startedAtMillis = startedAt, request = wireRequest))
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
                )
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
            )
        )
        return response
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    /**
     * Serves [rule] without touching the network: applies its latency, then either returns a
     * synthetic [Response] or throws the exception its behavior calls for. Emits a
     * `mocked = true` transaction (or an error-shaped one) so the exchange still shows up in
     * the IDE, clearly flagged as mocked.
     */
    private fun serveMock(
        rule: MockRule,
        request: okhttp3.Request,
        wireRequest: WireRequest,
        id: String,
        startedAt: Long,
        startNs: Long,
    ): Response {
        MockRegistry.recordServe(rule)
        if (rule.latencyMillis > 0) {
            runCatching { Thread.sleep(rule.latencyMillis) }
                .onFailure { Thread.currentThread().interrupt() }
        }

        // Failure behaviors: emit an error-shaped transaction (mocked) and throw, exactly as a
        // real network failure would surface to the caller.
        val failure: IOException? = when (rule.behavior) {
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
                )
            )
            throw failure
        }

        val bodyText = rule.body ?: ""
        val mediaType = rule.contentType.toMediaTypeOrNull()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(rule.status)
            .message(httpReason(rule.status))
            .apply { rule.headers.forEach { (k, v) -> header(k, v) } }
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
            )
        )
        return response
    }

    private fun httpReason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        409 -> "Conflict"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        504 -> "Gateway Timeout"; else -> "Mock"
    }
}
