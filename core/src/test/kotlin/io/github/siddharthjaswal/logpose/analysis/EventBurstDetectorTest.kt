package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventBurstDetectorTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun analytics(id: String, at: Long, name: String = "BTH_HIDDEN", screen: String? = "NavDrawerFragment", params: Map<String, String> = emptyMap()): LogEvent.Generic {
        val sections = if (params.isEmpty()) emptyList() else listOf(
            Section("Params", Section.TYPE_KV, JsonObject(params.mapValues { JsonPrimitive(it.value) })),
        )
        val g = GenericEvent(title = name, subtitle = screen, sections = sections)
        return LogEvent.Generic(g, Envelope(kind = Envelope.KIND_ANALYTICS, id = id, at = at, endedAt = at, payload = json.encodeToJsonElement(g)))
    }

    @Test fun `the live BTH_HIDDEN triple — 7ms then 6ms apart — is a burst`() {
        val events = listOf(
            analytics("a", 1791956297472), analytics("b", 1791956297479), analytics("c", 1791956297485),
        )
        val r = EventBurstDetector.analyze(events)
        assertEquals(setOf("b", "c"), r.marks.keys)
        assertEquals(EventBurstDetector.Pattern.BURST, r.marks.getValue("c").pattern)
        assertEquals(3, r.marks.getValue("c").ordinal)
        assertEquals("a", r.marks.getValue("c").originalId)
        assertEquals(listOf("a", "b", "c"), r.groups.single().eventIds)
    }

    @Test fun `two identical events within 300ms are a double-fire`() {
        val r = EventBurstDetector.analyze(listOf(analytics("a", 10_000), analytics("b", 10_239)))
        assertEquals(EventBurstDetector.Pattern.DOUBLE_FIRE, r.marks.getValue("b").pattern)
        assertEquals(2, r.groups.single().count)
    }

    @Test fun `two identical events 900ms apart are not flagged`() {
        assertTrue(EventBurstDetector.analyze(listOf(analytics("a", 10_000), analytics("b", 10_900))).marks.isEmpty())
    }

    @Test fun `three spread over 1_8s are not a burst`() {
        val events = listOf(analytics("a", 0 + 10_000), analytics("b", 10_900), analytics("c", 11_800))
        assertTrue(EventBurstDetector.analyze(events).marks.isEmpty())
    }

    @Test fun `the same event with different params is a list rendering, not a burst`() {
        val events = (1..5).map { analytics("e$it", 10_000L + it, name = "item_viewed", params = mapOf("id" to "$it")) }
        assertTrue(EventBurstDetector.analyze(events).marks.isEmpty())
    }

    @Test fun `the same name on a different screen does not match`() {
        val events = listOf(analytics("a", 10_000, screen = "Home"), analytics("b", 10_005, screen = "Cart"))
        assertTrue(EventBurstDetector.analyze(events).marks.isEmpty())
    }

    @Test fun `a per-event timestamp param does not hide a double-fire`() {
        val events = listOf(
            analytics("a", 10_000, params = mapOf("ts" to "1", "mode" to "x")),
            analytics("b", 10_010, params = mapOf("ts" to "2", "mode" to "x")),
        )
        assertEquals(setOf("b"), EventBurstDetector.analyze(events).marks.keys)
    }

    @Test fun `a burst never straddles an app restart`() {
        val events = listOf(analytics("a", 10_000), analytics("b", 10_005))
        val run = mapOf("a" to 1, "b" to 2)
        assertTrue(EventBurstDetector.analyze(events, { run.getValue(it) }).marks.isEmpty())
    }

    @Test fun `db rows are out of scope — repeated queries are query_hotspots' signal`() {
        val q = io.github.siddharthjaswal.logpose.model.DbQuery(sql = "SELECT 1")
        val db = (1..3).map { i ->
            LogEvent.Db(q, Envelope(kind = Envelope.KIND_DB, id = "d$i", at = 10_000L + i, payload = json.encodeToJsonElement(q)))
        }
        assertNull(EventBurstDetector.analyze(db).groups.firstOrNull())
    }

    @Test fun `session_summary lists the burst with its kind, and list_events marks the repeat`() {
        val events = listOf(analytics("a", 10_000), analytics("b", 10_007), analytics("c", 10_013))
        val summary = McpTools.call("session_summary", JsonObject(emptyMap()), events, { 0 }, true).jsonObject
        assertEquals(2, summary["duplicate_events"]!!.jsonPrimitive.int)
        val group = summary["duplicate_groups"]!!.jsonArray.single().jsonObject
        assertEquals(Envelope.KIND_ANALYTICS, group["kind"]!!.jsonPrimitive.content)
        assertEquals("BTH_HIDDEN", group["event"]!!.jsonPrimitive.content)
        assertEquals("burst", group["pattern"]!!.jsonPrimitive.content)
        assertEquals(3, group["count"]!!.jsonPrimitive.int)

        val listed = McpTools.call("list_events", JsonObject(emptyMap()), events, { 0 }, true).jsonObject
        val c = listed["events"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "c" }
        assertEquals(3, c["duplicate"]!!.jsonObject["ordinal"]!!.jsonPrimitive.int)
        assertEquals("a", c["duplicate"]!!.jsonObject["original_id"]!!.jsonPrimitive.content)

        val analytics = McpTools.call("analytics_events", buildJsonObject {}, events, { 0 }, true).jsonObject
        assertEquals(3, analytics["bursts"]!!.jsonArray.single().jsonObject["count"]!!.jsonPrimitive.int)
    }
}
