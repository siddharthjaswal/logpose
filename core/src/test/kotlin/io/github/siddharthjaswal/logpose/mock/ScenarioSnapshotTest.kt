package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Snapshotting is the one place LogPose could quietly invent data, so these pin what it refuses
 * to do: no rule without a captured body, no rule built from LogPose's own mocked response, and
 * one rule per endpoint rather than one per call.
 */
class ScenarioSnapshotTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private var seq = 0

    private fun http(
        method: String = "GET",
        path: String = "/v1/orders",
        code: Int? = 200,
        body: String? = """{"ok":true}""",
        contentType: String? = "application/json",
        mocked: Boolean = false,
        id: String = "e${seq++}",
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = 1_000L + seq,
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let {
                Response(code = it, body = body?.let { b -> Body(contentType = contentType, text = b) })
            },
            mocked = mocked,
        )
        return LogEvent.Http(
            tx,
            Envelope(kind = Envelope.KIND_HTTP, id = id, at = tx.startedAtMillis, payload = json.encodeToJsonElement(tx)),
        )
    }

    private fun analytics(id: String = "a${seq++}"): LogEvent.Generic {
        val ev = GenericEvent(title = "screen_view")
        return LogEvent.Generic(
            ev,
            Envelope(kind = Envelope.KIND_ANALYTICS, id = id, at = 1, payload = json.encodeToJsonElement(ev)),
        )
    }

    @Test
    fun `numeric path segments become wildcards so one rule serves every id`() {
        assertEquals("/app/v3/*/location", ScenarioSnapshot.normalizePath("/app/v3/79096/location"))
        assertEquals("/v1/orders/*/items/*", ScenarioSnapshot.normalizePath("/v1/orders/12/items/9"))
        assertEquals("/v1/orders", ScenarioSnapshot.normalizePath("/v1/orders?page=2"))
        // Only *all-numeric* segments — a slug that merely contains digits is a real endpoint.
        assertEquals("/v1/order-12", ScenarioSnapshot.normalizePath("/v1/order-12"))
    }

    @Test
    fun `calls to one endpoint collapse into one rule, and the latest response wins`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(
                http(path = "/v1/orders/12", body = """{"v":1}"""),
                http(path = "/v1/orders/13", body = """{"v":2}"""),
                http(path = "/v1/orders/14", body = """{"v":3}"""),
            )
        )
        assertEquals(1, result.rules.size)
        val rule = result.rules.single()
        assertEquals("/v1/orders/*", rule.pathPattern)
        assertEquals("""{"v":3}""", rule.body, "the newest capture is the state the app is in")
        assertEquals(MockRule.MODE_REPLACE, rule.mode)
    }

    @Test
    fun `method is part of the identity`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(http(method = "GET", path = "/v1/cart"), http(method = "POST", path = "/v1/cart"))
        )
        assertEquals(2, result.rules.size)
        assertEquals(setOf("GET", "POST"), result.rules.map { it.method }.toSet())
    }

    @Test
    fun `rows LogPose itself served are skipped and counted`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(http(path = "/v1/a"), http(path = "/v1/b", mocked = true))
        )
        assertEquals(listOf("/v1/a"), result.rules.map { it.pathPattern })
        assertEquals(1, result.skippedMocked)
    }

    @Test
    fun `in-flight and bodyless endpoints are skipped and reported, never invented`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(
                http(path = "/v1/pending", code = null, body = null),
                http(path = "/v1/nobody", code = 204, body = null),
                http(path = "/v1/fine"),
            )
        )
        assertEquals(listOf("/v1/fine"), result.rules.map { it.pathPattern })
        assertEquals(2, result.skippedIncomplete)
        assertTrue(result.summary().contains("skipped 2 in-flight/bodyless"), result.summary())
    }

    @Test
    fun `an endpoint that also answered is not counted as skipped`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(
                http(path = "/v1/orders", body = """{"v":1}"""),
                http(path = "/v1/orders", code = null, body = null), // a later call still in flight
            )
        )
        assertEquals(1, result.rules.size)
        assertEquals(0, result.skippedIncomplete, "the endpoint is covered; one pending call is not a hole")
    }

    @Test
    fun `the 2xx filter is off by default because error states are often the point`() {
        val events = listOf(http(path = "/v1/ok", code = 200), http(path = "/v1/bad", code = 500))

        val all = ScenarioSnapshot.fromEvents(events)
        assertEquals(2, all.rules.size)
        assertEquals(0, all.skippedNonSuccess)

        val onlySuccess = ScenarioSnapshot.fromEvents(events, successOnly = true)
        assertEquals(listOf("/v1/ok"), onlySuccess.rules.map { it.pathPattern })
        assertEquals(1, onlySuccess.skippedNonSuccess)
    }

    @Test
    fun `non-HTTP events are ignored entirely`() {
        val result = ScenarioSnapshot.fromEvents(listOf(analytics(), http(path = "/v1/x"), analytics()))
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `bodies and content type come verbatim from the capture`() {
        val result = ScenarioSnapshot.fromEvents(
            listOf(http(path = "/v1/x", code = 418, body = "not json at all", contentType = "text/plain; charset=utf-8"))
        )
        val rule = result.rules.single()
        assertEquals(418, rule.status)
        assertEquals("not json at all", rule.body)
        assertEquals("text/plain", rule.contentType, "charset params belong to the response, not the rule")
    }

    @Test
    fun `rule ids are derived from the endpoint, so re-snapshotting updates instead of stacking`() {
        val first = ScenarioSnapshot.fromEvents(listOf(http(path = "/v1/orders/1", body = """{"v":1}""")))
        val second = ScenarioSnapshot.fromEvents(listOf(http(path = "/v1/orders/2", body = """{"v":2}""")))
        assertEquals(first.rules.single().id, second.rules.single().id)
        assertNotNull(first.rules.single().id.takeIf { it.isNotBlank() })
    }

    @Test
    fun `an empty capture produces an empty scenario, not an error`() {
        val result = ScenarioSnapshot.fromEvents(emptyList())
        assertTrue(result.rules.isEmpty())
        assertEquals("0 endpoints", result.summary())
    }
}
