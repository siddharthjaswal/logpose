package io.github.siddharthjaswal.logpose.capture

import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CaptureFileTest {

    @TempDir lateinit var dir: File

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    private fun http(id: String, at: Long, code: Int?, body: String? = null): String {
        val tx = Transaction(
            id, at, Request("GET", "https://api.example.com/v1/$id", "api.example.com", "/v1/$id"),
            code?.let { Response(it, "OK", body = Body("application/json", 10, body)) }, durationMillis = code?.let { 40L },
        )
        return CaptureLines.of(Envelope(kind = "http", id = id, at = at, endedAt = code?.let { at + 40 }, payload = json.encodeToJsonElement(Transaction.serializer(), tx)))
    }

    private val hello = Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "p1")

    private fun lines() = listOf(
        CaptureLines.of(hello),
        http("a", 1_000, null),            // in flight…
        http("b", 1_100, 200, """{"ok":true}"""),
        http("a", 1_000, 500, """{"error":"x"}"""), // …then completed in place
    )

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test fun `writer to reader round trip keeps manifest, events, bookmarks and screenshots`() {
        val manifest = CaptureManifest(
            writtenBy = "test", writtenAtMillis = 5, device = CaptureManifest.DeviceInfo("Pixel 9a", "15", "SER"),
            app = CaptureManifest.AppInfo(hello.pkg, hello.libVersion), session = CaptureManifest.Span(900, 2_000),
            bookmarks = listOf(Bookmark(1_050, "tapped Accept here"), Bookmark(1_900)),
            screenshots = listOf(ScreenshotRef(1_200, "shot-1.png")), eventCount = 2,
        )
        val out = CaptureWriter.write(File(dir, "s.logpose"), manifest, lines(), mapOf("shot-1.png" to png))
        val c = CaptureReader.read(out)
        assertEquals(manifest, c.manifest)
        assertEquals(lines(), c.lines)
        assertArrayEquals(png, c.screenshots["shot-1.png"])
        assertEquals(listOf("a", "b"), c.events.map { it.id })
        assertEquals(500, (c.events[0] as LogEvent.Http).tx.response?.code) // latest state, first position
        assertEquals(0, c.malformedLines)
        assertEquals(listOf(hello), c.replay.hellos)
        assertTrue(File(dir, ".s.logpose.part").exists().not())
    }

    @Test fun `replay into a store is a live session — order, in-place update and hello session`() {
        val c = Capture(null, lines())
        val store = EventStore()
        val warmed = ArrayList<String>()
        c.replayInto(store) { warmed += it.id }
        assertEquals(listOf("a", "b"), store.snapshot().map { it.id })
        assertEquals(listOf("a", "b", "a"), warmed)
        assertEquals(1, store.sessions().size)
        assertEquals("in.shadowfax.gandalf", store.sessions()[0].pkg)
    }

    @Test fun `a bare ndjson — the library export — opens with no manifest`() {
        val f = File(dir, "capture.ndjson").apply { writeText(lines().drop(1).joinToString("\n", postfix = "\n")) }
        val c = CaptureReader.read(f)
        assertNull(c.manifest)
        assertEquals(listOf("a", "b"), c.events.map { it.id })
    }

    @Test fun `malformed lines are skipped and counted, chunk pieces and banners are not malformed`() {
        val text = listOf(
            "--------- beginning of main",
            http("a", 1, 200),
            "{not json",
            """{"id":"zz","seq":0,"total":2,"payload":"{\"kind\""}""",
            """{"kind":"http","id":"q","at":1,"payload":"nope"}""",
            "",
        ).joinToString("\n")
        val c = CaptureReader.read(ByteArrayInputStream(text.toByteArray()), "x.ndjson")
        assertEquals(listOf("a", "q"), c.events.map { it.id }) // an undecodable http payload still gets a generic row
        assertEquals(2, c.malformedLines) // the banner and the broken object
    }

    @Test fun `zip-slip entry names are refused`() {
        for (bad in listOf("../evil.png", "screenshots/../../x", "/abs/path", "C:/x", "a\\b")) {
            val bytes = zipOf(CaptureFormat.EVENTS to http("a", 1, 200).toByteArray(), bad to byteArrayOf(1))
            val e = assertThrows<CaptureFormatException> { CaptureReader.read(ByteArrayInputStream(bytes), "bad.logpose") }
            assertTrue(e.message!!.contains("unsafe"), e.message)
        }
    }

    @Test fun `entry sizes are capped while reading, whatever the zip claims`() {
        val big = ByteArray((CaptureReader.MAX_MANIFEST_BYTES + 10).toInt()) { ' '.code.toByte() }
        val bytes = zipOf(CaptureFormat.MANIFEST to big, CaptureFormat.EVENTS to ByteArray(0))
        assertThrows<CaptureFormatException> { CaptureReader.read(ByteArrayInputStream(bytes), "big.logpose") }
    }

    @Test fun `unknown manifest keys and unknown entries are tolerated, a newer format version is not`() {
        val manifest = """{"formatVersion":1,"writtenBy":"future","hologram":{"x":1},"bookmarks":[{"atMillis":5,"note":"n","colour":"red"}]}"""
        val ok = zipOf(CaptureFormat.MANIFEST to manifest.toByteArray(), CaptureFormat.EVENTS to http("a", 1, 200).toByteArray(), "video/clip.mp4" to byteArrayOf(1))
        val c = CaptureReader.read(ByteArrayInputStream(ok), "f.logpose")
        assertEquals("future", c.manifest?.writtenBy)
        assertEquals(listOf(Bookmark(5, "n")), c.bookmarks)

        val newer = zipOf(CaptureFormat.MANIFEST to """{"formatVersion":2}""".toByteArray(), CaptureFormat.EVENTS to ByteArray(0))
        val e = assertThrows<CaptureFormatException> { CaptureReader.read(ByteArrayInputStream(newer), "n.logpose") }
        assertTrue(e.message!!.contains("format 2"))
    }

    @Test fun `a bundle without events is not a capture`() {
        val bytes = zipOf(CaptureFormat.MANIFEST to "{}".toByteArray())
        assertThrows<CaptureFormatException> { CaptureReader.read(ByteArrayInputStream(bytes), "e.logpose") }
    }

    @Test fun `flattening a replay restores hellos, also for a slice that cut the event after the hello`() {
        val second = Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "p2")
        val c = Capture(null, lines() + CaptureLines.of(second) + http("c", 3_000, 200) + http("d", 3_100, 200))
        val all = CaptureLines.of(c.replay)
        assertEquals(listOf(CaptureLines.of(hello)), all.take(1))
        assertEquals(6, all.size) // 2 hellos + 4 distinct events
        val slice = CaptureLines.of(c.replay, setOf("d"))
        assertEquals(listOf(CaptureLines.of(second), CaptureLines.of(c.events.last())), slice)
        // A flattened replay replays to the same events.
        assertEquals(c.events, Capture(null, all).events)
    }

    @Test fun `screenshot names must be flat pngs and dangling manifest refs are dropped`() {
        assertTrue(CaptureFormat.isSafeScreenshotName("shot-12.png"))
        assertTrue(!CaptureFormat.isSafeScreenshotName("../x.png") && !CaptureFormat.isSafeScreenshotName("a/b.png") && !CaptureFormat.isSafeScreenshotName("x.exe"))
        val out = CaptureWriter.write(File(dir, "d.logpose"), CaptureManifest(screenshots = listOf(ScreenshotRef(1, "gone.png"))), lines())
        assertEquals(emptyList<ScreenshotRef>(), CaptureReader.read(out).manifest?.screenshots)
        assertTrue(CaptureReader.accepts("Bug 12.LOGPOSE") && CaptureReader.accepts("capture.ndjson") && !CaptureReader.accepts("a.zip"))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
