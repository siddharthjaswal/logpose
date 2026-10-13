package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.FcmNotification
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The HTTP and FCM row wording and the two fold rows — moved from the plugin's Swing renderer into
 * `:core` (and from the Mac app's M1 copy of it), so both hosts print the same words. These pin the
 * exact strings the renderer used to build inline.
 */
class RowWordingTest {

    private fun tx(
        method: String = "GET", code: Int? = 200, error: String? = null, ms: Long? = 142,
        size: Long = 1240, path: String = "/v2/orders", mocked: Boolean = false,
    ) = Transaction(
        id = "t", startedAtMillis = 1,
        request = Request(method, "https://api.example.com$path?x=1", "api.example.com", path),
        response = code?.let { Response(it, body = Body("application/json", size, "{}")) },
        durationMillis = ms, error = error, mocked = mocked,
    )

    private fun http(id: String, t: Transaction, at: Long = 1_000) =
        LogEvent.Http(t.copy(id = id), Envelope(kind = Envelope.KIND_HTTP, id = id, at = at, endedAt = at + (t.durationMillis ?: 1), payload = JsonNull))

    private fun fcm(msg: FcmMessage, at: Long = 5_000) =
        LogEvent.Fcm(msg, Envelope(kind = Envelope.KIND_FCM, id = msg.id, at = at, endedAt = at, payload = JsonNull))

    @Test fun `status text is the code, ERR for a throw, dots in flight`() {
        assertEquals("404", RowContent.statusText(tx(code = 404)))
        assertEquals("ERR", RowContent.statusText(tx(code = null, error = "timeout")))
        assertEquals("···", RowContent.statusText(tx(code = null)))
        assertTrue(RowContent.isPending(tx(code = null)))
        assertFalse(RowContent.isPending(tx(code = null, error = "x")))
    }

    @Test fun `method weight is read vs write, unknown verbs are writes`() {
        assertTrue(RowContent.isReadMethod(" get "))
        assertTrue(RowContent.isReadMethod("OPTIONS"))
        assertFalse(RowContent.isReadMethod("PATCH"))
        assertFalse(RowContent.isReadMethod("PROPFIND"))
        assertEquals(RowContent.Weight.REGULAR, RowContent.http(tx()).methodWeight)
        assertEquals(RowContent.Weight.BOLD, RowContent.http(tx(method = "POST")).methodWeight)
    }

    @Test fun `a finished row prints size and whole milliseconds, a pending row neither`() {
        val done = RowContent.http(tx(ms = 2310, size = 1240))
        assertEquals("1.2 KB", done.size)
        assertEquals("2310ms", done.duration)
        assertEquals("/v2/orders", done.path)
        val pending = RowContent.http(tx(code = null, ms = null))
        assertTrue(pending.pending)
        assertEquals("", pending.size)
        assertEquals("", pending.duration)
        assertEquals("812ms", RowContent.liveMillis(812))
    }

    @Test fun `human sizes`() {
        assertEquals("—", RowContent.humanSize(-1))
        assertEquals("18 B", RowContent.humanSize(18))
        assertEquals("1.5 MB", RowContent.humanSize(1_572_864))
    }

    @Test fun `fcm label, summary and the injected fallback clock`() {
        val notif = fcm(FcmMessage(id = "f1", receivedAtMillis = 0, notification = FcmNotification("New order", "Pickup"), data = mapOf("a" to "1"), injected = true))
        val row = RowContent.fcm(notif)
        assertEquals("NOTIF", row.label)
        assertEquals("New order", row.summary)
        assertTrue(row.injected)
        assertEquals("1 key", row.keys)
        assertEquals(5_000, row.atMillis)   // no receive time of its own → the envelope's clock
        val data = FcmMessage(id = "f2", receivedAtMillis = 7, data = mapOf("Channel" to "order_updates", "b" to "2"))
        assertEquals("DATA", RowContent.fcmLabel(data))
        assertEquals("order_updates", RowContent.fcmSummary(data))
        assertEquals("2 keys", RowContent.fcm(fcm(data)).keys)
        assertEquals("TOKEN", RowContent.fcmLabel(FcmMessage(id = "t", event = "token")))
        assertEquals("Registration token refreshed", RowContent.fcmSummary(FcmMessage(id = "t", event = "token")))
        assertEquals("(data message)", RowContent.fcmSummary(FcmMessage(id = "x")))
        assertEquals("proj", RowContent.fcmSummary(FcmMessage(id = "x", from = "proj")))
    }

    @Test fun `a poll fold prints the count, the latest size and the median`() {
        val events = (1..4).map { http("p$it", tx(ms = 100L * it, size = 10L * it), at = it * 1_000L) }
        val group = RowCollapse.collapse(events).single() as RowCollapse.Row.Group
        val fold = RowContent.pollFold(group)
        assertEquals("×4", fold.count)
        assertEquals("40 B", fold.size)      // the latest occurrence's
        assertEquals("~200ms", fold.median)  // lower-middle, a time one call really took
    }

    @Test fun `a transaction fold paints a mark only on evidence and fades unless it failed`() {
        val ok = RowContent.txnFold(RowCollapse.Facts(4, null, 128, 0, statements = 2, succeeded = true))
        assertEquals("transaction", ok.word)
        assertEquals(RowContent.TxnMark.SUCCEEDED, ok.mark)
        assertEquals(" · 2 statements · 128ms", ok.tail)
        assertTrue(ok.faded)
        val silent = RowContent.txnFold(RowCollapse.Facts(3, null, null, 0, statements = 1))
        assertNull(silent.mark)
        assertEquals(" · 1 statement", silent.tail)
        val failed = RowContent.txnFold(RowCollapse.Facts(3, null, 2_400, 0, statements = 1, failed = true))
        assertEquals(RowContent.TxnMark.FAILED, failed.mark)
        assertFalse(failed.faded)
        assertEquals(" · 1 statement · 2.4s", failed.tail)
    }

    @Test fun `group hints say the filter-relative count and how to expand`() {
        val events = (1..3).map { http("p$it", tx(ms = 50), at = it * 1_000L) }
        val group = RowCollapse.collapse(events).single() as RowCollapse.Row.Group
        val hint = RowContent.groupHint(group) { "12:00:03" }
        assertEquals("3 calls", hint.bold)
        assertEquals(" matching the current filter", hint.rest)
        assertEquals(listOf(KindPresenter.rowLabel(group.lead), "median 50ms · latest 12:00:03", "Double-click to expand"), hint.lines)
    }
}
