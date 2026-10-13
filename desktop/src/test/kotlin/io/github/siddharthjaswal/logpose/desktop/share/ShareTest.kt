package io.github.siddharthjaswal.logpose.desktop.share

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import io.github.siddharthjaswal.logpose.capture.Scrubber
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.TimeZone

private const val T0 = 1_760_340_000_000L // 13 Oct 2025 07:20 UTC
private const val MIN = 60_000L
private val json = Json { encodeDefaults = false; explicitNulls = false }

private fun http(id: String, at: Long, code: Int?, path: String = "/v1/$id", error: String? = null, body: String? = null, method: String = "GET"): String {
    val tx = Transaction(
        id, at, Request(method, "https://api.example.com$path", "api.example.com", path),
        code?.let { Response(it, "", body = Body("application/json", (body?.length ?: 0).toLong(), body)) }, code?.let { 40L }, error,
    )
    val ended = if (code == null && error == null) null else at + 40
    return CaptureLines.of(Envelope(kind = "http", id = id, at = at, endedAt = ended, payload = json.encodeToJsonElement(Transaction.serializer(), tx)))
}

private val hello = Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "p1")

/** A 12-minute session: a request left in flight early on, a failure near the bookmark, one far from it. */
private fun source(bookmarks: List<Bookmark> = listOf(Bookmark(T0 + 3 * MIN, "tapped Accept here, spinner never stopped"))) = ShareSource(
    capture = Capture(
        null,
        listOf(
            CaptureLines.of(hello),
            http("early", T0, 200),
            http("stuck", T0 + 30_000, null, path = "/v1/orders/OR-881/accept", method = "POST"), // never answered
            http("near", T0 + 3 * MIN + 20_000, 500, path = "/v1/orders/OR-881", body = """{"customer":{"name":"Ravi Kumar","phone":"7022730639"}}"""),
            http("late", T0 + 10 * MIN, 200, body = """{"ok":true}"""),
            http("timeout", T0 + 10 * MIN + 5_000, null, path = "/v1/earnings/today", error = "SocketTimeoutException: timeout"),
        ),
    ),
    device = CaptureManifest.DeviceInfo("Pixel 9a", "15", "SER"),
    app = CaptureManifest.AppInfo(hello.pkg, hello.libVersion),
    startedAtMillis = T0 - 5_000, endedAtMillis = T0 + 12 * MIN,
    bookmarks = bookmarks,
    screenshots = listOf(ScreenshotRef(T0 + 3 * MIN + 1_000, "shot-1.png"), ScreenshotRef(T0 + 11 * MIN, "shot-2.png")),
    loadScreenshot = { byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) },
)

class ShareScopeTest {

    @Test fun `default is around the last bookmark when there is one, else the whole session`() {
        assertEquals(ShareScope.Whole, ShareScope.default(emptyList()))
        assertEquals(ShareScope.AroundBookmark(1, 2), ShareScope.default(listOf(Bookmark(1), Bookmark(2))))
    }

