package io.github.siddharthjaswal.logpose.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.theme.LogPoseTheme
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the real screen composable off-screen — no window, no display, no device — to PNGs a
 * person (or an agent) can look at. `./gradlew :desktop:renderScreens -PshotsDir=…`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val pixel = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val events = Fixtures.store().snapshot()
    val running = SessionState.Running(pixel.serial, System.currentTimeMillis())
    val full = TimelineSnapshot(1, events)
    val ready = AdbStatus.Ready("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", downloaded = true)

    fun state(
        adb: AdbStatus = ready,
        devices: List<Adb.DeviceInfo> = listOf(pixel),
        session: SessionState = running,
        snapshot: TimelineSnapshot = full,
        selected: String? = null,
        verdict: QuietDetector.Verdict? = QuietDetector.Verdict.RECEIVING,
    ) = ScreenState(
        adb, devices, devices.firstOrNull { it.ready }?.serial, session, snapshot, selected,
        Placeholder.resolve(adb is AdbStatus.Ready, devices, devices.firstOrNull()?.model?.replace('_', ' '), session, verdict, snapshot.events.size),
    )

    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "timeline-$theme", dark) { LogPoseScreen(state(), ScreenActions()) }
        shot(out, "detail-http-$theme", dark) { LogPoseScreen(state(selected = "h1"), ScreenActions()) }
    }
    shot(out, "detail-worker-dark", true) { LogPoseScreen(state(selected = "w1"), ScreenActions()) }
    shot(out, "detail-config-light", false) { LogPoseScreen(state(selected = "c1"), ScreenActions()) }
    shot(out, "empty-no-adb-dark", true) { LogPoseScreen(state(adb = AdbStatus.Missing), ScreenActions()) }
    shot(out, "empty-no-adb-light", false) { LogPoseScreen(state(adb = AdbStatus.Missing), ScreenActions()) }
    shot(out, "empty-no-device-dark", true) {
        LogPoseScreen(state(devices = emptyList(), session = SessionState.Idle, snapshot = TimelineSnapshot.EMPTY), ScreenActions())
    }
    shot(out, "empty-no-logpose-dark", true) {
        LogPoseScreen(state(snapshot = TimelineSnapshot.EMPTY, verdict = QuietDetector.Verdict.NO_LOGPOSE), ScreenActions())
    }
    shot(out, "empty-no-logpose-light", false) {
        LogPoseScreen(state(snapshot = TimelineSnapshot.EMPTY, verdict = QuietDetector.Verdict.NO_LOGPOSE), ScreenActions())
    }
    shot(out, "unauthorized-light", false) {
        LogPoseScreen(
            state(devices = listOf(pixel.copy(state = "unauthorized")), session = SessionState.Idle, snapshot = TimelineSnapshot.EMPTY),
            ScreenActions(),
        )
    }
    println("Rendered to ${out.absolutePath}")
}

/** Renders [content] at 1280×800 logical px, 2× density (a Retina frame), after a few frames settle. */
fun shot(out: File, name: String, dark: Boolean, width: Int = 1280, height: Int = 800, content: @Composable () -> Unit) {
    val scale = 2f
    ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
        LogPoseTheme(dark) { content() }
    }.use { scene ->
        // Several frames: LaunchedEffects (tail-follow scroll) run between them.
        var image = scene.render(0)
        for (i in 1..4) image = scene.render(i * 16_000_000L)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        File(out, "$name.png").writeBytes(png.bytes)
    }
}
