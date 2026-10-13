package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.RowContent
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What one timeline row paints — every word of it from `:core`.
 *
 * M1 ported the HTTP/FCM wording and the plugin's `RowGeometry` numbers into this file because they
 * lived only in the plugin's Swing renderer. Both now live in core ([RowContent],
 * [io.github.siddharthjaswal.logpose.presentation.RowGeometry]) and the plugin reads them too, so
 * this is only the bundle a Compose row needs, resolved once per snapshot (off the UI thread, in
 * [TimelineViews.build]) rather than per recomposition. The one thing decided here is the host's
 * clock format, `HH:mm:ss`, which is the plugin renderer's too.
 */
sealed interface RowCells {
    val row: RowCollapse.Row

    /** An HTTP call — or a collapsed polling run of them, when [poll] is set. */
    data class Http(
        override val row: RowCollapse.Row,
        val event: LogEvent.Http,
        val content: RowContent.HttpRow,
        /** Outlined `DUP ×N`. Suppressed on a run: `×N` already says "this repeated". */
        val duplicate: DuplicateDetector.Mark?,
        /** A muted endpoint: 26px, faded. */
        val muted: Boolean,
        val poll: RowContent.PollFold?,
    ) : RowCells

    data class Fcm(override val row: RowCollapse.Row, val content: RowContent.FcmRow, val time: String) : RowCells

    data class Db(override val row: RowCollapse.Row, val content: RowContent.DbRow, val time: String) : RowCells

    /** A folded transaction's ceremony: one 26px muted line. */
    data class Txn(override val row: RowCollapse.Row, val fold: RowContent.TxnFold) : RowCells

    data class Worker(override val row: RowCollapse.Row, val content: RowContent.WorkerRow, val time: String) : RowCells

    data class Generic(override val row: RowCollapse.Row, val content: RowContent.GenericRow, val time: String) : RowCells {
        val analytics: Boolean get() = row.lead.kind == Envelope.KIND_ANALYTICS
    }

    /** Whether this row's time cell counts up live (an in-flight call, a running worker). */
    val live: Boolean get() = when (this) {
        is Http -> poll == null && content.pending
        is Worker -> content.time is RowContent.TimeCell.LiveCountUp
        else -> false
    }

    /** 34, or 26 for a muted endpoint and a folded transaction. */
    val short: Boolean get() = (this is Http && muted) || this is Txn
}

object Rows {

    fun of(row: RowCollapse.Row, marks: Map<String, DuplicateDetector.Mark>): RowCells {
        val group = row as? RowCollapse.Row.Group
        if (group?.groupKind == RowCollapse.GroupKind.DB_TXN) return RowCells.Txn(row, RowContent.txnFold(group.facts))
        return when (val event = row.lead) {
            is LogEvent.Http -> RowCells.Http(
                row, event, RowContent.http(event.tx),
                duplicate = if (group == null) marks[event.id] else null,
                muted = MutedEndpoints.isMuted(event.tx),
                poll = group?.let(RowContent::pollFold),
            )
            is LogEvent.Fcm -> RowContent.fcm(event).let { RowCells.Fcm(row, it, clock(it.atMillis)) }
            is LogEvent.Db -> RowCells.Db(row, RowContent.db(event.query), event.durationMillis?.let(RowContent::shortDuration) ?: clock(event.timestampMillis))
            is LogEvent.Worker -> RowContent.worker(event).let { RowCells.Worker(row, it, timeText(it.time)) }
            else -> RowContent.generic(event).let { RowCells.Generic(row, it, timeText(it.time)) }
        }
    }

    /** The time cell for a resolved [RowContent.TimeCell]; a live count-up is painted by the row. */
    fun timeText(cell: RowContent.TimeCell): String = when (cell) {
        is RowContent.TimeCell.Duration -> RowContent.shortDuration(cell.millis)
        is RowContent.TimeCell.Timestamp -> clock(cell.atMillis)
        is RowContent.TimeCell.LiveCountUp -> "…"
        RowContent.TimeCell.Empty -> ""
    }

    /**
     * A live row's time text. A pending call counts whole milliseconds like a finished one; a
     * running worker counts its *run* — the host age less the queue the device reported, floored
     * at zero because a device clock adjusted mid-run can overshoot.
     */
    fun liveText(cells: RowCells, elapsedMillis: Long?): String = when {
        elapsedMillis == null -> "…"
        cells is RowCells.Http -> RowContent.liveMillis(elapsedMillis)
        cells is RowCells.Worker -> {
            val offset = (cells.content.time as? RowContent.TimeCell.LiveCountUp)?.offsetMillis ?: 0
            RowContent.shortDuration((elapsedMillis - offset).coerceAtLeast(0))
        }
        else -> "…"
    }

    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }

    fun clock(millis: Long): String = millis.takeIf { it > 0 }?.let { timeFmt.get().format(Date(it)) }.orEmpty()
}
