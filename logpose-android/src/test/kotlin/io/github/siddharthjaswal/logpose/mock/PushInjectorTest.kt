package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.FcmMessageInfo
import io.github.siddharthjaswal.logpose.LogPose
import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.export.ExportBuffer
import io.github.siddharthjaswal.logpose.wire.PushAck
import io.github.siddharthjaswal.logpose.wire.PushMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The delivery tiering and its trace, with the Android half handed in as a lambda — Tier 2 needs
 * a `PackageManager` and a Firebase class, neither of which exists in a JVM unit test, and neither
 * of which is what actually goes wrong. What goes wrong is which tier runs, and whether a failure
 * reaches the ack instead of the app.
 *
 * Also covers what the injected row *is*, which is the other half of "the timeline never lies
 * about an injected push": the app's own messaging service re-logs the push LogPose delivered, and
 * that re-log has to land on the same row, still marked (correlation PRD §1a/§5).
 */
class PushInjectorTest {

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    private fun reset() {
        LogPose.clearPushInjectHandler()
        LogPose.clearInjectedPushIds()
        ExportBuffer.clear()
    }

    private val info = FcmMessageInfo(messageId = "m1", data = mapOf("order_id" to "21047484"))

    @Test fun `a registered handler takes the push and runs inside its trace`() {
        var seen: FcmMessageInfo? = null
        var trace: String? = null
        LogPose.onPushInject { seen = it; trace = LogPose.currentTraceId() }

        val outcome = PushInjector.deliver(info, traceId = "trace-9") { fail() }

        assertEquals(PushAck.DELIVERED_HANDLER, outcome.delivered)
        assertNull(outcome.error)
        assertEquals(info, seen)
        assertEquals("everything the push triggers must share its trace", "trace-9", trace)
    }

    @Test fun `an untraced injection leaves no ambient trace behind`() {
        var trace: String? = "unset"
        LogPose.onPushInject { trace = LogPose.currentTraceId() }

        PushInjector.deliver(info, traceId = null) { fail() }

        assertNull(trace)
        assertNull("the trace scope must not leak past delivery", LogPose.currentTraceId())
    }

    @Test fun `without a handler the service tier gets its turn`() {
        var tried = false
        val outcome = PushInjector.deliver(info, traceId = null) { tried = true; true }

        assertTrue(tried)
        assertEquals(PushAck.DELIVERED_SERVICE, outcome.delivered)
    }

    @Test fun `a registered handler wins over the service tier`() {
        var serviceTried = false
        LogPose.onPushInject { }

        PushInjector.deliver(info, traceId = null) { serviceTried = true; true }

        assertFalse("Tier 1 is the contract; reflection is only the fallback", serviceTried)
    }

    @Test fun `nothing listening is an outcome, not a failure`() {
        val outcome = PushInjector.deliver(info, traceId = null) { false }

        assertEquals(PushAck.DELIVERED_NONE, outcome.delivered)
        assertNull("a missing handler is not an error — the IDE turns `none` into a hint", outcome.error)
    }

    @Test fun `a throwing handler is reported, never rethrown at the app`() {
        LogPose.onPushInject { error("handler blew up") }

        val outcome = PushInjector.deliver(info, traceId = null) { false }

        assertEquals(PushAck.DELIVERED_NONE, outcome.delivered)
        assertTrue(outcome.error!!.contains("handler blew up"))
    }

    @Test fun `a failing service tier is reported the same way`() {
        val outcome = PushInjector.deliver(info, traceId = null) {
            throw ClassNotFoundException("com.google.firebase.messaging.RemoteMessage")
        }

        assertEquals(PushAck.DELIVERED_NONE, outcome.delivered)
        assertTrue(outcome.error!!.contains("RemoteMessage"))
    }

