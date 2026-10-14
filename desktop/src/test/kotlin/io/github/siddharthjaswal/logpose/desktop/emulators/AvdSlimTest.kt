package io.github.siddharthjaswal.logpose.desktop.emulators

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

class AvdSlimTest {
    @TempDir lateinit var home: File

    private val stock = """
        |# keep this comment
        |avd.ini.displayname=Pixel 9a
        |hw.gpu.enabled=no
        |hw.gpu.mode=swiftshader_indirect
        |hw.ramSize=2048
        |tag.id=google_apis
        |vm.heapSize=512
        |zz.unknown=1
        |""".trimMargin()

    private fun avd(text: String = stock, name: String = "Pixel_9a"): AvdInfo {
        val dir = File(home, "$name.avd").apply { mkdirs() }
        File(dir, "config.ini").writeText(text)
        return AvdInfo.from(name, dir, AvdIni.parse(text), hasQuickBootSnapshot = false)
    }

    private val t0 = LocalDateTime.of(2026, 10, 14, 12, 15, 0)

    @Test fun `plan lowers RAM, turns on the host GPU, and never raises RAM`() {
        val plan = AvdSlim.plan(AvdIni.parse(stock), 1536)
        assertEquals(
            listOf(AvdSlim.Change("hw.ramSize", "2048", "1536"), AvdSlim.Change("hw.gpu.enabled", "no", "yes"), AvdSlim.Change("hw.gpu.mode", "swiftshader_indirect", "host")),
            plan,
        )
        assertEquals(emptyList<AvdSlim.Change>(), AvdSlim.plan(AvdIni.parse("hw.ramSize=1024\nhw.gpu.enabled=yes\nhw.gpu.mode=host\n"), 1536))
        assertEquals(listOf("hw.ramSize"), AvdSlim.plan(AvdIni.parse("hw.ramSize=3G\nhw.gpu.enabled=true\nhw.gpu.mode=host\n"), 2048).map { it.key })
    }

    @Test fun `slim writes a timestamped backup first and edits in place`() {
        val a = avd()
        val r = AvdSlimmer { t0 }.slim(a, 1536, running = false) as AvdSlimmer.Result.Done
        val backup = File(a.dir, "config.ini.logpose-backup-20261014-121500")
        assertEquals(backup, r.backup)
        assertEquals(stock, backup.readText())
        val now = a.configFile.readText()
        assertEquals(stock.replace("hw.gpu.enabled=no", "hw.gpu.enabled=yes").replace("hw.gpu.mode=swiftshader_indirect", "hw.gpu.mode=host").replace("hw.ramSize=2048", "hw.ramSize=1536"), now)
        assertTrue(now.startsWith("# keep this comment\n"))
        val state = AvdSlim.state(a.dir)
        assertTrue(state.slimmed)
        assertFalse(state.editedSince)
        assertEquals(1536, state.slimmedToMb)
        assertEquals(2048, state.originalRamMb)
    }

    @Test fun `a second slim keeps the first backup, and revert restores the original byte for byte`() {
        val a = avd()
        val original = a.configFile.readBytes()
        val s = AvdSlimmer { t0 }
        s.slim(a, 2048, running = false)
        s.slim(a, 1024, running = false)
        assertEquals(1, AvdSlim.backups(a.dir).size)
        assertTrue(a.configFile.readText().contains("hw.ramSize=1024"))
        val r = s.revert(a, running = false)
        assertTrue(r is AvdSlimmer.Result.Done, r.toString())
        assertArrayEquals(original, a.configFile.readBytes())
        assertEquals(emptyList<File>(), AvdSlim.backups(a.dir))
        assertEquals(setOf("config.ini"), a.dir.list()!!.toSet())
    }

    @Test fun `CRLF files and files without a final newline survive slim and revert exactly`() {
        for (text in listOf(stock.replace("\n", "\r\n"), stock.removeSuffix("\n"))) {
            val a = avd(text, "A${text.length}")
            AvdSlimmer { t0 }.slim(a, 1536, running = false)
            assertTrue(a.configFile.readText().contains("hw.ramSize=1536"))
            AvdSlimmer { t0 }.revert(a, running = false)
            assertEquals(text, a.configFile.readText())
        }
    }

    @Test fun `running AVDs are refused and nothing is written`() {
        val a = avd()
        val r = AvdSlimmer { t0 }.slim(a, 1536, running = true)
        assertTrue(r is AvdSlimmer.Result.Refused)
        assertTrue((r as AvdSlimmer.Result.Refused).reason.contains("Shut the emulator down"))
        assertEquals(stock, a.configFile.readText())
        assertEquals(emptyList<File>(), AvdSlim.backups(a.dir))
        AvdSlimmer { t0 }.slim(a, 1536, running = false)
        assertTrue(AvdSlimmer { t0 }.revert(a, running = true) is AvdSlimmer.Result.Refused)
        assertTrue(a.configFile.readText().contains("hw.ramSize=1536"))
    }

    @Test fun `nothing to change writes no backup, and revert without a backup refuses`() {
        val slim = "hw.ramSize=1024\nhw.gpu.enabled=yes\nhw.gpu.mode=host\n"
        val a = avd(slim)
        val r = AvdSlimmer { t0 }.slim(a, 1536, running = false) as AvdSlimmer.Result.Done
        assertTrue(r.changes.isEmpty())
        assertEquals(emptyList<File>(), AvdSlim.backups(a.dir))
        assertTrue(AvdSlimmer { t0 }.revert(a, running = false) is AvdSlimmer.Result.Refused)
    }

    @Test fun `an edit after the slim is noticed`() {
        val a = avd()
        AvdSlimmer { t0 }.slim(a, 1536, running = false)
        a.configFile.appendText("hw.lcd.density=440\n")
        assertTrue(AvdSlim.state(a.dir).editedSince)
        assertNotNull(AvdSlim.state(a.dir).backup)
    }
}
