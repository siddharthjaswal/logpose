package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The waterfall's words, pinned away from Swing.
 *
 * Everything here is what the paint pass would otherwise decide inline: which lane counts as a
 * failure (and so takes the one recolor the card allows), what its fixed duration cell says, which
 * font a gutter label takes, the strings under the axis, and the shape of an open span's breath.
 * A wrong answer to any of them is invisible in a screenshot and obvious in use — a finished call
 * reading `running`, a 500 painted in its kind hue — so they're decided here and asserted here.
 */
class WaterfallPresentationTest {

    // ---- failure: the only recolor ------------------------------------------------------------

    @Test
    fun `a 2xx call is not a failure`() {
        assertNull(WaterfallPresentation.failureOf(http(code = 200)))
        assertFalse(WaterfallPresentation.isFailure(http(code = 304)))
    }

    @Test
    fun `4xx and 5xx are failures, and say so without an error`() {
        assertEquals(WaterfallPresentation.FAILED, WaterfallPresentation.failureOf(http(code = 404)))
        assertEquals(WaterfallPresentation.FAILED, WaterfallPresentation.failureOf(http(code = 500)))
    }

    @Test
    fun `an error names a timeout when the message does`() {
        assertEquals(
            WaterfallPresentation.TIMEOUT,
            WaterfallPresentation.failureOf(http(code = null, error = "java.net.SocketTimeoutException")),
        )
        assertEquals(
            WaterfallPresentation.TIMEOUT,
            WaterfallPresentation.failureOf(http(code = null, error = "connection timed out")),
        )
        assertEquals(
            WaterfallPresentation.FAILED,
            WaterfallPresentation.failureOf(http(code = null, error = "Unable to resolve host")),
        )
    }

    @Test
    fun `only HTTP can fail — every other kind keeps its kind hue`() {
        assertNull(WaterfallPresentation.failureOf(db("SELECT 1")))
        assertNull(WaterfallPresentation.failureOf(worker()))
        assertNull(WaterfallPresentation.failureOf(generic("ORDER_ACCEPT_TAP")))
    }

    // ---- the duration column ------------------------------------------------------------------

    @Test
    fun `a clockless lane says untimed, not a duration`() {
        val cell = WaterfallPresentation.durationCell(lane(generic("x"), timed = false), 0L, null)
        assertEquals("untimed", cell.text)
        assertEquals(WaterfallPresentation.Tone.MUTED, cell.tone)
    }

    @Test
    fun `an open span says running, in the accent voice`() {
        val cell = WaterfallPresentation.durationCell(
            lane(http(), shape = WaterfallLayout.Shape.OPEN, start = 100, end = 900),
            0L,
            null,
        )
        assertEquals("running", cell.text)
        assertEquals(WaterfallPresentation.Tone.RUNNING, cell.tone)
    }

    @Test
    fun `a failure states the failure, not the milliseconds it took to fail`() {
        val timedOut = WaterfallPresentation.durationCell(
            lane(http(code = null, error = "SocketTimeoutException"), start = 0, end = 30_000),
            0L,
            null,
        )
        assertEquals("timeout", timedOut.text)
        assertEquals(WaterfallPresentation.Tone.FAILED, timedOut.tone)

        val failed = WaterfallPresentation.durationCell(lane(http(code = 500), start = 0, end = 120), 0L, null)
        assertEquals("failed", failed.text)
        assertEquals(WaterfallPresentation.Tone.FAILED, failed.tone)
    }

    @Test
    fun `a point states its offset from the start of the flow`() {
        val cell = WaterfallPresentation.durationCell(
            lane(generic("tap"), shape = WaterfallLayout.Shape.POINT, start = 1_400, end = 1_400),
            axisStartMillis = 1_000,
            slowestId = null,
        )
        assertEquals("+400ms", cell.text)
    }

    @Test
    fun `the slowest span is the one duration that reads bold`() {
        val slow = lane(http(), id = "slow", start = 0, end = 1_380)
        val quick = lane(http(), id = "quick", start = 0, end = 31)

        assertEquals(
            WaterfallPresentation.Tone.SLOWEST,
            WaterfallPresentation.durationCell(slow, 0L, "slow").tone,
        )
        assertEquals(
            WaterfallPresentation.Tone.MUTED,
            WaterfallPresentation.durationCell(quick, 0L, "slow").tone,
        )
        assertEquals("1.38s", WaterfallPresentation.durationCell(slow, 0L, "slow").text)
    }

    @Test
    fun `a failed lane is never promoted to bold just because it was the slowest`() {
        val lane = lane(http(code = 503), id = "slow", start = 0, end = 9_000)
        assertEquals(WaterfallPresentation.Tone.FAILED, WaterfallPresentation.durationCell(lane, 0L, "slow").tone)
    }

    // ---- gutter labels ------------------------------------------------------------------------

    @Test
    fun `paths and SQL read as code, event names read as words`() {
        assertTrue(WaterfallPresentation.laneLabel(http()).mono)
        assertTrue(WaterfallPresentation.laneLabel(db("INSERT INTO hl_orders VALUES (1)")).mono)
        assertFalse(WaterfallPresentation.laneLabel(worker()).mono)
        assertFalse(WaterfallPresentation.laneLabel(generic("ORDER_ACCEPT_TAP")).mono)
    }

