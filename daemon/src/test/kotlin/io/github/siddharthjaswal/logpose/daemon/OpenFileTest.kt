package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.CaptureWriter
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `logpose serve --open <file>`: a shared capture served over MCP with no device. */
class OpenFileTest {

    @TempDir lateinit var dir: File

    private fun line(id: String) = CaptureLines.of(Envelope(kind = "event", id = id, at = 1, endedAt = 1, payload = JsonPrimitive(id)))

    @Test fun `--open parses, resolves against the cwd, and refuses device flags`() {
        val cmd = Cli.parse(listOf("serve", "--open", "bug.logpose"), dir) as Cli.Command.Serve
        assertEquals(File(dir, "bug.logpose"), cmd.options.open)
        val abs = Cli.parse(listOf("serve", "--open=/tmp/x.ndjson"), dir) as Cli.Command.Serve
        assertEquals(File("/tmp/x.ndjson"), abs.options.open)
        for (extra in listOf("--mocks", "--clear", "--device=SER")) {
            val r = Cli.parse(listOf("serve", "--open", "a.logpose", extra), dir)
            assertTrue(r is Cli.Command.Exit && r.exitCode == 2, "$extra: $r")
        }
        assertTrue(Cli.usage().contains("--open FILE"))
    }

    @Test fun `a capture file loads into the store the MCP tools read, and adb is never touched`() {
        val file = CaptureWriter.write(
            File(dir, "bug.logpose"),
            CaptureManifest(app = CaptureManifest.AppInfo("in.shadowfax.gandalf", "1.7.3")),
            listOf(CaptureLines.of(Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "p")), line("a"), line("b")),
        )
        val options = Cli.ServeOptions(projectDir = dir, open = file)
        val capture = Capture(options, KeyValueStore.InMemory(), KeyValueStore.InMemory(), Log(verboseStateChanges = false))
        capture.start()
        assertEquals(listOf("a", "b"), capture.store.snapshot().map { it.id })
        assertEquals(1, capture.store.sessions().size)
        assertEquals("file", capture.state())
        assertFalse(capture.isRunning())
        capture.stop(clearDeviceRules = false)
    }

    @Test fun `an unreadable file fails start with a sentence`() {
        val bad = File(dir, "bad.logpose").apply { writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)) }
        val capture = Capture(Cli.ServeOptions(projectDir = dir, open = bad), KeyValueStore.InMemory(), KeyValueStore.InMemory(), Log(verboseStateChanges = false))
        assertThrows<Exception> { capture.start() }
    }
}
