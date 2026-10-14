package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.app.EmulatorController
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorPanel
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdScanner
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlimmer
import io.github.siddharthjaswal.logpose.desktop.emulators.EmulatorSdk
import io.github.siddharthjaswal.logpose.desktop.emulators.EmulatorSystem
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaker
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaks
import io.github.siddharthjaswal.logpose.desktop.emulators.HostMemory
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.emulators.RunningEmulators
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevices
import io.github.siddharthjaswal.logpose.logcat.Adb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.system.exitProcess

/**
 * The Emulators panel against this Mac's real SDK and AVD, in two JVMs so "detached" is proven:
 *
 *   ./gradlew :desktop:emulatorsLive -PemuPhase=launch  -PemuAvd=Pixel_9a -PemuState=/some/dir
 *       lists AVDs, refuses if the AVD runs, slims it (1536 MB), launches lean through the
 *       controller, waits for Ready — then this JVM exits, leaving the emulator up.
 *   ./gradlew :desktop:emulatorsLive -PemuPhase=finish  -PemuAvd=Pixel_9a -PemuState=/some/dir
 *       idles 60 s, measures the qemu process (footprint), applies the guest tweaks, checks them,
 *       undoes them and checks the undo, shuts the emulator down, and reverts config.ini.
 *
 * Only ever addresses the emulator's own serial. Settings go to [emuState], not the app's.
 */
fun main(args: Array<String>) {
    val phase = args.getOrNull(0) ?: "launch"
    val avdName = args.getOrNull(1) ?: "Pixel_9a"
    val state = File(args.getOrNull(2) ?: "build/emulators-live").apply { mkdirs() }
    val settings = RememberedDevices.forApp(state)
    val adb = AdbCommands.Real
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = EmulatorController(scope, settings, logDir = File(state, "logs"))
    fun waitUi(what: String, seconds: Int, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!ok()) { check(System.currentTimeMillis() < until) { "timed out: $what — ${controller.ui.value}" }; Thread.sleep(250) }
    }
    val avds = AvdScanner.scan(AvdScanner.avdHome())
    println("AVDs in ${AvdScanner.avdHome()}:")
    avds.forEach { println("  ${it.name}: ${it.imageLine} | ${it.configLine} | slim=${AvdSlim.state(it.dir).slimmed} | snapshot=${it.hasQuickBootSnapshot}") }
    val avd = avds.firstOrNull { it.name == avdName } ?: error("no AVD $avdName")
    println("emulator: ${EmulatorSdk.locate(Adb.resolve())?.emulator}")

    controller.open()
    waitUi("rows", 30) { controller.ui.value.rows.isNotEmpty() }
    when (phase) {
        "launch" -> {
            val row = controller.ui.value.row(avdName)!!
            check(!row.isRunning) { "$avdName is already running — not touching it" }
            println("supported lean flags: ${listOf("-memory", "-gpu", "-no-snapshot-save", "-no-snapshot-load", "-no-boot-anim", "-lowram").filter { it in controller.ui.value.supported }}")
            // Refused while running is unit-tested; here: slim for real.
            val r = AvdSlimmer().slim(avd, 1536, running = false)
            println("slim: $r")
            controller.refresh()
            waitUi("slimmed row", 30) { controller.ui.value.row(avdName)?.slim?.slimmed == true }
            controller.openLaunch(avdName)
            val o = controller.ui.value.panel as EmulatorPanel.LaunchOptions
            println("launch options: $o")
            controller.editLaunch(o.copy(coldBoot = true, guest = false))
            controller.launch()
            waitUi("launch", 300) { controller.ui.value.launch?.stage.let { it is LaunchStage.Ready || it is LaunchStage.Failed } }
            val l = controller.ui.value.launch!!
            println("command: ${l.command}")
            println("stage: ${l.stage}  stages=${l.stageAt.mapValues { it.value - l.startedAtMillis }}  lowRamDevice=${l.lowRamDevice} floor=${l.ramFloorMb}")
            check(l.stage is LaunchStage.Ready)
            println("serial=${l.serial}")
            File(state, "serial.txt").writeText(l.serial)
            exitProcess(0) // the emulator must outlive this JVM
        }
        "finish" -> {
            val serial = File(state, "serial.txt").readText().trim()
            val row = controller.ui.value.row(avdName)!!
            println("after the launching JVM exited: running=${row.isRunning} serial=${row.running?.serial} pid=${row.running?.pid}")
            check(row.running?.serial == serial) { "the emulator didn't outlive the launching JVM" }
            println("idling 60 s…")
            Thread.sleep(60_000)
            fun measure(): HostMemory? {
                val pid = RunningEmulators.parsePs(EmulatorSystem.Real.ps().orEmpty()).first { it.avd == avdName }.pid
                return HostMemory.parseFootprint(EmulatorSystem.Real.footprint(pid))
            }
            println("LEAN host memory: ${measure()}")
            println("guest MemTotal: ${adb.run(listOf("-s", serial, "shell", "grep", "MemTotal", "/proc/meminfo"))?.trim()}")
            // Guest tweaks: apply, check, undo, check.
            val enabledBefore = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-e"), 20))
            val disabledBefore = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-d"), 20))
            val settingBefore = GuestTweaks.SETTINGS.associate { it.key to GuestTweaks.parseSetting(adb.run(listOf("-s", serial, "shell", "settings", "get", it.namespace, it.name))) }
            val t = GuestTweaker(adb, settings)
            val applied = t.apply(serial, avdName)
            println("guest apply: $applied")
            val rec = GuestTweaks.load(settings, avdName)!!
            val disabledNow = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-d"), 20))
            check(rec.packages.all { it in disabledNow }) { "not all recorded packages are disabled" }
            check(GuestTweaks.NEVER.none { it in disabledNow - disabledBefore }) { "a protected package was disabled" }
            println("disabled ${rec.packages.size}: ${rec.packages}")
            println("summary for capture: ${controller.guestSummaryFor(serial)}")
            Thread.sleep(30_000)
            println("LEAN + guest tweaks host memory (30 s later): ${measure()}")
            val undo = t.undo(serial, avdName)
            println("guest undo: $undo")
            val enabledAfter = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-e"), 20))
            val disabledAfter = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-d"), 20))
            val settingAfter = GuestTweaks.SETTINGS.associate { it.key to GuestTweaks.parseSetting(adb.run(listOf("-s", serial, "shell", "settings", "get", it.namespace, it.name))) }
            check(enabledAfter == enabledBefore) { "enabled set differs after undo: +${enabledAfter - enabledBefore} -${enabledBefore - enabledAfter}" }
            check(disabledAfter == disabledBefore) { "disabled set differs after undo" }
            check(settingAfter == settingBefore) { "settings differ after undo: $settingBefore vs $settingAfter" }
            check(GuestTweaks.load(settings, avdName) == null)
            println("undo verified: enabled ${enabledAfter.size} packages identical, settings identical $settingAfter")
            controller.shutDown(avdName)
            waitUi("shutdown", 60) { controller.ui.value.busy.isEmpty() && controller.ui.value.row(avdName)?.isRunning == false }
            println("shut down; qemu left: ${RunningEmulators.parsePs(EmulatorSystem.Real.ps().orEmpty())}")
            val rev = AvdSlimmer().revert(avd, running = false)
            println("revert: $rev")
            exitProcess(0)
        }
    }
}
