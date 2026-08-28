package io.github.siddharthjaswal.logpose.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The plugin's `model/Transaction.kt` and the library's `wire/Wire.kt` are two declarations of
 * ONE JSON contract, kept in sync by hand. These tests pin the halves together from this side:
 * every payload below is written the way the device emits it (the library serializes with
 * `encodeDefaults = true; explicitNulls = false`), so a rename or a changed default on either
 * side fails here instead of on someone's phone.
 */
class WireContractTest {

    // Same configuration the library's emitter uses, so encoded output is comparable.
    private val wire = Json { encodeDefaults = true; explicitNulls = false }
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- FCM: the injected flag (library 1.7.0) ---------------------------------------------

    @Test
    fun `an injected fcm payload decodes with injected set`() {
        val json = """
            {"kind":"fcm","id":"p1","event":"message","receivedAtMillis":10,
             "data":{"order":"42"},"injected":true}
        """.trimIndent()
        val msg = lenient.decodeFromString(FcmMessage.serializer(), json)
        assertTrue(msg.injected)
        assertEquals("42", msg.data["order"])
    }

    @Test
    fun `a pre-1_7_0 fcm payload defaults injected to false`() {
        val json = """{"kind":"fcm","id":"p1","event":"message","receivedAtMillis":10}"""
        assertFalse(lenient.decodeFromString(FcmMessage.serializer(), json).injected)
    }

    // ---- MockRule: matchers + sequential steps (library 1.7.0) -------------------------------

    @Test
    fun `a rule with the new matchers and steps decodes field for field`() {
        val json = """
            {"id":"r1","method":"GET","pathPattern":"/v1/orders","status":200,"headers":{},
             "contentType":"application/json","latencyMillis":0,"behavior":"normal",
             "serveLimit":0,"enabled":true,"mode":"replace",
             "matchQuery":{"debug":"1","cursor":"*"},
             "matchHeaders":{"X-Env":"staging"},
             "matchBodyContains":"\"force\":true",
             "responses":[{"status":500,"headers":{},"contentType":"application/json",
                           "latencyMillis":0,"behavior":"normal"},
                          {"status":200,"body":"{\"ok\":true}","headers":{},
                           "contentType":"application/json","latencyMillis":250,
                           "behavior":"normal"}]}
        """.trimIndent()
        val rule = lenient.decodeFromString(MockRule.serializer(), json)

        assertEquals(mapOf("debug" to "1", "cursor" to MockRule.MATCH_ANY), rule.matchQuery)
        assertEquals(mapOf("X-Env" to "staging"), rule.matchHeaders)
        assertEquals("\"force\":true", rule.matchBodyContains)
        assertEquals(2, rule.responses.size)
        assertEquals(500, rule.responses[0].status)
        assertNull(rule.responses[0].body)
        assertEquals(250, rule.responses[1].latencyMillis)
        assertEquals("{\"ok\":true}", rule.responses[1].body)
        // Step defaults are the rule's defaults, not inherited from the rule instance.
        assertEquals("application/json", rule.responses[0].contentType)
        assertEquals(MockRule.BEHAVIOR_NORMAL, rule.responses[0].behavior)
    }

    @Test
    fun `a rule saved before the new fields existed still decodes`() {
        val json = """{"id":"old","method":"POST","pathPattern":"/v1/x","status":204}"""
        val rule = lenient.decodeFromString(MockRule.serializer(), json)
        assertTrue(rule.matchQuery.isEmpty())
        assertTrue(rule.matchHeaders.isEmpty())
        assertNull(rule.matchBodyContains)
        assertTrue(rule.responses.isEmpty())
        assertEquals(MockRule.MODE_REPLACE, rule.mode)
    }

