package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.PushMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Re-send this push" is only useful if it re-sends *this* push: every field the app saw has to
 * make the trip from the captured (nested) FCM shape to the flat `FcmMessageInfo` the device
 * rebuilds a RemoteMessage from (PRD FR-A3). A dropped field would mean replaying something the
 * app never received — worse than not replaying at all.
 */
class PushReplayTest {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A capture as it actually arrives on the wire, notification and all. */
    private val capturedJson = """
        {"kind":"fcm","id":"0:1699","event":"message","receivedAtMillis":1700000000000,
         "messageId":"0:1699%abc","from":"1234567890","to":"/topics/riders",
         "collapseKey":"order_assigned","messageType":"gcm","sentTimeMillis":1699999999000,
         "ttlSeconds":2419200,"priority":1,
         "notification":{"title":"Order assigned","body":"Pick up at 5pm","channelId":"orders",
                         "clickAction":"OPEN_ORDER","imageUrl":"https://cdn/x.png"},
         "data":{"channel":"order","orderId":"42"}}
    """.trimIndent()

    private val captured: FcmMessage get() = lenient.decodeFromString(FcmMessage.serializer(), capturedJson)

    @Test
    fun `every captured field survives the trip back to the device`() {
        val replay = PushReplay.toMessage(captured, messageId = "new-id", sentTimeMillis = 5_000)

        assertEquals(
            PushMessage(
                messageId = "new-id",
                from = "1234567890",
                to = "/topics/riders",
                collapseKey = "order_assigned",
                messageType = "gcm",
                sentTimeMillis = 5_000,
                ttlSeconds = 2419200,
                priority = 1,
                notificationTitle = "Order assigned",
                notificationBody = "Pick up at 5pm",
                notificationChannelId = "orders",
                notificationClickAction = "OPEN_ORDER",
                notificationImageUrl = "https://cdn/x.png",
                data = mapOf("channel" to "order", "orderId" to "42"),
            ),
            replay,
        )
    }

    @Test
    fun `a replay is a new message, not a copy of the old one`() {
        val original = captured
        val replay = PushReplay.toMessage(original, PushReplay.newId(), 5_000)
        assertNotEquals(original.messageId, replay.messageId, "reusing the id would defeat the app's own dedup")
        assertNotEquals(original.sentTimeMillis, replay.sentTimeMillis)
    }

    @Test
    fun `a pure data message carries no notification fields`() {
        val dataOnly = lenient.decodeFromString(
            FcmMessage.serializer(),
            """{"kind":"fcm","id":"p1","event":"message","data":{"orderId":"7"}}""",
        )
        val replay = PushReplay.toMessage(dataOnly, "id", 1)
        assertNull(replay.notificationTitle)
        assertNull(replay.notificationBody)
        assertNull(replay.notificationChannelId)
        assertNull(replay.from)
        assertEquals(mapOf("orderId" to "7"), replay.data)
    }

    @Test
    fun `a token refresh is not a message and cannot be replayed`() {
        val token = lenient.decodeFromString(
            FcmMessage.serializer(),
            """{"kind":"fcm","id":"t1","event":"token","token":"abc"}""",
        )
        assertFalse(PushReplay.canReplay(token))
        assertTrue(PushReplay.canReplay(captured))
    }

    @Test
    fun `an injection always gets its own fresh trace, so two replays are two flows`() {
        val first = PushReplay.inject(PushReplay.toMessage(captured, PushReplay.newId(), 1))
        val second = PushReplay.inject(PushReplay.toMessage(captured, PushReplay.newId(), 2))

        assertNotEquals(first.traceId, second.traceId)
        assertNotEquals(first.id, second.id)
        assertTrue(first.traceId!!.isNotBlank())
        assertEquals("push_inject", first.kind)
    }

    @Test
    fun `the injection id and the message id are one value`() {
        // Two ids meant two rows: the device emits the injected row under the message id, and the
        // app's own service re-logs the same push under the same id moments later. Daylight
        // between them is what put an unmarked twin on the timeline (correlation PRD §1a).
        val message = PushReplay.toMessage(captured, "inj-abc123", 1)
        val injection = PushReplay.inject(message)

        assertEquals("inj-abc123", injection.id)
        assertEquals(injection.id, injection.message.messageId)
        assertEquals(message, injection.message, "every other field travels untouched")
    }

    @Test
    fun `a message with no id of its own is stamped with the injection id`() {
        val composed = PushMessage(data = mapOf("orderId" to "42"))
        val injection = PushReplay.inject(composed)

        assertTrue(injection.id.isNotBlank())
        assertEquals(injection.id, injection.message.messageId)

        // An explicit id wins, and takes the message with it.
        val forced = PushReplay.inject(composed, id = "chosen")
        assertEquals("chosen", forced.id)
        assertEquals("chosen", forced.message.messageId)
    }
}
