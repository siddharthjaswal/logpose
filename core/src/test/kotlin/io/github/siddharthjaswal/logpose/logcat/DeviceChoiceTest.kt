package io.github.siddharthjaswal.logpose.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The capture-start device decision. The rule under test: with 0 or 1 devices everything behaves
 * exactly as before the picker existed (auto, silently), and only a real ambiguity — two devices,
 * or a saved device that's gone — produces a serial and a sentence.
 */
class DeviceChoiceTest {

    private fun device(serial: String, model: String? = null) = Adb.DeviceInfo(serial, "device", model)

    @Test
    fun `no selection and one device stays auto and silent`() {
        val choice = DeviceChoice.choose(null, listOf(device("emulator-5554")))
        assertNull(choice.serial)
        assertNull(choice.notice)
    }

    @Test
    fun `no selection and no devices stays auto and silent`() {
        val choice = DeviceChoice.choose(null, emptyList())
        assertNull(choice.serial)
        assertNull(choice.notice)
    }

    @Test
    fun `no selection and two devices picks the first and says which`() {
        val choice = DeviceChoice.choose(
            null,
            listOf(device("emulator-5554", "Pixel_9a"), device("R58N123ABC", "SM_G991B")),
        )
        assertEquals("emulator-5554", choice.serial)
        assertNotNull(choice.notice)
        assertEquals(true, choice.notice!!.contains("Pixel_9a (emulator-5554)"))
    }

    @Test
    fun `an attached selection is honoured silently`() {
        val choice = DeviceChoice.choose(
            "R58N123ABC",
            listOf(device("emulator-5554"), device("R58N123ABC")),
        )
        assertEquals("R58N123ABC", choice.serial)
        assertNull(choice.notice)
    }

    @Test
    fun `a missing selection falls back to the first of several, with a notice`() {
        val choice = DeviceChoice.choose(
            "gone-device",
            listOf(device("emulator-5554", "Pixel_9a"), device("emulator-5556")),
        )
        assertEquals("emulator-5554", choice.serial)
        assertNotNull(choice.notice)
    }

    @Test
    fun `a missing selection with one device attached goes back to auto`() {
        val choice = DeviceChoice.choose("gone-device", listOf(device("emulator-5554")))
        assertNull(choice.serial)
        assertNull(choice.notice)
    }

    @Test
    fun `a selection is kept when adb reported nothing at all`() {
        // Empty also covers "adb failed to list" — attaching with -s surfaces the real error.
        val choice = DeviceChoice.choose("R58N123ABC", emptyList())
        assertEquals("R58N123ABC", choice.serial)
    }
}
