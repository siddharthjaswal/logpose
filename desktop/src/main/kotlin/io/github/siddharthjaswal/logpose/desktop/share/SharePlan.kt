package io.github.siddharthjaswal.logpose.desktop.share

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import io.github.siddharthjaswal.logpose.capture.ScrubReport
import io.github.siddharthjaswal.logpose.capture.Scrubber
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What to include (PRD §5.3, part 1). */
sealed interface ShareScope {
    data object Whole : ShareScope
    data object SinceLastBookmark : ShareScope
    /** ±[minutes] around bookmark [index] (0-based, in the order they were made). */
    data class AroundBookmark(val index: Int, val minutes: Int) : ShareScope
    data object Selected : ShareScope

    companion object {
        val MINUTE_CHOICES = listOf(1, 2, 5)
        const val DEFAULT_MINUTES = 2

        /** Around the last bookmark when there is one — that is where the tester said "here". */
        fun default(bookmarks: List<Bookmark>): ShareScope =
            if (bookmarks.isEmpty()) Whole else AroundBookmark(bookmarks.lastIndex, DEFAULT_MINUTES)
    }
}

data class ShareOptions(
    val scope: ShareScope = ShareScope.Whole,
    val scrub: Boolean = true,
    val includeBodies: Boolean = true,
    val keys: List<String> = Scrubber.DEFAULT_KEYS,
)

/**
 * The record a share is cut from: the full disk recording of a session (or an opened capture),
 * plus what is known about the session. Built on a background thread; pure from here on.
 */
data class ShareSource(
    val capture: Capture,
    val device: CaptureManifest.DeviceInfo?,
    val app: CaptureManifest.AppInfo?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val bookmarks: List<Bookmark>,
    val screenshots: List<ScreenshotRef>,
    /** Reads a screenshot's PNG by file name — blocking; only the export calls it. */
    val loadScreenshot: (String) -> ByteArray? = { capture.screenshots[it] },
)

/** One scrubbed payload shown on the sheet, exactly as it will leave the machine. */
data class ShareSample(val title: String, val text: String, val scrubbed: Int)

/** Everything the sheet previews and the export writes — computed, not yet written. */
data class SharePreview(
    val events: List<LogEvent>,
    val lines: List<String>,
    val report: ScrubReport,
    val samples: List<ShareSample>,
    val bookmarks: List<Bookmark>,
    val screenshots: List<ScreenshotRef>,
    val span: CaptureManifest.Span,
    val scopeLabel: String,
    val options: ShareOptions,
)

/** Pure share logic: scope selection, scrubbing, samples, the manifest and the file name. */
object SharePlanner {

    private val pretty = Json { prettyPrint = true }

    fun plan(source: ShareSource, options: ShareOptions, selectedIds: Set<String> = emptySet()): SharePreview {
        val all = source.capture.events
        val scope = effective(options.scope, source.bookmarks, selectedIds)
        val (lo, hi) = window(scope, source)
        val chosen = when (scope) {
            ShareScope.Selected -> all.filter { it.id in selectedIds }
            ShareScope.Whole -> all
            else -> all.filter { overlaps(it, lo, hi) }
        }
        val span = when (scope) {
            ShareScope.Whole -> CaptureManifest.Span(source.startedAtMillis, source.endedAtMillis)
            ShareScope.Selected -> CaptureManifest.Span(
                chosen.minOfOrNull { it.timestampMillis } ?: source.startedAtMillis,
                chosen.maxOfOrNull { it.envelope.endedAt ?: it.timestampMillis } ?: source.endedAtMillis,
            )
            else -> CaptureManifest.Span(lo, hi)
        }
        val inSpan = { at: Long -> scope == ShareScope.Whole || at in span.startMillis..span.endMillis }

        val scrubber = Scrubber(Scrubber.Options(enabled = options.scrub, keys = options.keys, includeBodies = options.includeBodies))
        var report = ScrubReport()
        val scrubbed = HashMap<String, Envelope>(chosen.size)
        val touched = ArrayList<Pair<LogEvent, Scrubber.Result>>()
        for (e in chosen) {
            val r = scrubber.scrub(e.envelope)
            report += ScrubReport.of(e.kind, r)
            scrubbed[e.id] = r.envelope
            if (r.scrubbed > 0 || r.bodiesDropped > 0) touched += e to r
        }
        val ids = chosen.mapTo(HashSet()) { it.id }
        val lines = CaptureLines.of(source.capture.replay, if (scope == ShareScope.Whole) null else ids) { scrubbed[it.id] ?: it.envelope }
        val samples = touched.sortedByDescending { it.second.scrubbed }.take(2).map { (e, r) -> sample(e, r) }
            .ifEmpty { chosen.firstOrNull { it is LogEvent.Http && it.tx.response?.body?.text != null }?.let { e -> listOf(sample(e, scrubber.scrub(e.envelope))) }.orEmpty() }
        return SharePreview(
            events = chosen, lines = lines, report = report, samples = samples,
            bookmarks = source.bookmarks.filter { inSpan(it.atMillis) },
            screenshots = source.screenshots.filter { inSpan(it.atMillis) },
            span = span, scopeLabel = describe(scope, source.bookmarks, chosen.size), options = options.copy(scope = scope),
        )
    }

