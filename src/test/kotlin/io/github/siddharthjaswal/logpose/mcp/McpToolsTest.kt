package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.ConfigChange
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
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
    ): LogEvent.Worker {
        val work = WorkerEvent(worker = name, state = state, workId = id, runAttempt = attempt)
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

    @Test fun `find_slow_queries ranks by duration and reports the SQL`() {
        val events = listOf(
            db("fast", "SELECT * FROM users", durationMillis = 3),
            db("slow", "SELECT * FROM orders JOIN items", durationMillis = 240),
            db("mid", "UPDATE riders SET x = 1", durationMillis = 40),
        )
        val out = call("find_slow_queries", events)
        val ids = out["queries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("slow", "mid", "fast"), ids)

        val slowest = out["queries"]!!.jsonArray.first().jsonObject
        assertEquals("orders", slowest["table"]!!.jsonPrimitive.content, "table is parsed from the SQL")
        assertEquals(240, slowest["duration_ms"]!!.jsonPrimitive.int())
    }

    @Test fun `find_slow_queries excludes unmeasured queries instead of calling them instant`() {
        // Room's query callback gives no timing. Treating those as 0ms would sort them to the
        // fast end of a slowness ranking — exactly backwards — so they're excluded and counted.
        val events = listOf(db("measured", durationMillis = 50), db("unmeasured", durationMillis = null))
        val out = call("find_slow_queries", events)

        assertEquals(2, out["total_queries"]!!.jsonPrimitive.int())
        assertEquals(1, out["measured"]!!.jsonPrimitive.int())
        assertTrue(out.containsKey("note"), "the exclusion must be stated, not silent")
        assertEquals(
            listOf("measured"),
            out["queries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )
    }

    @Test fun `find_slow_queries filters by threshold and table`() {
        val events = listOf(
            db("a", "SELECT * FROM users", durationMillis = 5),
            db("b", "SELECT * FROM orders", durationMillis = 300),
            db("c", "SELECT * FROM users", durationMillis = 100),
        )
        assertEquals(
            listOf("b", "c"),
            call("find_slow_queries", events, buildJsonObject { put("min_ms", 50) })
                .queryIds(),
        )
        assertEquals(
            listOf("c", "a"),
            call("find_slow_queries", events, buildJsonObject { put("table", "users") }).queryIds(),
        )
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

    @Test fun `worker durations say what they include`() {
        // WorkInfo reports state, not execution time, so a duration covers queue time too.
        val out = call("worker_history", listOf(worker("w1", durationMillis = 1_500)))
        val entry = out["workers"]!!.jsonArray.single().jsonObject
        assertEquals(1_500, entry["duration_ms"]!!.jsonPrimitive.int())
        assertTrue(entry["duration_note"]!!.jsonPrimitive.content.contains("queue time"))
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
        assertEquals(listOf("bad-sql", "w-failed"), call("find_failures", events).ids())
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

    private fun JsonObject.queryIds(): List<String> =
        this["queries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    // ---- mock write tools ------------------------------------------------------------------

    /** In-memory stand-in for MocksController, so the tool behavior is testable on its own. */
    private class FakeMocks : McpTools.Mocks {
        val rules = mutableListOf<MockRule>()
        var lastBaseBody: String? = null
        override fun list() = rules.toList()
        override fun hits() = mapOf<String, Int>()
        override fun deviceHint() = "test-device · synced rev 1"
        override fun create(rule: MockRule, baseBody: String?) { rules += rule; lastBaseBody = baseBody }
        override fun setEnabled(id: String, enabled: Boolean) {
            rules.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        override fun delete(id: String) { rules.removeAll { it.id == id } }
    }

    private fun callWrite(name: String, args: JsonObject, mocks: McpTools.Mocks, events: List<LogEvent> = emptyList()) =
        McpTools.call(name, args, events, { 0 }, true, mocks).jsonObject

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

    @Test fun `write tools refuse cleanly when mocking is unavailable`() {
        val out = call("create_mock", emptyList(), buildJsonObject { put("path_pattern", "/x") })
        assertTrue(out.containsKey("error"))
    }

    @Test fun `every catalogued tool declares a schema and is callable`() {
        val names = McpTools.catalogue().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "list_events", "get_event", "get_trace", "find_failures", "session_summary",
                "find_slow_queries", "worker_history", "config_changes",
                "list_mocks", "create_mock", "set_mock_enabled", "delete_mock",
            ),
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