    @Test fun `the wire message becomes the app-facing holder field for field`() {
        val message = PushMessage(
            messageId = "0:17", from = "/topics/orders", to = "device", collapseKey = "c",
            messageType = "gcm", sentTimeMillis = 17, ttlSeconds = 60, priority = 2,
            notificationTitle = "New order", notificationBody = "Pickup in 4 min",
            notificationChannelId = "orders", notificationClickAction = "OPEN",
            notificationImageUrl = "https://ex.com/x.png", data = mapOf("order_id" to "1"),
        )
        assertEquals(
            FcmMessageInfo(
                messageId = "0:17", from = "/topics/orders", to = "device", collapseKey = "c",
                messageType = "gcm", sentTimeMillis = 17, ttlSeconds = 60, priority = 2,
                notificationTitle = "New order", notificationBody = "Pickup in 4 min",
                notificationChannelId = "orders", notificationClickAction = "OPEN",
                notificationImageUrl = "https://ex.com/x.png", data = mapOf("order_id" to "1"),
            ),
            message.toInfo(),
        )
    }

    // ---- the injected row, and the app's re-log of it ------------------------------------------

    @Test fun `the app re-logging an injected push keeps it marked as injected`() {
        // The real sequence on a device: LogPose emits the row, delivers the push, and the app's
        // own FirebaseMessagingService reports the very same message back through logFcmMessage.
        LogPose.logInjectedFcm(info, id = "inj-1", traceId = "trc-1", config = capture)
        LogPose.logFcmMessage(info, capture)

        val rows = emitted()
        assertEquals(2, rows.size)
        assertTrue(
            "an injected push must never be reported as a real one, whoever logs it",
            rows.all { it.injected },
        )
    }

    @Test fun `the injected emission and the re-log are one row, not two`() {
        LogPose.logInjectedFcm(info, id = "inj-1", traceId = "trc-1", config = capture)
        LogPose.logFcmMessage(info, capture)

        // The plugin's store is keyed by envelope id: one id means the re-log updates the row
        // LogPose already emitted instead of appearing beside it.
        assertEquals(listOf("m1", "m1"), emitted().map { it.id })
    }

    @Test fun `a push LogPose never injected is reported exactly as the app logged it`() {
        LogPose.logInjectedFcm(info, id = "inj-1", traceId = null, config = capture)
        ExportBuffer.clear()

        LogPose.logFcmMessage(FcmMessageInfo(messageId = "0:real-push"), capture)

        val row = emitted().single()
        assertFalse("a real push must not inherit another push's injected flag", row.injected)
        assertEquals("0:real-push", row.id)
    }

    @Test fun `remembering injections is bounded, oldest first`() {
        val ids = (1..MEMORY + 1).map { "m-$it" }
        ids.forEach { LogPose.logInjectedFcm(FcmMessageInfo(messageId = it), it, null, capture) }
        ExportBuffer.clear()

        assertEquals("the set must not grow with the session", MEMORY, LogPose.injectedPushIdCount())

        LogPose.logFcmMessage(FcmMessageInfo(messageId = ids.first()), capture)
        LogPose.logFcmMessage(FcmMessageInfo(messageId = ids.last()), capture)

        val (evicted, remembered) = emitted()
        assertFalse("the oldest injection is the one forgotten", evicted.injected)
        assertTrue("the most recent injections are the ones that matter", remembered.injected)
    }

    @Test fun `an injection with no message id falls back to the ack's correlation id`() {
        // Nothing the IDE sends today, but a push carrying no id of its own must still produce a
        // row that the ack can be matched to.
        LogPose.logInjectedFcm(FcmMessageInfo(data = mapOf("k" to "v")), "inj-9", null, capture)

        val row = emitted().single()
        assertEquals("inj-9", row.id)
        assertTrue(row.injected)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Emissions are read off the export ring — the same lines that go to logcat, minus Android. */
    private val capture = LogPoseConfig(exportEnabled = true)

    private data class Row(val id: String, val injected: Boolean)

    private fun emitted(): List<Row> = ExportBuffer.snapshot().map { line ->
        val envelope = json.parseToJsonElement(line).jsonObject
        Row(
            id = envelope.getValue("id").jsonPrimitive.content,
            injected = envelope.getValue("payload").jsonObject["injected"]?.jsonPrimitive?.boolean
                ?: false,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun fail(): Nothing = throw AssertionError("the service tier must not be reached")

    private companion object {
        /** Mirrors `LogPose.MAX_INJECTED_IDS`; the cap is the point of the test above. */
        const val MEMORY = 32
    }
}
