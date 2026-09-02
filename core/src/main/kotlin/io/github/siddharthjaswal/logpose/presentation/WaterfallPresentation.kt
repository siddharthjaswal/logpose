package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Every decision the waterfall's paint pass makes that isn't a pixel.
 *
 * [WaterfallLayout] answers *where* a lane goes; this answers *what it says* — the word in the
 * duration column, whether a lane counts as a failure, which font its gutter label takes, the
 * strings under the axis, and how far through its breath an open span is. All of it is a function
 * of values, so all of it is unit-testable, and none of it needs a `Graphics2D` to decide.
 *
 * The split matters most for the duration column. It is a **fixed 64px right-aligned cell**, which
 * is what lets the paint pass drop the per-bar text measurement it used to do; deciding the text
 * here keeps that cell's four voices (a duration, a failure, a live call, a clockless event) in one
 * `when` instead of scattered across the painter.
 */
object WaterfallPresentation {

    /** The four voices of the duration column. The painter maps each to a colour and a weight. */
    enum class Tone {
        /**
         * An ordinary fact: `642ms`, `+0ms`, `untimed`. Regular weight, `textDim` — the quietest
         * neutral that still clears 4.5:1 on `bg0` in both themes, which `textMuted` does not.
         */
        MUTED,

        /** The longest span in the flow — the one number worth finding. `text`, bold. */
        SLOWEST,

        /** Still in flight. `accentHover` — the same blue as `accent`, legible on `bg0`. */
        RUNNING,

        /** Failed or timed out. `danger`. */
        FAILED,
    }

    /** What the duration column says for one lane, and in which voice. */
    data class Cell(val text: String, val tone: Tone)

    /**
     * A lane's gutter label, split so the painter can colour the part that means "this one broke".
     *
     * [status] is the failed HTTP call's code (or `ERR`), rendered in `danger` after [text]; it is
     * null for everything else. [mono] picks the font: HTTP and db rows name themselves with paths
     * and SQL, which read as code, so they take mono 10.5; every other kind is named by a human
     * (`ORDER_ACCEPT_TAP`, `DataSyncWorker`) and takes label 11.5.
     */
    data class LaneLabel(val text: String, val status: String?, val mono: Boolean)

    /** Failure text for a lane, or null when nothing went wrong. */
    const val TIMEOUT = "timeout"
    const val FAILED = "failed"

    /** Alpha at the bottom and top of an open span's breath. */
    const val PULSE_MIN = 36
    const val PULSE_MAX = 80

    /** One full breath, in millis. */
    const val PULSE_PERIOD_MILLIS = 1_600L

    /** Fill alpha of a finished span: the kind hue at 70%. */
    const val SPAN_ALPHA = 179

    /** Fill alpha of a failed span: `danger` at ~55%, so the stroke still reads as the edge. */
    const val FAILED_ALPHA = 140

    /**
     * Whether a lane is a **failure** — the one thing in the waterfall allowed to change a lane's
     * colour. Everything else keeps its kind hue, so red always means the same thing.
     *
     * Only HTTP can fail this way today: an error, or a response the server itself called a
     * problem. Returns the word the duration column uses, since the two decisions have exactly one
     * predicate between them and splitting them is how they drift.
     */
    fun failureOf(event: LogEvent): String? {
        val tx = (event as? LogEvent.Http)?.tx ?: return null
        val error = tx.error
        val code = tx.response?.code ?: 0
        if (error == null && code < 400) return null
        val timedOut = error != null &&
            (error.contains("timeout", ignoreCase = true) || error.contains("timed out", ignoreCase = true))
        return if (timedOut) TIMEOUT else FAILED
    }

    /** True when the lane paints in `danger` rather than its kind hue. */
    fun isFailure(event: LogEvent): Boolean = failureOf(event) != null

    /**
     * The gutter label for a lane.
     *
     * The text is [KindPresenter.rowLabel] — the same name the timeline row, a copied line and the
     * tooltip use, so a lane and its row can never disagree about what the event is called.
     */
    fun laneLabel(event: LogEvent): LaneLabel {
        val status = if (event is LogEvent.Http && isFailure(event)) {
            event.tx.response?.code?.toString() ?: "ERR"
        } else {
            null
        }
        val mono = event is LogEvent.Http || event is LogEvent.Db
        return LaneLabel(KindPresenter.rowLabel(event), status, mono)
    }

    /**
     * What the fixed duration column says for [lane].
     *
     * Precedence is deliberate: a lane with no device clock has no duration to state at all; a call
     * still in flight has no final one; a failure is worth more than the number of milliseconds it
     * took to fail. Only what's left is a duration, and only a duration can be the slowest.
     */
    fun durationCell(
        lane: WaterfallLayout.Lane,
        axisStartMillis: Long,
        slowestId: String?,
    ): Cell {
        val failure = failureOf(lane.event)
        return when {
            !lane.timed -> Cell("untimed", Tone.MUTED)
            lane.isOpen -> Cell("running", Tone.RUNNING)
            failure != null -> Cell(failure, Tone.FAILED)
            lane.isPoint -> Cell("+" + humanMillis(lane.startMillis - axisStartMillis), Tone.MUTED)
            else -> Cell(
                humanMillis(lane.durationMillis),
                if (slowestId != null && lane.id == slowestId) Tone.SLOWEST else Tone.MUTED,
            )
        }
    }

    /**
     * The strings under the canvas, one per gridline: `+0ms  +1.05s  +2.10s  +3.15s  +4.20s`.
     *
     * Precomputed as a list because the axis is the same for every lane — deriving it once per
     * paint rather than per tick keeps the paint pass to arithmetic.
     */
    fun axisLabels(spanMillis: Long, ticks: Int): List<String> =
        (0..ticks).map { i -> "+" + humanMillis((spanMillis * axisFraction(i, ticks)).toLong()) }

    /** Where tick [i] of [ticks] sits on the axis, in `0.0..1.0`. */
    fun axisFraction(i: Int, ticks: Int): Double = if (ticks <= 0) 0.0 else i.toDouble() / ticks

    /**
     * An open span's fill alpha, [PULSE_MIN]→[PULSE_MAX] on a cosine ease over
     * [PULSE_PERIOD_MILLIS].
     *
     * Taking elapsed millis rather than a frame counter is what makes the breath smooth: the
     * animation timer runs far faster than the 250ms tick that grows the bar, and a curve sampled
     * on wall time stays the same shape no matter which of them fired last.
     */
    fun pulseAlpha(elapsedMillis: Long): Int {
        val phase = 0.5 - 0.5 * cos(2 * Math.PI * elapsedMillis / PULSE_PERIOD_MILLIS)
        return (PULSE_MIN + (PULSE_MAX - PULSE_MIN) * phase).roundToInt()
    }

    /** Compact, honest durations: sub-second in millis, above that in seconds. */
    fun humanMillis(millis: Long): String = when {
        millis < 0 -> "—"
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> "%.2fs".format(millis / 1000.0)
        else -> "%dm %02ds".format(millis / 60_000, (millis % 60_000) / 1000)
    }
}
