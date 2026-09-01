package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.export.ExportBuffer
import io.github.siddharthjaswal.logpose.mock.MockRegistry
import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The privacy half of query redaction, checked where it matters: on the emitted wire line.
 * `?api_key=…` in logcat is the same leak as an unredacted `Authorization` header — the capture
 * gets pasted into tickets and handed to coding agents — so these read the actual lines off
 * [ExportBuffer] (the same strings that go to logcat) rather than any in-memory model.
 *
 * The other half is that redaction must stay emission-only: mock rules match on the request's
 * REAL query values (`request.url`), never the redacted string, or a rule pinned to a key would
 * silently stop matching the moment redaction ships.
 */
class QueryRedactionWireTest {

    private val secret = "sk_live_51H8aVq"
    private val config = LogPoseConfig(enabled = true, emitPending = false, exportEnabled = true)
    private val interceptor = LogPoseInterceptor(config, LogcatEmitter(config))

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun reset() {
        MockRegistry.reset()
        ExportBuffer.clear()
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()

    private fun ok(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("{}".toResponseBody())
        .build()

    @Test fun `the emitted wire line masks the secret and keeps the rest of the query`() {
        val req = request("https://api.ex.com/v1/orders?api_key=$secret&page=2")

        interceptor.intercept(FakeChain(req) { ok(it) })

        val lines = ExportBuffer.snapshot()
        assertTrue("no wire line captured", lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse("secret leaked onto the wire: $line", line.contains(secret))
            assertTrue("param name must stay visible: $line", line.contains("api_key=██"))
            assertTrue("benign params must survive: $line", line.contains("page=2"))
        }
    }

    @Test fun `an insensitive query goes to the wire byte-for-byte`() {
        interceptor.intercept(FakeChain(request("https://api.ex.com/search?q=shoes&page=2")) { ok(it) })

        val line = ExportBuffer.snapshot().single()
        assertTrue(line.contains("https://api.ex.com/search?q=shoes&page=2"))
    }

    @Test fun `mock matchQuery still matches the real value redaction hides`() {
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(
                    MockRule(
                        id = "r1", method = "GET", pathPattern = "/v1/orders",
                        matchQuery = mapOf("api_key" to secret),
                        status = 418, body = """{"mock":true}""",
                    )
                ),
            )
        )

        val response = interceptor.intercept(
            FakeChain(request("https://api.ex.com/v1/orders?api_key=$secret")) {
                throw AssertionError("rule must match on the real value — network was hit")
            }
        )

        assertEquals("rule keyed on the real value must still serve", 418, response.code)
        ExportBuffer.snapshot().forEach { line ->
            assertFalse("mocked serve still must not leak the secret: $line", line.contains(secret))
            assertTrue(line.contains("api_key=██"))
        }
    }

    // ---- the FCM registration token: a credential whose display use is a prefix ----------------

    @Test fun `logFcmToken emits only a recognizable prefix by default`() {
        val token = "fXk93jQm4RtP:APA91bFakeFakeFakeFakeFakeFake"
        LogPose.logFcmToken(token, config)

        val line = ExportBuffer.snapshot().single()
        assertFalse("full registration token leaked: $line", line.contains(token))
        assertTrue("the prefix is what 'token refreshed' needs", line.contains("fXk93jQm4RtP…"))
    }

    @Test fun `redactFcmToken=false emits the whole token for the copy-it-out workflow`() {
        val token = "fXk93jQm4RtP:APA91bFakeFakeFakeFakeFakeFake"
        LogPose.logFcmToken(token, config.copy(redactFcmToken = false))

        assertTrue(ExportBuffer.snapshot().single().contains(token))
    }

    /** Minimal [Interceptor.Chain]: serves [request], delegates proceed() to [onProceed]. */
    private class FakeChain(
        private val request: Request,
        private val onProceed: (Request) -> Response,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = onProceed(request)
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
