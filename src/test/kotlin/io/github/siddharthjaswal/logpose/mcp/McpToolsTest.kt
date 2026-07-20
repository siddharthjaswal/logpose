package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers what an agent actually receives from the MCP read tools. The transport is a thin
 * shell over this, so testing here is what keeps the answers honest.
 */
class McpToolsTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun http(
        id: String,
        method: String = "GET",
        path: String = "/app/v1/x",
        code: Int? = 200,
        error: String? = null,
        at: Long = 1_000,
        durationMillis: Long? = 30,
        body: String? = null,
        traceId: String? = null,
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = at,
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let { Response(code = it, body = body?.let { b -> Body(text = b) }) },
            durationMillis = durationMillis,
            error = error,
        )
        return LogEvent.Http(
            tx,
            Envelope(
                kind = Envelope.KIND_HTTP, id = id, at = at,
                endedAt = durationMillis?.let { at + it },
                traceId = traceId,
                payload = json.encodeToJsonElement(tx),
            ),
        )
    }

    private fun app(id: String, title: String, tone: String = Badge.TONE_INFO, at: Long = 2_000): LogEvent.Generic {
        val event = GenericEvent(title = title, badges = listOf(Badge("JOB", tone)))
        return LogEvent.Generic(
            event,
            Envelope(
                kind = Envelope.KIND_EVENT, id = id, at = at, endedAt = at,
                payload = json.encodeToJsonElement(event),
            ),
        )
    }

    private fun call(
        name: String,
        events: List<LogEvent>,
        args: JsonObject = JsonObject(emptyMap()),
        ages: (String) -> Long = { 0 },
        bodies: Boolean = true,
    ): JsonObject = McpTools.call(name, args, events, ages, bodies).jsonObject

    @Test fun `list_events summarises each kind in one line`() {
        val out = call("list_events", listOf(http("a", code = 200), app("b", "SyncWorker")))
        val summaries = out["events"]!!.jsonArray.map { it.jsonObject["summary"]!!.jsonPrimitive.content }
        assertEquals(listOf("GET /app/v1/x → 200", "SyncWorker"), summaries)
    }

    @Test fun `list_events returns the most recent when truncating`() {
        val events = (1..10).map { http("e$it", at = it * 100L) }
        val out = call("list_events", events, buildJsonObject { put("limit", 3) })

        assertEquals(10, out["total_matched"]!!.jsonPrimitive.int())
        val ids = out["events"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("e8", "e9", "e10"), ids, "an agent wants the latest, and reads top-down")
        assertTrue(out.containsKey("note"), "truncation must be stated, not silent")
    }

    @Test fun `list_events filters by method, status class and substring`() {
        val events = listOf(
            http("a", method = "GET", path = "/feed", code = 200),
            http("b", method = "POST", path = "/orders", code = 500),
            http("c", method = "POST", path = "/orders/9", code = 201),
        )
        assertEquals(
            listOf("b", "c"),
            call("list_events", events, buildJsonObject { put("method", "post") })
                .ids(),
            "method match is case-insensitive",
        )
        assertEquals(
            listOf("b"),
            call("list_events", events, buildJsonObject { put("status_class", 5) }).ids(),
        )
        assertEquals(
            listOf("b", "c"),
            call("list_events", events, buildJsonObject { put("contains", "orders") }).ids(),
        )
    }

    @Test fun `failures cover both error responses and transport errors`() {
        val events = listOf(
            http("ok", code = 200),
            http("server", code = 503),
            http("boom", code = null, error = "SocketTimeoutException"),
            app("job", "SyncWorker", tone = Badge.TONE_ERROR),
        )
        val out = call("find_failures", events)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
        assertEquals(listOf("server", "boom", "job"), out.ids())

        // failed_only on list_events agrees with find_failures.
        assertEquals(
            listOf("server", "boom", "job"),
            call("list_events", events, buildJsonObject { put("failed_only", true) }).ids(),
        )
    }

    @Test fun `since_seconds uses host age, not device timestamps`() {
        // Device clocks drift; the plugin's own arrival time is the only comparable one.
        val events = listOf(http("old", at = 9_999_999), http("new", at = 1))
        val ages = mapOf("old" to 60_000L, "new" to 2_000L)
        val out = call("list_events", events, buildJsonObject { put("since_seconds", 10) }, ages = { ages.getValue(it) })
        assertEquals(listOf("new"), out.ids())
    }

    @Test fun `get_event returns the full payload including bodies`() {
        val out = call("get_event", listOf(http("a", body = """{"secret":"visible"}""")), buildJsonObject { put("id", "a") })
        assertEquals("a", out["id"]!!.jsonPrimitive.content)
        assertTrue(out["payload"].toString().contains("visible"))
    }

    @Test fun `get_event withholds bodies when the setting is off but keeps the shape`() {
        val out = call(
            "get_event",
            listOf(http("a", body = """{"secret":"hidden"}""")),
            buildJsonObject { put("id", "a") },
            bodies = false,
        )
        assertFalse(out["payload"].toString().contains("hidden"), "body text must not leave the IDE")
        assertTrue(out.containsKey("payload_withheld"))
        assertTrue(out["payload"].toString().contains("app/v1/x"), "the request shape is still useful")
    }

    @Test fun `missing or unknown ids answer with an error, not an exception`() {
        assertTrue(call("get_event", emptyList()).containsKey("error"))
        assertTrue(call("get_event", emptyList(), buildJsonObject { put("id", "nope") }).containsKey("error"))
        assertTrue(call("unknown_tool", emptyList()).containsKey("error"))
    }

    @Test fun `get_trace returns the events of one flow`() {
        val events = listOf(
            http("a", traceId = "t1", path = "/one"),
            http("b", traceId = "t2", path = "/two"),
            http("c", traceId = "t1", path = "/three"),
        )
        val out = call("get_trace", events, buildJsonObject { put("trace_id", "t1") })
        assertEquals(2, out["count"]!!.jsonPrimitive.int())
        assertEquals(listOf("a", "c"), out.ids())
    }

    @Test fun `session_summary counts kinds, failures and endpoints`() {
        val events = listOf(
            http("a", path = "/feed", code = 200),
            http("b", path = "/feed", code = 200),
            http("c", path = "/orders", code = 500),
            app("d", "SyncWorker"),
        )
        val out = call("session_summary", events)
        assertEquals(4, out["total_events"]!!.jsonPrimitive.int())
        assertEquals(3, out["by_kind"]!!.jsonObject["http"]!!.jsonPrimitive.int())
        assertEquals(1, out["by_kind"]!!.jsonObject["event"]!!.jsonPrimitive.int())
        assertEquals(1, out["failures"]!!.jsonPrimitive.int())

        val top = out["endpoints"]!!.jsonArray.first().jsonObject
        assertEquals("GET /feed", top["endpoint"]!!.jsonPrimitive.content)
        assertEquals(2, top["calls"]!!.jsonPrimitive.int())
    }

    @Test fun `every catalogued tool declares a schema and is callable`() {
        val names = McpTools.catalogue().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("list_events", "get_event", "get_trace", "find_failures", "session_summary"),
            names,
        )
        McpTools.catalogue().forEach { tool ->
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertTrue(schema.containsKey("properties"))
            // The internal "_required" marker must never reach the client.
            assertFalse(schema.toString().contains("_required"))
        }
    }

    private fun JsonObject.ids(): List<String> =
        this["events"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()
}
