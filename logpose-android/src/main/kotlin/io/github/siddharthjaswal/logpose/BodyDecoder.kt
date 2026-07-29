package io.github.siddharthjaswal.logpose

import okhttp3.Request
import okhttp3.Response

/**
 * Turns a raw request/response body into human-readable text for the LogPose inspector — the hook
 * for payloads LogPose can't read on its own, such as an encrypted or custom-binary body.
 *
 * Register one or more on [LogPoseConfig.bodyDecoders]. For each body LogPose walks the list and
 * uses the **first non-null** result as the displayed text (flagged as decoded, so the UI can show
 * it was transformed rather than sent in the clear). Returning `null` means "not mine" — LogPose
 * falls back to the next decoder, and finally to the raw body, so a decoder can never break the
 * calls it doesn't handle.
 *
 * Both methods default to `null`; override only the direction you need. `body` is the raw bytes
 * as LogPose captured them (already gunzipped for responses). Use [Request]/[Response] to scope
 * decoding to the endpoints that actually need it:
 *
 * ```kotlin
 * class EncryptedJsonDecoder(private val decrypt: (ByteArray) -> ByteArray) : BodyDecoder {
 *     override fun decodeResponse(response: Response, body: ByteArray): String? {
 *         if (!response.request.url.encodedPath.startsWith("/secure/")) return null
 *         return runCatching { decrypt(body).toString(Charsets.UTF_8) }.getOrNull()
 *     }
 * }
 *
 * LogPoseInterceptor(
 *     LogPoseConfig(
 *         enabled = BuildConfig.DEBUG,
 *         bodyDecoders = listOf(EncryptedJsonDecoder(::decryptAes)),
 *     )
 * )
 * ```
 *
 * A decoder runs only in builds that carry the real interceptor (the release `no-op` ships none),
 * but note that a decoded body is **plaintext leaving the device** — into the IDE, and into any
 * connected coding agent. Keep decryption keys and logic on the debug path, as you already do.
 */
interface BodyDecoder {
    /** Decoded text for a request body, or `null` to leave it to the next decoder / the raw body. */
    fun decodeRequest(request: Request, body: ByteArray): String? = null

    /** Decoded text for a response body, or `null` to leave it to the next decoder / the raw body. */
    fun decodeResponse(response: Response, body: ByteArray): String? = null
}
