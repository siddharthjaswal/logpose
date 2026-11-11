package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.appcontrol.AppAction
import io.github.siddharthjaswal.logpose.appcontrol.AppControl
import io.github.siddharthjaswal.logpose.appcontrol.AppMarker
import io.github.siddharthjaswal.logpose.appcontrol.AppTarget
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.AppActionsUi
import io.github.siddharthjaswal.logpose.desktop.app.AppResultUi
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.Popover
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.File

/**
 * The App actions: the toolbar's App menu (one app, several apps, none announced), the destructive
 * confirm, the progress strip, the run-as-refused fallback, and the timeline's marker row.
 *
 *   ./gradlew :desktop:renderScreens -PshotsOnly=app -PshotsDir=…
 */
fun renderAppActions(out: File) {
    val adb = AdbStatus.Ready("/Users/qa/Library/Android/sdk/platform-tools/adb", downloaded = false)
    val emu = Adb.DeviceInfo("emulator-5580", "device", "sdk_gphone64_arm64")
    val pkg = "in.shadowfax.gandalf.debug"
    val t0 = System.currentTimeMillis()
    val base = Fixtures.store().snapshot()
    val marker = AppMarker.of(
        AppControl.Report(AppAction.CLEAR_CACHE, emu.serial, pkg, emptyList(), stopped = true, cleared = true, launchedVia = "am start -n $pkg/.MainActivity",
            externalCache = io.github.siddharthjaswal.logpose.appcontrol.AppOutput.ExternalCache.CLEARED),
        atMillis = (base.lastOrNull()?.timestampMillis ?: t0) + 500,
    )
    val events = base + marker
    val one = AppTarget.choose(listOf(pkg), null)

    fun state(app: AppActionsUi, popover: Popover = Popover.NONE, selected: String? = null) = ScreenState(
        adb, listOf(emu), emu.serial, SessionState.Running(emu.serial, t0), TimelineSnapshot(1, events), selected, Placeholder.None,
    ).copy(app = app, popover = popover)

    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "app-menu-$theme", dark) { LogPoseScreen(state(AppActionsUi(target = one), Popover.APP), ScreenActions()) }
    }
    shot(out, "app-menu-several-apps-dark", true) {
        LogPoseScreen(state(AppActionsUi(target = AppTarget.choose(listOf(pkg, "in.shadowfax.zeus.debug"), null)), Popover.APP), ScreenActions())
    }
    shot(out, "app-menu-none-announced-light", false) {
        LogPoseScreen(state(AppActionsUi(target = AppTarget.choose(emptyList(), null), draft = "com.acme.app.debug"), Popover.APP), ScreenActions())
    }
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "app-confirm-clear-data-$theme", dark) {
            LogPoseScreen(state(AppActionsUi(target = one, confirm = pkg, regrant = true)), ScreenActions())
        }
    }
    shot(out, "app-progress-dark", true) {
        LogPoseScreen(state(AppActionsUi(target = one, running = AppAction.CLEAR_CACHE, progress = "Clearing cache…")), ScreenActions())
    }
    shot(out, "app-runas-failed-light", false) {
        LogPoseScreen(state(AppActionsUi(target = one, result = AppResultUi(
            AppAction.CLEAR_CACHE, "in.shadowfax.gandalf",
            "in.shadowfax.gandalf isn't a debuggable build, so its cache can't be reached on its own. Nothing was changed. " + AppAction.NOT_DEBUGGABLE_ADVICE,
            error = true, offerClearAll = true,
        ))), ScreenActions())
    }
    shot(out, "app-done-marker-selected-dark", true) {
        LogPoseScreen(state(AppActionsUi(target = one, result = AppResultUi(
            AppAction.CLEAR_CACHE, pkg, "Cleared $pkg's cache and restarted it. New run #4. Mocks re-pushed (1).", error = false,
        )), selected = marker.id), ScreenActions())
    }
}
