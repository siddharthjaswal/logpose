package io.github.siddharthjaswal.logpose

import okhttp3.Request
import okhttp3.Response

/**
 * No-op twin of the real [BodyDecoder], so call sites that pass
 * `bodyDecoders = listOf(...)` compile unchanged against `logpose-no-op`. Release builds link the
 * stub, capture nothing, and never call a decoder — the interface exists only for API parity.
 */
interface BodyDecoder {
    fun decodeRequest(request: Request, body: ByteArray): String? = null
    fun decodeResponse(response: Response, body: ByteArray): String? = null
}
