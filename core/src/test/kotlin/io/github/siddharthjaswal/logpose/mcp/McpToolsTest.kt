package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.KeyValue
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.ConfigChange
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.FcmNotification
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Section
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

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
        mocked: Boolean = false,
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = at,
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let { Response(code = it, body = body?.let { b -> Body(text = b) }) },
            durationMillis = durationMillis,
            error = error,
            mocked = mocked,
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

    // How the library emits an analytics event: a self-describing Generic under the analytics kind.
    private fun analytics(id: String, name: String, screen: String? = null, params: Map<String, String> = emptyMap(), at: Long = 1_000): LogEvent.Generic {
        val sections = if (params.isEmpty()) emptyList() else listOf(
            Section("Params", Section.TYPE_KV, buildJsonObject { params.forEach { (k, v) -> put(k, v) } }),
        )
        val event = GenericEvent(title = name, subtitle = screen, badges = listOf(Badge("ANALYTICS", Badge.TONE_INFO)), sections = sections)
        return LogEvent.Generic(
            event,
            Envelope(kind = Envelope.KIND_ANALYTICS, id = id, at = at, endedAt = at, payload = json.encodeToJsonElement(event)),
        )
    }

    private fun call(
        name: String,
        events: List<LogEvent>,
        args: JsonObject = JsonObject(emptyMap()),
        ages: (String) -> Long = { 0 },
        bodies: Boolean = true,
        sessions: List<EventStore.Session> = emptyList(),
        sessionOf: (String) -> Int = { 0 },
    ): JsonObject =
        McpTools.call(name, args, events, ages, bodies, null, sessions, sessionOf).jsonObject

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

    @Test fun `contains searches FCM data keys and values, not just the notification`() {
        val events = listOf(
            fcm("push1", title = null, data = mapOf("channel" to "order-assigned", "orderId" to "91")),
            fcm("push2", title = "Promo", data = mapOf("channel" to "marketing")),
        )
        assertEquals(
            listOf("push1"),
            call("list_events", events, buildJsonObject { put("contains", "order-assigned") }).ids(),
            "a data-only push is findable by its payload",
        )
        assertEquals(
            listOf("push1"),
            call("list_events", events, buildJsonObject { put("contains", "orderId") }).ids(),
            "data keys are searchable too",
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
        assertEquals(listOf("server", "boom", "job"), out.failureIds())

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

    // ---- app-runtime kinds -----------------------------------------------------------------

    private fun db(
        id: String,
        sql: String = "SELECT * FROM users",
        durationMillis: Long? = 10,
        rows: Int? = null,
        at: Long = 1_000,
    ): LogEvent.Db {
        val query = DbQuery(sql = sql, rows = rows, database = "app-db")
        return LogEvent.Db(
            query,
            Envelope(
                kind = Envelope.KIND_DB, id = id, at = at,
                endedAt = durationMillis?.let { at + it },
                payload = json.encodeToJsonElement(query),
            ),
        )
    }

    private fun worker(
        id: String,
        name: String = "SyncWorker",
        state: String = WorkerEvent.STATE_SUCCEEDED,
        attempt: Int = 1,
        at: Long = 1_000,
        durationMillis: Long? = 500,
        replayedAtAttach: Boolean = false,
        enqueuedAt: Long? = null,
        runStartedAt: Long? = null,
    ): LogEvent.Worker {
        val work = WorkerEvent(
            worker = name, state = state, workId = id, runAttempt = attempt,
            replayedAtAttach = replayedAtAttach,
            // Null by default: a pre-1.7.2 capture, which every existing assertion here describes.
            enqueuedAtMillis = enqueuedAt, runStartedAtMillis = runStartedAt,
        )
        return LogEvent.Worker(
            work,
            Envelope(
                kind = Envelope.KIND_WORKER, id = id, at = at,
                endedAt = durationMillis?.let { at + it },
                payload = json.encodeToJsonElement(work),
            ),
        )
    }

    private fun config(id: String, vararg changes: Pair<String, Pair<String?, String>>, at: Long = 1_000): LogEvent.Config {
        val update = ConfigUpdate(
            source = "remote",
            totalKeys = 187,
            changes = changes.map { (key, values) ->
                ConfigChange(key = key, previous = values.first, value = values.second)
            },
        )
        return LogEvent.Config(
            update,
            Envelope(
                kind = Envelope.KIND_CONFIG, id = id, at = at, endedAt = at,
                payload = json.encodeToJsonElement(update),
            ),
        )
    }

    @Test fun `query_hotspots ranks the N+1, not the one-off`() {
        // The shape this exists to catch: a list adapter re-running one statement per row. No
        // timing is involved, which is the point — Room's callback never supplies any.
        val events = List(12) { db("n$it", "SELECT * FROM items WHERE order_id = ?") } +
            listOf(db("once", "SELECT * FROM users"), db("twice-a", "UPDATE riders SET x = 1"),
                   db("twice-b", "UPDATE riders SET x = 1"))
        val out = call("query_hotspots", events)

        val hotspots = out["hotspots"]!!.jsonArray
        assertEquals(2, hotspots.size, "a statement run once is not a hotspot")
        val worst = hotspots.first().jsonObject
        assertEquals(12, worst["count"]!!.jsonPrimitive.int())
        assertEquals("items", worst["table"]!!.jsonPrimitive.content)
        assertEquals("select", worst["operation"]!!.jsonPrimitive.content)
    }

    @Test fun `query_hotspots says so plainly when nothing repeats`() {
        // The tool it replaced returned an empty list on every real capture and taught callers
        // to stop asking; an empty answer here has to explain itself.
        val out = call("query_hotspots", listOf(db("a", "SELECT * FROM users")))
        assertTrue(out["hotspots"]!!.jsonArray.isEmpty())
        assertTrue(out.containsKey("note"))
        assertEquals(1, out["total_queries"]!!.jsonPrimitive.int())
    }

    @Test fun `query_hotspots filters by table and threshold`() {
        val events = List(3) { db("u$it", "SELECT * FROM users") } +
            List(5) { db("o$it", "SELECT * FROM orders") }

        val users = call("query_hotspots", events, buildJsonObject { put("table", "users") })
        assertEquals(1, users["hotspots"]!!.jsonArray.size)
        assertEquals(3, users["hotspots"]!!.jsonArray.first().jsonObject["count"]!!.jsonPrimitive.int())

        val strict = call("query_hotspots", events, buildJsonObject { put("min_count", 4) })
        assertEquals(1, strict["hotspots"]!!.jsonArray.size, "only orders clears a threshold of 4")
    }

    @Test fun `query_hotspots reports timings only when the app measured them`() {
        val events = listOf(
            db("a", "SELECT * FROM users", durationMillis = 30),
            db("b", "SELECT * FROM users", durationMillis = 50),
        )
        val hotspot = call("query_hotspots", events)["hotspots"]!!.jsonArray.first().jsonObject
        assertEquals(80, hotspot["total_ms"]!!.jsonPrimitive.int())

        val unmeasured = listOf(db("c", durationMillis = null), db("d", durationMillis = null))
        val bare = call("query_hotspots", unmeasured)["hotspots"]!!.jsonArray.first().jsonObject
        assertFalse(bare.containsKey("total_ms"), "no invented durations when nothing was measured")
    }

    @Test fun `worker_history reports attempts, failures and filters`() {
        val events = listOf(
            worker("w1", "SyncWorker", WorkerEvent.STATE_SUCCEEDED, attempt = 3),
            worker("w2", "CleanupWorker", WorkerEvent.STATE_FAILED, attempt = 1),
            worker("w3", "SyncWorker", WorkerEvent.STATE_RUNNING, attempt = 1, durationMillis = null),
        )
        val all = call("worker_history", events)
        assertEquals(3, all["count"]!!.jsonPrimitive.int())
        assertEquals(1, all["retried"]!!.jsonPrimitive.int(), "attempt > 1 counts as a retry")
        assertEquals(1, all["failed"]!!.jsonPrimitive.int())

        val filtered = call("worker_history", events, buildJsonObject { put("worker", "sync") })
        assertEquals(2, filtered["count"]!!.jsonPrimitive.int(), "worker match is case-insensitive")

        val failed = call("worker_history", events, buildJsonObject { put("state", "failed") })
        assertEquals(1, failed["count"]!!.jsonPrimitive.int())
    }

    @Test fun `worker_history splits replayed-at-attach from work that ran this session`() {
        // The FetchDeliveryRecipientsWorker case: WorkManager replays terminal history on attach,
        // so "ran 20 times" is really 1 run + 19 replays. The tool has to keep them apart.
        val events = listOf(
            worker("live", state = WorkerEvent.STATE_SUCCEEDED),
            worker("old1", state = WorkerEvent.STATE_SUCCEEDED, replayedAtAttach = true),
            worker("old2", state = WorkerEvent.STATE_SUCCEEDED, replayedAtAttach = true),
        )
        val out = call("worker_history", events)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
        assertEquals(1, out["ran_this_session"]!!.jsonPrimitive.int())
        assertEquals(2, out["replayed_at_attach"]!!.jsonPrimitive.int())
        val flagged = out["workers"]!!.jsonArray.count { it.jsonObject["replayed_at_attach"] != null }
        assertEquals(2, flagged)
    }

    @Test fun `worker durations say what they include`() {
        // Pre-1.7.2 capture: the device never reported the split, so a duration covers queue time
        // too and the entry still says so — unchanged, key for key.
        val out = call("worker_history", listOf(worker("w1", durationMillis = 1_500)))
        val entry = out["workers"]!!.jsonArray.single().jsonObject
        assertEquals(1_500, entry["duration_ms"]!!.jsonPrimitive.int())
        assertTrue(entry["duration_note"]!!.jsonPrimitive.content.contains("queue time"))
        assertFalse(entry.containsKey("queued_ms"), "never a queue wait the device did not measure")
        assertFalse(entry.containsKey("run_ms"))
        assertEquals(0, out["queue_bound"]!!.jsonPrimitive.int(), "unmeasured is not evidence of fast")
    }

    @Test fun `worker_history separates waiting from running when the device measured both`() {
        // at 1_000, enqueued 1_000, ran from 7_200, ended 7_640: 6.2s waiting, 440ms working.
        val out = call(
            "worker_history",
            listOf(
                worker("w1", at = 1_000, durationMillis = 6_640, enqueuedAt = 1_000, runStartedAt = 7_200)
            ),
        )
        val entry = out["workers"]!!.jsonArray.single().jsonObject
        assertEquals(6_200, entry["queued_ms"]!!.jsonPrimitive.int())
        assertEquals(440, entry["run_ms"]!!.jsonPrimitive.int())
        // duration_ms keeps its old meaning — agents and saved scenarios already read it.
        assertEquals(6_640, entry["duration_ms"]!!.jsonPrimitive.int())
        assertTrue(entry["duration_note"]!!.jsonPrimitive.content.contains("queued_ms"))
        // The whole answer to "is my work slow, or just waiting?".
        assertEquals(1, out["queue_bound"]!!.jsonPrimitive.int())
    }

    @Test fun `worker_history reports half a measurement as half a measurement`() {
        // Capture attached mid-flight: the run start was observed, the wait never was.
        val out = call(
            "worker_history",
            listOf(worker("w1", at = 1_000, durationMillis = 900, runStartedAt = 1_400)),
        )
        val entry = out["workers"]!!.jsonArray.single().jsonObject
        assertFalse(entry.containsKey("queued_ms"))
        assertEquals(500, entry["run_ms"]!!.jsonPrimitive.int())
        assertEquals(0, out["queue_bound"]!!.jsonPrimitive.int(), "no wait measured, so no verdict")
    }

    @Test fun `config_changes flattens activations into individual flags`() {
        val events = listOf(
            config("c1", "new_checkout" to (null to "true")),
            config("c2", "feed_v2" to ("false" to "true"), "timeout_ms" to ("3000" to "5000")),
        )
        val out = call("config_changes", events)

        assertEquals(2, out["activations"]!!.jsonPrimitive.int())
        assertEquals(3, out["count"]!!.jsonPrimitive.int(), "an agent wants flags, not activations")

        val keys = out["changes"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(listOf("new_checkout", "feed_v2", "timeout_ms"), keys)

        val first = out["changes"]!!.jsonArray.first().jsonObject
        assertTrue(first["new_key"]!!.jsonPrimitive.content.toBoolean(), "no previous value means new")

        val second = out["changes"]!!.jsonArray[1].jsonObject
        assertEquals("false", second["previous"]!!.jsonPrimitive.content)
        assertEquals("true", second["value"]!!.jsonPrimitive.content)
    }

    @Test fun `config_changes can narrow to one flag`() {
        val events = listOf(config("c1", "feed_v2" to ("false" to "true"), "other" to ("1" to "2")))
        val out = call("config_changes", events, buildJsonObject { put("key", "feed") })
        assertEquals(1, out["count"]!!.jsonPrimitive.int())
    }

    @Test fun `db and worker failures show up in find_failures`() {
        val failingQuery = DbQuery(sql = "SELECT 1", error = "SQLiteException: no such table")
        val events = listOf(
            LogEvent.Db(
                failingQuery,
                Envelope(
                    kind = Envelope.KIND_DB, id = "bad-sql", at = 1, endedAt = 1,
                    payload = json.encodeToJsonElement(failingQuery),
                ),
            ),
            worker("w-failed", state = WorkerEvent.STATE_FAILED),
            worker("w-ok", state = WorkerEvent.STATE_SUCCEEDED),
            config("c1", "flag" to ("a" to "b")),
        )
        assertEquals(listOf("bad-sql", "w-failed"), call("find_failures", events).failureIds())
    }

    @Test fun `summaries for the new kinds read like the rows do`() {
        val out = call(
            "list_events",
            listOf(
                db("q", "SELECT id FROM users WHERE id = 7"),
                worker("w", "SyncWorker", WorkerEvent.STATE_FAILED),
                config("c", "new_checkout" to ("false" to "true")),
            ),
        )
        val summaries = out["events"]!!.jsonArray.map { it.jsonObject["summary"]!!.jsonPrimitive.content }
        assertTrue(summaries[0].startsWith("users · SELECT id FROM users"), summaries[0])
        assertTrue(summaries[1].startsWith("SyncWorker"), summaries[1])
        assertTrue(summaries[2].contains("new_checkout"), summaries[2])
    }

    // ---- mock write tools ------------------------------------------------------------------

    /** In-memory stand-in for MocksController, so the tool behavior is testable on its own. */
    private class FakeMocks : McpTools.Mocks {
        val rules = mutableListOf<MockRule>()
        var lastBaseBody: String? = null
        override fun list() = rules.toList()
        override fun hits() = mapOf<String, Int>()
        override fun deviceHint() = if (ready) "test-device · synced rev 1" else "waiting for device"
        var ready = true
        /** What the device announced; the matcher/step gating reads this. */
        var libVersion: String? = "1.7.0"
        override fun deviceReady() = ready
        override fun deviceLibVersion() = libVersion
        override fun create(rule: MockRule, baseBody: String?) { rules += rule; lastBaseBody = baseBody }
        override fun setEnabled(id: String, enabled: Boolean) {
            rules.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        override fun delete(id: String) { rules.removeAll { it.id == id } }
    }

    private fun callWrite(name: String, args: JsonObject, mocks: McpTools.Mocks, events: List<LogEvent> = emptyList()) =
        McpTools.call(name, args, events, { 0 }, true, mocks).jsonObject

    @Test fun `create_mock leads with active=false and a warning when no device is synced`() {
        val mocks = FakeMocks().apply { ready = false }
        val out = callWrite("create_mock", buildJsonObject { put("path_pattern", "/orders") }, mocks)
        assertFalse(out["active"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out.containsKey("warning"), "an unserved mock must warn, not just report device state as a footnote")
    }

    @Test fun `create_mock is active when a device is synced`() {
        val out = callWrite("create_mock", buildJsonObject { put("path_pattern", "/orders") }, FakeMocks())
        assertTrue(out["active"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `create_mock warns a failure with no latency tests the path, not the window`() {
        val mocks = FakeMocks()
        val instant = callWrite("create_mock", buildJsonObject { put("path_pattern", "/a"); put("behavior", "timeout") }, mocks)
        assertTrue(instant.containsKey("latency_warning"), "a 0-latency failure must warn it can't reproduce an in-flight race")

        val windowed = callWrite("create_mock", buildJsonObject { put("path_pattern", "/b"); put("behavior", "timeout"); put("latency_ms", 500) }, mocks)
        assertFalse(windowed.containsKey("latency_warning"), "a failure with latency has a real window")

        val normal = callWrite("create_mock", buildJsonObject { put("path_pattern", "/c") }, mocks)
        assertFalse(normal.containsKey("latency_warning"), "a normal mock is not a failure")
    }

    @Test fun `clear_capture resets and reports the count that was cleared`() {
        var cleared = false
        val out = McpTools.call(
            "clear_capture", JsonObject(emptyMap()), listOf(http("a"), http("b")),
            { 0 }, true, null, emptyList(), { 0 }, { true }, { cleared = true },
        ).jsonObject
        assertTrue(cleared, "the clear callback must actually fire")
        assertEquals(2, out["cleared"]!!.jsonPrimitive.int())
    }

    @Test fun `session_summary reports capture health so empty is never ambiguous`() {
        val stopped = McpTools.call(
            "session_summary", JsonObject(emptyMap()), emptyList<LogEvent>(),
            { 0 }, true, null, emptyList(), { 0 }, { false }, {},
        ).jsonObject
        assertFalse(stopped["capture"]!!.jsonObject["running"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `create_mock copies method, path and body from a captured event`() {
        val mocks = FakeMocks()
        val event = http("e1", method = "POST", path = "/orders", code = 201, body = """{"ok":true}""")

        val out = callWrite("create_mock", buildJsonObject { put("from_event_id", "e1") }, mocks, listOf(event))

        val rule = mocks.rules.single()
        assertEquals("POST", rule.method)
        assertEquals("/orders", rule.pathPattern)
        assertEquals(201, rule.status, "the captured status is the sensible default")
        assertEquals("""{"ok":true}""", rule.body)
        assertEquals("""{"ok":true}""", mocks.lastBaseBody, "the base body seeds the field editor")
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("running app"))
    }

    @Test fun `create_mock overrides only what the caller states`() {
        val mocks = FakeMocks()
        val event = http("e1", method = "GET", path = "/feed", code = 200, body = """{"items":[]}""")

        callWrite(
            "create_mock",
            buildJsonObject { put("from_event_id", "e1"); put("status", 500) },
            mocks, listOf(event),
        )

        val rule = mocks.rules.single()
        assertEquals(500, rule.status)
        assertEquals("/feed", rule.pathPattern, "everything unstated still comes from the capture")
        assertEquals("""{"items":[]}""", rule.body)
    }

    @Test fun `create_mock without a seed requires a path`() {
        val mocks = FakeMocks()
        val out = callWrite("create_mock", buildJsonObject { put("status", 500) }, mocks)
        assertTrue(out.containsKey("error"))
        assertTrue(mocks.rules.isEmpty(), "a rejected call must not create a rule")
    }

    @Test fun `create_mock rejects unknown modes and behaviors instead of guessing`() {
        val mocks = FakeMocks()
        val bad = buildJsonObject { put("path_pattern", "/x"); put("mode", "merge") }
        assertTrue(callWrite("create_mock", bad, mocks).containsKey("error"))

        val badBehavior = buildJsonObject { put("path_pattern", "/x"); put("behavior", "explode") }
        assertTrue(callWrite("create_mock", badBehavior, mocks).containsKey("error"))
        assertTrue(mocks.rules.isEmpty())
    }

    @Test fun `create_mock with an unknown seed id fails rather than inventing a rule`() {
        val mocks = FakeMocks()
        val out = callWrite("create_mock", buildJsonObject { put("from_event_id", "nope") }, mocks)
        assertTrue(out.containsKey("error"))
        assertTrue(mocks.rules.isEmpty())
    }

    @Test fun `patch mode does not inherit the captured body`() {
        // In patch mode the body is the override set, so copying the whole response would
        // silently mean "replace everything with itself".
        val mocks = FakeMocks()
        val event = http("e1", path = "/feed", body = """{"a":1,"b":2}""")
        callWrite(
            "create_mock",
            buildJsonObject { put("from_event_id", "e1"); put("mode", "patch") },
            mocks, listOf(event),
        )
        assertEquals(null, mocks.rules.single().body)
    }

    @Test fun `set_mock_enabled and delete_mock act on real ids only`() {
        val mocks = FakeMocks()
        callWrite("create_mock", buildJsonObject { put("path_pattern", "/x") }, mocks)
        val id = mocks.rules.single().id

        callWrite("set_mock_enabled", buildJsonObject { put("id", id); put("enabled", false) }, mocks)
        assertFalse(mocks.rules.single().enabled)

        assertTrue(callWrite("set_mock_enabled", buildJsonObject { put("id", "ghost") }, mocks).containsKey("error"))
        assertTrue(callWrite("delete_mock", buildJsonObject { put("id", "ghost") }, mocks).containsKey("error"))
        assertEquals(1, mocks.rules.size)

        callWrite("delete_mock", buildJsonObject { put("id", id) }, mocks)
        assertTrue(mocks.rules.isEmpty())
    }

    @Test fun `mock tools report the device state so an agent knows if a rule is live`() {
        val mocks = FakeMocks()
        val out = callWrite("list_mocks", JsonObject(emptyMap()), mocks)
        assertEquals("test-device · synced rev 1", out["device"]!!.jsonPrimitive.content)
    }

    @Test fun `list_mocks counts served from captured mocked responses, not the device counter`() {
        // The device hit counter only rides back on a rule-set apply, so it reads 0 while a rule
        // is demonstrably serving. The mocked:true flag on captured responses is the honest signal.
        val mocks = FakeMocks()
        callWrite("create_mock", buildJsonObject { put("method", "GET"); put("path_pattern", "/orders") }, mocks)
        val id = mocks.rules.single().id

        val events = listOf(
            http("m1", method = "GET", path = "/orders", mocked = true),
            http("m2", method = "GET", path = "/orders", mocked = true),
            http("r1", method = "GET", path = "/orders", mocked = false), // real, not served by the rule
            http("o1", method = "GET", path = "/feed", mocked = true),     // mocked but a different path
        )
        val out = callWrite("list_mocks", JsonObject(emptyMap()), mocks, events)

        val rule = out["mocks"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(2, rule["served"]!!.jsonPrimitive.int(), "two mocked /orders responses were served")
        assertEquals(0, rule["device_hits"]!!.jsonPrimitive.int(), "the device counter stays a footnote")
    }

    @Test fun `list_mocks served count honours the path glob`() {
        val mocks = FakeMocks()
        callWrite("create_mock", buildJsonObject { put("method", "*"); put("path_pattern", "/v1/*/orders") }, mocks)
        val id = mocks.rules.single().id

        val events = listOf(
            http("a", method = "GET", path = "/v1/abc/orders", mocked = true),
            http("b", method = "POST", path = "/v1/xyz/orders", mocked = true),
            http("c", method = "GET", path = "/v1/abc/items", mocked = true), // outside the glob
        )
        val out = callWrite("list_mocks", JsonObject(emptyMap()), mocks, events)
        val rule = out["mocks"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(2, rule["served"]!!.jsonPrimitive.int())
    }

    @Test fun `write tools refuse cleanly when mocking is unavailable`() {
        val out = call("create_mock", emptyList(), buildJsonObject { put("path_pattern", "/x") })
        assertTrue(out.containsKey("error"))
    }

    @Test fun `every catalogued tool declares a schema and is callable`() {
        val names = McpTools.catalogue().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "list_events", "get_event", "get_trace", "get_related", "list_correlation_keys",
                "await_event", "find_failures",
                "session_summary", "query_hotspots", "worker_history", "config_changes",
                "analytics_events", "clear_capture",
                "list_mocks", "create_mock", "set_mock_enabled", "delete_mock",
                "inject_fcm", "list_scenarios", "load_scenario", "save_scenario",
            ),
            names,
        )
        // The deferred tools must be routed to callAsync by the transport; a sync call to one
        // would return "unknown tool" to an agent that did nothing wrong.
        assertEquals(
            setOf(
                "await_event", "inject_fcm", "list_scenarios", "load_scenario", "save_scenario",
                "get_related", "list_correlation_keys",
            ),
            names.filter { McpTools.isAsync(it) }.toSet(),
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

    /** find_failures groups identical failures, so ids come out of the groups. */
    private fun JsonObject.failureIds(): List<String> =
        this["failures"]!!.jsonArray.flatMap { group ->
            group.jsonObject["event_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()

    // ---- analytics ---------------------------------------------------------------------------

    @Test fun `analytics_events returns events, counts and the screen flow`() {
        val events = listOf(
            analytics("a", "screen_view", screen = "home", at = 1),
            analytics("b", "add_to_cart", screen = "product", params = mapOf("id" to "7"), at = 2),
            analytics("c", "screen_view", screen = "cart", at = 3),
            analytics("d", "purchase_complete", screen = "cart", params = mapOf("value" to "499"), at = 4),
        )
        val out = call("analytics_events", events)

        assertEquals(4, out["count"]!!.jsonPrimitive.int())
        // by_name catches double-fires and wrong-count events.
        assertEquals(2, out["by_name"]!!.jsonObject["screen_view"]!!.jsonPrimitive.int())
        // params come through.
        val purchase = out["events"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "purchase_complete" }
        assertEquals("499", purchase.jsonObject["params"]!!.jsonObject["value"]!!.jsonPrimitive.content)

        // screen_flow: home->product->cart; the two cart events don't invent a cart->cart edge.
        val edges = out["screen_flow"]!!.jsonArray.map {
            "${it.jsonObject["from"]!!.jsonPrimitive.content}->${it.jsonObject["to"]!!.jsonPrimitive.content}"
        }
        assertEquals(setOf("home->product", "product->cart"), edges.toSet())
    }

    @Test fun `analytics_events filters by name`() {
        val events = listOf(
            analytics("a", "screen_view", screen = "home"),
            analytics("b", "purchase_complete", screen = "cart"),
        )
        val out = call("analytics_events", events, buildJsonObject { put("name", "purchase") })
        assertEquals(1, out["count"]!!.jsonPrimitive.int())
        assertEquals("purchase_complete", out["events"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    // ---- sessions and the jump table --------------------------------------------------------

    private fun session(index: Int, pid: String = "p$index") =
        EventStore.Session(index, startedAt = index * 1000L, processId = pid, pkg = "com.acme", libVersion = "1.5.0")

    @Test fun `session_summary breaks a capture apart at app restarts`() {
        // Two bursts either side of a restart: reported as one span they look like six hours of
        // steady traffic, which is what made every aggregate over them misleading.
        val events = listOf(
            http("a", at = 1_000), http("b", at = 2_000),
            http("c", at = 21_000_000), http("d", at = 21_001_000),
        )
        val owner = mapOf("a" to 1, "b" to 1, "c" to 2, "d" to 2)
        val out = call(
            "session_summary", events,
            sessions = listOf(session(1), session(2)),
            sessionOf = { owner[it] ?: 0 },
        )

        val sessions = out["sessions"]!!.jsonArray
        assertEquals(2, sessions.size)
        assertEquals(2, sessions[0].jsonObject["events"]!!.jsonPrimitive.int())
        assertEquals(1_000, sessions[0].jsonObject["duration_ms"]!!.jsonPrimitive.int())
        assertEquals(2, sessions[1].jsonObject["events"]!!.jsonPrimitive.int())
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("2 app runs"))
    }

    @Test fun `events with no handshake are reported as unattributed, not dropped`() {
        val out = call("session_summary", listOf(http("a")), sessions = emptyList(), sessionOf = { 0 })
        val orphan = out["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(0, orphan["session"]!!.jsonPrimitive.int())
        assertEquals(1, orphan["events"]!!.jsonPrimitive.int())
    }

    @Test fun `list_events scopes to one run`() {
        val owner = mapOf("a" to 1, "b" to 2)
        val out = call(
            "list_events", listOf(http("a"), http("b")),
            args = buildJsonObject { put("session", 2) },
            sessionOf = { owner[it] ?: 0 },
        )
        assertEquals(listOf("b"), out.ids())
    }

    @Test fun `by_kind reports every known kind so zero is distinguishable from absent`() {
        val out = call("session_summary", listOf(http("a")))
        val byKind = out["by_kind"]!!.jsonObject
        assertEquals(1, byKind["http"]!!.jsonPrimitive.int())
        // The capture that prompted this had worker and config instrumented but silent; an
        // absent key left no way to tell "never fired" from "not counted".
        assertEquals(0, byKind["worker"]!!.jsonPrimitive.int())
        assertEquals(0, byKind["config"]!!.jsonPrimitive.int())
    }

    @Test fun `session_summary hands back failure ids instead of only a count`() {
        val out = call("session_summary", listOf(http("a", code = 404), http("b", code = 200)))
        assertEquals(1, out["failures"]!!.jsonPrimitive.int())
        val group = out["failure_groups"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("a"), group["event_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `find_failures collapses the same failure repeated`() {
        val out = call("find_failures", listOf(http("a", code = 404), http("b", code = 404)))
        assertEquals(2, out["count"]!!.jsonPrimitive.int())
        assertEquals(1, out["distinct"]!!.jsonPrimitive.int())
        val group = out["failures"]!!.jsonArray.single().jsonObject
        assertEquals(2, group["count"]!!.jsonPrimitive.int())
        assertEquals(listOf("a", "b"), group["event_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `an empty traces array explains itself`() {
        val out = call("session_summary", listOf(http("a")))
        assertTrue(out["traces"]!!.jsonArray.isEmpty())
        // A bare [] reads as "no problems" when it really means the app never set a trace id.
        assertTrue(out["traces_note"]!!.jsonPrimitive.content.contains("opts in"))
    }

    // ---- deferred tools ----------------------------------------------------------------------
    //
    // The async tools are driven through fakes rather than real threads: a test that slept would
    // be both slow and flaky, and the thing worth pinning is the decision (what completes the
    // wait, what the answer says), not the scheduler.

    /** Captures the single answer a deferred tool produces, and refuses a second one. */
    private class Answer {
        private var value: JsonObject? = null
        var answers = 0; private set
        fun accept(element: kotlinx.serialization.json.JsonElement) { answers++; value = element.jsonObject }
        val answered: Boolean get() = value != null
        fun get(): JsonObject = value ?: error("the tool has not answered yet")
    }

    /** Stand-in for EventStore's waiter registry, driven by the test rather than a reader thread. */
    private class FakeWaits(private val limit: Int = 8) : McpTools.Waits {
        class Parked(val timeoutMillis: Long, val predicate: (LogEvent) -> Boolean) {
            val future = CompletableFuture<LogEvent?>()
        }

        val parked = mutableListOf<Parked>()

        override fun await(timeoutMillis: Long, predicate: (LogEvent) -> Boolean): CompletableFuture<LogEvent?>? {
            if (parked.size >= limit) return null
            return Parked(timeoutMillis, predicate).also { parked += it }.future
        }

        /** What EventStore.add does: complete every waiter this event satisfies. */
        fun deliver(event: LogEvent) {
            parked.filter { it.predicate(event) }.forEach { waiter ->
                parked -= waiter
                waiter.future.complete(event)
            }
        }

        fun expire() = parked.toList().forEach { waiter ->
            parked -= waiter
            waiter.future.complete(null)
        }
    }

    private class FakePush : McpTools.Push {
        var reason: String? = null
        var hint = "com.acme · logpose-android 1.7.0"
        var sent: io.github.siddharthjaswal.logpose.model.PushInject? = null
        private var pending: ((McpTools.Push.Ack?) -> Unit)? = null

        override fun deviceHint() = hint
        override fun notReady() = reason
        override fun inject(
            inject: io.github.siddharthjaswal.logpose.model.PushInject,
            onAck: (McpTools.Push.Ack?) -> Unit,
        ) {
            sent = inject; pending = onAck
        }

        fun ack(delivered: String, error: String? = null) = pending!!(McpTools.Push.Ack(delivered, error))
        fun neverAnswers() = pending!!(null)
    }

    private class FakeScenarios : McpTools.Scenarios {
        val saved = mutableListOf<McpTools.Scenarios.Info>()
        var loadReport: McpTools.Scenarios.LoadReport? = null
        var saveReport: McpTools.Scenarios.SaveReport? = null
        var lastSave: Triple<String, Boolean, Boolean>? = null
        var lastLoad: Pair<String, Boolean>? = null

        override fun list(onResult: (List<McpTools.Scenarios.Info>) -> Unit) = onResult(saved)

        override fun load(
            name: String,
            replace: Boolean,
            onResult: (McpTools.Scenarios.LoadReport) -> Unit,
        ) {
            lastLoad = name to replace
            onResult(loadReport ?: McpTools.Scenarios.LoadReport(name, found = false))
        }

        override fun save(
            name: String,
            note: String?,
            fromSession: Boolean,
            successOnly: Boolean,
            onResult: (McpTools.Scenarios.SaveReport) -> Unit,
        ) {
            lastSave = Triple(name, fromSession, successOnly)
            onResult(
                saveReport ?: McpTools.Scenarios.SaveReport(
                    name, rules = 3, path = ".logpose/scenarios/$name.json",
                )
            )
        }
    }

    private var clock = 0L

    /**
     * The correlation surface the tool window implements over its cache.
     *
     * It computes with the real [Correlation], so the tools are exercised against the rules they
     * actually ship with rather than a hand-written stand-in, and runs [offThread] inline — what
     * is worth pinning is that the tools do their work inside it, not the scheduler.
     */
    private class FakeCorrelations(
        var configured: List<CorrelationKey> = emptyList(),
    ) : McpTools.Correlations {
        var offThreadCalls = 0
            private set

        override fun keys(): List<CorrelationKey> = configured
        override fun textOf(event: LogEvent): String = Correlation.searchableText(event)
        override fun valuesOf(event: LogEvent) = Correlation.extract(event, configured)
        override fun keyLabelFor(events: List<LogEvent>, value: String): String? =
            Correlation.keyLabelFor(events, configured, value)

        override fun offThread(work: () -> Unit) {
            offThreadCalls++
            work()
        }
    }

    private fun callAsync(
        name: String,
        args: JsonObject = JsonObject(emptyMap()),
        events: List<LogEvent> = emptyList(),
        push: McpTools.Push? = null,
        waits: McpTools.Waits? = null,
        scenarios: McpTools.Scenarios? = null,
        correlations: McpTools.Correlations? = null,
        capturing: Boolean = true,
    ): Answer = Answer().also { answer ->
        McpTools.callAsync(
            name, args, events, push, waits, scenarios, correlations,
            captureRunning = { capturing }, now = { clock }, onResult = answer::accept,
        )
    }

    // ---- await_event ---------------------------------------------------------------------------

    @Test fun `await_event answers with the event that arrives after the call`() {
        val waits = FakeWaits()
        clock = 1_000
        val out = callAsync("await_event", buildJsonObject { put("method", "POST") }, waits = waits)
        assertFalse(out.answered, "the call must stay open until something arrives")

        // Events that don't match keep the wait open — that's the whole difference from polling.
        waits.deliver(http("noise", method = "GET", path = "/feed"))
        assertFalse(out.answered)

        clock = 1_250
        waits.deliver(http("hit", method = "POST", path = "/orders", code = 201))

        val answer = out.get()
        assertTrue(answer["matched"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hit", answer["id"]!!.jsonPrimitive.content)
        assertEquals(250, answer["waited_ms"]!!.jsonPrimitive.int())
        assertEquals("POST /orders → 201", answer["event"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
    }

    @Test fun `await_event filters the same way list_events does`() {
        val waits = FakeWaits()
        callAsync(
            "await_event",
            buildJsonObject { put("kind", "http"); put("status_class", 5); put("contains", "orders"); put("failed_only", true) },
            waits = waits,
        )
        val predicate = waits.parked.single().predicate

        assertFalse(predicate(http("a", path = "/orders", code = 200)), "a 200 is not a 5xx")
        assertFalse(predicate(http("b", path = "/feed", code = 503)), "the substring must match too")
        assertFalse(predicate(app("c", "orders")), "kind is honoured")
        assertTrue(predicate(http("d", path = "/orders", code = 503)))
    }

    @Test fun `await_event matches on trace id, which is how a push is followed`() {
        val waits = FakeWaits()
        val out = callAsync("await_event", buildJsonObject { put("trace_id", "trc-1") }, waits = waits)
        waits.deliver(http("other", traceId = "trc-2"))
        assertFalse(out.answered)
        waits.deliver(http("mine", traceId = "trc-1"))
        assertEquals("mine", out.get()["id"]!!.jsonPrimitive.content)
    }

    @Test fun `a timeout is a result, not an error`() {
        // An error would make an agent retry the transport; "nothing happened" is a finding it
        // can assert on.
        val waits = FakeWaits()
        clock = 0
        val out = callAsync("await_event", buildJsonObject { put("timeout_ms", 2_000) }, waits = waits)
        clock = 2_000
        waits.expire()

        val answer = out.get()
        assertFalse(answer["matched"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(answer.containsKey("error"))
        assertEquals(2_000, answer["waited_ms"]!!.jsonPrimitive.int())
        assertTrue(answer["note"]!!.jsonPrimitive.content.contains("list_events"), "point at where a past event would be")
    }

    @Test fun `await_event clamps the timeout instead of trusting it`() {
        val waits = FakeWaits()
        callAsync("await_event", buildJsonObject { put("timeout_ms", 5) }, waits = waits)
        callAsync("await_event", buildJsonObject { put("timeout_ms", 9_999_999) }, waits = waits)
        callAsync("await_event", JsonObject(emptyMap()), waits = waits)
        assertEquals(listOf(1_000L, 120_000L, 30_000L), waits.parked.map { it.timeoutMillis })
    }

    @Test fun `await_event says so when the waiter cap is reached`() {
        val waits = FakeWaits(limit = 1)
        callAsync("await_event", JsonObject(emptyMap()), waits = waits)
        val out = callAsync("await_event", JsonObject(emptyMap()), waits = waits)
        assertTrue(out.get()["error"]!!.jsonPrimitive.content.contains("Too many waits"))
    }

    @Test fun `await_event returns immediately when capture is stopped`() {
        // Nothing can arrive while logcat isn't tailed, so waiting 30s would just be a slow way
        // of saying the capture is dead.
        val waits = FakeWaits()
        val out = callAsync("await_event", JsonObject(emptyMap()), waits = waits, capturing = false)
        assertTrue(out.answered)
        assertTrue(out.get()["capture_stopped"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(waits.parked.isEmpty(), "no waiter is parked on a dead capture")
    }

    @Test fun `await_event rejects a nonsense status class`() {
        val out = callAsync("await_event", buildJsonObject { put("status_class", 42) }, waits = FakeWaits())
        assertTrue(out.get().containsKey("error"))
    }

    @Test fun `deferred tools answer even when their surface is missing`() {
        // A request must never be left hanging: with no tool window there is no push or scenario
        // surface, and the agent has to be told that rather than time out.
        assertTrue(callAsync("await_event").get().containsKey("error"))
        assertTrue(callAsync("inject_fcm").get().containsKey("error"))
        assertTrue(callAsync("list_scenarios").get().containsKey("error"))
        assertTrue(callAsync("load_scenario").get().containsKey("error"))
        assertTrue(callAsync("save_scenario").get().containsKey("error"))
        assertTrue(callAsync("get_related").get().containsKey("error"))
        assertTrue(callAsync("list_correlation_keys").get().containsKey("error"))
    }

    // ---- inject_fcm ----------------------------------------------------------------------------

    private fun fcm(
        id: String,
        title: String? = "Order assigned",
        data: Map<String, String> = mapOf("channel" to "order_assigned", "orderId" to "91"),
        event: String = "message",
        at: Long = 1_000,
    ): LogEvent.Fcm {
        val msg = FcmMessage(
            id = id, event = event, from = "/topics/riders", collapseKey = "orders",
            notification = title?.let { FcmNotification(title = it, body = "Pick up at 7") },
            data = data,
        )
        return LogEvent.Fcm(
            msg,
            Envelope(kind = Envelope.KIND_FCM, id = id, at = at, endedAt = at, payload = json.encodeToJsonElement(msg)),
        )
    }

    @Test fun `inject_fcm needs a payload or a captured push to replay`() {
        val push = FakePush()
        assertTrue(callAsync("inject_fcm", push = push).get().containsKey("error"))
        assertEquals(null, push.sent, "a rejected call must not reach the device")
    }

    @Test fun `inject_fcm sends the data map it was given`() {
        val push = FakePush()
        clock = 7_000
        val out = callAsync(
            "inject_fcm",
            buildJsonObject {
                put("data", buildJsonObject { put("channel", "order_assigned"); put("orderId", "91") })
                put("notification_title", "Order assigned")
                put("collapse_key", "orders")
            },
            push = push,
        )

        val message = push.sent!!.message
        assertEquals(mapOf("channel" to "order_assigned", "orderId" to "91"), message.data)
        assertEquals("Order assigned", message.notificationTitle)
        assertEquals("orders", message.collapseKey)
        assertEquals(7_000, message.sentTimeMillis, "the send stamps 'now', not the caller's guess")

        val answer = out.get()
        assertTrue(answer["sent"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("pending", answer["delivered"]!!.jsonPrimitive.content, "await defaults to off")
        // The trace is the handle for await_event — it has to come back, generated or not.
        assertEquals(push.sent!!.traceId, answer["trace_id"]!!.jsonPrimitive.content)
    }

    @Test fun `inject_fcm replays a captured push field for field`() {
        val push = FakePush()
        val captured = fcm("fcm-1")
        val out = callAsync("inject_fcm", buildJsonObject { put("from_event_id", "fcm-1") }, listOf(captured), push = push)

        val message = push.sent!!.message
        assertEquals(mapOf("channel" to "order_assigned", "orderId" to "91"), message.data)
        assertEquals("Order assigned", message.notificationTitle)
        assertEquals("/topics/riders", message.from)
        // A replay is a new message: reusing the captured id would defeat the app's own dedup.
        assertNotEquals("fcm-1", message.messageId)
        assertTrue(out.get()["sent"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `a replay can be overridden field by field`() {
        val push = FakePush()
        callAsync(
            "inject_fcm",
            buildJsonObject { put("from_event_id", "fcm-1"); put("notification_title", "Order cancelled") },
            listOf(fcm("fcm-1")),
            push = push,
        )
        val message = push.sent!!.message
        assertEquals("Order cancelled", message.notificationTitle)
        assertEquals("Pick up at 7", message.notificationBody, "what wasn't stated still comes from the capture")
    }

    @Test fun `inject_fcm refuses an unknown id and a token refresh`() {
        val push = FakePush()
        assertTrue(
            callAsync("inject_fcm", buildJsonObject { put("from_event_id", "nope") }, push = push)
                .get().containsKey("error"),
        )
        val token = fcm("tok-1", title = null, data = emptyMap(), event = "token")
        assertTrue(
            callAsync("inject_fcm", buildJsonObject { put("from_event_id", "tok-1") }, listOf(token), push = push)
                .get().containsKey("error"),
            "a token refresh isn't a message — there's nothing to deliver",
        )
        assertEquals(null, push.sent)
    }

    @Test fun `inject_fcm warns loudly when the device can't take a push`() {
        val push = FakePush().apply { reason = "the device's library is too old." }
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }) },
            push = push,
        )
        val answer = out.get()
        assertFalse(answer["sent"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("none", answer["delivered"]!!.jsonPrimitive.content)
        assertTrue(answer["warning"]!!.jsonPrimitive.content.contains("NOT DELIVERED"))
        assertEquals(null, push.sent, "nothing is sent to a device that can't take it")
    }

    @Test fun `inject_fcm with await reports which tier consumed the push`() {
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }); put("await", true) },
            push = push,
        )
        assertFalse(out.answered, "await holds the call until the device answers")

        push.ack("handler")
        val answer = out.get()
        assertEquals("handler", answer["delivered"]!!.jsonPrimitive.content)
        assertTrue(answer["note"]!!.jsonPrimitive.content.contains("await_event"))
    }

    @Test fun `an injected push nothing consumed comes back with the handler guidance`() {
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }); put("await", true) },
            push = push,
        )
        push.ack("none", error = "no service in manifest")
        val answer = out.get()
        assertEquals("none", answer["delivered"]!!.jsonPrimitive.content)
        assertTrue(answer["warning"]!!.jsonPrimitive.content.contains("onPushInject"))
        assertEquals("no service in manifest", answer["error"]!!.jsonPrimitive.content)
    }

    @Test fun `an unacknowledged push says so rather than claiming delivery`() {
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }); put("await", true) },
            push = push,
        )
        push.neverAnswers()
        val answer = out.get()
        assertEquals("unknown", answer["delivered"]!!.jsonPrimitive.content)
        assertTrue(answer["warning"]!!.jsonPrimitive.content.contains("did not report"))
    }

    @Test fun `a late ack cannot write a second answer`() {
        // The transport turns one call into one HTTP response; a push that acks after we've
        // already answered must not produce another.
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }) },
            push = push,
        )
        assertEquals(1, out.answers)
        push.ack("handler")
        assertEquals(1, out.answers)
    }

    @Test fun `inject_fcm refuses a data map that isn't strings`() {
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("nested", buildJsonObject { put("a", "b") }) }) },
            push = push,
        )
        assertTrue(out.get().containsKey("error"))
        assertEquals(null, push.sent)
    }

    // ---- scenarios -----------------------------------------------------------------------------

    @Test fun `list_scenarios reports names, counts and where they live`() {
        val scenarios = FakeScenarios().apply {
            saved += McpTools.Scenarios.Info("offline-demo", 12, 1_700_000_000_000, "full happy path")
        }
        val out = callAsync("list_scenarios", scenarios = scenarios).get()
        assertEquals(1, out["count"]!!.jsonPrimitive.int())
        assertEquals(".logpose/scenarios", out["dir"]!!.jsonPrimitive.content)
        val entry = out["scenarios"]!!.jsonArray.single().jsonObject
        assertEquals("offline-demo", entry["name"]!!.jsonPrimitive.content)
        assertEquals(12, entry["rules"]!!.jsonPrimitive.int())
    }

    @Test fun `an empty scenario list explains how to make one`() {
        val out = callAsync("list_scenarios", scenarios = FakeScenarios()).get()
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("save_scenario"))
    }

    @Test fun `load_scenario reports the device state it ended in`() {
        val scenarios = FakeScenarios().apply {
            loadReport = McpTools.Scenarios.LoadReport(
                "offline-demo", found = true, rules = 12, replaced = true, activeRules = 12,
                deviceHint = "com.acme · synced rev 4", live = true,
            )
        }
        val out = callAsync(
            "load_scenario",
            buildJsonObject { put("name", "offline-demo"); put("replace", true) },
            scenarios = scenarios,
        ).get()

        assertEquals("offline-demo" to true, scenarios.lastLoad)
        assertEquals("replace", out["mode"]!!.jsonPrimitive.content)
        assertEquals(12, out["rules"]!!.jsonPrimitive.int())
        assertEquals("com.acme · synced rev 4", out["device"]!!.jsonPrimitive.content)
        // "Loaded" is not "live" — the note has to keep the two apart.
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("acknowledges"))
    }

    @Test fun `loading with capture off warns that nothing is serving`() {
        val scenarios = FakeScenarios().apply {
            loadReport = McpTools.Scenarios.LoadReport("demo", found = true, rules = 3, live = false)
        }
        val out = callAsync("load_scenario", buildJsonObject { put("name", "demo") }, scenarios = scenarios).get()
        assertTrue(out["warning"]!!.jsonPrimitive.content.contains("NOT SERVING YET"))
        assertEquals("merge", out["mode"]!!.jsonPrimitive.content, "merge is the safe default")
    }

    @Test fun `loading rules an old device can't take warns rather than pretending`() {
        val scenarios = FakeScenarios().apply {
            loadReport = McpTools.Scenarios.LoadReport("demo", found = true, rules = 3, live = true, withheld = 2)
        }
        val out = callAsync("load_scenario", buildJsonObject { put("name", "demo") }, scenarios = scenarios).get()
        assertTrue(out["warning"]!!.jsonPrimitive.content.contains("WITHHELD"))
    }

    @Test fun `load_scenario errors on an unknown or unusable name`() {
        val scenarios = FakeScenarios()
        assertTrue(callAsync("load_scenario", scenarios = scenarios).get().containsKey("error"))
        assertTrue(
            callAsync("load_scenario", buildJsonObject { put("name", "../etc/passwd") }, scenarios = scenarios)
                .get().containsKey("error"),
            "a name that could address another directory never reaches the store",
        )
        assertEquals(null, scenarios.lastLoad)
        assertTrue(
            callAsync("load_scenario", buildJsonObject { put("name", "ghost") }, scenarios = scenarios)
                .get().containsKey("error"),
        )
    }

    @Test fun `save_scenario passes the source through and reports the file`() {
        val scenarios = FakeScenarios()
        val out = callAsync(
            "save_scenario",
            buildJsonObject { put("name", "offline-demo"); put("from", "session"); put("success_only", true) },
            scenarios = scenarios,
        ).get()

        assertEquals(Triple("offline-demo", true, true), scenarios.lastSave)
        assertTrue(out["saved"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(".logpose/scenarios/offline-demo.json", out["path"]!!.jsonPrimitive.content)
        // Scenario files hold captured bodies; whoever is about to commit one must be told.
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("captured response bodies"))
    }

    @Test fun `save_scenario refuses a bad name or an unknown source`() {
        val scenarios = FakeScenarios()
        assertTrue(
            callAsync("save_scenario", buildJsonObject { put("name", "Offline Demo"); put("from", "rules") }, scenarios = scenarios)
                .get().containsKey("error"),
        )
        assertTrue(
            callAsync("save_scenario", buildJsonObject { put("name", "demo"); put("from", "everything") }, scenarios = scenarios)
                .get().containsKey("error"),
        )
        assertTrue(
            callAsync("save_scenario", buildJsonObject { put("name", "demo") }, scenarios = scenarios)
                .get().containsKey("error"),
            "'from' is stated explicitly: saving the wrong thing silently is worse than an error",
        )
        assertEquals(null, scenarios.lastSave)
    }

    @Test fun `save_scenario surfaces what a snapshot refused to guess at`() {
        val scenarios = FakeScenarios().apply {
            saveReport = McpTools.Scenarios.SaveReport(
                "demo", rules = 5, path = ".logpose/scenarios/demo.json",
                detail = "5 endpoints · skipped 3 in-flight/bodyless",
            )
        }
        val out = callAsync(
            "save_scenario",
            buildJsonObject { put("name", "demo"); put("from", "session") },
            scenarios = scenarios,
        ).get()
        assertTrue(out["detail"]!!.jsonPrimitive.content.contains("skipped 3"))
    }

    @Test fun `a failed save is an error, not a cheerful success`() {
        val scenarios = FakeScenarios().apply {
            saveReport = McpTools.Scenarios.SaveReport("demo", error = "There are no mock rules to save.")
        }
        val out = callAsync(
            "save_scenario",
            buildJsonObject { put("name", "demo"); put("from", "rules") },
            scenarios = scenarios,
        ).get()
        assertTrue(out["error"]!!.jsonPrimitive.content.contains("no mock rules"))
    }

    // ---- richer mock matching + sequential responses ---------------------------------------------

    @Test fun `create_mock carries the narrowing matchers onto the rule`() {
        val mocks = FakeMocks()
        val out = callWrite(
            "create_mock",
            buildJsonObject {
                put("path_pattern", "/orders")
                put("match_query", buildJsonObject { put("debug", "1"); put("page", "*") })
                put("match_headers", buildJsonObject { put("X-Tenant", "acme") })
                put("match_body_contains", "\"force\":true")
            },
            mocks,
        )

        val rule = mocks.rules.single()
        assertEquals(mapOf("debug" to "1", "page" to "*"), rule.matchQuery)
        assertEquals(mapOf("X-Tenant" to "acme"), rule.matchHeaders)
        assertEquals("\"force\":true", rule.matchBodyContains)
        // The constraints must show up in what's reported back, or a rule that reads "mock
        // /orders" looks like it fires on every call to it.
        val created = out["created"]!!.jsonObject
        assertEquals("1", created["match_query"]!!.jsonObject["debug"]!!.jsonPrimitive.content)
        assertEquals("1.7.0", created["needs_device_lib"]!!.jsonPrimitive.content)
    }

    @Test fun `create_mock builds a response sequence for retry testing`() {
        val mocks = FakeMocks()
        val out = callWrite(
            "create_mock",
            buildJsonObject {
                put("path_pattern", "/orders")
                put("responses", buildJsonArray {
                    add(buildJsonObject { put("status", 500) })
                    add(buildJsonObject { put("status", 200); put("body", """{"ok":true}"""); put("latency_ms", 50) })
                })
            },
            mocks,
        )

        val rule = mocks.rules.single()
        assertEquals(listOf(500, 200), rule.responses.map { it.status })
        assertEquals("""{"ok":true}""", rule.responses[1].body)
        assertEquals(50, rule.responses[1].latencyMillis)
        val created = out["created"]!!.jsonObject
        assertEquals(2, created["steps"]!!.jsonArray.size)
        assertFalse(created.containsKey("status"), "a sequence overrides the rule-level response")
    }

    @Test fun `a rule-level response alongside a sequence is called out, not silently dropped`() {
        val mocks = FakeMocks()
        val out = callWrite(
            "create_mock",
            buildJsonObject {
                put("path_pattern", "/orders")
                put("status", 418)
                put("responses", buildJsonArray { add(buildJsonObject { put("status", 200) }) })
            },
            mocks,
        )
        assertTrue(out["ignored"]!!.jsonPrimitive.content.contains("not served"))
    }

    @Test fun `a malformed step is rejected loudly, never coerced`() {
        val mocks = FakeMocks()
        fun step(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("path_pattern", "/x")
            put("responses", buildJsonArray { add(buildJsonObject(build)) })
        }

        // An unknown key would silently do nothing — exactly the kind of quiet mismatch a mock
        // must never have.
        assertTrue(callWrite("create_mock", step { put("status", 200); put("statuz", 500) }, mocks).containsKey("error"))
        assertTrue(callWrite("create_mock", step { put("body", "{}") }, mocks).containsKey("error"), "a step needs a status")
        assertTrue(callWrite("create_mock", step { put("status", 200); put("behavior", "explode") }, mocks).containsKey("error"))
        assertTrue(callWrite("create_mock", step { put("status", 9) }, mocks).containsKey("error"))
        assertTrue(
            callWrite("create_mock", buildJsonObject { put("path_pattern", "/x"); put("responses", "500") }, mocks)
                .containsKey("error"),
        )
        assertTrue(mocks.rules.isEmpty(), "no half-specified rule survives a rejected call")
    }

    @Test fun `a matcher that isn't an object of strings is rejected`() {
        val mocks = FakeMocks()
        assertTrue(
            callWrite("create_mock", buildJsonObject { put("path_pattern", "/x"); put("match_query", "debug=1") }, mocks)
                .containsKey("error"),
        )
        assertTrue(
            callWrite(
                "create_mock",
                buildJsonObject {
                    put("path_pattern", "/x")
                    put("match_headers", buildJsonObject {
                        put("X-Ids", buildJsonArray { add(JsonPrimitive("a")) })
                    })
                },
                mocks,
            ).containsKey("error"),
        )
        assertTrue(mocks.rules.isEmpty())
    }

    @Test fun `a rule an old device can't serve is reported as withheld, not active`() {
        // Excluding beats sending: an old library ignores the constraint and would mock every
        // call to the path — a mock matching more broadly than it reads.
        val mocks = FakeMocks().apply { libVersion = "1.6.0" }
        val out = callWrite(
            "create_mock",
            buildJsonObject {
                put("path_pattern", "/orders")
                put("match_query", buildJsonObject { put("debug", "1") })
            },
            mocks,
        )
        assertFalse(out["active"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out["warning"]!!.jsonPrimitive.content.contains("WITHHELD"))
        assertTrue(out["warning"]!!.jsonPrimitive.content.contains("1.6.0"), "name the version the device reports")
    }

    @Test fun `the same rule is active on a device new enough for it`() {
        val mocks = FakeMocks().apply { libVersion = "1.7.0" }
        val out = callWrite(
            "create_mock",
            buildJsonObject {
                put("path_pattern", "/orders")
                put("responses", buildJsonArray { add(buildJsonObject { put("status", 500) }) })
            },
            mocks,
        )
        assertTrue(out["active"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(out.containsKey("warning"))
    }

    @Test fun `a plain rule is unaffected by the version gate`() {
        // The fields that predate gating must never start being withheld, whatever the device says.
        val mocks = FakeMocks().apply { libVersion = null }
        val out = callWrite("create_mock", buildJsonObject { put("path_pattern", "/orders") }, mocks)
        assertTrue(out["active"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(mocks.rules.single().let { it.responses.isNotEmpty() || it.matchQuery.isNotEmpty() })
    }

    // ---- correlation -----------------------------------------------------------------------------
    //
    // The shape these tools exist for, taken from the capture that prompted them: one flow whose
    // events span two traces and one row with no trace at all, held together only by the order_id
    // a human knows — and, on the push, nested as a JSON string inside data["body"].

    private fun flow(): List<LogEvent> = listOf(
        fcm("push-1", data = mapOf("body" to """{"order_id":"21053953","trip_id":"88123"}"""), at = 1_000),
        // The id as a bare path segment, with no key beside it and no trace on the row.
        http("h1", path = "/app/v4/79096/order/21053953/", at = 1_100),
        http(
            "h2", method = "PUT", path = "/app/v4/order/accept/",
            body = """{"order_id":"21053953","trip_id":"88123"}""", traceId = "t2", at = 1_200,
        ),
        // A different order, in its own trace: the guard against a grouping that over-reaches.
        http("h3", path = "/app/v4/79096/order/21053999/", traceId = "t3", at = 1_300),
    )

    private fun orderKeys(vararg names: String) =
        FakeCorrelations(names.map { CorrelationKey(it) })

    private fun related(args: JsonObject, correlations: McpTools.Correlations): JsonObject =
        callAsync("get_related", args, flow(), correlations = correlations).get()

    @Test fun `list_correlation_keys keeps the configured vocabulary and inert suggestions apart`() {
        val correlations = orderKeys("order_id")
        val out = callAsync("list_correlation_keys", events = flow(), correlations = correlations).get()

        val configured = out["configured"]!!.jsonArray.single().jsonObject
        assertEquals(1, out["configured_count"]!!.jsonPrimitive.int())
        assertEquals("order_id", configured["key"]!!.jsonPrimitive.content)
        assertTrue(configured["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4, configured["min_length"]!!.jsonPrimitive.int())
        assertFalse(configured["allow_short_values"]!!.jsonPrimitive.content.toBoolean())
        // "the values seen most recently" — an agent can go straight from here to get_related.
        assertEquals("21053953", configured["latest_value"]!!.jsonPrimitive.content)

        val suggested = out["suggested"]!!.jsonArray.map { it.jsonObject }
        val trip = suggested.single { it["key"]!!.jsonPrimitive.content == "trip_id" }
        assertTrue(trip["events_grouped"]!!.jsonPrimitive.int() >= 2, "evidence, not just a name")
        assertTrue(trip["largest_group"]!!.jsonPrimitive.int() >= 2)
        assertEquals(2, trip["events_carrying"]!!.jsonPrimitive.int())
        assertEquals("88123", trip["latest_value"]!!.jsonPrimitive.content)
        assertFalse(
            suggested.any { it["key"]!!.jsonPrimitive.content == "order_id" },
            "a key already configured is not offered back as something to add",
        )
        // Suggestions are a discovery aid a human confirms; nothing here may read as enabled.
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("INERT"))
        assertEquals(listOf(CorrelationKey("order_id")), correlations.configured, "this tool enables nothing")
    }

    @Test fun `list_correlation_keys says when a project has no vocabulary yet`() {
        val out = callAsync("list_correlation_keys", events = flow(), correlations = orderKeys()).get()
        assertEquals(0, out["configured_count"]!!.jsonPrimitive.int())
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("No correlation key is configured"))
        // The capture still has something to say, and get_related works without a configured key.
        assertTrue(out["suggested_count"]!!.jsonPrimitive.int() >= 2)
    }

    @Test fun `list_correlation_keys explains a configured key that groups nothing`() {
        val correlations = FakeCorrelations(
            listOf(CorrelationKey("ghost_id"), CorrelationKey("order_id", enabled = false)),
        )
        val out = callAsync("list_correlation_keys", events = flow(), correlations = correlations).get()
        val byKey = out["configured"]!!.jsonArray.associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject
        }
        assertEquals(0, byKey["ghost_id"]!!["events_carrying"]!!.jsonPrimitive.int())
        assertTrue(byKey["ghost_id"]!!["note"]!!.jsonPrimitive.content.contains("No event in this capture"))
        assertTrue(byKey["order_id"]!!["note"]!!.jsonPrimitive.content.contains("Disabled"))
    }

    @Test fun `get_related groups a flow by key and value across traces and rows with none`() {
        val correlations = orderKeys("order_id")
        val out = related(buildJsonObject { put("key", "order_id"); put("value", "21053953") }, correlations)

        assertEquals("order_id 21053953", out["grouped_by"]!!.jsonPrimitive.content)
        assertEquals("order_id", out["key"]!!.jsonPrimitive.content)
        assertEquals("21053953", out["value"]!!.jsonPrimitive.content)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
        // The push (no trace), the GET whose only mention is a path segment (no trace), and the
        // PUT in its own trace — the grouping a trace structurally cannot produce.
        assertEquals(listOf("push-1", "h1", "h2"), out.ids())
        assertFalse(out.containsKey("note"), "a healthy group needs no explanation")
        // The scan may never run on the transport's IO thread.
        assertEquals(1, correlations.offThreadCalls)
    }

    @Test fun `get_related takes a bare value and labels it with the key that holds it`() {
        val out = related(buildJsonObject { put("value", "21053953") }, orderKeys("order_id"))
        assertEquals("order_id 21053953", out["grouped_by"]!!.jsonPrimitive.content)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
    }

    @Test fun `a bare value no key claims is grouped, and says it is unlabelled`() {
        val out = related(buildJsonObject { put("value", "21053999") }, orderKeys("order_id"))
        assertEquals("value 21053999", out["grouped_by"]!!.jsonPrimitive.content)
        assertFalse(out.containsKey("key"))
        assertEquals(listOf("h3"), out.ids())
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("grouped by the value alone"))
    }

    @Test fun `get_related reads a pasted key=value pair`() {
        val out = related(buildJsonObject { put("value", "\"order_id=21053953\"") }, orderKeys("order_id"))
        assertEquals("order_id 21053953", out["grouped_by"]!!.jsonPrimitive.content)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
    }

    @Test fun `get_related groups by an event's own keys and names the others`() {
        val out = related(buildJsonObject { put("event_id", "push-1") }, orderKeys("order_id", "trip_id"))

        // Configured order decides, exactly as the row menu and the hover glyph decide.
        assertEquals("order_id 21053953", out["grouped_by"]!!.jsonPrimitive.content)
        assertEquals(3, out["count"]!!.jsonPrimitive.int())
        val other = out["other_keys"]!!.jsonArray.single().jsonObject
        assertEquals("trip_id", other["key"]!!.jsonPrimitive.content)
        assertEquals("88123", other["value"]!!.jsonPrimitive.content)
        // Which id the flow is really about is the caller's question, so the road to it is stated.
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("trip_id 88123"))
    }

    @Test fun `the first configured key wins, and key picks another of the event's own`() {
        // Same row, vocabulary in the other order: the user's ordering is the precedence.
        val reordered = related(buildJsonObject { put("event_id", "push-1") }, orderKeys("trip_id", "order_id"))
        assertEquals("trip_id 88123", reordered["grouped_by"]!!.jsonPrimitive.content)

        val picked = related(
            buildJsonObject { put("event_id", "push-1"); put("key", "trip_id") },
            orderKeys("order_id", "trip_id"),
        )
        assertEquals("trip_id 88123", picked["grouped_by"]!!.jsonPrimitive.content)
        assertEquals(listOf("push-1", "h2"), picked.ids())
    }

    @Test fun `get_related refuses an unknown key instead of guessing`() {
        val out = related(buildJsonObject { put("key", "ordre_id"); put("value", "21053953") }, orderKeys("order_id"))
        val error = out["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("not a configured correlation key"))
        assertTrue(error.contains("order_id"), "name what is configured, so the fix is one call away")
    }

    @Test fun `a too-short value is refused out loud, never as an empty timeline`() {
        val out = related(buildJsonObject { put("key", "order_id"); put("value", "210") }, orderKeys("order_id"))
        assertTrue(out["too_short"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4, out["min_length"]!!.jsonPrimitive.int())
        assertEquals(0, out["count"]!!.jsonPrimitive.int())
        assertTrue(out["events"]!!.jsonArray.isEmpty())
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("TOO SHORT"))
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("short values"), "name the per-key opt-in")
        assertFalse(out.containsKey("error"), "an explicit result an agent can read, not a failure")
    }

    @Test fun `the per-key short-value opt-in is honoured, not re-decided`() {
        val opted = FakeCorrelations(listOf(CorrelationKey("order_id", allowShortValues = true)))
        val out = related(buildJsonObject { put("key", "order_id"); put("value", "210") }, opted)
        assertFalse(out.containsKey("too_short"), "the key's own rule decides, exactly as in the UI")
    }

    @Test fun `get_related says nothing carries a value, and stays delimiter-bounded`() {
        val missing = related(buildJsonObject { put("value", "99999999") }, orderKeys("order_id"))
        assertEquals(0, missing["count"]!!.jsonPrimitive.int())
        assertTrue(missing["note"]!!.jsonPrimitive.content.contains("No event carries this value"))

        // Four characters clears the length floor, so this is the boundary rule alone: '2105'
        // sitting inside '21053953' is not a match, or every id would group with every other.
        val partial = related(buildJsonObject { put("value", "2105") }, orderKeys("order_id"))
        assertEquals(0, partial["count"]!!.jsonPrimitive.int())
    }

    @Test fun `get_related reports the whole group even when it pages`() {
        val out = related(
            buildJsonObject { put("key", "order_id"); put("value", "21053953"); put("limit", 2) },
            orderKeys("order_id"),
        )
        assertEquals(3, out["count"]!!.jsonPrimitive.int(), "count is the group, not the page")
        assertEquals(2, out["returned"]!!.jsonPrimitive.int())
        assertEquals(listOf("h1", "h2"), out.ids(), "newest last, like list_events")
        assertTrue(out["note"]!!.jsonPrimitive.content.contains("limit"))
    }

    @Test fun `get_related needs something to group by, and a real event id`() {
        val correlations = orderKeys("order_id")
        assertTrue(related(JsonObject(emptyMap()), correlations)["error"]!!.jsonPrimitive.content.contains("Provide"))
        assertTrue(
            related(buildJsonObject { put("event_id", "ghost") }, correlations)["error"]!!
                .jsonPrimitive.content.contains("No event with id"),
        )
    }

    @Test fun `an event carrying no key is told what to do instead`() {
        val out = related(buildJsonObject { put("event_id", "h3") }, orderKeys("order_id"))
        val error = out["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("no groupable correlation key"))
        assertTrue(error.contains("t3"), "the row's own trace is the honest fallback to offer")
    }

    @Test fun `get_trace points at get_related when the trace isn't the flow`() {
        // The gandalf failure mode: the app mints its own trace, HTTP rows never join it, and the
        // trace answer is empty or a single row — which reads as "nothing happened" and isn't.
        val empty = call("get_trace", flow(), buildJsonObject { put("trace_id", "nope") })
        assertEquals(0, empty["count"]!!.jsonPrimitive.int())
        assertTrue(empty["note"]!!.jsonPrimitive.content.contains("get_related"))
        assertTrue(empty["note"]!!.jsonPrimitive.content.contains("business key"))

        val lone = call("get_trace", flow(), buildJsonObject { put("trace_id", "t2") })
        assertEquals(1, lone["count"]!!.jsonPrimitive.int())
        assertTrue(lone["note"]!!.jsonPrimitive.content.contains("get_related(event_id='h2')"))
    }

    @Test fun `a trace that really does hold a flow is left alone`() {
        val events = listOf(http("a", traceId = "t1"), http("b", traceId = "t1"))
        val out = call("get_trace", events, buildJsonObject { put("trace_id", "t1") })
        assertEquals(2, out["count"]!!.jsonPrimitive.int())
        assertFalse(out.containsKey("note"), "a working trace needs no advice")
    }

    @Test fun `a correlation failure is answered, never left hanging`() {
        // The work runs on a pooled thread; an exception escaping it would hold an agent's request
        // open until its client gave up, which is the one outcome this layer never allows.
        val broken = object : McpTools.Correlations {
            override fun keys(): List<CorrelationKey> = error("the cache is gone")
            override fun textOf(event: LogEvent) = ""
            override fun valuesOf(event: LogEvent) = emptyList<KeyValue>()
            override fun keyLabelFor(events: List<LogEvent>, value: String): String? = null
            override fun offThread(work: () -> Unit) = work()
        }
        assertTrue(related(buildJsonObject { put("value", "21053953") }, broken).containsKey("error"))
        assertTrue(
            callAsync("list_correlation_keys", events = flow(), correlations = broken)
                .get().containsKey("error"),
        )
    }

    @Test fun `inject_fcm reports the id the injected row will carry`() {
        // The ack correlation id, the message id and the FCM row's envelope id are one value. Two
        // ids meant the reported id named nothing on the timeline, and the app's own re-log of the
        // push landed beside it as a second, unmarked row.
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("data", buildJsonObject { put("k", "v") }) },
            push = push,
        ).get()

        val id = out["id"]!!.jsonPrimitive.content
        assertEquals(id, push.sent!!.id)
        assertEquals(id, push.sent!!.message.messageId)
    }

    @Test fun `a replayed push carries one fresh id too`() {
        val push = FakePush()
        val out = callAsync(
            "inject_fcm",
            buildJsonObject { put("from_event_id", "fcm-1") },
            listOf(fcm("fcm-1")),
            push = push,
        ).get()

        val id = out["id"]!!.jsonPrimitive.content
        assertEquals(id, push.sent!!.message.messageId)
        assertNotEquals("fcm-1", id, "a replay is a new message, or the app's own dedup swallows it")
    }
}
