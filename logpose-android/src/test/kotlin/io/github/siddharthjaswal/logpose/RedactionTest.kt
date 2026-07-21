package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.internal.BodyCapture
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Redaction is the one LogPose behaviour whose failure escapes the debug session: captures get
 * pasted into tickets and handed to coding agents. These pin the headers that must never come
 * through in the clear.
 *
 * The `API-KEY` case is not hypothetical — it leaked in full from a real capture while
 * `Authorization` beside it was correctly masked, because the default list was four exact names.
 */
class RedactionTest {

    private val masked = "██"

    private fun redact(vararg pairs: Pair<String, String>): Map<String, String> {
        val builder = Headers.Builder()
        pairs.forEach { (name, value) -> builder.add(name, value) }
        return BodyCapture.headersToMap(builder.build(), LogPoseConfig())
    }

    @Test
    fun `redacts credential headers beyond Authorization`() {
        val out = redact(
            "Authorization" to "Bearer abc",
            "API-KEY" to "sgXeeoxo8dQ_YXHtSPofrmGosi8TKOYWmsixUhf1uPU58ZFhG5u3U4x8",
            "X-Auth-Token" to "t0ken",
            "Cookie" to "session=1",
        )
        out.forEach { (name, value) -> assertEquals("$name leaked", masked, value) }
    }

    @Test
    fun `redacts vendor headers the exact list cannot enumerate`() {
        // Nobody will ever finish enumerating these, which is why patterns exist.
        val out = redact(
            "X-Shopify-Access-Token" to "shpat_x",
            "X-Some-Vendor-Secret" to "s3cret",
            "Weird-Api-Key-Header" to "k",
        )
        out.forEach { (name, value) -> assertEquals("$name leaked", masked, value) }
    }

    @Test
    fun `case does not matter`() {
        val out = redact("aUtHoRiZaTiOn" to "Bearer abc", "x-api-key" to "k")
        out.forEach { (name, value) -> assertEquals("$name leaked", masked, value) }
    }

    @Test
    fun `leaves ordinary headers alone`() {
        // Over-redaction has a real cost too: these are what you actually read a capture for.
        val out = redact(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "gandalf/1.0",
            "X-Request-Id" to "req-42",
        )
        assertEquals("application/json", out["Content-Type"])
        assertEquals("application/json", out["Accept"])
        assertEquals("gandalf/1.0", out["User-Agent"])
        assertEquals("req-42", out["X-Request-Id"])
    }

    @Test
    fun `custom names extend the defaults instead of replacing them`() {
        // The old API made `redactHeaders = setOf("X-Tenant-Key")` silently drop every default;
        // exposing the constant is what makes extending it the easy path.
        val config = LogPoseConfig(
            redactHeaders = LogPoseConfig.DEFAULT_REDACT_HEADERS + "X-Tenant-Key",
        )
        val out = BodyCapture.headersToMap(
            Headers.Builder()
                .add("X-Tenant-Key", "tenant")
                .add("Authorization", "Bearer abc")
                .build(),
            config,
        )
        assertEquals(masked, out["X-Tenant-Key"])
        assertEquals(masked, out["Authorization"])
    }
}
