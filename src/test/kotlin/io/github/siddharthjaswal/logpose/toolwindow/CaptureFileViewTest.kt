package io.github.siddharthjaswal.logpose.toolwindow

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureFileViewTest {
    private fun line(id: String) = CaptureLines.of(Envelope(kind = "event", id = id, at = 1, endedAt = 1, payload = JsonPrimitive(id)))
    private val hello = CaptureLines.of(Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3"))

    @Test fun `banner names the file, the count and what the manifest knows`() {
        val m = CaptureManifest(
            app = CaptureManifest.AppInfo("in.shadowfax.gandalf"), device = CaptureManifest.DeviceInfo("Pixel 9a"),
            scope = "±2 min around bookmark 1", bookmarks = listOf(Bookmark(1, "x")), privacy = CaptureManifest.Privacy(scrubbed = true),
        )
        assertEquals(
            "Viewing bug.logpose (read-only) · 2 events · in.shadowfax.gandalf · Pixel 9a · ±2 min around bookmark 1 · 1 bookmark · PII scrubbed",
            CaptureFileView.bannerText("bug.logpose", Capture(m, listOf(line("a"), line("b")))),
        )
    }

    @Test fun `a bare ndjson takes the app from its hello`() {
        assertEquals(
            "Viewing capture.ndjson (read-only) · 1 event · in.shadowfax.gandalf",
            CaptureFileView.bannerText("capture.ndjson", Capture(null, listOf(hello, line("a")))),
        )
    }

    @Test fun `malformed lines are reported, live actions are off while viewing`() {
        assertNull(CaptureFileView.malformedNotice(Capture(null, listOf(line("a")))))
        assertEquals("2 unreadable lines skipped", CaptureFileView.malformedNotice(Capture(null, listOf("{x", "nope", line("a")))))
        assertTrue(CaptureFileView.liveActionsEnabled(null))
        assertFalse(CaptureFileView.liveActionsEnabled("a.logpose"))
    }

    @Test fun `a cancelled replay stops feeding the store`() {
        val store = EventStore()
        var n = 0
        Capture(null, (1..10).map { line("e$it") }).replayInto(store, cancelled = { n >= 3 }) { n++ }
        assertEquals(3, store.snapshot().size)
    }
}