    @Test
    fun `a pushed rule serializes under the field names the device reads`() {
        val rule = MockRule(
            id = "r1",
            method = "GET",
            pathPattern = "/v1/orders",
            matchQuery = mapOf("debug" to "1"),
            matchHeaders = mapOf("X-Env" to "staging"),
            matchBodyContains = "force",
            responses = listOf(MockStep(status = 500), MockStep(status = 200, body = "{}")),
        )
        val encoded = wire.encodeToString(MockRule.serializer(), rule)

        for (field in listOf("matchQuery", "matchHeaders", "matchBodyContains", "responses")) {
            assertTrue(encoded.contains("\"$field\""), "missing $field in $encoded")
        }
        assertEquals(rule, lenient.decodeFromString(MockRule.serializer(), encoded))
    }

    // ---- Push injection (library 1.7.0) ------------------------------------------------------

    @Test
    fun `a push inject command round-trips through the library's shape`() {
        val inject = PushInject(
            id = "inj-1",
            traceId = "t-9",
            message = PushMessage(
                messageId = "m1",
                from = "/topics/orders",
                collapseKey = "orders",
                sentTimeMillis = 1_700_000_000_000,
                ttlSeconds = 60,
                priority = 2,
                notificationTitle = "Order assigned",
                notificationBody = "Tap to view",
                data = mapOf("order_id" to "42"),
            ),
        )
        val encoded = wire.encodeToString(PushInject.serializer(), inject)
        assertTrue(encoded.contains("\"kind\":\"push_inject\""))
        // Flat notification fields — the command mirrors FcmMessageInfo, not FcmMessage.
        assertTrue(encoded.contains("\"notificationTitle\":\"Order assigned\""))
        assertEquals(inject, lenient.decodeFromString(PushInject.serializer(), encoded))
    }

    @Test
    fun `a push inject written by the device library decodes here`() {
        val json = """
            {"kind":"push_inject","id":"inj-2","traceId":"t-1",
             "message":{"from":"/topics/x","data":{"k":"v"},"notificationTitle":"Hi"}}
        """.trimIndent()
        val inject = lenient.decodeFromString(PushInject.serializer(), json)
        assertEquals("inj-2", inject.id)
        assertEquals("t-1", inject.traceId)
        assertEquals("v", inject.message.data["k"])
        assertEquals("Hi", inject.message.notificationTitle)
        assertNull(inject.message.messageId)
    }

    @Test
    fun `push ack delivery tiers match the library's constants`() {
        assertEquals("handler", PushAck.DELIVERED_HANDLER)
        assertEquals("service", PushAck.DELIVERED_SERVICE)
        assertEquals("none", PushAck.DELIVERED_NONE)

        val json = """{"kind":"push_ack","pkg":"com.x","id":"inj-1","delivered":"none",
                       "error":"no handler"}"""
        val ack = lenient.decodeFromString(PushAck.serializer(), json)
        assertEquals("com.x", ack.pkg)
        assertEquals(PushAck.DELIVERED_NONE, ack.delivered)
        assertEquals("no handler", ack.error)

        // A minimal ack (delivery succeeded, no error field emitted at all) still decodes.
        val ok = lenient.decodeFromString(
            PushAck.serializer(),
            """{"kind":"push_ack","pkg":"com.x","id":"inj-1","delivered":"handler"}""",
        )
        assertEquals(PushAck.DELIVERED_HANDLER, ok.delivered)
        assertNull(ok.error)
    }

    // ---- Hello / MockAck rule counts (read from 1.6.0 on) ------------------------------------

    @Test
    fun `hello and ack carry the rule count the sync check compares against`() {
        val hello = lenient.decodeFromString(
            Hello.serializer(),
            """{"kind":"hello","pkg":"com.x","libVersion":"1.7.0","mockRevision":4,
                "ruleCount":0,"processId":"p9"}""",
        )
        assertEquals(4, hello.mockRevision)
        assertEquals(0, hello.ruleCount)
        assertEquals("1.7.0", hello.libVersion)

        val ack = lenient.decodeFromString(
            MockAck.serializer(),
            """{"kind":"mock_ack","pkg":"com.x","revision":4,"ruleCount":2,"hits":{"r1":3}}""",
        )
        assertEquals(2, ack.ruleCount)
        assertEquals(3, ack.hits["r1"])
    }
}
