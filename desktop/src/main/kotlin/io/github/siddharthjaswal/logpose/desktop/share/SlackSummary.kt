package io.github.siddharthjaswal.logpose.desktop.share

import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The text a tester pastes into the Slack thread beside the `.logpose` file (PRD §5.3): who, what
 * device, when, what happened, what broke, and where they said "here" — in Slack mrkdwn (`*bold*`,
 * `` `code` ``, `•` bullets; `&`, `<`, `>` escaped as Slack requires).
 *
 * Pure: it formats what it is handed, in [zone], so every line is a unit test.
 */
object SlackSummary {

    const val MAX_FAILURES = 3

    fun format(
        manifest: CaptureManifest,
        events: List<LogEvent>,
        fileName: String,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = buildString {
        val app = manifest.app
        append("*LogPose capture*")
        if (app != null) {
            append(" — `").append(esc(app.pkg)).append('`')
            app.libVersion?.takeIf { it.isNotBlank() }?.let { append(" (LogPose ").append(esc(it)).append(')') }
        }
        appendLine()

        val d = manifest.device
        val device = listOfNotNull(d?.model?.replace('_', ' '), d?.androidVersion?.let { "Android $it" }).joinToString(" · ")
        if (device.isNotBlank()) appendLine("*Device:* ${esc(device)}")

        val span = manifest.session
        if (span.startMillis > 0) {
            append("*Session:* ").append(spanText(span, zone))
            manifest.scope?.takeIf { it != "whole session" }?.let { append(" · ").append(esc(it)) }
            appendLine()
        }

        appendLine("*Events:* ${events.size}" + kindCounts(events).let { if (it.isEmpty()) "" else " — $it" })

        val failures = events.filterIsInstance<LogEvent.Http>().filter { isFailure(it) }
        appendLine("*Failures:* ${failures.size}")
        failures.take(MAX_FAILURES).forEach { f ->
            val t = f.tx
            val status = t.response?.code?.toString() ?: t.error?.let { "failed (${it.substringBefore(':').take(40)})" } ?: "failed"
            appendLine("• `${esc(t.request.method)} ${esc(t.request.path.ifBlank { t.request.url })}` → ${esc(status)}")
        }
        if (failures.size > MAX_FAILURES) appendLine("• …and ${failures.size - MAX_FAILURES} more")

        if (manifest.bookmarks.isNotEmpty()) {
            appendLine("*Bookmarks:*")
            manifest.bookmarks.forEach { b -> appendLine("• ${time(b.atMillis, zone)} — ${esc(b.note.ifBlank { "(no note)" })}") }
        }
        if (manifest.privacy.scrubbed || !manifest.privacy.includeBodies) {
            val parts = buildList {
                if (manifest.privacy.scrubbed) add("PII scrubbed (${manifest.privacy.scrubbedFields} fields)")
                if (!manifest.privacy.includeBodies) add("bodies not included")
            }
            appendLine("_${parts.joinToString(" · ")}_")
        }
        append("Capture attached: `${esc(fileName)}` — open in LogPose for Mac or Android Studio")
    }

    fun isFailure(e: LogEvent.Http): Boolean = e.tx.error != null || (e.tx.response?.code ?: 0) >= 400

    /** `12 http · 3 db · 2 fcm`, largest first, kinds in the timeline's own words. */
    fun kindCounts(events: List<LogEvent>): String =
        events.groupingBy { it.kind }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { KIND_ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } })
            .joinToString(" · ") { (k, n) -> "$n ${esc(k)}" }

    private val KIND_ORDER = listOf(Envelope.KIND_HTTP, Envelope.KIND_FCM, Envelope.KIND_DB, Envelope.KIND_WORKER, Envelope.KIND_CONFIG, Envelope.KIND_ANALYTICS, Envelope.KIND_EVENT)

    /** `13 Oct 2026, 14:02–14:19 (17 min)`. */
    fun spanText(span: CaptureManifest.Span, zone: TimeZone): String {
        val day = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = zone }
        val hm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
        val start = Date(span.startMillis)
        val end = Date(maxOf(span.endMillis, span.startMillis))
        val mins = (end.time - start.time) / 60_000
        val sameDay = day.format(start) == day.format(end)
        val endText = if (sameDay) hm.format(end) else "${day.format(end)}, ${hm.format(end)}"
        val dur = if (mins < 1) "<1 min" else "$mins min"
        return "${day.format(start)}, ${hm.format(start)}–$endText ($dur)"
    }

    fun time(atMillis: Long, zone: TimeZone): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(atMillis))

    /** Slack's three control characters; everything else in mrkdwn is literal enough. */
    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
