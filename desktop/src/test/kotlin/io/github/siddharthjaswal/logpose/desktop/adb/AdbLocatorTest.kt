package io.github.siddharthjaswal.logpose.desktop.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class AdbLocatorTest {
    private fun fs(vararg paths: String): (File) -> Boolean = { it.path in paths }

    @Test fun `a real SDK adb from core wins`() {
        assertEquals("/sdk/platform-tools/adb", AdbLocator.locate("/sdk/platform-tools/adb", "", "/h", fs("/sdk/platform-tools/adb")))
    }

    @Test fun `core's unchecked bare adb only counts when PATH really has one`() {
        assertEquals("/usr/bin/x/adb", AdbLocator.locate("adb", "/nope:/usr/bin/x", "/h", fs("/usr/bin/x/adb")))
        assertNull(AdbLocator.locate("adb", "/usr/bin", "/h", fs()))
    }

    @Test fun `Homebrew is searched even though a Finder launch has no such PATH`() {
        assertEquals("/opt/homebrew/bin/adb", AdbLocator.locate("adb", "/usr/bin", "/h", fs("/opt/homebrew/bin/adb")))
    }

    @Test fun `the downloaded copy is the last resort`() {
        val downloaded = "/h/Library/Application Support/LogPose/platform-tools/adb"
        assertEquals(downloaded, AdbLocator.locate("adb", "/usr/bin", "/h", fs(downloaded)))
    }
}
