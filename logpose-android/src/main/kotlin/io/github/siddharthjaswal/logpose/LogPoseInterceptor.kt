package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.emit.TransactionEmitter
import io.github.siddharthjaswal.logpose.internal.BodyCapture
import io.github.siddharthjaswal.logpose.wire.Transaction
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
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

        // Emit a "pending" event (request only, no response) the instant the call starts,
        // so the IDE can show the in-flight request with a live timer. The completed event
        // below shares the same id and replaces it.
        if (config.emitPending) {
            emitter.emit(Transaction(id = id, startedAtMillis = startedAt, request = wireRequest))
        }

        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            emitter.emit(
                Transaction(
                    id = id,
                    startedAtMillis = startedAt,
                    request = wireRequest,
                    error = e.toString(),
                    durationMillis = elapsedMs(startNs),
                )
            )
            throw e
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
}
