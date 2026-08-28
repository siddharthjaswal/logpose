package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.FcmMessageInfo
import io.github.siddharthjaswal.logpose.LogPose
import io.github.siddharthjaswal.logpose.wire.PushAck
import io.github.siddharthjaswal.logpose.wire.PushMessage
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
 */
class PushInjectorTest {

    @Before fun setUp() = LogPose.clearPushInjectHandler()
    @After fun tearDown() = LogPose.clearPushInjectHandler()

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

    private fun fail(): Nothing = throw AssertionError("the service tier must not be reached")
}
