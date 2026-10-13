package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.capture.CaptureReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class M3GateTest {

    @TempDir lateinit var dir: File

    @Test fun `a shared session opens to the same timeline in the plugin path and the app path`() {
        val g = M3Gate.run(File(dir, "work"), File(dir, "out"))
        assertTrue(g.plugin.isNotEmpty())
        assertEquals(g.plugin, g.desktop) // identical events, payloads included
        assertEquals(g.recorded.map { it.id }, g.desktop.map { it.id }) // nothing lost on the way
        val c = CaptureReader.read(g.file)
        assertFalse(c.lines.any { "7022730639" in it || "Ravi Kumar" in it || "ravi.k@example.com" in it }, "PII left the machine")
        val m = c.manifest!!
        assertEquals("in.shadowfax.gandalf", m.app?.pkg)
        assertEquals("Pixel 9a", m.device?.model)
        assertEquals(1, m.bookmarks.size)
        assertEquals(1, c.screenshots.size)
        assertTrue(m.privacy.scrubbed && m.privacy.scrubbedFields > 0)
        assertEquals("tapped Accept here, spinner never stopped", g.model.marks.value.bookmarks.single().note)
        assertEquals(1, g.model.marks.value.shots.size)
        assertTrue(g.summary.contains("Capture attached: `${g.file.name}`"))
    }
}
