package io.github.siddharthjaswal.logpose.desktop.session

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SessionRecorderTest {

    @TempDir lateinit var dir: File

    private class FakeSource : LineSource {
        var onLine: (String) -> Unit = {}
        override fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) { this.onLine = onLine }
        override fun stop() {}
    }

    private fun event(i: Int) = CaptureLines.of(Envelope(kind = "event", id = "e$i", at = 1_000L + i, endedAt = 1_000L + i, payload = JsonPrimitive("x$i")))

    @Test fun `every event reaches the file even after the store evicted it`() {
        val src = FakeSource()
        var rec: SessionRecorder? = null
        val session = CaptureSession(
            store = EventStore(capacity = 50),
            sourceFor = { src },
            recorderFor = { serial, at ->
                SessionRecorder(SessionFiles(dir, SessionFiles.baseName(at, "Pixel_9a")), SessionMeta(serial, "Pixel 9a", startedAtMillis = at), flushEveryMillis = 50)
                    .also { rec = it }
            },
        )
        session.start("SER")
        val hello = Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "p1")
        src.onLine(CaptureLines.of(hello))
        repeat(500) { src.onLine(event(it)) }
        assertEquals(50, session.store.snapshot().size) // the live view is capped…
        session.stop()
        val r = rec!!
        assertTrue(r.awaitClosed())
        val capture = CaptureReader.read(r.files.events)
        assertEquals(500, capture.events.size) // …the record is not
        assertEquals("e0", capture.events.first().id)
        assertEquals(listOf(hello), capture.replay.hellos)
        val meta = r.files.readMeta()!!
        assertEquals("in.shadowfax.gandalf", meta.appPackage)
        assertEquals("1.7.3", meta.libVersion)
        assertTrue(meta.endedAtMillis != null)
    }

    @Test fun `in-place updates are all recorded and replay to the latest state`() {
        val rec = SessionRecorder(SessionFiles(dir, "s"), SessionMeta("S", startedAtMillis = 1), flushEveryMillis = 20)
        Fixtures.lines().forEach(rec::append)
        assertTrue(rec.flush())
        assertEquals(Fixtures.lines(), rec.files.events.readLines())
        rec.close(2); rec.awaitClosed()
    }

    @Test fun `bookmarks and screenshots persist in the session metadata`() {
        val rec = SessionRecorder(SessionFiles(dir, "s"), SessionMeta("S", startedAtMillis = 1), flushEveryMillis = 20)
        rec.addBookmark(Bookmark(1_500, "tapped Accept here"))
        rec.addBookmark(Bookmark(1_900))
        val (ref, file) = rec.addScreenshot(byteArrayOf(1, 2, 3), 1_700)
        assertTrue(file.isFile)
        assertTrue(rec.flush())
        val meta = SessionFiles(dir, "s").readMeta()!!
        assertEquals(listOf(Bookmark(1_500, "tapped Accept here"), Bookmark(1_900)), meta.bookmarks)
        assertEquals(listOf(ref), meta.screenshots)
        rec.close(3); rec.awaitClosed()
        assertEquals(3L, SessionFiles(dir, "s").readMeta()!!.endedAtMillis)
    }

    @Test fun `appending after close is ignored, close is idempotent`() {
        val rec = SessionRecorder(SessionFiles(dir, "s"), SessionMeta("S", startedAtMillis = 1), flushEveryMillis = 20)
        rec.append("{\"a\":1}")
        rec.close(2); rec.close(3)
        assertTrue(rec.awaitClosed())
        rec.append("{\"b\":2}")
        assertEquals(listOf("{\"a\":1}"), rec.files.events.readLines())
        assertEquals(2L, rec.files.readMeta()!!.endedAtMillis)
    }

    @Test fun `old sessions are pruned to the newest N, files and screenshots together`() {
        for (i in 1..25) {
            val f = SessionFiles(dir, "202610%02d-120000-Pixel".format(i))
            f.events.writeText("x\n"); f.meta.writeText("{}"); f.screenshots.mkdirs()
        }
        val doomed = SessionFiles.prune(dir, keep = 20)
        assertEquals(5, doomed.size)
        assertFalse(File(dir, "20261001-120000-Pixel.ndjson").exists())
        assertFalse(File(dir, "20261001-120000-Pixel.screenshots").exists())
        assertTrue(File(dir, "20261025-120000-Pixel.ndjson").exists())
        assertEquals(20, dir.listFiles { f -> f.name.endsWith(".ndjson") }!!.size)
    }

    @Test fun `base names sort by start time and are file-safe`() {
        val a = SessionFiles.baseName(1_760_340_000_000, "Pixel 9a / test")
        val b = SessionFiles.baseName(1_760_340_060_000, "adb-R5CT20ABCDE-QoXtBe._adb-tls-connect._tcp")
        assertTrue(a < b)
        assertTrue(a.matches(Regex("\\d{8}-\\d{6}-[A-Za-z0-9._-]+")), a)
        assertFalse(b.contains('/'))
    }
}
