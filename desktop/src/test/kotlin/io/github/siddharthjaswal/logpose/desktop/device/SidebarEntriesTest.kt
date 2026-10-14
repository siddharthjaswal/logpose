package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.logcat.Adb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidebarEntriesTest {
    private val cable = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    private val tlsSerial = "adb-R5CT20ABCDE-QoXtBe._adb-tls-connect._tcp"
    private val tlsDevice = Adb.DeviceInfo(tlsSerial, "device", "SM_S918B")
    private val tlsMemory = RememberedDevice("adb-R5CT20ABCDE-QoXtBe", "Galaxy S23", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-R5CT20ABCDE-QoXtBe")
    private val legacyMemory = RememberedDevice("usb:XYZ", "Redmi Note 8", RememberedDevice.Kind.LEGACY, "192.168.1.31:5555")
    private val absentTls = RememberedDevice("adb-OLD-1", "Pixel 6", RememberedDevice.Kind.TLS, "192.168.1.50:40000", "adb-OLD-1")

    @Test fun `cable, wireless and remembered-absent rows`() {
        val e = SidebarEntries.build(listOf(cable, tlsDevice), listOf(tlsMemory, legacyMemory, absentTls), reconnecting = setOf("adb-OLD-1"))
        assertEquals(4, e.size)
        assertEquals(Transport.CABLE, e[0].transport)
        assertTrue(e[0].canSwitchToWireless)
        assertNull(e[0].remembered)

        assertEquals(Transport.WIFI, e[1].transport)
        assertEquals("SM S918B", e[1].name)
        assertEquals("192.168.1.20:41234", e[1].detail)
        assertEquals(tlsMemory, e[1].remembered)
        assertFalse(e[1].canSwitchToWireless)

        assertNull(e[2].serial) // absent legacy
        assertTrue(e[2].status.label.contains("plug in the cable"))
        assertEquals("Reconnecting…", e[3].status.label)
    }

    @Test fun `an offline wifi transport is one row with a wifi instruction, not two`() {
        val offline = Adb.DeviceInfo("192.168.1.31:5555", "offline")
        val e = SidebarEntries.build(listOf(offline), listOf(legacyMemory))
        assertEquals(1, e.size)
        assertEquals("Redmi Note 8", e[0].name)
        assertEquals("Wi-Fi connection lost — reconnecting…", e[0].status.label)
        assertFalse(e[0].status.label.contains("cable"))
    }

    @Test fun `emulators and unready cables are not offered switch to wireless`() {
        val e = SidebarEntries.build(listOf(Adb.DeviceInfo("emulator-5554", "device", "sdk_gphone64_arm64"), cable.copy(state = "unauthorized")), emptyList())
        assertEquals(Transport.EMULATOR, e[0].transport)
        assertFalse(e[0].canSwitchToWireless)
        assertFalse(e[1].canSwitchToWireless)
    }

    @Test fun `short serial from an mdns transport`() {
        assertEquals("R5CT20ABCDE", SidebarEntries.shortSerial(tlsSerial))
        assertEquals("192.168.1.31:5555", SidebarEntries.shortSerial("192.168.1.31:5555"))
    }

    @Test fun `a disconnected remembered phone says how to bring it back, unless it's being retried`() {
        val e = SidebarEntries.build(emptyList(), listOf(absentTls), paused = setOf("adb-OLD-1"))
        assertEquals("Disconnected — Reconnect to use it again", e.single().status.label)
        assertEquals("Reconnecting…", SidebarEntries.build(emptyList(), listOf(absentTls), setOf("adb-OLD-1"), setOf("adb-OLD-1")).single().status.label)
    }
}
