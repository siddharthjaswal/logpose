package io.github.siddharthjaswal.logpose.desktop.emulators

import io.github.siddharthjaswal.logpose.desktop.app.EmulatorController
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorPanel
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi
import io.github.siddharthjaswal.logpose.desktop.wireless.FakeAdb
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The panel end to end over fakes: list, refuse-while-running, a lean launch with guest tweaks, the chip. */
class EmulatorControllerTest {
    @TempDir lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun done() = scope.cancel()

    private val help = EmulatorControllerTest::class.java.getResource("/emulators/emulator-help-37.2.12.txt")!!.readText()
    private val footprint = EmulatorControllerTest::class.java.getResource("/emulators/footprint-qemu.txt")!!.readText()

    /** One AVD; "booting" flips on when spawn is called. */
    private inner class World {
        @Volatile var started = false
        val spawned = mutableListOf<List<String>>()
        val home = File(root, "avd").apply { mkdirs() }
        val sdk = File(root, "sdk").apply { File(this, "emulator").mkdirs(); File(this, "emulator/emulator").apply { writeText("#!/bin/sh"); setExecutable(true) } }
        val disabled = mutableSetOf<String>()
        val adb = FakeAdb { args ->
            when {
                args == listOf("devices") -> "List of devices attached\nadb-XYZ._adb-tls-connect._tcp\tdevice\n" + if (started) "emulator-5554\tdevice\n" else ""
                args.drop(2) == listOf("emu", "avd", "name") -> "Pixel_9a\r\nOK\r\n"
                args.drop(2) == listOf("shell", "getprop", "sys.boot_completed") -> "1"
                args.drop(2) == listOf("shell", "getprop", "ro.config.low_ram") -> ""
                args.drop(2) == listOf("shell", "pm", "list", "packages") -> "package:com.google.android.youtube\npackage:com.google.android.gms\n"
                args.drop(2) == listOf("shell", "pm", "list", "packages", "-e") -> listOf("com.google.android.youtube", "com.google.android.gms").filter { it !in disabled }.joinToString("\n") { "package:$it" }
                args.getOrNull(3) == "pm" && args.getOrNull(4) == "disable-user" -> { disabled += args.last(); "Package ${args.last()} new state: disabled-user" }
                args.getOrNull(3) == "settings" && args.getOrNull(4) == "get" -> "null"
                else -> ""
            }
        }
        val system = object : EmulatorSystem {
            override fun help(emulator: File) = help
            override fun ps() = if (started) "4242 2528624 ${sdk.path}/emulator/qemu/darwin-aarch64/qemu-system-aarch64 -avd Pixel_9a -port 5554 -memory 1536\n" else ""
            override fun footprint(pid: Int) = footprint
            override fun spawn(command: List<String>, log: File): (() -> Boolean)? {
                spawned += command
                log.parentFile.mkdirs(); log.writeText("INFO | Increasing RAM size to 2560MB\n")
                started = true
                return { true }
            }
        }
        val store = KeyValueStore.InMemory()
        val controller = EmulatorController(
            scope, store, adb, system, locateSdk = { EmulatorSdk(sdk) }, avdHome = { home }, logDir = File(root, "logs"),
        )

        init {
            val dir = File(home, "Pixel_9a.avd").apply { mkdirs() }
            File(dir, "config.ini").writeText("avd.ini.displayname=Pixel 9a\nhw.ramSize=2048\nhw.gpu.mode=host\nhw.gpu.enabled=yes\ntag.id=google_apis\n")
            File(home, "Pixel_9a.ini").writeText("path=${dir.path}\n")
        }
    }

