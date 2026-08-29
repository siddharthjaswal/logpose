package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.ConfigChange
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Section
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The correlation model is the whole design risk of the feature, so this covers it at the level
 * the PRD states it: the key names the value, the value does the matching, and neither half is
 * allowed to guess.
 *
 * Fixtures mirror the real gandalf capture that motivated the design — an FCM push whose data map
 * hides the meaningful JSON inside a *string*, and an HTTP call that carries the same id as a bare
 * path segment with no key anywhere near it, in a different trace (or none at all).
 */
class CorrelationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val orderKey = CorrelationKey("order_id")

    // ---- fixtures ---------------------------------------------------------------------------

    /** An event carrying an arbitrary payload — extraction only ever reads the envelope payload. */
    private fun raw(
        id: String,
        payload: JsonElement,
        traceId: String? = null,
        at: Long = 1_000,
    ): LogEvent.Generic = LogEvent.Generic(
        null,
        Envelope(kind = "app", id = id, at = at, endedAt = at, traceId = traceId, payload = payload),
    )

    private fun http(
        id: String,
        method: String = "GET",
        path: String = "/app/v4/79096/order/21053953/",
        requestBody: String? = null,
        responseBody: String? = null,
        headers: Map<String, String> = emptyMap(),
        traceId: String? = null,
        at: Long = 1_000,
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = at,
            request = Request(
                method = method,
                url = "https://api.example.com$path",
                host = "api.example.com",
                path = path,
                headers = headers,
                body = requestBody?.let { Body(text = it) },
            ),
            response = Response(code = 200, body = responseBody?.let { Body(text = it) }),
            durationMillis = 30,
        )
        return LogEvent.Http(
            tx,
            Envelope(
                kind = Envelope.KIND_HTTP, id = id, at = at, endedAt = at + 30, traceId = traceId,
                payload = json.encodeToJsonElement(tx),
            ),
        )
    }

    private fun fcm(
        id: String,
        data: Map<String, String>,
        traceId: String? = null,
        at: Long = 900,
    ): LogEvent.Fcm {
        val msg = FcmMessage(id = id, messageId = id, receivedAtMillis = at, data = data)
        return LogEvent.Fcm(
            msg,
            Envelope(
                kind = Envelope.KIND_FCM, id = id, at = at, endedAt = at, traceId = traceId,
                payload = json.encodeToJsonElement(msg),
            ),
        )
    }

    /** gandalf's real shape: the payload that matters is a JSON *string* under `data["body"]`. */
    private fun gandalfPush(orderId: String = "21053953", traceId: String? = "trc-916a23d1") = fcm(
        id = "inj-a919724d",
        traceId = traceId,
        data = mapOf(
            "channel" to "order-assigned",
            "body" to """{"notification":{"title":"New order"},"data":{"order_id":$orderId,"trip_id":"TRP-99881"}}""",
        ),
    )

    // ---- extract: the nested-JSON case --------------------------------------------------------

    @Test fun `reads a key out of JSON nested in a string value`() {
        // A shallow scan of this payload finds nothing at all — data["body"] is a String.
        assertEquals(mapOf("order_id" to "21053953"), Correlation.valuesFor(gandalfPush(), listOf(orderKey)))
    }

    @Test fun `reads a key out of an HTTP response body`() {
        val event = http("h1", responseBody = """{"result":{"order_id":"21053953","eta":12}}""")
        assertEquals(mapOf("order_id" to "21053953"), Correlation.valuesFor(event, listOf(orderKey)))
    }

    @Test fun `reads a key nested several levels down, JSON inside JSON inside JSON`() {
        val inner = """{"order":{"order_id":"21053953"}}"""
        val middle = """{"payload":${json.encodeToJsonElement(inner)}}"""
        val event = raw("e1", buildJsonObject { put("envelope", middle) })
        assertEquals("21053953", Correlation.valuesFor(event, listOf(orderKey))["order_id"])
    }

    @Test fun `key spelling is case and snake-camel insensitive`() {
        val spellings = listOf("order_id", "orderId", "ORDER_ID", "order-id", "OrderID", "orderid")
        for (spelling in spellings) {
            val event = raw("e-$spelling", buildJsonObject { put(spelling, "21053953") })
            assertEquals(
                "21053953",
                Correlation.valuesFor(event, listOf(orderKey))["order_id"],
                "payload key '$spelling' should satisfy configured key 'order_id'",
            )
        }
        // …and the configured spelling is equally free — the user may type it either way.
        val event = raw("e-camel-config", buildJsonObject { put("order_id", "21053953") })
        assertEquals("21053953", Correlation.valuesFor(event, listOf(CorrelationKey("orderId")))["orderId"])
    }

    @Test fun `a near-miss key name is not the configured key`() {
        val event = raw("e1", buildJsonObject { put("parent_order_id", "21053953") })
        assertTrue(Correlation.valuesFor(event, listOf(orderKey)).isEmpty())
    }

    @Test fun `numbers, not just strings, are read as values`() {
        val event = raw("e1", buildJsonObject { put("order_id", 21053953) })
        assertEquals("21053953", Correlation.valuesFor(event, listOf(orderKey))["order_id"])
    }

    @Test fun `disabled keys are skipped and an empty key list extracts nothing`() {
        val push = gandalfPush()
        assertTrue(Correlation.valuesFor(push, listOf(orderKey.copy(enabled = false))).isEmpty())
        assertTrue(Correlation.valuesFor(push, emptyList()).isEmpty())
        assertTrue(Correlation.extract(push, listOf(orderKey.copy(enabled = false))).isEmpty())
    }

    // ---- guards: length and delimiters ---------------------------------------------------------

    @Test fun `a short value is extracted but not matchable`() {
        val event = raw("e1", buildJsonObject { put("order_id", "210") })

        val extracted = Correlation.extract(event, listOf(orderKey)).single()
        assertEquals("210", extracted.value)
        assertFalse(extracted.matchable, "210 is below the 4-character floor")

        // valuesFor answers "what can this row group by", so a low-confidence value is absent.
        assertTrue(Correlation.valuesFor(event, listOf(orderKey)).isEmpty())
    }

    @Test fun `a key can opt in to short values`() {
        val event = raw("e1", buildJsonObject { put("order_id", "210") })
        val opted = CorrelationKey("order_id", allowShortValues = true)

        assertTrue(Correlation.extract(event, listOf(opted)).single().matchable)
        assertEquals(mapOf("order_id" to "210"), Correlation.valuesFor(event, listOf(opted)))
        assertEquals(1, Correlation.group(listOf(event), opted, "210").size)
    }

    @Test fun `a custom minimum length is honoured`() {
        val event = raw("e1", buildJsonObject { put("order_id", "210539") })
        assertFalse(Correlation.extract(event, listOf(CorrelationKey("order_id", minLength = 8))).single().matchable)
        assertTrue(Correlation.extract(event, listOf(CorrelationKey("order_id", minLength = 6))).single().matchable)
    }

    @Test fun `matching is delimiter-bounded in both directions`() {
        val path = "GET /app/v4/79096/order/21053953/"
        assertTrue(Correlation.containsValue(path, "21053953"))
        // The short id must not match inside the long one…
        assertFalse(Correlation.containsValue(path, "2105"))
        assertFalse(Correlation.containsValue(path, "3953"))
        // …nor the long one inside a longer one.
        assertFalse(Correlation.containsValue("/order/210539531/", "21053953"))
        assertFalse(Correlation.containsValue("/order/x21053953/", "21053953"))
        // Underscores, dashes and quotes are delimiters, per the [A-Za-z0-9] rule.
        assertTrue(Correlation.containsValue("""{"id":"21053953"}""", "21053953"))
        assertTrue(Correlation.containsValue("order_21053953_v2", "21053953"))
        assertTrue(Correlation.containsValue("21053953", "21053953"))
    }

    @Test fun `matching is case-insensitive`() {
        assertTrue(Correlation.containsValue("trace TRC-916A23D1 done", "trc-916a23d1"))
        assertFalse(Correlation.containsValue("trace XTRC-916A23D1X done", "trc-916a23d1"))
    }

    @Test fun `a value that occurs twice, once bounded, still matches`() {
        // First occurrence is flanked; the scan must keep looking rather than stop at it.
        assertTrue(Correlation.containsValue("x21053953x and then /order/21053953/", "21053953"))
    }

    // ---- group ----------------------------------------------------------------------------------

    @Test fun `groups a flow across different traces and events with no trace`() {
        val push = gandalfPush(traceId = "trc-916a23d1")
        val get = http("h1", path = "/app/v4/79096/order/21053953/", traceId = null)
        val accept = http("h2", method = "PUT", path = "/app/v4/order/accept/", traceId = "26ef6ebb", requestBody = """{"orderId":"21053953"}""")
        val decoy = http("h3", path = "/app/v4/79096/order/210539531/", traceId = "other")
        val unrelated = raw("e9", buildJsonObject { put("order_id", "88881111") })
        val events = listOf(push, get, accept, decoy, unrelated)

        val group = Correlation.group(events, "order_id", "21053953")

        assertEquals(listOf("inj-a919724d", "h1", "h2"), group.map { it.id })
        // Three traces' worth of events — one of which has no trace at all. This is the thing
        // trace grouping structurally cannot do, and the reason the feature exists.
        assertEquals(setOf("trc-916a23d1", null, "26ef6ebb"), group.map { it.traceId }.toSet())
    }

    @Test fun `grouping preserves arrival order`() {
        val events = listOf(
            raw("a", buildJsonObject { put("order_id", "21053953") }, at = 1),
            raw("b", buildJsonObject { put("order_id", "21053953") }, at = 2),
            raw("c", buildJsonObject { put("order_id", "21053953") }, at = 3),
        )
        assertEquals(listOf("a", "b", "c"), Correlation.group(events, null, "21053953").map { it.id })
    }

    @Test fun `a too-short value groups nothing rather than grouping loosely`() {
        val events = listOf(http("h1", path = "/app/v4/order/210/"))
        assertTrue(Correlation.group(events, "order_id", "210").isEmpty())
        assertTrue(Correlation.group(events, "order_id", "  ").isEmpty())
        // Opted in, the same call groups — the guard is a floor, not a ban.
        assertEquals(1, Correlation.group(events, "order_id", "210", allowShortValues = true).size)
    }

    @Test fun `group takes cached haystacks so nothing is rescanned`() {
        val events = listOf(http("h1"), http("h2", path = "/health"))
        var scans = 0
        val cache = events.associate { it.id to Correlation.searchableText(it) }

        val group = Correlation.group(events, "order_id", "21053953") { event ->
            scans++
            cache.getValue(event.id)
        }

        assertEquals(listOf("h1"), group.map { it.id })
        assertEquals(2, scans, "group must consult the supplied lookup, never rescan behind it")
    }

    @Test fun `every kind is searchable, bodies and all`() {
        val value = "21053953"
        val db = LogEvent.Db(
            DbQuery(sql = "SELECT * FROM orders WHERE id = ?", args = listOf(value)),
            Envelope(kind = Envelope.KIND_DB, id = "d1", at = 1, payload = JsonPrimitive("")),
        )
        val worker = LogEvent.Worker(
            WorkerEvent(worker = "SyncWorker", state = "running", inputData = mapOf("order" to value)),
            Envelope(kind = Envelope.KIND_WORKER, id = "w1", at = 1, payload = JsonPrimitive("")),
        )
        val config = LogEvent.Config(
            ConfigUpdate(changes = listOf(ConfigChange(key = "pinned_order", value = value))),
            Envelope(kind = Envelope.KIND_CONFIG, id = "c1", at = 1, payload = JsonPrimitive("")),
        )
        val app = LogEvent.Generic(
            GenericEvent(
                title = "Order screen",
                badges = listOf(Badge("NAV")),
                sections = listOf(Section("Args", Section.TYPE_KV, buildJsonObject { put("order_id", value) })),
            ),
            Envelope(kind = Envelope.KIND_EVENT, id = "g1", at = 1, payload = JsonPrimitive("")),
        )
        val unknown = raw("u1", buildJsonObject { put("whatever", value) })

        val events = listOf(db, worker, config, app, unknown, http("h1", path = "/health"))
        assertEquals(
            listOf("d1", "w1", "c1", "g1", "u1"),
            Correlation.group(events, null, value).map { it.id },
        )
    }

    @Test fun `an id or trace pasted from elsewhere finds its own event`() {
        val event = http("inj-a919724d", path = "/health", traceId = "trc-916a23d1")
        assertTrue(Correlation.matches(event, "inj-a919724d"))
        assertTrue(Correlation.matches(event, "trc-916a23d1"))
    }

    @Test fun `headers are deliberately not searchable`() {
        // Auth tokens and trace headers would add matches for values the user never sees on the
        // row; §3 lists urls and bodies, not headers.
        val event = http("h1", path = "/health", headers = mapOf("Authorization" to "Bearer 21053953"))
        assertFalse(Correlation.matches(event, "21053953"))
    }

    // ---- suggest ----------------------------------------------------------------------------

    @Test fun `suggests id-ish keys ranked by how many events they would group`() {
        val events = listOf(
            gandalfPush(),                                                     // order_id + trip_id
            http("h1", path = "/app/v4/79096/order/21053953/"),                // matched by value only
            http("h2", method = "PUT", path = "/app/v4/order/accept/", requestBody = """{"order_id":"21053953"}"""),
            raw("e1", buildJsonObject { put("trip_id", "TRP-99881") }),
        )

        val suggestions = Correlation.suggest(events)
        val order = suggestions.single { it.key == "order_id" }
        val trip = suggestions.single { it.key == "trip_id" }

        assertEquals("order_id", suggestions.first().key)
        assertEquals(3, order.eventsGrouped)
        assertEquals(3, order.largestGroup)
        // The key is named by two events but reaches three — the point of the whole design.
        assertEquals(2, order.eventsCarrying)
        assertEquals("21053953", order.latestValue)
        assertEquals(2, trip.eventsGrouped)
        assertTrue(order.eventsGrouped > trip.eventsGrouped)
    }

    @Test fun `suggestions are inert - nothing groups without a configured key`() {
        val events = listOf(gandalfPush(), http("h1"))
        val suggestions = Correlation.suggest(events)

        assertTrue(suggestions.any { it.key == "order_id" })
        // A suggestion is not a key: until one is configured, extraction yields nothing.
        assertTrue(Correlation.valuesFor(events.first(), emptyList()).isEmpty())
    }

    @Test fun `only id-ish names are suggested`() {
        val events = listOf(
            raw("a", buildJsonObject { put("valid", "21053953"); put("paid", "21053953"); put("android", "21053953") }),
            raw("b", buildJsonObject { put("valid", "21053953"); put("order_uuid", "21053953") }),
        )
        val names = Correlation.suggest(events).map { it.key }
        assertEquals(listOf("order_uuid"), names)
    }

    @Test fun `values that are not identifiers are not suggested`() {
        val events = listOf(
            raw("a", buildJsonObject { put("order_id", "true"); put("trip_id", "a long human sentence") }),
            raw("b", buildJsonObject { put("order_id", "true"); put("trip_id", "a long human sentence") }),
        )
        assertTrue(Correlation.suggest(events).isEmpty())
    }

    @Test fun `a key whose values group only one event each is not suggested`() {
        val events = listOf(
            raw("a", buildJsonObject { put("request_id", "aaaa1111") }),
            raw("b", buildJsonObject { put("request_id", "bbbb2222") }),
            raw("c", buildJsonObject { put("request_id", "cccc3333") }),
        )
        assertTrue(Correlation.suggest(events).isEmpty())
    }

    @Test fun `suggest handles an empty capture and honours its limit`() {
        assertTrue(Correlation.suggest(emptyList()).isEmpty())
        val events = listOf(
            raw("a", buildJsonObject { put("order_id", "21053953"); put("trip_id", "88881111") }),
            raw("b", buildJsonObject { put("order_id", "21053953"); put("trip_id", "88881111") }),
        )
        assertEquals(1, Correlation.suggest(events, limit = 1).size)
    }

    // ---- find by value ------------------------------------------------------------------------

    @Test fun `a bare pasted value is ready to group`() {
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("21053953"))
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("   21053953  "))
    }

    @Test fun `a key equals value pair keeps the key as the label`() {
        assertEquals(FindQuery.Ready("order_id", "21053953"), Correlation.parseFindQuery("order_id=21053953"))
        assertEquals(FindQuery.Ready("order_id", "21053953"), Correlation.parseFindQuery(" order_id = 21053953 "))
        assertEquals(FindQuery.Ready("orderId", "21053953"), Correlation.parseFindQuery("orderId=\"21053953\""))
    }

    @Test fun `surrounding quotes are stripped, as ids arrive out of JSON`() {
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("\"21053953\""))
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("'21053953'"))
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("`21053953`"))
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("“21053953”"))
        assertEquals(FindQuery.Ready(null, "21053953"), Correlation.parseFindQuery("  \" 21053953 \"  "))
    }

    @Test fun `a too-short value says so instead of returning an empty result`() {
        assertEquals(FindQuery.TooShort(null, "210", 4), Correlation.parseFindQuery("210"))
        assertEquals(FindQuery.TooShort("order_id", "21", 4), Correlation.parseFindQuery("order_id=21"))
        // Opting in is the escape hatch, and a custom floor is honoured.
        assertEquals(FindQuery.Ready(null, "210"), Correlation.parseFindQuery("210", allowShortValues = true))
        assertEquals(FindQuery.TooShort(null, "21053953", 12), Correlation.parseFindQuery("21053953", minLength = 12))
    }

    @Test fun `an empty input is not an error`() {
        assertEquals(FindQuery.Empty, Correlation.parseFindQuery(""))
        assertEquals(FindQuery.Empty, Correlation.parseFindQuery("   "))
        assertEquals(FindQuery.Empty, Correlation.parseFindQuery("\"\""))
    }

    @Test fun `a value that merely contains an equals sign stays a value`() {
        // base64 padding is the case that breaks a naive split-on-first-equals.
        assertEquals(FindQuery.Ready(null, "MjEwNTM5NTM="), Correlation.parseFindQuery("MjEwNTM5NTM="))
        assertEquals(FindQuery.Ready(null, "abc=="), Correlation.parseFindQuery("abc=="))
        // A colon is not a separator: too many bare values (urls, timestamps) carry one.
        assertEquals(FindQuery.Ready(null, "2026-08-29T10:00:00"), Correlation.parseFindQuery("2026-08-29T10:00:00"))
    }

    @Test fun `a bare value is labelled by whichever configured key holds it`() {
        val events = listOf(gandalfPush(), http("h1"))
        assertEquals("order_id", Correlation.keyLabelFor(events, listOf(orderKey), "21053953"))
        assertNull(Correlation.keyLabelFor(events, listOf(orderKey), "99999999"))
        assertNull(Correlation.keyLabelFor(events, emptyList(), "21053953"))
    }

    @Test fun `the newest event wins when a value is labelled`() {
        val events = listOf(
            raw("old", buildJsonObject { put("order_id", "21053953") }),
            raw("new", buildJsonObject { put("trip_id", "21053953") }),
        )
        val keys = listOf(CorrelationKey("order_id"), CorrelationKey("trip_id"))
        assertEquals("trip_id", Correlation.keyLabelFor(events, keys, "21053953"))
    }

    // ---- safety ------------------------------------------------------------------------------

    @Test fun `strings that are not JSON are ignored quietly`() {
        val event = raw(
            "e1",
            buildJsonObject {
                put("body", "{ this is not json at all")
                put("other", "[unclosed")
                put("plain", "hello world")
                put("order_id", "21053953")
            },
        )
        assertEquals("21053953", Correlation.valuesFor(event, listOf(orderKey))["order_id"])
    }

    @Test fun `absurdly nested embedded JSON is skipped rather than parsed`() {
        val deep = buildString {
            repeat(400) { append('[') }
            append("""{"order_id":"21053953"}""")
            repeat(400) { append(']') }
        }
        val event = raw("e1", buildJsonObject { put("body", deep) })
        assertTrue(Correlation.valuesFor(event, listOf(orderKey)).isEmpty())

        // …while ordinary nesting is still read, so the cap isn't just "always off".
        val shallow = buildString {
            repeat(3) { append('[') }
            append("""{"order_id":"21053953"}""")
            repeat(3) { append(']') }
        }
        val ok = raw("e2", buildJsonObject { put("body", shallow) })
        assertEquals("21053953", Correlation.valuesFor(ok, listOf(orderKey))["order_id"])
    }

    @Test fun `a huge payload is bounded, not endless`() {
        val big = buildJsonObject {
            put("order_id", "21053953")
            repeat(30_000) { put("filler_$it", "value_$it") }
        }
        val event = raw("e1", big)

        assertEquals("21053953", Correlation.valuesFor(event, listOf(orderKey))["order_id"])
        // The haystack is capped, so one pathological payload can't stall a scan.
        assertTrue(Correlation.searchableText(event).isNotEmpty())
    }

    @Test fun `a payload that is not an object at all is harmless`() {
        assertTrue(Correlation.valuesFor(raw("e1", JsonPrimitive("just a string")), listOf(orderKey)).isEmpty())
        assertTrue(Correlation.valuesFor(raw("e2", JsonPrimitive(42)), listOf(orderKey)).isEmpty())
        assertTrue(Correlation.suggest(listOf(raw("e3", JsonPrimitive("x")))).isEmpty())
        assertEquals(emptyList<LogEvent>(), Correlation.group(emptyList(), "order_id", "21053953"))
    }

    @Test fun `a blank or absurd key name is ignored`() {
        val event = raw("e1", buildJsonObject { put("order_id", "21053953") })
        assertTrue(Correlation.valuesFor(event, listOf(CorrelationKey("   "))).isEmpty())
        assertTrue(Correlation.valuesFor(event, listOf(CorrelationKey("___"))).isEmpty())
    }
}
