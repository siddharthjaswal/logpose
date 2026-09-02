package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The waterfall's arithmetic, pinned away from Swing.
 *
 * The cases that matter are the degenerate ones: a trace of one event, a trace where everything
 * happened in the same millisecond, an event still in flight, and a device clock that ran
 * backwards. Each of those either divides by zero or draws a negative-width bar if the layout
 * gets it wrong, and none of them is rare in a real capture.
 */
class WaterfallLayoutTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private var seq = 0
    private val base = 1_700_000_000_000L

    /** A generic event occupying [at]..[endedAt] on the device clock. */
    private fun event(
        at: Long,
        endedAt: Long?,
        kind: String = Envelope.KIND_EVENT,
        id: String = "e${seq++}",
        trace: String? = "t1",
    ): LogEvent.Generic {
        val payload = GenericEvent(title = id)
        return LogEvent.Generic(
            payload,
            Envelope(
                kind = kind,
                id = id,
                at = at,
                endedAt = endedAt,
                traceId = trace,
                payload = json.encodeToJsonElement(GenericEvent.serializer(), payload),
            ),
        )
    }

    // ---- mapping math -------------------------------------------------------------------------

    @Test
    fun `maps time onto the axis linearly`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(event(base, base + 100), event(base + 500, base + 1000)),
            nowMillis = base + 1000,
        )

        assertEquals(base, layout.startMillis)
        assertEquals(base + 1000, layout.endMillis)
        assertEquals(1000L, layout.wallSpanMillis)
        assertEquals(0.0, layout.fractionAt(base), 1e-9)
        assertEquals(0.5, layout.fractionAt(base + 500), 1e-9)
        assertEquals(1.0, layout.fractionAt(base + 1000), 1e-9)

        val (first, second) = layout.lanes
        assertEquals(0.0, first.startFraction, 1e-9)
        assertEquals(0.1, first.endFraction, 1e-9)
        assertEquals(0.5, second.startFraction, 1e-9)
        assertEquals(1.0, second.endFraction, 1e-9)
    }

    @Test
    fun `clamps times outside the axis instead of overflowing the row`() {
        val layout = WaterfallLayout.of("t1", listOf(event(base, base + 100)), nowMillis = base)
        assertEquals(0.0, layout.fractionAt(base - 5_000), 1e-9)
        assertEquals(1.0, layout.fractionAt(base + 5_000), 1e-9)
    }

    @Test
    fun `keeps arrival order rather than sorting by time`() {
        // The store is arrival-ordered on purpose; a waterfall that re-sorted by device time would
        // make rows jump under clock skew, which is exactly what the store avoids.
        val late = event(base + 900, base + 950, id = "late")
        val early = event(base, base + 10, id = "early")
        val layout = WaterfallLayout.of("t1", listOf(late, early), nowMillis = base + 950)

        assertEquals(listOf("late", "early"), layout.lanes.map { it.id })
        assertEquals(listOf(0, 1), layout.lanes.map { it.index })
    }

    // ---- classification -----------------------------------------------------------------------

    @Test
    fun `classifies points spans and open spans`() {
        val point = event(base + 10, base + 10, id = "point")
        val span = event(base + 20, base + 120, id = "span")
        val open = event(base + 200, null, id = "open")
        val layout = WaterfallLayout.of("t1", listOf(point, span, open), nowMillis = base + 500)

        assertEquals(WaterfallLayout.Shape.POINT, layout.lanes[0].shape)
        assertEquals(WaterfallLayout.Shape.SPAN, layout.lanes[1].shape)
        assertEquals(WaterfallLayout.Shape.OPEN, layout.lanes[2].shape)
        assertEquals(0L, layout.lanes[0].durationMillis)
        assertEquals(100L, layout.lanes[1].durationMillis)
    }

    @Test
    fun `an open span is drawn out to now and ends the axis`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(event(base, base + 50), event(base + 100, null, id = "open")),
            nowMillis = base + 900,
        )

        val open = layout.lanes.single { it.id == "open" }
        assertTrue(open.isOpen)
        assertEquals(base + 900, open.endMillis)
        assertEquals(800L, open.durationMillis)
        assertEquals(base + 900, layout.endMillis)
        assertEquals(1.0, open.endFraction, 1e-9)
        assertTrue(layout.hasOpenSpans)
    }

    @Test
    fun `an open span never draws backwards when now predates the event`() {
        // Clock skew between two device timestamps; better a zero-width bar than a negative one.
        val layout = WaterfallLayout.of("t1", listOf(event(base + 500, null)), nowMillis = base)
        val lane = layout.lanes.single()
        assertEquals(base + 500, lane.endMillis)
        assertEquals(0L, lane.durationMillis)
    }

    @Test
    fun `a backwards endedAt degrades to a point`() {
        val layout = WaterfallLayout.of("t1", listOf(event(base + 100, base + 40)), nowMillis = base + 100)
        val lane = layout.lanes.single()
        assertEquals(WaterfallLayout.Shape.POINT, lane.shape)
        assertEquals(lane.startMillis, lane.endMillis)
    }

    // ---- degenerate traces --------------------------------------------------------------------

    @Test
    fun `a single point event still lays out`() {
        val layout = WaterfallLayout.of("t1", listOf(event(base, base)), nowMillis = base)

        assertEquals(1, layout.eventCount)
        assertEquals(0L, layout.wallSpanMillis)
        // A zero-width axis maps everything to the origin rather than dividing by zero.
        assertEquals(0.0, layout.fractionAt(base), 1e-9)
        assertEquals(0.0, layout.fractionAt(base + 999), 1e-9)
        assertEquals(0.0, layout.lanes.single().startFraction, 1e-9)
        assertNull(layout.slowest)
    }

    @Test
    fun `a single completed span fills the whole axis`() {
        val layout = WaterfallLayout.of("t1", listOf(event(base, base + 240)), nowMillis = base + 240)
        val lane = layout.lanes.single()
        assertEquals(0.0, lane.startFraction, 1e-9)
        assertEquals(1.0, lane.endFraction, 1e-9)
        assertSame(lane, layout.slowest)
    }

    @Test
    fun `events sharing one millisecond do not divide by zero`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(event(base, base), event(base, base), event(base, base)),
            nowMillis = base,
        )
        assertEquals(3, layout.eventCount)
        assertEquals(0L, layout.wallSpanMillis)
        assertTrue(layout.lanes.all { it.startFraction == 0.0 && it.endFraction == 0.0 })
    }

    @Test
    fun `an empty trace is empty rather than a zero-size axis`() {
        val layout = WaterfallLayout.of("t1", emptyList(), nowMillis = base)
        assertTrue(layout.isEmpty)
        assertEquals(0, layout.eventCount)
        assertNull(layout.slowest)
    }

    @Test
    fun `a clockless event is pinned at the start and flagged, not placed at the epoch`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(event(0, null, id = "unstamped"), event(base, base + 100)),
            nowMillis = base + 100,
        )

        assertEquals(base, layout.startMillis)
        val unstamped = layout.lanes.single { it.id == "unstamped" }
        assertFalse(unstamped.timed)
        assertEquals(WaterfallLayout.Shape.POINT, unstamped.shape)
        assertEquals(base, unstamped.startMillis)
    }

    // ---- header stats -------------------------------------------------------------------------

    @Test
    fun `slowest is the longest span and ignores points`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(
                event(base, base + 20, id = "quick"),
                event(base + 5, base + 5, id = "moment"),
                event(base + 30, base + 430, id = "slow"),
            ),
            nowMillis = base + 430,
        )

        assertEquals("slow", layout.slowest?.id)
        assertEquals(400L, layout.slowest?.durationMillis)
        assertEquals(3, layout.eventCount)
        assertEquals(430L, layout.wallSpanMillis)
    }

    @Test
    fun `an in-flight event can be the slowest, and says so`() {
        val layout = WaterfallLayout.of(
            "t1",
            listOf(event(base, base + 100, id = "done"), event(base + 10, null, id = "running")),
            nowMillis = base + 5_000,
        )
        assertEquals("running", layout.slowest?.id)
        assertTrue(layout.slowest?.isOpen == true)
    }

    // ---- projected now ------------------------------------------------------------------------

    @Test
    fun `projectedNow advances an open span by its host-measured age`() {
        val events = listOf(event(base, base + 100, id = "done"), event(base + 50, null, id = "running"))
        val now = WaterfallLayout.projectedNow(events) { id -> if (id == "running") 2_500 else 0 }
        assertEquals(base + 50 + 2_500, now)
    }

    @Test
    fun `projectedNow of a closed trace is its last end, not the host clock`() {
        val events = listOf(event(base, base + 100), event(base + 20, base + 640))
        assertEquals(base + 640, WaterfallLayout.projectedNow(events) { 9_999_999 })
    }

    @Test
    fun `projectedNow ignores clockless events`() {
        val events = listOf(event(0, null, id = "unstamped"), event(base, base + 10))
        assertEquals(base + 10, WaterfallLayout.projectedNow(events) { 5_000 })
    }
}
