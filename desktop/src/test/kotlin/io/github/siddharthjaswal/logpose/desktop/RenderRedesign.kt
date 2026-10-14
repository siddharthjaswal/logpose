package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedUi
import io.github.siddharthjaswal.logpose.desktop.app.AvdRow
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi
import io.github.siddharthjaswal.logpose.desktop.app.LaunchUi
import io.github.siddharthjaswal.logpose.desktop.app.PaneLayout
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.Popover
import io.github.siddharthjaswal.logpose.desktop.app.ScenarioInfo
import io.github.siddharthjaswal.logpose.desktop.app.UpdateUi
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.correlation.Vocabulary
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdIni
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdInfo
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaks
import io.github.siddharthjaswal.logpose.desktop.emulators.HostMemory
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.emulators.RunningEmulators
import io.github.siddharthjaswal.logpose.desktop.mcp.McpUi
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.desktop.update.UpdateCheck
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.MockRule
import java.io.File

/**
 * The redesigned window: device switcher in the toolbar, status bar, splitters, collapsible panes, the
 * no-device welcome, the Developer and MCP popovers, and a narrow window.
 *
 *   ./gradlew :desktop:renderScreens -PshotsOnly=redesign -PshotsDir=…
 */
fun renderRedesign(out: File) {
    val adb = AdbStatus.Ready("/Users/qa/Library/Android/sdk/platform-tools/adb", downloaded = false)
    val pixel = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val emu = Adb.DeviceInfo("emulator-5554", "device", "sdk_gphone64_arm64")
    val events = Fixtures.store().snapshot()
    val t0 = System.currentTimeMillis()

    // AVDs: the running, slimmed Pixel 9a (guest-tweaked too), a 16 KB Pixel 8 and a Play-image phone, stopped.
    fun ini(vararg kv: Pair<String, String>) = AvdIni.parse(kv.joinToString("\n") { "${it.first}=${it.second}" })
    val p9dir = File("/Users/qa/.android/avd/Pixel_9a.avd")
    val p9 = AvdInfo.from(
        "Pixel_9a", p9dir,
        ini("avd.ini.displayname" to "Pixel 9a", "image.sysdir.1" to "system-images/android-36.1/google_apis/arm64-v8a/", "tag.id" to "google_apis",
            "tag.display" to "Google APIs", "abi.type" to "arm64-v8a", "hw.ramSize" to "1536", "hw.gpu.mode" to "host"),
        hasQuickBootSnapshot = false,
    )
    val slim = AvdSlim.State(File(p9dir, "config.ini.logpose-backup-20261014-121500"), editedSince = false, slimmedToMb = 1536, originalRamMb = 2048)
    val guest = GuestTweaks.Record("Pixel_9a", t0, GuestTweaks.ALL_PACKAGES.take(41), mapOf("global/activity_manager_constants" to null))
    val p9row = AvdRow(p9, slim, RunningEmulators.Running("Pixel_9a", emu.serial, 34042, HostMemory(1874, HostMemory.Source.FOOTPRINT)), guest = guest)
    val p8 = AvdRow(AvdInfo.from(
        "Pixel_8_16K", File("/Users/qa/.android/avd/Pixel_8_16K.avd"),
        ini("avd.ini.displayname" to "Pixel 8 (16 KB)", "image.sysdir.1" to "system-images/android-35/google_apis_ps16k/arm64-v8a/", "tag.id" to "google_apis_ps16k",
            "tag.display" to "Google APIs PS16K", "abi.type" to "arm64-v8a", "hw.ramSize" to "4096"),
        hasQuickBootSnapshot = true,
    ))
    val medium = AvdRow(AvdInfo.from(
        "Medium_Phone_API_35", File("/Users/qa/.android/avd/Medium_Phone_API_35.avd"),
        ini("avd.ini.displayname" to "Medium Phone API 35", "image.sysdir.1" to "system-images/android-35/google_apis_playstore/arm64-v8a/", "tag.id" to "google_apis_playstore",
            "tag.display" to "Google Play", "abi.type" to "arm64-v8a", "PlayStore.enabled" to "true", "hw.ramSize" to "2048"),
        hasQuickBootSnapshot = true,
    ))
    val stoppedP9 = p9row.copy(running = null)
    val emulators = EmulatorsUi(
        emulatorPath = "/Users/qa/Library/Android/sdk/emulator/emulator", avdHome = "/Users/qa/.android/avd",
        rows = listOf(p9row, p8, medium), guestBySerial = mapOf(emu.serial to guest.summary()),
    )
    val remembered = listOf(RememberedDevice("adb-R5CT20ABCDE-QoXtBe", "Galaxy S23", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-R5CT20ABCDE-QoXtBe"))
    val wireless = WirelessUi(remembered = remembered)
    val update = UpdateUi("1.1.0", enabled = true)

    fun live(
        selected: String? = null,
        layout: PaneLayout = PaneLayout(),
        popover: Popover = Popover.NONE,
        devices: List<Adb.DeviceInfo> = listOf(pixel, emu),
        advanced: AdvancedUi = AdvancedUi(),
        mcp: McpUi = McpUi(),
        updates: UpdateUi = update,
    ) = ScreenState(
        adb, devices, pixel.serial, SessionState.Running(pixel.serial, t0), TimelineSnapshot(1, events), selected, Placeholder.None,
        wireless = wireless,
    ).copy(emulators = emulators, layout = layout, popover = popover, advanced = advanced, mcp = mcp, updates = updates)

    fun idle(devices: List<Adb.DeviceInfo>, e: EmulatorsUi = emulators, popover: Popover = Popover.NONE, query: String = "", highlight: String? = null) = ScreenState(
        adb, devices, devices.firstOrNull { it.ready }?.serial, SessionState.Idle, TimelineSnapshot.EMPTY, null,
        Placeholder.resolve(true, devices, devices.firstOrNull()?.model?.replace('_', ' '), SessionState.Idle, QuietDetector.Verdict.RECEIVING, 0),
        wireless = wireless,
    ).copy(emulators = e, popover = popover, switcherQuery = query, switcherHighlight = highlight, updates = update)

    // 1. The full window: a phone selected, a capture running, the detail open.
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-window-capturing-$theme", dark) { LogPoseScreen(live(selected = "h1"), ScreenActions()) }
    }
    // 2. The switcher: phone + running slimmed emulator + 2 stopped AVDs + 1 offline remembered phone.
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-switcher-open-$theme", dark) { LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER), ScreenActions()) }
    }
    // Hover actions: the running emulator (Shut down) and a stopped AVD (Launch, Options…).
    shot(out, "redesign-switcher-emulator-actions-dark", true) {
        LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER, highlight = emu.serial), ScreenActions())
    }
    shot(out, "redesign-switcher-avd-actions-light", false) {
        LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER, highlight = "avd:Medium_Phone_API_35"), ScreenActions())
    }
    // 3. With a search query.
    shot(out, "redesign-switcher-search-dark", true) { LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER, query = "pixel"), ScreenActions()) }
    shot(out, "redesign-switcher-search-none-light", false) { LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER, query = "galaxy tab"), ScreenActions()) }
    // 4. An emulator booting inline (and an unauthorized phone, its instruction visible).
    val booting = LaunchUi(
        "Pixel_8_16K", "emulator-5556", "emulator -avd Pixel_8_16K -port 5556 -memory 1536 -gpu host -no-snapshot-save -no-boot-anim",
        LaunchStage.Booting, t0 - 14_000, t0, mapOf("Starting" to t0 - 14_000, "WaitingForAdb" to t0 - 12_000, "Booting" to t0 - 9_000),
    )
    val unauthorized = pixel.copy(state = "unauthorized")
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-switcher-booting-$theme", dark) {
            LogPoseScreen(idle(listOf(unauthorized, emu), emulators.copy(launch = booting), popover = Popover.SWITCHER), ScreenActions())
        }
    }
    // 5. The empty state: no device, three ways in.
    val noDevice = emulators.copy(rows = listOf(stoppedP9, p8, medium), guestBySerial = emptyMap())
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-empty-$theme", dark) { LogPoseScreen(idle(emptyList(), noDevice), ScreenActions()) }
    }
    shot(out, "redesign-empty-booting-dark", true) {
        LogPoseScreen(idle(emptyList(), noDevice.copy(launch = booting)), ScreenActions())
    }
    shot(out, "redesign-empty-switcher-light", false) {
        LogPoseScreen(idle(emptyList(), noDevice, popover = Popover.SWITCHER), ScreenActions())
    }
    // 6. Collapsed panes: timeline only (⌘1), detail only (⌘2).
    shot(out, "redesign-timeline-only-dark", true) { LogPoseScreen(live(selected = "h1", layout = PaneLayout(detailCollapsed = true)), ScreenActions()) }
    shot(out, "redesign-detail-only-light", false) { LogPoseScreen(live(selected = "h1", layout = PaneLayout(timelineCollapsed = true)), ScreenActions()) }
    // 7. Dragged splitters: a wide detail, and Advanced's mocks pane made short.
    val sync = SyncState().apply { onLocalRevision(3); onPush(3, 2, t0); onAck(3, 2) }
    val device = MocksController.DeviceState("in.shadowfax.gandalf.debug", "1.7.3", true, 3, mapOf("loc" to 14, "acc" to 2), sync.snapshot(), capturing = true)
    val rules = listOf(
        MockRule("loc", "PUT", "/app/v1/rider/*/location/", status = 200, body = "{}", latencyMillis = 800),
        MockRule("acc", "POST", "/v1/orders/*/accept", status = 500),
        MockRule("eta", "GET", "/v1/orders/*/eta", behavior = MockRule.BEHAVIOR_TIMEOUT, latencyMillis = 5_000, enabled = false),
    )
    val adv = AdvancedUi(
        advanced = true, serial = pixel.serial, mode = MockWriter.Mode.WRITER, rules = rules, device = device,
        scenarios = listOf(ScenarioInfo("accept-fails-once", 2, "500 then 200")),
        location = Vocabulary.resolve(null, File("/Users/qa/Library/Application Support/LogPose")),
    )
    shot(out, "redesign-splitters-dragged-dark", true) {
        LogPoseScreen(live(selected = "h1", layout = PaneLayout(detailWidth = 720, mocksHeight = 96), advanced = adv), ScreenActions())
    }
    shot(out, "redesign-mocks-collapsed-light", false) {
        LogPoseScreen(live(selected = "h1", layout = PaneLayout(mocksCollapsed = true), advanced = adv), ScreenActions())
    }
    // 8. The status bar with MCP on, the MCP popover, and an update available.
    val mcp = McpUi(serving = true, port = 63343, token = "3f9c2a71d0b84e6f9a5c1e27b4d8f063", readOnly = false)
    val newer = UpdateUi("1.1.0", enabled = true, available = UpdateCheck.Release("desktop-v1.2.0", UpdateCheck.Version(1, 2, 0), UpdateCheck.RELEASE_PAGE_PREFIX + "tag/desktop-v1.2.0"))
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-mcp-popover-$theme", dark) { LogPoseScreen(live(selected = "h1", popover = Popover.MCP, mcp = mcp, updates = newer), ScreenActions()) }
    }
    shot(out, "redesign-statusbar-mcp-on-dark", true) { LogPoseScreen(live(mcp = mcp, updates = newer), ScreenActions()) }
    // 9. The Developer popover.
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "redesign-developer-popover-$theme", dark) { LogPoseScreen(live(popover = Popover.DEVELOPER, advanced = adv, mcp = mcp), ScreenActions()) }
    }
    // 10. Narrow windows: 900 px (the detail shrinks beside the list), 720 px (it overlays the list).
    shot(out, "redesign-narrow-900-dark", true, width = 900, height = 700) { LogPoseScreen(live(selected = "h1"), ScreenActions()) }
    shot(out, "redesign-narrow-900-switcher-light", false, width = 900, height = 700) { LogPoseScreen(idle(listOf(pixel, emu), popover = Popover.SWITCHER), ScreenActions()) }
    shot(out, "redesign-narrow-760-overlay-light", false, width = 760, height = 700) { LogPoseScreen(live(selected = "h1"), ScreenActions()) }
    shot(out, "redesign-narrow-900-empty-dark", true, width = 900, height = 700) { LogPoseScreen(idle(emptyList(), noDevice), ScreenActions()) }
}
