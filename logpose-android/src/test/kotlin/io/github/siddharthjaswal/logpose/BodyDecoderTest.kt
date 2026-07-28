package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.internal.BodyCapture
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body-decoder hook (issue #4): teams with encrypted or custom-binary payloads plug in a
 * decoder so the inspector shows readable text instead of ciphertext. These pin the contract a
 * decoder author relies on — first-non-null wins, raw fallback, and one bad decoder can't take
 * down capture.
 */
class BodyDecoderTest {

    // A trivially reversible "cipher" so the test needs no crypto — XOR each byte with a constant.
    private fun encrypt(s: String) = s.toByteArray(Charsets.UTF_8).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    private fun decrypt(b: ByteArray) = b.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun response(bytes: ByteArray, contentType: String, url: String = "https://ex.com/secure/orders"): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()

    private fun config(vararg decoders: BodyDecoder) = LogPoseConfig(enabled = true, bodyDecoders = decoders.toList())

    @Test fun `decoder turns ciphertext into readable text and flags it as decoded`() {
        val decoder = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String? =
                if (response.request.url.encodedPath.startsWith("/secure/")) decrypt(body) else null
        }
        // octet-stream would normally be summarised as "(binary body)"; the decoder runs first.
        val body = BodyCapture.captureResponse(
            response(encrypt("""{"ok":true}"""), "application/octet-stream"),
            config(decoder),
        )!!
        assertEquals("""{"ok":true}""", body.text)
        assertTrue("a decoded body must be flagged so the UI can mark it", body.decoded)
    }

    @Test fun `a decoder that returns null leaves the body raw`() {
        val skip = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String? = null
        }
        val body = BodyCapture.captureResponse(response("plain text".toByteArray(), "text/plain"), config(skip))!!
        assertEquals("plain text", body.text)
        assertFalse(body.decoded)
    }

    @Test fun `the first non-null decoder wins`() {
        val passes = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String? = null
        }
        val decodes = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String = "from second"
        }
        val never = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String = "from third"
        }
        val body = BodyCapture.captureResponse(response(encrypt("x"), "application/octet-stream"), config(passes, decodes, never))!!
        assertEquals("from second", body.text)
    }

    @Test fun `a throwing decoder is skipped, not fatal`() {
        val boom = object : BodyDecoder {
            override fun decodeResponse(response: Response, body: ByteArray): String = throw IllegalStateException("bad key")
        }
        val body = BodyCapture.captureResponse(response("plain text".toByteArray(), "text/plain"), config(boom))!!
        assertEquals("plain text", body.text)
        assertFalse(body.decoded)
    }

    @Test fun `no decoders keeps the existing raw behaviour`() {
        val body = BodyCapture.captureResponse(response("""{"a":1}""".toByteArray(), "application/json"), LogPoseConfig(enabled = true))!!
        assertEquals("""{"a":1}""", body.text)
        assertFalse(body.decoded)
    }
}
