package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.desktop.app.AvdRow
import io.github.siddharthjaswal.logpose.desktop.app.LaunchUi
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdIni
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdInfo
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.emulators.RunningEmulators
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.logcat.Adb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DeviceSwitcherTest {
    private val cable = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    private val emu = Adb.DeviceInfo("emulator-5554", "device", "sdk_gphone64_arm64")
    private val tls = Adb.DeviceInfo("adb-R5CT20ABCDE-QoXtBe._adb-tls-connect._tcp", "device", "SM_S918B")
    private val tlsMemory = RememberedDevice("adb-R5CT20ABCDE-QoXtBe", "Galaxy S23", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-R5CT20ABCDE-QoXtBe")
    private val offlinePhone = RememberedDevice("adb-OLD-1", "Pixel 6", RememberedDevice.Kind.TLS, "192.168.1.50:40000", "adb-OLD-1")
    private val anotherOffline = RememberedDevice("usb:XYZ", "Moto G", RememberedDevice.Kind.LEGACY, "192.168.1.31:5555")

    private fun avd(name: String, display: String, play: Boolean = false, ps16k: Boolean = false): AvdInfo {
        val tag = if (ps16k) "google_apis_ps16k" else if (play) "google_apis_playstore" else "google_apis"
        val ini = AvdIni.parse("avd.ini.displayname=$display\nimage.sysdir.1=system-images/android-35/$tag/arm64-v8a/\ntag.id=$tag\nPlayStore.enabled=$play\nhw.ramSize=2048")
        return AvdInfo.from(name, File("/tmp/$name.avd"), ini, hasQuickBootSnapshot = false)
    }

    private val p9 = AvdRow(
        avd("Pixel_9a", "Pixel 9a"), AvdSlim.State(File("/tmp/b"), false, 1536, 2048),
        RunningEmulators.Running("Pixel_9a", emu.serial, 1, null),
    )
    private val zeta = AvdRow(avd("Zeta_Tab", "Zeta Tablet"))
    private val medium = AvdRow(avd("Medium_Phone_API_35", "Medium Phone API 35", play = true))
    private val p8 = AvdRow(avd("Pixel_8_16K", "Pixel 8 (16 KB)", ps16k = true))

    private fun build(
        devices: List<Adb.DeviceInfo> = listOf(emu, cable),
        launch: LaunchUi? = null,
        selected: String? = cable.serial,
        capturing: String? = null,
    ) = DeviceSwitcher.build(
        devices, listOf(offlinePhone, anotherOffline), listOf(p9, zeta, medium, p8), selected, capturing,
        launch = launch, guestBySerial = mapOf(emu.serial to "41 packages disabled"),
    )

    @Test fun `merges connected phones and emulators, stopped AVDs and offline remembered phones in order`() {
        val items = build()
        assertEquals(
            listOf("37211FDJH00ABC", "emulator-5554", "avd:Medium_Phone_API_35", "avd:Pixel_8_16K", "avd:Zeta_Tab", "remembered:usb:XYZ", "remembered:adb-OLD-1"),
            items.map { it.key },
        )
        // Phones before emulators even though adb listed the emulator first.
        val emulatorRow = items[1] as SwitcherItem.Connected
        assertEquals("Pixel 9a", emulatorRow.title) // named after its AVD, not adb's model
        assertEquals("41 packages disabled", emulatorRow.guest)
        assertTrue((items[0] as SwitcherItem.Connected).selected)
        // The running AVD is not offered again under Emulators.
        assertFalse(items.any { it.key == "avd:Pixel_9a" })
        assertEquals(listOf(SwitcherSection.CONNECTED, SwitcherSection.EMULATORS, SwitcherSection.REMEMBERED), DeviceSwitcher.sections(items).map { it.first })
    }

    @Test fun `a booting AVD sorts first and is not launchable again`() {
        val launch = LaunchUi("Zeta_Tab", "emulator-5556", "emulator …", LaunchStage.Booting, 1_000, 13_000)
        val items = build(launch = launch)
        val first = items.filterIsInstance<SwitcherItem.Avd>().first()
        assertEquals("avd:Zeta_Tab", first.key)
        assertTrue(first.booting)
        assertEquals("Booting Android…", first.subtitle)
        assertEquals(12, DeviceSwitcher.launchSeconds(launch))
        assertEquals(SwitcherAction.None, DeviceSwitcher.primary(first, sessionRunning = false))
        val failed = build(launch = launch.copy(stage = LaunchStage.Failed("The emulator exited during startup."))).filterIsInstance<SwitcherItem.Avd>().first { it.key == "avd:Zeta_Tab" }
        assertFalse(failed.booting)
        assertEquals("The emulator exited during startup.", failed.failed?.message)
    }

    @Test fun `a running AVD that adb doesn't see yet stays under Emulators`() {
        val items = build(devices = listOf(cable))
        assertTrue(items.any { it.key == "avd:Pixel_9a" })
    }

    @Test fun `search matches every term across name, serial, kind and badges`() {
        val items = build()
        assertEquals(listOf("37211FDJH00ABC", "emulator-5554", "avd:Pixel_8_16K", "remembered:adb-OLD-1"), DeviceSwitcher.filter(items, "pixel").map { it.key })
        assertEquals(listOf("avd:Medium_Phone_API_35"), DeviceSwitcher.filter(items, "PLAY").map { it.key })
        assertEquals(listOf("avd:Pixel_8_16K"), DeviceSwitcher.filter(items, "16 kb").map { it.key })
        assertEquals(listOf("emulator-5554"), DeviceSwitcher.filter(items, "android slimmed").map { it.key })
        assertEquals(listOf("37211FDJH00ABC"), DeviceSwitcher.filter(items, "usb").map { it.key })
        assertEquals(listOf("37211FDJH00ABC"), DeviceSwitcher.filter(items, "37211").map { it.key })
        assertEquals(items, DeviceSwitcher.filter(items, "   "))
        assertTrue(DeviceSwitcher.filter(items, "pixel tablet").isEmpty())
    }

    @Test fun `keyboard moves through display order and wraps`() {
        val items = build()
        assertEquals(cable.serial, DeviceSwitcher.initialHighlight(items))
        assertEquals(emu.serial, DeviceSwitcher.move(items, cable.serial, 1))
        assertEquals("remembered:adb-OLD-1", DeviceSwitcher.move(items, cable.serial, -1))
        assertEquals(cable.serial, DeviceSwitcher.move(items, "remembered:adb-OLD-1", 1))
        // A highlight the search hid restarts at the top (↓) or bottom (↑).
        val pixels = DeviceSwitcher.filter(items, "pixel")
        assertEquals(cable.serial, DeviceSwitcher.move(pixels, "avd:Zeta_Tab", 1))
        assertEquals("remembered:adb-OLD-1", DeviceSwitcher.move(pixels, "avd:Zeta_Tab", -1))
        assertEquals(cable.serial, DeviceSwitcher.keep(pixels, "avd:Zeta_Tab"))
        assertEquals("avd:Pixel_8_16K", DeviceSwitcher.keep(pixels, "avd:Pixel_8_16K"))
        assertNull(DeviceSwitcher.move(emptyList(), null, 1))
    }

    @Test fun `enter selects, launches lean or reconnects`() {
        val items = build()
        assertEquals(SwitcherAction.Select(cable.serial), DeviceSwitcher.primary(items[0], sessionRunning = false))
        assertEquals(SwitcherAction.None, DeviceSwitcher.primary(items[0], sessionRunning = true))
        assertEquals(SwitcherAction.LaunchLean("Medium_Phone_API_35"), DeviceSwitcher.primary(items.first { it.key == "avd:Medium_Phone_API_35" }, false))
        assertEquals(SwitcherAction.None, DeviceSwitcher.primary(items.first { it.key == "avd:Medium_Phone_API_35" }, false, emulatorAvailable = false))
        assertEquals(SwitcherAction.Reconnect("adb-OLD-1"), DeviceSwitcher.primary(items.last(), false))
        val unauthorized = build(devices = listOf(cable.copy(state = "unauthorized")))
        val row = unauthorized.first() as SwitcherItem.Connected
        assertEquals(SwitcherAction.None, DeviceSwitcher.primary(row, false))
        assertEquals("Tap Allow on the phone's USB-debugging prompt", row.subtitle)
    }

    @Test fun `the chip follows capture, then selection, then the first device`() {
        assertEquals(emu.serial, DeviceSwitcher.chipItem(build(capturing = emu.serial))?.serial)
        assertEquals(cable.serial, DeviceSwitcher.chipItem(build())?.serial)
        assertEquals(cable.serial, DeviceSwitcher.chipItem(build(selected = null))?.serial) // phones list first
        assertNull(DeviceSwitcher.chipItem(build(devices = emptyList())))
        assertEquals("Capturing", DeviceSwitcher.chipItem(build(capturing = emu.serial))?.subtitle)
    }

    @Test fun `a connected remembered wifi phone is one connected row, a paused one says so`() {
        val items = DeviceSwitcher.build(listOf(tls), listOf(tlsMemory, offlinePhone), emptyList(), null, paused = setOf("adb-OLD-1"))
        assertEquals(2, items.size)
        val connected = items[0] as SwitcherItem.Connected
        assertEquals(Transport.WIFI, connected.entry.transport)
        assertEquals(tlsMemory, connected.entry.remembered)
        assertEquals("Disconnected — Reconnect to use it again", items[1].subtitle)
    }
}
