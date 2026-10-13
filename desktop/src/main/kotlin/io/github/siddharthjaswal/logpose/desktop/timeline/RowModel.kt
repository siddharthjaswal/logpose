package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.RowContent
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What a timeline row says, per kind — the painter looks answers up here rather than computing them.
 *
 * db / worker / config / analytics / app rows come straight from core's [RowContent] (and through
 * it [io.github.siddharthjaswal.logpose.presentation.KindPresenter]), so their titles, badges and
 * facts are the plugin's, decided once. HTTP and FCM row decisions still live in the plugin's
 * Swing renderer (`TransactionListRenderer`), which this module cannot depend on; the few lines
 * they amount to — status text, pending, the FCM TOKEN/NOTIF/DATA label and summary — are ported
 * here verbatim. Moving them into `RowContent` is an M4 parity item.
 */
sealed interface RowModel {
    val event: LogEvent

    data class Http(
        override val event: LogEvent.Http,
        val method: String,
        val read: Boolean,
        val status: String,
        val statusCode: Int?,
        val error: String?,
        val pending: Boolean,
        val path: String,
        val mocked: Boolean,
        val size: String,
        val duration: String,
    ) : RowModel

    data class Fcm(
        override val event: LogEvent.Fcm,
        val label: String,
        val summary: String,
        val injected: Boolean,
        val keys: String,
        val time: String,
    ) : RowModel

    data class Db(override val event: LogEvent.Db, val content: RowContent.DbRow, val time: String) : RowModel

    data class Worker(override val event: LogEvent.Worker, val content: RowContent.WorkerRow, val time: String) : RowModel

    data class Generic(override val event: LogEvent, val content: RowContent.GenericRow, val time: String) : RowModel
}

object RowModels {

    private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")

    fun of(event: LogEvent): RowModel = when (event) {
        is LogEvent.Http -> http(event)
        is LogEvent.Fcm -> fcm(event)
        is LogEvent.Db -> RowModel.Db(event, RowContent.db(event.query), dbTime(event))
        is LogEvent.Worker -> RowContent.worker(event).let { RowModel.Worker(event, it, timeText(it.time)) }
        else -> RowContent.generic(event).let { RowModel.Generic(event, it, timeText(it.time)) }
    }

    fun http(event: LogEvent.Http): RowModel.Http {
        val tx = event.tx
        val pending = isPending(tx)
        return RowModel.Http(
            event = event,
            method = tx.request.method,
            read = tx.request.method.trim().uppercase() in READ_METHODS,
            status = statusText(tx),
            statusCode = tx.response?.code,
            error = tx.error,
            pending = pending,
            path = tx.request.path.ifBlank { tx.request.url },
            mocked = tx.mocked,
            size = if (pending) "" else tx.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let(::humanSize).orEmpty(),
            duration = if (pending) "…" else tx.durationMillis?.let { "${it}ms" }.orEmpty(),
        )
    }

    fun fcm(event: LogEvent.Fcm): RowModel.Fcm {
        val msg = event.msg
        val at = msg.receivedAtMillis.takeIf { it > 0 } ?: event.timestampMillis
        return RowModel.Fcm(
            event = event,
            label = fcmLabel(msg),
            summary = fcmSummary(msg),
            injected = msg.injected,
            keys = msg.data.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "key" else "keys"}" }.orEmpty(),
            time = clock(at),
        )
    }

    // ---- ported from the plugin (Ui.kt / TransactionListRenderer) ----------------------------------

    fun statusText(tx: Transaction): String = tx.response?.code?.toString() ?: if (tx.error != null) "ERR" else "···"

    fun isPending(tx: Transaction): Boolean = tx.response == null && tx.error == null

    fun fcmLabel(msg: FcmMessage): String = when {
        msg.event == "token" -> "TOKEN"
        msg.notification != null -> "NOTIF"
        else -> "DATA"
    }

    fun fcmSummary(msg: FcmMessage): String = when {
        msg.event == "token" -> "Registration token refreshed"
        msg.notification != null ->
            msg.notification?.title?.takeIf { it.isNotBlank() }
                ?: msg.notification?.body?.takeIf { it.isNotBlank() }
                ?: "(notification)"
        else -> msg.data.entries.firstOrNull { it.key.equals("channel", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            ?: msg.collapseKey?.takeIf { it.isNotBlank() }
            ?: msg.from?.takeIf { it.isNotBlank() }
            ?: "(data message)"
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }

    private fun dbTime(event: LogEvent.Db): String =
        event.durationMillis?.let(RowContent::shortDuration) ?: clock(event.timestampMillis)

    fun timeText(cell: RowContent.TimeCell): String = when (cell) {
        is RowContent.TimeCell.Duration -> RowContent.shortDuration(cell.millis)
        is RowContent.TimeCell.Timestamp -> clock(cell.atMillis)
        // No live count-up in M1: a running worker shows the same ellipsis a pending call does.
        is RowContent.TimeCell.LiveCountUp -> "…"
        RowContent.TimeCell.Empty -> ""
    }

    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }

    private fun clock(millis: Long): String = millis.takeIf { it > 0 }?.let { timeFmt.get().format(Date(it)) }.orEmpty()
}

/**
 * The plugin's `RowGeometry` numbers (logical px = dp here). Copied, because the plugin's object
 * scales through `JBUI` and cannot leave the IDE; the arithmetic is two constants deep.
 */
object RowGeometry {
    const val HEIGHT = 34
    const val EDGE = 14
    const val GLYPH = 18
    const val GAP = 10
    const val METHOD = 46
    const val STATUS = 46
    const val TEXT_GAP = 12
    const val PILL_GAP = 8
    const val SIZE = 64
    const val FACT = 120
    const val FACT_ANALYTICS = 150
    const val TIME = 56
    const val META_GAP = 10
}
