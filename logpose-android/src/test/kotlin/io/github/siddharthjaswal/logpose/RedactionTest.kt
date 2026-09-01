package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.internal.BodyCapture
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    // ---- query parameters: the same leak, one line lower in the request ------------------------

    private fun redactUrl(url: String, config: LogPoseConfig = LogPoseConfig()): String =
        BodyCapture.redactUrl(url.toHttpUrl(), config)

    @Test
    fun `redacts credential query params by exact name, keeping the name visible`() {
        assertEquals(
            "https://ex.com/v1/orders?api_key=██&page=2",
            redactUrl("https://ex.com/v1/orders?api_key=sk_live_51H8&page=2"),
        )
    }

    @Test
    fun `redacts vendor query params the exact list cannot enumerate`() {
        // Same reason header patterns exist: every vendor invents its own credential param.
        assertEquals(
            "https://ex.com/dl?shop_token=██&x-goog-signature=██&file=a.png",
            redactUrl("https://ex.com/dl?shop_token=shpat_9&x-goog-signature=YWJj&file=a.png"),
        )
    }

    @Test
    fun `query param case does not matter`() {
        assertEquals(
            "https://ex.com/a?API_KEY=██&Token=██",
            redactUrl("https://ex.com/a?API_KEY=k&Token=t"),
        )
    }

    @Test
    fun `leaves an ordinary query string alone`() {
        val url = "https://ex.com/search?q=shoes&page=2&sort=price_asc"
        assertEquals(url, redactUrl(url))
    }

    @Test
    fun `a url with no query comes through untouched`() {
        val url = "https://ex.com/v1/orders"
        assertEquals(url, redactUrl(url))
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