    /** A scope that needs a bookmark, asked for with none, falls back to the whole session. */
    fun effective(scope: ShareScope, bookmarks: List<Bookmark>, selectedIds: Set<String>): ShareScope = when (scope) {
        ShareScope.SinceLastBookmark -> if (bookmarks.isEmpty()) ShareScope.Whole else scope
        is ShareScope.AroundBookmark -> when {
            bookmarks.isEmpty() -> ShareScope.Whole
            scope.index !in bookmarks.indices -> scope.copy(index = bookmarks.lastIndex)
            else -> scope
        }
        ShareScope.Selected -> scope
        ShareScope.Whole -> scope
    }

    private fun window(scope: ShareScope, s: ShareSource): Pair<Long, Long> = when (scope) {
        ShareScope.SinceLastBookmark -> s.bookmarks.last().atMillis to maxOf(s.endedAtMillis, s.bookmarks.last().atMillis)
        is ShareScope.AroundBookmark -> {
            val at = s.bookmarks[scope.index].atMillis
            (at - scope.minutes * 60_000L) to (at + scope.minutes * 60_000L)
        }
        else -> Long.MIN_VALUE to Long.MAX_VALUE
    }

    /**
     * Whether an event's span touches [lo, hi]. An open span (still in flight when the capture
     * ended) reaches "forever" — the request whose spinner never stopped is exactly the one the
     * bookmark is about.
     */
    fun overlaps(e: LogEvent, lo: Long, hi: Long): Boolean {
        val start = e.timestampMillis
        val end = e.envelope.endedAt ?: Long.MAX_VALUE
        return start <= hi && end >= lo
    }

    fun describe(scope: ShareScope, bookmarks: List<Bookmark>, count: Int): String = when (scope) {
        ShareScope.Whole -> "whole session"
        ShareScope.SinceLastBookmark -> "since bookmark ${bookmarks.size}" + note(bookmarks.last())
        is ShareScope.AroundBookmark -> "±${scope.minutes} min around bookmark ${scope.index + 1}" + note(bookmarks[scope.index])
        ShareScope.Selected -> "$count selected ${if (count == 1) "row" else "rows"}"
    }

    private fun note(b: Bookmark) = if (b.note.isBlank()) "" else " (“${b.note.take(40)}”)"

    private fun sample(e: LogEvent, r: Scrubber.Result): ShareSample {
        val payload = r.envelope.payload
        val (title, text) = when (e) {
            is LogEvent.Http -> {
                val t = e.tx
                val status = t.response?.code?.toString() ?: t.error?.let { "failed" } ?: "pending"
                val tx = payload as? kotlinx.serialization.json.JsonObject
                val req = bodyText(tx?.get("request"))?.let(::prettyIfJson)
                val res = bodyText(tx?.get("response"))?.let(::prettyIfJson)
                // Both bodies when both exist — the scrubbed value may be in either.
                val body = when {
                    req != null && res != null -> "request body:\n$req\n\nresponse body:\n$res"
                    else -> req ?: res
                }
                "${t.request.method} ${t.request.path.ifBlank { t.request.url }} → $status" to (body ?: "(body not included)")
            }
            else -> "${e.kind} · ${e.id}" to pretty.encodeToString(JsonElement.serializer(), payload)
        }
        val lines = text.lines()
        val clipped = if (lines.size > MAX_SAMPLE_LINES) (lines.take(MAX_SAMPLE_LINES) + "…").joinToString("\n") else text
        return ShareSample(title, clipped, r.scrubbed)
    }

    private fun bodyText(m: JsonElement?): String? {
        val body = (m as? kotlinx.serialization.json.JsonObject)?.get("body") as? kotlinx.serialization.json.JsonObject ?: return null
        val t = body["text"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return if (t is kotlinx.serialization.json.JsonNull) null else t.content
    }

    private fun prettyIfJson(s: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(s)) }.getOrDefault(s)

    private const val MAX_SAMPLE_LINES = 40

    /** The manifest for an export — what was cut, from what, and what the scrubber did to it. */
    fun manifest(source: ShareSource, preview: SharePreview, writtenBy: String, nowMillis: Long): CaptureManifest =
        CaptureManifest(
            writtenBy = writtenBy,
            writtenAtMillis = nowMillis,
            device = source.device,
            app = source.app,
            session = preview.span,
            privacy = CaptureManifest.Privacy(
                deviceRedaction = true,
                scrubbed = preview.options.scrub,
                scrubKeys = if (preview.options.scrub) preview.options.keys else emptyList(),
                scrubPatterns = preview.options.scrub,
                includeBodies = preview.options.includeBodies,
                scrubbedFields = preview.report.total,
            ),
            bookmarks = preview.bookmarks,
            screenshots = preview.screenshots,
            eventCount = preview.events.size,
            scope = preview.scopeLabel,
        )

    /** `gandalf-Pixel-9a-20261013-1402.logpose`. */
    fun fileName(app: CaptureManifest.AppInfo?, device: CaptureManifest.DeviceInfo?, atMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val appPart = app?.pkg?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "capture"
        val devPart = device?.model ?: device?.serial ?: "device"
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = zone }.format(Date(atMillis))
        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9.]+"), "-").trim('-', '.').take(32).ifBlank { "x" }
        return "${safe(appPart)}-${safe(devPart)}-$ts.logpose"
    }

    /** [name] in [dir], or `name-2`, `name-3`… so an export never overwrites the last one. */
    fun uniqueTarget(dir: java.io.File, name: String): java.io.File {
        var f = java.io.File(dir, name)
        var n = 2
        val stem = name.removeSuffix(".logpose")
        while (f.exists()) f = java.io.File(dir, "$stem-${n++}.logpose")
        return f
    }
}