    @Test fun `around a bookmark keeps the window — and the request still in flight from before it`() {
        val p = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.AroundBookmark(0, 2)))
        assertEquals(listOf("stuck", "near"), p.events.map { it.id })
        assertEquals(CaptureManifest.Span(T0 + MIN, T0 + 5 * MIN), p.span)
        assertEquals(listOf("shot-1.png"), p.screenshots.map { it.file })
        assertEquals(1, p.bookmarks.size)
        assertTrue(p.scopeLabel.startsWith("±2 min around bookmark 1"))
        // ±5 reaches the first request too.
        assertEquals(listOf("early", "stuck", "near"), SharePlanner.plan(source(), ShareOptions(scope = ShareScope.AroundBookmark(0, 5))).events.map { it.id })
    }

    @Test fun `since the last bookmark`() {
        val p = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.SinceLastBookmark))
        assertEquals(listOf("stuck", "near", "late", "timeout"), p.events.map { it.id })
        assertEquals(2, p.screenshots.size)
    }

    @Test fun `only selected rows, and a bookmark scope with no bookmarks falls back to the whole session`() {
        val sel = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.Selected), setOf("late", "early"))
        assertEquals(listOf("early", "late"), sel.events.map { it.id })
        assertEquals("2 selected rows", sel.scopeLabel)
        val none = SharePlanner.plan(source(emptyList()), ShareOptions(scope = ShareScope.AroundBookmark(0, 2)))
        assertEquals(ShareScope.Whole, none.options.scope)
        assertEquals(5, none.events.size)
    }

    @Test fun `the plan is scrubbed, keeps the session's hello, and replays like the original`() {
        val p = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.AroundBookmark(0, 2)))
        assertEquals(CaptureLines.of(hello), p.lines.first())
        assertFalse(p.lines.any { "7022730639" in it || "Ravi" in it })
        assertEquals(2, p.report.total)
        assertEquals("scrubbed 2 fields: 1 phone number, 1 name", p.report.summary())
        assertTrue(p.samples.single().text.contains(Scrubber.MARK))
        assertTrue(p.samples.single().title.startsWith("GET /v1/orders/OR-881 → 500"))
        val replayed = Capture(null, p.lines)
        assertEquals(listOf("stuck", "near"), replayed.events.map { it.id })
    }

    @Test fun `scrubber off leaves bodies as captured, includeBodies off drops them`() {
        val raw = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.Whole, scrub = false))
        assertTrue(raw.lines.any { "7022730639" in it })
        assertEquals(0, raw.report.total)
        val noBodies = SharePlanner.plan(source(), ShareOptions(scope = ShareScope.Whole, includeBodies = false))
        assertEquals(2, noBodies.report.bodiesDropped)
        assertFalse(noBodies.lines.any { "\"text\"" in it })
    }

    @Test fun `export writes a bundle that reads back with manifest, screenshots and the scrubbed events`(@TempDir dir: File) {
        val src = source()
        val p = SharePlanner.plan(src, ShareOptions(scope = ShareScope.AroundBookmark(0, 2)))
        val f = ShareIo.export(src, p, dir, "LogPose for Mac test", T0 + 13 * MIN)
        assertTrue(f.name.startsWith("gandalf-Pixel-9a-") && f.name.endsWith(".logpose"), f.name)
        val c = CaptureReader.read(f)
        val m = c.manifest!!
        assertEquals("in.shadowfax.gandalf", m.app?.pkg)
        assertEquals("15", m.device?.androidVersion)
        assertEquals(2, m.eventCount)
        assertTrue(m.privacy.scrubbed)
        assertEquals(2, m.privacy.scrubbedFields)
        assertEquals(listOf("shot-1.png"), c.screenshots.keys.toList())
        assertEquals(p.lines, c.lines)
        // A second export never overwrites the first.
        val f2 = ShareIo.export(src, p, dir, "x", T0 + 13 * MIN)
        assertTrue(f2.name.endsWith("-2.logpose"), f2.name)
    }

    @Test fun `file names`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("gandalf-Pixel-9a-20251013-0720.logpose", SharePlanner.fileName(CaptureManifest.AppInfo("in.shadowfax.gandalf"), CaptureManifest.DeviceInfo("Pixel 9a"), T0, utc))
        assertEquals("capture-SER-20251013-0720.logpose", SharePlanner.fileName(null, CaptureManifest.DeviceInfo(serial = "SER"), T0, utc))
    }

    @Test fun `overlap includes open spans that started before the window`() {
        val events = Capture(null, listOf(http("open", 0, null), http("closed", 0, 200))).events
        assertTrue(SharePlanner.overlaps(events[0], 1_000_000, 2_000_000))
        assertFalse(SharePlanner.overlaps(events[1], 1_000_000, 2_000_000))
    }
}

class SlackSummaryTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun `summary carries app, device, span, counts, failures, bookmarks and the file`() {
        val src = source()
        val p = SharePlanner.plan(src, ShareOptions(scope = ShareScope.Whole))
        val m = SharePlanner.manifest(src, p, "LogPose for Mac", T0 + 13 * MIN)
        val text = SlackSummary.format(m, p.events, "gandalf-Pixel-9a-20251013-0720.logpose", utc)
        val expected = """
            *LogPose capture* — `in.shadowfax.gandalf` (LogPose 1.7.3)
            *Device:* Pixel 9a · Android 15
            *Session:* 13 Oct 2025, 07:19–07:32 (12 min)
            *Events:* 5 — 5 http
            *Failures:* 2
            • `GET /v1/orders/OR-881` → 500
            • `GET /v1/earnings/today` → failed (SocketTimeoutException)
            *Bookmarks:*
            • 07:23:00 — tapped Accept here, spinner never stopped
            _PII scrubbed (2 fields)_
            Capture attached: `gandalf-Pixel-9a-20251013-0720.logpose` — open in LogPose for Mac or Android Studio
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test fun `scope, failure overflow, escaping and no-bookmark wording`() {
        val lines = (1..5).map { http("f$it", T0 + it, 503, path = "/v1/a<$it>&b") }
        val events = Capture(null, lines).events
        val m = CaptureManifest(
            app = CaptureManifest.AppInfo("app"), session = CaptureManifest.Span(T0, T0 + 30_000), scope = "±1 min around bookmark 2",
            bookmarks = listOf(Bookmark(T0 + 1_000)), privacy = CaptureManifest.Privacy(scrubbed = false, includeBodies = false),
        )
        val text = SlackSummary.format(m, events, "a.logpose", utc)
        assertTrue(text.contains("*Session:* 13 Oct 2025, 07:20–07:20 (<1 min) · ±1 min around bookmark 2"), text)
        assertTrue(text.contains("• `GET /v1/a&lt;1&gt;&amp;b` → 503"), text)
        assertTrue(text.contains("• …and 2 more"), text)
        assertTrue(text.contains("• 07:20:01 — (no note)"), text)
        assertTrue(text.contains("_bodies not included_"), text)
        assertFalse(text.contains("*Device:*"))
    }

    @Test fun `kind counts are largest first, in timeline order on ties`() {
        val events: List<LogEvent> = Capture(null, Fixtures.lines()).events
        assertEquals("7 http · 2 fcm · 2 db · 2 worker · 1 config · 1 analytics · 1 event", SlackSummary.kindCounts(events))
    }
}