    @Test
    fun `a failed HTTP label carries its status code as a separate, red run`() {
        val label = WaterfallPresentation.laneLabel(http(method = "PUT", path = "/location/", code = 500))
        assertEquals("PUT /location/", label.text)
        assertEquals("500", label.status)
    }

    @Test
    fun `a call that never got a response says ERR rather than a code`() {
        assertEquals("ERR", WaterfallPresentation.laneLabel(http(code = null, error = "boom")).status)
    }

    @Test
    fun `a healthy label has no status run at all`() {
        assertNull(WaterfallPresentation.laneLabel(http(code = 200)).status)
        assertNull(WaterfallPresentation.laneLabel(worker()).status)
    }

    // ---- the axis -----------------------------------------------------------------------------

    @Test
    fun `axis labels divide the span evenly and always start at zero`() {
        assertEquals(
            listOf("+0ms", "+1.05s", "+2.10s", "+3.15s", "+4.20s"),
            WaterfallPresentation.axisLabels(4_200, ticks = 4),
        )
    }

    @Test
    fun `a flow with no measurable width still labels every gridline`() {
        assertEquals(List(5) { "+0ms" }, WaterfallPresentation.axisLabels(0, ticks = 4))
    }

    @Test
    fun `tick fractions span the axis end to end`() {
        assertEquals(0.0, WaterfallPresentation.axisFraction(0, 4))
        assertEquals(0.5, WaterfallPresentation.axisFraction(2, 4))
        assertEquals(1.0, WaterfallPresentation.axisFraction(4, 4))
        // A degenerate tick count must not divide by zero.
        assertEquals(0.0, WaterfallPresentation.axisFraction(0, 0))
    }

    // ---- the breath ---------------------------------------------------------------------------

    @Test
    fun `the pulse starts at the floor, peaks mid-cycle and returns`() {
        val period = WaterfallPresentation.PULSE_PERIOD_MILLIS
        assertEquals(WaterfallPresentation.PULSE_MIN, WaterfallPresentation.pulseAlpha(0))
        assertEquals(WaterfallPresentation.PULSE_MAX, WaterfallPresentation.pulseAlpha(period / 2))
        assertEquals(WaterfallPresentation.PULSE_MIN, WaterfallPresentation.pulseAlpha(period))
        // ...and keeps looping rather than running off the top.
        assertEquals(WaterfallPresentation.PULSE_MAX, WaterfallPresentation.pulseAlpha(period * 5 + period / 2))
    }

    @Test
    fun `the pulse stays inside its own alpha range the whole way round`() {
        for (t in 0..WaterfallPresentation.PULSE_PERIOD_MILLIS step 37) {
            val alpha = WaterfallPresentation.pulseAlpha(t)
            assertTrue(
                alpha in WaterfallPresentation.PULSE_MIN..WaterfallPresentation.PULSE_MAX,
                "alpha $alpha at ${t}ms escaped the range",
            )
        }
    }

    @Test
    fun `the ease is symmetric about its peak`() {
        val period = WaterfallPresentation.PULSE_PERIOD_MILLIS
        assertEquals(
            WaterfallPresentation.pulseAlpha(period / 4),
            WaterfallPresentation.pulseAlpha(period * 3 / 4),
        )
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun envelope(kind: String, id: String) =
        Envelope(kind = kind, id = id, at = 1_000, payload = JsonObject(emptyMap()))

    private fun http(
        method: String = "GET",
        path: String = "/v4/order/21053953/",
        code: Int? = 200,
        error: String? = null,
        id: String = "h1",
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let { Response(code = it) },
            error = error,
        )
        return LogEvent.Http(tx, envelope(Envelope.KIND_HTTP, id))
    }

    private fun db(sql: String) =
        LogEvent.Db(DbQuery(sql = sql), envelope(Envelope.KIND_DB, "d1"))

    private fun worker() = LogEvent.Worker(
        WorkerEvent(worker = "DataSyncWorker", state = "running"),
        envelope(Envelope.KIND_WORKER, "w1"),
    )

    private fun generic(title: String) =
        LogEvent.Generic(GenericEvent(title = title), envelope(Envelope.KIND_ANALYTICS, "g1"))

    /** A lane built straight, so a cell can be asserted without staging a whole flow. */
    private fun lane(
        event: LogEvent,
        id: String = event.id,
        shape: WaterfallLayout.Shape = WaterfallLayout.Shape.SPAN,
        start: Long = 0,
        end: Long = 100,
        timed: Boolean = true,
    ): WaterfallLayout.Lane {
        // Lane.id delegates to the event, so an id under test has to travel on the event itself.
        val carried = if (id == event.id) event else (event as? LogEvent.Http)?.let {
            LogEvent.Http(it.tx.copy(id = id), it.envelope.copy(id = id))
        } ?: event
        return WaterfallLayout.Lane(
            event = carried,
            index = 0,
            shape = shape,
            startMillis = start,
            endMillis = end,
            startFraction = 0.0,
            endFraction = 1.0,
            timed = timed,
        )
    }
}
