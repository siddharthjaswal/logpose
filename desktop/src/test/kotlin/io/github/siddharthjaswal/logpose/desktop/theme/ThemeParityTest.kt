package io.github.siddharthjaswal.logpose.desktop.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Mac app copies the plugin's token values rather than depending on it, so this reads the
 * plugin's `Ui.kt` and fails the moment a light/dark pair drifts.
 */
class ThemeParityTest {
    private val uiKt = File("../src/main/kotlin/io/github/siddharthjaswal/logpose/ui/Ui.kt")

    @Test fun `every desktop token matches the plugin's JBColor pair`() {
        val src = uiKt.readText()
        val consts = Regex("""const val (\w+) = 0x([0-9A-Fa-f]{6})""").findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
        val pairs = Regex("""val (\w+) = c\((0x[0-9A-Fa-f]{6}|\w+), (0x[0-9A-Fa-f]{6}|\w+)\)""").findAll(src).associate { m ->
            fun v(s: String) = (if (s.startsWith("0x")) s.drop(2) else consts.getValue(s)).uppercase()
            m.groupValues[1] to (v(m.groupValues[2]) to v(m.groupValues[3]))
        }
        val desktop = DesktopTheme.pairs()
        assertTrue(desktop.size >= 30)
        for ((name, colors) in desktop) {
            val plugin = pairs[name] ?: error("Ui.kt has no token '$name'")
            fun hex(c: androidx.compose.ui.graphics.Color) = "%06X".format((c.value shr 32).toLong() and 0xFFFFFF)
            assertEquals(plugin, hex(colors.first) to hex(colors.second), name)
        }
        // And nothing in the plugin's palette was forgotten.
        assertEquals(pairs.keys - desktop.keys, emptySet<String>())
    }

    @Test fun `status plates are the plugin's tints pre-blended onto bg0`() {
        // 0xE3B34C at 17% (alpha 43) over 0x1E1F22, the plugin's integer math.
        fun mix(f: Int, b: Int) = (f * (43 / 255f) + b * (1 - 43 / 255f)).toInt()
        val expected = androidx.compose.ui.graphics.Color(mix(0xE3, 0x1E), mix(0xB3, 0x1F), mix(0x4C, 0x22))
        assertEquals(expected, DesktopTheme.Dark.warnPlate)
    }
}