    private fun await(c: EmulatorController, what: String, ok: (EmulatorsUi) -> Boolean): EmulatorsUi {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) { c.ui.value.let { if (ok(it)) return it }; Thread.sleep(20) }
        error("timed out waiting for $what: ${c.ui.value}")
    }

    @Test fun `lists the AVD with its config, stopped`() {
        val w = World()
        w.controller.open()
        val ui = await(w.controller, "rows") { it.rows.isNotEmpty() }
        val row = ui.rows.single()
        assertEquals("Pixel_9a", row.avd.name)
        assertFalse(row.isRunning)
        assertTrue("-lowram" in ui.supported)
        w.controller.close()
    }

    @Test fun `a lean launch with guest tweaks reaches Ready, shows host RAM, and marks the serial`() {
        val w = World()
        w.controller.open()
        await(w.controller, "rows") { it.rows.isNotEmpty() }
        w.controller.openLaunch("Pixel_9a")
        val options = w.controller.ui.value.panel as EmulatorPanel.LaunchOptions
        assertEquals(1536, options.ramMb)
        assertTrue(options.lowRam)
        assertFalse(options.guest, "guest slimming is off by default")
        w.controller.editLaunch(options.copy(guest = true))
        w.controller.launch()
        val ready = await(w.controller, "ready") { it.launch?.stage is LaunchStage.Ready }
        assertEquals(
            listOf(w.sdk.path + "/emulator/emulator", "-avd", "Pixel_9a", "-port", "5554", "-memory", "1536", "-gpu", "host", "-no-snapshot-save", "-no-boot-anim", "-lowram"),
            w.spawned.single(),
        )
        assertEquals(2560, ready.launch!!.ramFloorMb)
        assertEquals(false, ready.launch!!.lowRamDevice)
        assertEquals(setOf("com.google.android.youtube"), w.disabled)
        val running = await(w.controller, "running row") { it.rows.single().running?.memory != null && it.guestBySerial.isNotEmpty() }
        assertEquals(3467, running.rows.single().running!!.memory!!.mb)
        assertEquals(HostMemory.Source.FOOTPRINT, running.rows.single().running!!.memory!!.source)
        assertTrue(running.guestBySerial["emulator-5554"]!!.startsWith("Android slimmed by LogPose"))
        assertEquals("2560", w.store.get(EmulatorController.FLOOR_PREFIX + "Pixel_9a"))
        assertTrue(w.controller.guestSummaryFor("emulator-5554")!!.contains("1 background packages"))
        assertEquals(null, w.controller.guestSummaryFor("adb-XYZ._adb-tls-connect._tcp"))
        // Never the phone: every serial-scoped call addressed the emulator.
        assertTrue(w.adb.calls.filter { it.firstOrNull() == "-s" }.all { it[1] == "emulator-5554" })
        w.controller.close()
    }

    @Test fun `the switcher's Launch lean runs the sheet's defaults with no sheet, then clears its progress`() {
        val w = World()
        w.controller.refresh()
        await(w.controller, "rows") { it.rows.isNotEmpty() && it.supported.isNotEmpty() }
        w.controller.launchLean("Pixel_9a")
        await(w.controller, "running") { it.rows.single().isRunning && it.launch == null }
        assertEquals(
            listOf(w.sdk.path + "/emulator/emulator", "-avd", "Pixel_9a", "-port", "5554", "-memory", "1536", "-gpu", "host", "-no-snapshot-save", "-no-boot-anim", "-lowram"),
            w.spawned.single(),
        )
        assertFalse(w.controller.ui.value.open, "no sheet opened")
        assertEquals(EmulatorPanel.Overview, w.controller.ui.value.panel)
        assertTrue(w.disabled.isEmpty(), "guest slimming stays opt-in")
    }

    @Test fun `Launch boots as Android Studio would, with only -avd and -port`() {
        val w = World()
        w.controller.refresh()
        await(w.controller, "rows") { it.rows.isNotEmpty() && it.supported.isNotEmpty() }
        w.controller.launchStock("Pixel_9a")
        await(w.controller, "spawned") { w.spawned.isNotEmpty() }
        assertEquals(listOf(w.sdk.path + "/emulator/emulator", "-avd", "Pixel_9a", "-port", "5554"), w.spawned.single())
    }

    @Test fun `Options and Details open the sheet on the right panel`() {
        val w = World()
        w.controller.refresh()
        await(w.controller, "rows") { it.rows.isNotEmpty() }
        w.controller.openLaunchOptions("Pixel_9a")
        val ui = w.controller.ui.value
        assertTrue(ui.open)
        assertEquals("Pixel_9a", (ui.panel as EmulatorPanel.LaunchOptions).avd)
        w.controller.close()
    }

    @Test fun `slim is refused while the AVD runs, and the file is untouched`() {
        val w = World()
        w.started = true
        val before = File(w.home, "Pixel_9a.avd/config.ini").readBytes()
        w.controller.open()
        await(w.controller, "running") { it.rows.singleOrNull()?.isRunning == true }
        w.controller.openSlim("Pixel_9a")
        val ui = await(w.controller, "message") { it.message != null }
        assertTrue(ui.messageIsError)
        assertTrue(ui.message!!.contains("Shut the emulator down"))
        assertEquals(EmulatorPanel.Overview, ui.panel)
        assertTrue(before.contentEquals(File(w.home, "Pixel_9a.avd/config.ini").readBytes()))
        assertTrue(AvdSlim.backups(File(w.home, "Pixel_9a.avd")).isEmpty())
        w.controller.close()
    }

    @Test fun `noteDevices marks a guest-slimmed emulator without the sheet open`() {
        val w = World()
        GuestTweaks.save(w.store, GuestTweaks.Record("Pixel_9a", 1, listOf("a"), emptyMap()), "Pixel_9a")
        w.controller.noteDevices(listOf("adb-XYZ._adb-tls-connect._tcp", "emulator-5554"))
        val ui = await(w.controller, "chip") { it.guestBySerial.isNotEmpty() }
        assertEquals(setOf("emulator-5554"), ui.guestBySerial.keys)
        w.controller.noteDevices(emptyList())
        await(w.controller, "chip cleared") { it.guestBySerial.isEmpty() }
    }
}
