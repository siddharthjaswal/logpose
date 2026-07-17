package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.TransactionEmitter
import io.github.siddharthjaswal.logpose.mock.MockRegistry
import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.Transaction
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/** Exercises the mock short-circuit in [LogPoseInterceptor] without a device/network. */
class MockServeTest {

    private val emitted = mutableListOf<Transaction>()
    private val emitter = TransactionEmitter { emitted.add(it) }
    private val config = LogPoseConfig(enabled = true, mocksEnabled = true, emitPending = false)
    private val interceptor = LogPoseInterceptor(config, emitter)

    @Before fun setUp() { MockRegistry.reset(); emitted.clear() }
    @After fun tearDown() = MockRegistry.reset()

    private fun request(method: String = "GET", url: String = "https://ex.com/app/v1/x"): Request =
        Request.Builder().url(url).method(method, null).build()

    @Test fun `matched rule is served without hitting the network`() {
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(
                    MockRule(
                        id = "a", method = "GET", pathPattern = "/app/v1/x",
                        status = 503, body = """{"error":"down"}""",
                        headers = mapOf("X-Mock" to "1"),
                    )
                ),
            )
        )

        val response = interceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") })

        assertEquals(503, response.code)
        assertEquals("""{"error":"down"}""", response.body?.string())
        assertEquals("1", response.header("X-Mock"))
        assertEquals(1, emitted.size)
        assertTrue("emitted transaction must be flagged mocked", emitted.single().mocked)
        assertEquals(503, emitted.single().response?.code)
    }

    @Test fun `timeout behavior throws and emits a mocked error transaction`() {
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(
                    MockRule(id = "t", method = "*", pathPattern = "/app/v1/x", behavior = MockRule.BEHAVIOR_TIMEOUT)
                ),
            )
        )

        try {
            interceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") })
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
            // expected
        }
        assertEquals(1, emitted.size)
        assertTrue(emitted.single().mocked)
        assertTrue(emitted.single().error!!.contains("timeout"))
    }

    @Test fun `unmatched request proceeds to the network`() {
        var proceeded = false
        val networkResponse = Response.Builder()
            .request(request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body("".toByteArray().toResponseBody(null))
            .build()

        val response = interceptor.intercept(FakeChain(request()) { proceeded = true; networkResponse })

        assertTrue(proceeded)
        assertEquals(200, response.code)
        assertFalse("a real network response is not mocked", emitted.single().mocked)
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
