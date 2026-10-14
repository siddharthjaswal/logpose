package io.github.siddharthjaswal.logpose.desktop.emulators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AvdIniTest {
    /** Shaped like Studio's file, plus what a hand edit adds: comments, blanks, spacing, an unknown key. */
    private val config = """
        |# my notes about this AVD
        |AvdId=Pixel_9a
        |PlayStore.enabled=false
        |abi.type=arm64-v8a
        |
        |hw.gpu.enabled = no
        |hw.gpu.mode=auto
        |; legacy comment style
        |hw.ramSize=2048M
        |my.custom.key=keep me = exactly
        |image.sysdir.1=system-images/android-36.1/google_apis/arm64-v8a/
        |vm.heapSize=512
        |""".trimMargin()

    @Test fun `parse then render is byte-identical`() {
        for (text in listOf(config, config.removeSuffix("\n"), config.replace("\n", "\r\n"), "", "\n", "a=1", "  \n# only\n")) {
            assertEquals(text, AvdIni.parse(text).render(), "round trip of ${text.take(20)}")
        }
    }

    @Test fun `set replaces only the value and keeps spacing, comments, order and unknown keys`() {
        val ini = AvdIni.parse(config)
        assertTrue(ini.set("hw.gpu.enabled", "yes"))
        assertTrue(ini.set("hw.ramSize", "1536"))
        val out = ini.render()
        assertEquals(config.replace("hw.gpu.enabled = no", "hw.gpu.enabled = yes").replace("hw.ramSize=2048M", "hw.ramSize=1536"), out)
        assertEquals("keep me = exactly", ini["my.custom.key"])
    }

    @Test fun `a missing key is appended and a same value is no change`() {
        val ini = AvdIni.parse("a=1\nb=2")
        assertFalse(ini.set("a", "1"))
        assertTrue(ini.set("c", "3"))
        assertEquals("a=1\nb=2\nc=3", ini.render())
        val crlf = AvdIni.parse("a=1\r\n")
        crlf.set("b", "2")
        assertEquals("a=1\r\nb=2\r\n", crlf.render())
    }

    @Test fun `comments and blanks are not keys, and the last assignment wins`() {
        val ini = AvdIni.parse("# x=1\n; y=2\n\nz=1\nz=2\n")
        assertNull(ini["# x"])
        assertNull(ini["x"])
        assertEquals("2", ini["z"])
        assertEquals(listOf("z" to "1", "z" to "2"), ini.entries())
        ini.set("z", "3")
        assertEquals("# x=1\n; y=2\n\nz=1\nz=3\n", ini.render())
    }
}
