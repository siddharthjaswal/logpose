package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.wire.FcmMessage
import io.github.siddharthjaswal.logpose.wire.PushAck
import io.github.siddharthjaswal.logpose.wire.PushInject
import io.github.siddharthjaswal.logpose.wire.PushMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-injection half of the wire contract. Both directions matter: the IDE writes
 * [PushInject] and the device must read every field back, and the device writes [PushAck] and the
 * IDE must read that. Encoding matches the emitter's (`explicitNulls = false`) and decoding the
 * receiver's (`ignoreUnknownKeys`), so what's asserted here is what actually travels.
 */
class PushWireTest {

    private val out = Json { encodeDefaults = true; explicitNulls = false }
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T =
        parser.decodeFromString(serializer, out.encodeToString(serializer, value))

    @Test fun `an injection round-trips every push field`() {
        val injection = PushInject(
            id = "push-1",
            traceId = "trace-9",
            message = PushMessage(
                messageId = "0:1700000000",
                from = "/topics/orders",
                to = "device-token",
                collapseKey = "order-assigned",
                messageType = "gcm",
                sentTimeMillis = 1_700_000_000_000,
                ttlSeconds = 3600,
                priority = 2,
                notificationTitle = "New order",
                notificationBody = "Pickup in 4 min",
                notificationChannelId = "orders",
                notificationClickAction = "OPEN_ORDER",
                notificationImageUrl = "https://ex.com/x.png",
                data = mapOf("order_id" to "21047484", "action" to "assign"),
            ),
        )
        assertEquals(injection, roundTrip(PushInject.serializer(), injection))
        assertEquals("push_inject", roundTrip(PushInject.serializer(), injection).kind)
    }

    @Test fun `an injection without a trace decodes with none`() {
        val back = parser.decodeFromString<PushInject>("""{"id":"p1","message":{"data":{"k":"v"}}}""")
        assertNull(back.traceId)
        assertEquals(mapOf("k" to "v"), back.message.data)
        // An empty push is legal — a bare data-less ping is still a delivery worth testing.
        assertEquals(PushMessage(), parser.decodeFromString<PushInject>("""{"id":"p2"}""").message)
    }

    @Test fun `an ack round-trips and reports one of the three tiers`() {
        val ack = PushAck(pkg = "com.ex.app", id = "push-1", delivered = PushAck.DELIVERED_HANDLER)
        assertEquals(ack, roundTrip(PushAck.serializer(), ack))
        assertEquals("push_ack", ack.kind)
        // The tier strings are a contract with the plugin, not free text.
        assertEquals("handler", PushAck.DELIVERED_HANDLER)
        assertEquals("service", PushAck.DELIVERED_SERVICE)
        assertEquals("none", PushAck.DELIVERED_NONE)
    }

    @Test fun `an ack defaults to none and carries the failure that caused it`() {
        val ack = PushAck(pkg = "com.ex.app", id = "push-1")
        assertEquals(PushAck.DELIVERED_NONE, ack.delivered)
        assertNull(ack.error)
        val failed = roundTrip(
            PushAck.serializer(),
            ack.copy(error = "java.lang.ClassNotFoundException: …RemoteMessage"),
        )
        assertTrue(failed.error!!.contains("RemoteMessage"))
    }

    @Test fun `an fcm row emitted before injection existed still decodes`() {
        // Every addition is default-valued, so a payload from an older library is readable and
        // reads as "not injected" — the flag can only ever be set by the injecting path.
        val old = """{"kind":"fcm","id":"m1","event":"message","receivedAtMillis":5,"from":"/topics/x"}"""
        assertFalse(parser.decodeFromString<FcmMessage>(old).injected)
    }

    @Test fun `an injected fcm row says so on the wire`() {
        val message = FcmMessage(id = "m2", receivedAtMillis = 7, injected = true)
        val back = roundTrip(FcmMessage.serializer(), message)
        assertTrue(back.injected)
        assertTrue("""the flag must be written, not implied""", out.encodeToString(FcmMessage.serializer(), message).contains("\"injected\":true"))
    }

    @Test fun `fields a newer plugin adds are ignored, not fatal`() {
        val future = """{"id":"p1","traceId":"t","message":{"from":"x","futureField":1},"futureTop":true}"""
        assertEquals("x", parser.decodeFromString<PushInject>(future).message.from)
    }
}
