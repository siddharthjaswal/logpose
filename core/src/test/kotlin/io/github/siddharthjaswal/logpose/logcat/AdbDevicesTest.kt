package io.github.siddharthjaswal.logpose.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing `adb devices -l` output — the pure half of the device picker. The shapes pinned here
 * are the ones adb actually emits: the header line, emulator and USB rows with `key:value`
 * properties, states other than `device`, and the daemon-start chatter that precedes the header
 * when adb wasn't running.
 */
class AdbDevicesTest {

    @Test
    fun `parses emulator and usb rows with model labels`() {
        val out = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64_arm64 model:Pixel_9a device:emu64a transport_id:1
            R58N123ABC             device usb:34603008X product:o1sxeea model:SM_G991B device:o1s transport_id:2
        """.trimIndent()
        val devices = Adb.parseDevices(out)
        assertEquals(2, devices.size)
        assertEquals(Adb.DeviceInfo("emulator-5554", "device", "Pixel_9a"), devices[0])
        assertEquals("Pixel_9a (emulator-5554)", devices[0].label)
        assertEquals("SM_G991B (R58N123ABC)", devices[1].label)
        assertTrue(devices.all { it.ready })
    }

    @Test
    fun `keeps non-ready devices but marks them not ready`() {
        val out = """
            List of devices attached
            emulator-5554          device model:Pixel_9a
            192.168.1.7:5555       offline
            R58N123ABC             unauthorized usb:34603008X
        """.trimIndent()
        val devices = Adb.parseDevices(out)
        assertEquals(3, devices.size)
        assertFalse(devices[1].ready)
        assertEquals("offline", devices[1].state)
        assertFalse(devices[2].ready)
        assertEquals("unauthorized", devices[2].state)
    }

    @Test
    fun `a device without a model property labels itself by serial`() {
        val devices = Adb.parseDevices("List of devices attached\nemulator-5554\tdevice\n")
        assertEquals(1, devices.size)
        assertNull(devices[0].model)
        assertEquals("emulator-5554", devices[0].label)
    }

    @Test
    fun `ignores the header, blank lines and daemon chatter`() {
        val out = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554          device model:Pixel_9a

        """.trimIndent()
        assertEquals(listOf("emulator-5554"), Adb.parseDevices(out).map { it.serial })
    }

    @Test
    fun `no devices parses to an empty list`() {
        assertTrue(Adb.parseDevices("List of devices attached\n").isEmpty())
        assertTrue(Adb.parseDevices("").isEmpty())
    }
}
