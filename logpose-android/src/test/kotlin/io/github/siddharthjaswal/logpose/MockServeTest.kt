package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.EventEmitter
import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.mock.MockRegistry
import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.MockStep
import io.github.siddharthjaswal.logpose.wire.Transaction
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/** Exercises the mock short-circuit in [LogPoseInterceptor] without a device/network. */
class MockServeTest {

    private val wireJson = Json { ignoreUnknownKeys = true }

    private val envelopes = mutableListOf<Envelope>()
    // Decoding the payload back out of the envelope keeps these assertions readable and
    // doubles as a round-trip check that HTTP still survives the wrapping.
    private val emitted: List<Transaction>
        get() = envelopes.map { wireJson.decodeFromJsonElement(Transaction.serializer(), it.payload) }
    private val emitter = EventEmitter { envelopes.add(it) }
    private val config = LogPoseConfig(enabled = true, mocksEnabled = true, emitPending = false)
    private val interceptor = LogPoseInterceptor(config, emitter)

    @Before fun setUp() { MockRegistry.reset(); envelopes.clear() }
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

    @Test fun `patch mode deep-merges into the real response`() {
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(
                    MockRule(
                        id = "p", method = "GET", pathPattern = "/app/v1/x",
                        mode = MockRule.MODE_PATCH,
                        body = """{"status":5,"nested":{"b":2},"added":true}""",
                    )
                ),
            )
        )
        val realBody = """{"status":3,"name":"Vikram","nested":{"a":1}}"""
        val networkResponse = Response.Builder()
            .request(request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body(realBody.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = interceptor.intercept(FakeChain(request()) { networkResponse })

        val out = Json.parseToJsonElement(response.body!!.string()).jsonObject
        assertEquals(5, out["status"]!!.jsonPrimitive.int)          // overridden
        assertEquals("Vikram", out["name"]!!.jsonPrimitive.content) // kept from backend
        assertTrue(out["added"]!!.jsonPrimitive.boolean)            // new key added
        assertEquals(1, out["nested"]!!.jsonObject["a"]!!.jsonPrimitive.int) // nested kept
        assertEquals(2, out["nested"]!!.jsonObject["b"]!!.jsonPrimitive.int) // nested merged
        assertTrue(emitted.single().mocked)
    }

    @Test fun `patch merges array elements by index without replacing the array`() {
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(
                    MockRule(
                        id = "arr", method = "GET", pathPattern = "/app/v1/x",
                        mode = MockRule.MODE_PATCH,
                        body = """{"data":[{"status":5}]}""", // override status in data[0] only
                    )
                ),
            )
        )
        val realBody = """{"data":[{"status":3,"order_id":21047836},{"status":1}]}"""
        val networkResponse = Response.Builder()
            .request(request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body(realBody.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

        val out = Json.parseToJsonElement(
            interceptor.intercept(FakeChain(request()) { networkResponse }).body!!.string()
        ).jsonObject
        val data = out["data"]!!.let { (it as kotlinx.serialization.json.JsonArray) }
        assertEquals(2, data.size)                                              // array not truncated
        assertEquals(5, data[0].jsonObject["status"]!!.jsonPrimitive.int)       // element 0 overridden
        assertEquals(21047836, data[0].jsonObject["order_id"]!!.jsonPrimitive.int) // sibling kept
        assertEquals(1, data[1].jsonObject["status"]!!.jsonPrimitive.int)       // element 1 untouched
    }

    @Test fun `a mocked serve shows the request in flight before the final row`() {
        // Without a visible pending row, a slow mock (how you reproduce a timeout-during-X race)
        // would have no in-flight window on the timeline — only the finished row.
        MockRegistry.apply(
            MockRuleSet(
                revision = 1,
                rules = listOf(MockRule(id = "a", method = "GET", pathPattern = "/app/v1/x", status = 200, body = "{}")),
            )
        )
        val pendingInterceptor = LogPoseInterceptor(config.copy(emitPending = true), emitter)

        pendingInterceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") })

        assertEquals(2, emitted.size)
        assertEquals("both rows are the same request", emitted[0].id, emitted[1].id)
        assertEquals("first row is in-flight (no response yet)", null, emitted[0].response)
        assertEquals(200, emitted[1].response?.code)
        assertTrue(emitted[1].mocked)
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

    // ---- match constraints + response sequences ----------------------------------------------

    private fun apply(vararg rules: MockRule) =
        MockRegistry.apply(MockRuleSet(revision = 1, rules = rules.toList()))

    private fun jsonBody(text: String): RequestBody =
        text.toRequestBody("application/json".toMediaTypeOrNull())

    /** A body OkHttp may only read once — writing it here would corrupt the real request. */
    private fun oneShotBody(text: String): RequestBody = object : RequestBody() {
        override fun contentType() = "application/json".toMediaTypeOrNull()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) { sink.writeUtf8(text) }
    }

    private fun post(body: RequestBody, url: String = "https://ex.com/app/v1/x"): Request =
        Request.Builder().url(url).post(body).build()

    @Test fun `a sequence serves each step in turn and then sticks on the last`() {
        // The retry test in one rule: fail, fail, then succeed for good.
        apply(
            MockRule(
                id = "seq", method = "*", pathPattern = "/app/v1/x",
                responses = listOf(
                    MockStep(status = 500),
                    MockStep(status = 503, body = """{"retry":true}"""),
                    MockStep(status = 200, body = """{"ok":true}"""),
                ),
            )
        )
        val codes = (1..4).map {
            interceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") }).code
        }
        assertEquals(listOf(500, 503, 200, 200), codes)
        assertEquals("""{"retry":true}""", emitted[1].response?.body?.text)
        assertTrue("every step is still a mocked serve", emitted.all { it.mocked })
    }

    @Test fun `a step's own latency and behavior apply, not the rule's`() {
        apply(
            MockRule(
                id = "seq", method = "*", pathPattern = "/app/v1/x",
                status = 200, body = """{"ignored":true}""",
                responses = listOf(
                    MockStep(status = 599, behavior = MockRule.BEHAVIOR_TIMEOUT),
                    MockStep(status = 201, body = """{"created":true}""", contentType = "application/json"),
                ),
            )
        )

        try {
            interceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") })
            fail("expected the first step to time out")
        } catch (_: SocketTimeoutException) {
            // expected
        }
        val second = interceptor.intercept(FakeChain(request()) { fail("network must not be hit"); error("") })

        assertEquals(201, second.code)
        assertEquals("""{"created":true}""", second.body?.string())
        assertEquals("the rule-level response is ignored once a sequence exists", 2, emitted.size)
    }

    @Test fun `patch mode applies each step's body as that call's patch`() {
        apply(
            MockRule(
                id = "p", method = "GET", pathPattern = "/app/v1/x", mode = MockRule.MODE_PATCH,
                responses = listOf(
                    MockStep(body = """{"status":5}"""),
                    MockStep(body = """{"status":9,"note":"second"}"""),
                ),
            )
        )
        val real = { ->
            Response.Builder()
                .request(request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":1,"name":"Vikram"}""".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val first = Json.parseToJsonElement(
            interceptor.intercept(FakeChain(request()) { real() }).body!!.string()
        ).jsonObject
        val second = Json.parseToJsonElement(
            interceptor.intercept(FakeChain(request()) { real() }).body!!.string()
        ).jsonObject

        assertEquals(5, first["status"]!!.jsonPrimitive.int)
        assertNull("the first patch adds nothing it wasn't given", first["note"])
        assertEquals(9, second["status"]!!.jsonPrimitive.int)
        assertEquals("second", second["note"]!!.jsonPrimitive.content)
        assertEquals("the backend's own fields survive both patches", "Vikram", second["name"]!!.jsonPrimitive.content)
    }

    @Test fun `a body matcher serves only the request that carries the text`() {
        apply(
            MockRule(
                id = "b", method = "POST", pathPattern = "/app/v1/x",
                status = 409, body = """{"error":"expired"}""",
                matchBodyContains = "\"reason\":\"EXPIRED\"",
            )
        )

        val matched = interceptor.intercept(
            FakeChain(post(jsonBody("""{"reason":"EXPIRED"}"""))) { fail("network must not be hit"); error("") }
        )
        assertEquals(409, matched.code)

        var proceeded = false
        val other = post(jsonBody("""{"reason":"CANCELLED"}"""))
        interceptor.intercept(FakeChain(other) { proceeded = true; ok(other) })
        assertTrue("a request the rule doesn't describe must reach the network", proceeded)
    }

    @Test fun `a streaming body is never read twice - body matching fails closed`() {
        // Reading a one-shot body to match it would corrupt the request OkHttp is about to send.
        // So it isn't read, the rule can't match, and the call goes to the network as written.
        apply(
            MockRule(
                id = "b", method = "POST", pathPattern = "/app/v1/x",
                matchBodyContains = "reason",
            )
        )
        val streaming = post(oneShotBody("""{"reason":"EXPIRED"}"""))

        var sent: Request? = null
        interceptor.intercept(FakeChain(streaming) { sent = it; ok(streaming) })

        assertEquals("the request must reach the network untouched", streaming, sent)
        assertFalse("nothing may be served off an unread body", emitted.single().mocked)
        // And the body OkHttp will write is still there to write.
        assertEquals(
            """{"reason":"EXPIRED"}""",
            Buffer().also { streaming.body!!.writeTo(it) }.readUtf8(),
        )
    }

    @Test fun `query and header matchers narrow a rule to one call`() {
        apply(
            MockRule(
                id = "q", method = "GET", pathPattern = "/app/v1/x", status = 402,
                matchQuery = mapOf("city_id" to "79096"),
                matchHeaders = mapOf("X-App-Version" to MockRule.MATCH_ANY),
            )
        )

        val matching = Request.Builder()
            .url("https://ex.com/app/v1/x?city_id=79096&debug=1")
            .header("x-app-version", "9.1.0")
            .build()
        assertEquals(402, interceptor.intercept(FakeChain(matching) { fail("network"); error("") }).code)

        val wrongCity = Request.Builder()
            .url("https://ex.com/app/v1/x?city_id=12")
            .header("X-App-Version", "9.1.0")
            .build()
        var proceeded = false
        interceptor.intercept(FakeChain(wrongCity) { proceeded = true; ok(wrongCity) })
        assertTrue(proceeded)

        val noHeader = Request.Builder().url("https://ex.com/app/v1/x?city_id=79096").build()
        var proceededAgain = false
        interceptor.intercept(FakeChain(noHeader) { proceededAgain = true; ok(noHeader) })
        assertTrue(proceededAgain)
    }

    @Test fun `a header matcher sees the real value, not the redacted one`() {
        // Emission redacts Authorization; matching must not, or no rule could ever key on a token.
        apply(
            MockRule(
                id = "h", method = "GET", pathPattern = "/app/v1/x", status = 401,
                matchHeaders = mapOf("Authorization" to "Bearer test-token"),
            )
        )
        val authed = Request.Builder()
            .url("https://ex.com/app/v1/x").header("Authorization", "Bearer test-token").build()

        assertEquals(401, interceptor.intercept(FakeChain(authed) { fail("network"); error("") }).code)
        assertEquals("…and the emitted row still hides it", "██", emitted.single().request.headers["Authorization"])
    }

    // ---- trace resolution -------------------------------------------------------------------

    private fun ok(req: Request = request()): Response = Response.Builder()
        .request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
        .body("".toByteArray().toResponseBody(null)).build()

    @Test fun `interceptor files the row under a LogPoseTrace tag on the request`() {
        val tagged = request().newBuilder().logPoseTrace("trace-xyz").build()
        interceptor.intercept(FakeChain(tagged) { ok(tagged) })
        assertEquals("trace-xyz", envelopes.single().traceId)
    }

    @Test fun `interceptor falls back to the ambient trace for a synchronous call`() {
        LogPose.withTrace("amb-1") { interceptor.intercept(FakeChain(request()) { ok() }) }
        assertEquals("amb-1", envelopes.single().traceId)
    }

    @Test fun `an explicit request tag wins over the ambient trace`() {
        val tagged = request().newBuilder().logPoseTrace("explicit").build()
        LogPose.withTrace("ambient") { interceptor.intercept(FakeChain(tagged) { ok(tagged) }) }
        assertEquals("explicit", envelopes.single().traceId)
    }

    @Test fun `untraced call carries no trace`() {
        interceptor.intercept(FakeChain(request()) { ok() })
        assertNull(envelopes.single().traceId)
    }

    @Test fun `traceCalls tags new requests with the ambient trace, but never overwrites an explicit one`() {
        var seen: Request? = null
        val client = OkHttpClient()
        val delegate = object : Call.Factory {
            override fun newCall(request: Request): Call { seen = request; return client.newCall(request) }
        }
        val factory = LogPose.traceCalls(delegate)

        LogPose.withTrace("ambient") { factory.newCall(request()) }
        assertEquals("ambient", seen!!.tag(LogPoseTrace::class.java)?.traceId)

        LogPose.withTrace("ambient") { factory.newCall(request().newBuilder().logPoseTrace("explicit").build()) }
        assertEquals("explicit", seen!!.tag(LogPoseTrace::class.java)?.traceId)

        factory.newCall(request()) // no trace in scope
        assertNull(seen!!.tag(LogPoseTrace::class.java))
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
