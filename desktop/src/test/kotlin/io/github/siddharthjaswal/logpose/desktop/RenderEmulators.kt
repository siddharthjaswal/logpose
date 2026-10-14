package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.AvdRow
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorPanel
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi
import io.github.siddharthjaswal.logpose.desktop.app.LaunchUi
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdIni
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdInfo
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaks
import io.github.siddharthjaswal.logpose.desktop.emulators.HostMemory
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchPlan
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.emulators.RunningEmulators
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.File

/**
 * The Emulators sheet: the list (stock / slimmed / running with RAM / 16 KB / Play image), the slim
 * confirm, the launch options with the guest opt-in, launch progress, and the empty states — plus the
 * sidebar chip and session header of a capture from a guest-slimmed emulator. Values mirror the dev
 * Mac's Pixel_9a and the RAM measured there.
 *
 *   ./gradlew :desktop:renderScreens -PshotsOnly=emulators -PshotsDir=…
 */
fun renderEmulators(out: File) {
    val ready = AdbStatus.Ready("/Users/qa/Library/Android/sdk/platform-tools/adb", downloaded = false)
    val phone = Adb.DeviceInfo("adb-00173359D000017-zABNgD._adb-tls-connect._tcp", "device", "A059P")
    fun idle(e: EmulatorsUi, devices: List<Adb.DeviceInfo> = listOf(phone)) = ScreenState(
        ready, devices, null, SessionState.Idle, TimelineSnapshot.EMPTY, null,
        Placeholder.resolve(true, devices, null, SessionState.Idle, null, 0),
    ).copy(emulators = e)

    val dir = File("/Users/qa/.android/avd/Pixel_9a.avd")
    fun ini(vararg kv: Pair<String, String>) = AvdIni.parse(kv.joinToString("\n") { "${it.first}=${it.second}" })
    val pixelKeys = arrayOf(
        "avd.ini.displayname" to "Pixel 9a", "image.sysdir.1" to "system-images/android-36.1/google_apis/arm64-v8a/",
        "tag.id" to "google_apis", "tag.display" to "Google APIs", "abi.type" to "arm64-v8a", "PlayStore.enabled" to "false",
        "hw.gpu.enabled" to "yes", "hw.gpu.mode" to "host", "vm.heapSize" to "512",
    )
    val stock = AvdInfo.from("Pixel_9a", dir, ini(*pixelKeys, "hw.ramSize" to "2048"), hasQuickBootSnapshot = false)
    val slimmed = AvdInfo.from("Pixel_9a", dir, ini(*pixelKeys, "hw.ramSize" to "1536"), hasQuickBootSnapshot = false)
    val slimState = AvdSlim.State(File(dir, "config.ini.logpose-backup-20261014-121500"), editedSince = false, slimmedToMb = 1536, originalRamMb = 2048)
    val ps16k = AvdInfo.from(
        "Pixel_8_16K", File("/Users/qa/.android/avd/Pixel_8_16K.avd"),
        ini("avd.ini.displayname" to "Pixel 8 (16 KB)", "image.sysdir.1" to "system-images/android-35/google_apis_ps16k/arm64-v8a/", "tag.id" to "google_apis_ps16k",
            "tag.display" to "Google APIs PS16K", "abi.type" to "arm64-v8a", "hw.ramSize" to "4096", "hw.gpu.mode" to "auto"),
        hasQuickBootSnapshot = true,
    )
    val play = AvdInfo.from(
        "Medium_Phone_API_35", File("/Users/qa/.android/avd/Medium_Phone_API_35.avd"),
        ini("avd.ini.displayname" to "Medium Phone API 35", "image.sysdir.1" to "system-images/android-35/google_apis_playstore/arm64-v8a/", "tag.id" to "google_apis_playstore",
            "tag.display" to "Google Play", "abi.type" to "arm64-v8a", "PlayStore.enabled" to "true", "hw.ramSize" to "2048", "hw.gpu.mode" to "auto", "vm.heapSize" to "256"),
        hasQuickBootSnapshot = true,
    )
    val supported = LaunchPlanSupported.flags
    val base = EmulatorsUi(open = true, emulatorPath = "/Users/qa/Library/Android/sdk/emulator/emulator", avdHome = "/Users/qa/.android/avd", supported = supported)
    val guest = GuestTweaks.Record("Pixel_9a", 1_792_000_000_000, GuestTweaks.ALL_PACKAGES.take(41), mapOf("global/activity_manager_constants" to null))
    val runningRow = AvdRow(
        slimmed, slimState, RunningEmulators.Running("Pixel_9a", "emulator-5554", 34042, HostMemory(3474, HostMemory.Source.FOOTPRINT)),
        guest = guest, ramFloorMb = 2560,
    )

    // The list.
    shot(out, "emulators-list-stock-dark", true) { LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(stock)))), ScreenActions()) }
    shot(out, "emulators-list-slimmed-light", false) {
        LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(slimmed, slimState, ramFloorMb = 2560)), message = "Slimmed Pixel_9a. The original is kept as config.ini.logpose-backup-20261014-121500.")), ScreenActions())
    }
    val emu = Adb.DeviceInfo("emulator-5554", "device", "sdk_gphone64_arm64")
    shot(out, "emulators-list-running-ram-dark", true, height = 860) {
        LogPoseScreen(idle(base.copy(rows = listOf(runningRow), guestBySerial = mapOf("emulator-5554" to guest.summary())), listOf(phone, emu)), ScreenActions())
    }
    shot(out, "emulators-list-ps16k-light", false) { LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(ps16k)))), ScreenActions()) }
    shot(out, "emulators-list-play-image-dark", true) { LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(play)))), ScreenActions()) }

    // Slim confirm.
    val changes = listOf(AvdSlim.Change("hw.ramSize", "2048", "1536"))
    shot(out, "emulators-slim-confirm-dark", true, height = 860) {
        LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(stock, ramFloorMb = 2560)), panel = EmulatorPanel.SlimConfirm("Pixel_9a", 1536, changes, loading = false))), ScreenActions())
    }
    shot(out, "emulators-slim-confirm-play-light", false, height = 860) {
        LogPoseScreen(
            idle(base.copy(rows = listOf(AvdRow(play)), panel = EmulatorPanel.SlimConfirm("Medium_Phone_API_35", 1536,
                listOf(AvdSlim.Change("hw.ramSize", "2048", "1536"), AvdSlim.Change("hw.gpu.enabled", null, "yes"), AvdSlim.Change("hw.gpu.mode", "auto", "host")), loading = false))),
            ScreenActions(),
        )
    }

    // Launch options with the guest opt-in (off, then on with the list open).
    val opts = EmulatorPanel.LaunchOptions("Pixel_9a", 1536, coldBoot = false, lowRam = true)
    shot(out, "emulators-launch-options-light", false, height = 860) {
        LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(slimmed, slimState)), panel = opts)), ScreenActions())
    }
    shot(out, "emulators-guest-opt-in-dark", true, height = 1000) {
        LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(slimmed, slimState)), panel = opts.copy(guest = true, showPackages = true))), ScreenActions())
    }

    // Launch progress: booting, then ready.
    val plan = LaunchPlan.build(LaunchPlan.Options("Pixel_9a", 5554, 1536, coldBoot = false, lowRam = true), supported)
    val t0 = 1_792_000_000_000L
    val booting = LaunchUi(
        "Pixel_9a", "emulator-5554", plan.commandLine("/Users/qa/Library/Android/sdk/emulator/emulator"), LaunchStage.Booting, t0, t0 + 9_400,
        mapOf("Starting" to t0, "WaitingForAdb" to t0 + 1_500, "Booting" to t0 + 3_100), guest = true,
    )
    shot(out, "emulators-launch-progress-light", false) {
        LogPoseScreen(idle(base.copy(rows = listOf(AvdRow(slimmed, slimState)), panel = EmulatorPanel.Launching("Pixel_9a"), launch = booting)), ScreenActions())
    }
    val readyLaunch = booting.copy(
        stage = LaunchStage.Ready, nowMillis = t0 + 17_000,
        stageAt = booting.stageAt + ("Tweaking" to t0 + 14_200) + ("Ready" to t0 + 17_000),
        lowRamDevice = false, tweakMessage = "Slimmed Android on Pixel_9a: 41 background packages disabled, cached processes capped at 4.",
    )
    shot(out, "emulators-launch-ready-dark", true, height = 860) {
        LogPoseScreen(idle(base.copy(rows = listOf(runningRow), panel = EmulatorPanel.Launching("Pixel_9a"), launch = readyLaunch)), ScreenActions())
    }
    shot(out, "emulators-launch-failed-light", false, height = 860) {
        LogPoseScreen(
            idle(base.copy(rows = listOf(AvdRow(stock)), panel = EmulatorPanel.Launching("Pixel_9a"),
                launch = booting.copy(stage = LaunchStage.Failed("The emulator exited during startup.", "ERROR | Running multiple emulators with the same AVD\nERROR | is an experimental feature.\nERROR | Please use -read-only flag to enable this feature."), guest = false))),
            ScreenActions(),
        )
    }

    // Empty states.
    shot(out, "emulators-empty-no-avds-light", false) { LogPoseScreen(idle(base.copy(rows = emptyList())), ScreenActions()) }
    shot(out, "emulators-empty-no-sdk-dark", true) { LogPoseScreen(idle(base.copy(rows = emptyList(), sdkMissing = true, emulatorPath = null)), ScreenActions()) }

    // A session on a guest-slimmed emulator: the sidebar chip and the header say so.
    val events = Fixtures.store().snapshot()
    shot(out, "emulators-session-header-guest-dark", true) {
        LogPoseScreen(
            ScreenState(
                ready, listOf(phone, emu), emu.serial, SessionState.Running(emu.serial, t0), TimelineSnapshot(1, events), null,
                Placeholder.None,
            ).copy(emulators = EmulatorsUi(guestBySerial = mapOf("emulator-5554" to guest.summary()))),
            ScreenActions(),
        )
    }
}

/** The flags emulator 37.2.12 lists in `-help` (the captured fixture), for the renders' command lines. */
private object LaunchPlanSupported {
    val flags: Set<String> = LaunchPlan.supportedFlags(LaunchPlanSupported::class.java.getResource("/emulators/emulator-help-37.2.12.txt")!!.readText())
}
