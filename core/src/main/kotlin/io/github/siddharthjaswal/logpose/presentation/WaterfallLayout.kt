package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * The geometry of a trace waterfall, as pure numbers.
 *
 * This is to [TraceWaterfallPanel] what [KindPresenter] is to the detail views: everything that
 * can be decided without a `Graphics2D` is decided here, so the drawing code is left with
 * nothing but pixels. Time→fraction mapping, point-vs-span classification and the header stats
 * are the parts most likely to be wrong on a degenerate trace (one event, a zero-length span, a
 * device clock that ran backwards), and they're the parts a unit test can actually pin.
 *
 * Nothing here imports Swing, and nothing here reads the store — a layout is computed from an
 * immutable snapshot the caller already took, which is what keeps the store's monitor out of the
 * paint path.
 */
object WaterfallLayout {

    /** How an event occupies the axis. */
    enum class Shape {
        /** `endedAt == at` (or missing/backwards) — a moment, drawn as a dot. */
        POINT,

        /** `endedAt > at` — a finished span, drawn as a bar. */
        SPAN,

        /** `endedAt == null` — still running, drawn as a bar out to "now". */
        OPEN,
    }

    /**
     * One row of the waterfall.
     *
     * [startFraction]/[endFraction] are positions on the shared axis in `0.0..1.0`, so the panel
     * multiplies by its own width and never re-derives time.
     */
    data class Lane(
        val event: LogEvent,
        /** Row position, 0-based, in arrival order. */
        val index: Int,
        val shape: Shape,
        val startMillis: Long,
        val endMillis: Long,
        val startFraction: Double,
        val endFraction: Double,
        /**
         * False when the device sent no timestamp (`at == 0`). Such a lane is pinned at the axis
         * start, and the panel dims it rather than pretending it happened at t=0 — one clockless
         * event must not drag the whole axis back to the epoch.
         */
        val timed: Boolean,
    ) {
        val id: String get() = event.id
        val kind: String get() = event.kind

        /** Length of the drawn span; 0 for a point. */
        val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

        val isPoint: Boolean get() = shape == Shape.POINT
        val isOpen: Boolean get() = shape == Shape.OPEN

        /** Millis from the start of the trace — the number the lane's tooltip leads with. */
        val offsetMillis: Long get() = startMillis
    }

    /**
     * A whole waterfall: the lanes plus the axis they share and the facts the header states.
     */
    data class Layout(
        val traceId: String,
        val lanes: List<Lane>,
        /** Axis bounds in device epoch millis. Equal when the trace has no measurable width. */
        val startMillis: Long,
        val endMillis: Long,
        /**
         * The longest span in the trace, or null when every event is a point. Open spans are
         * eligible — an in-flight call that has been running for ten seconds *is* the slowest
         * thing in the trace, and [Lane.isOpen] lets the header say so instead of implying it
         * finished.
         */
        val slowest: Lane?,
    ) {
        val isEmpty: Boolean get() = lanes.isEmpty()

        /** Wall-clock width of the trace: first event's start to the last end (or "now"). */
        val wallSpanMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)

        val eventCount: Int get() = lanes.size

        val hasOpenSpans: Boolean get() = lanes.any { it.isOpen }

        /**
         * Where [millis] sits on the axis, in `0.0..1.0`. A zero-width axis (a single point, or
         * several events sharing one millisecond) maps everything to 0.0 — the panel gives points
         * a fixed pixel size, so they stay visible instead of collapsing.
         */
        fun fractionAt(millis: Long): Double {
            val span = endMillis - startMillis
            if (span <= 0L) return 0.0
            return ((millis - startMillis).toDouble() / span).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Lays [events] — the events of one trace, in arrival order — onto a shared axis.
     *
     * [nowMillis] is "now" **in the device's clock**, used as the right edge of any open span.
     * See [projectedNow]: the host clock can't be diffed against device timestamps, so the caller
     * projects it rather than passing `System.currentTimeMillis()`.
     */
    fun of(traceId: String, events: List<LogEvent>, nowMillis: Long): Layout {
        if (events.isEmpty()) return Layout(traceId, emptyList(), 0L, 0L, null)

        val timed = events.filter { it.timestampMillis > 0L }
        val axisStart = timed.minOfOrNull { it.timestampMillis } ?: 0L
        val axisEnd = timed.maxOfOrNull { endOf(it, nowMillis) }?.coerceAtLeast(axisStart) ?: axisStart

        // Built before the lanes so every lane maps through the same axis instance.
        val axis = Layout(traceId, emptyList(), axisStart, axisEnd, null)

        val lanes = events.mapIndexed { index, event ->
            val at = event.timestampMillis
            if (at <= 0L) {
                // Clockless: pinned at the axis start as a point, and flagged so it can be drawn
                // as "we don't know when this happened" rather than "it happened first".
                return@mapIndexed Lane(
                    event = event, index = index, shape = Shape.POINT,
                    startMillis = axisStart, endMillis = axisStart,
                    startFraction = 0.0, endFraction = 0.0, timed = false,
                )
            }
            val end = endOf(event, nowMillis)
            val shape = when {
                event.envelope.endedAt == null -> Shape.OPEN
                end > at -> Shape.SPAN
                // endedAt == at is a point by contract; endedAt < at is a device clock that ran
                // backwards, and a negative-width bar is worse than a dot.
                else -> Shape.POINT
            }
            Lane(
                event = event,
                index = index,
                shape = shape,
                startMillis = at,
                endMillis = end,
                startFraction = axis.fractionAt(at),
                endFraction = axis.fractionAt(end),
                timed = true,
            )
        }

        val slowest = lanes
            .filter { it.timed && !it.isPoint && it.durationMillis > 0 }
            .maxByOrNull { it.durationMillis }

        return Layout(traceId, lanes, axisStart, axisEnd, slowest)
    }

    /**
     * "Now" in the device's clock, for drawing open spans.
     *
     * Device and host clocks can't be diffed (that's why the store measures in-flight age on the
     * host side), so this projects instead: the newest device timestamp in the trace, and for each
     * still-open event its own `at` advanced by [hostAge] — how long the plugin has been watching
     * that event, in millis. The result is monotonic with real time without ever assuming the two
     * clocks agree.
     */
    fun projectedNow(events: List<LogEvent>, hostAge: (String) -> Long): Long {
        var now = 0L
        for (event in events) {
            val at = event.timestampMillis
            if (at <= 0L) continue
            val ended = event.envelope.endedAt
            now = maxOf(now, ended ?: at)
            if (ended == null) now = maxOf(now, at + hostAge(event.id).coerceAtLeast(0L))
        }
        return now
    }

    /** Right edge of an event: its close, or "now" while it's still open. */
    private fun endOf(event: LogEvent, nowMillis: Long): Long {
        val at = event.timestampMillis
        val ended = event.envelope.endedAt ?: return maxOf(nowMillis, at)
        return maxOf(ended, at)
    }
}
