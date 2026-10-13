package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Notice
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.ShareController
import io.github.siddharthjaswal.logpose.desktop.device.ScreenCapture
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.share.ShareIo
import io.github.siddharthjaswal.logpose.desktop.share.SharePlanner
import io.github.siddharthjaswal.logpose.desktop.share.ShareSource
import io.github.siddharthjaswal.logpose.desktop.share.SlackSummary
import io.github.siddharthjaswal.logpose.desktop.timeline.SessionMarks
import io.github.siddharthjaswal.logpose.desktop.timeline.ShotMark
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files

/** M3: bookmark + screenshot markers, the ⌘B prompt, the share sheet, and a file opened read-only. */
fun renderM3(out: File) {
    val pixel = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val ready = AdbStatus.Ready("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", downloaded = true)
    val t0 = Fixtures.START
    val running = SessionState.Running(pixel.serial, t0 - 2_000)
    val png = Fixtures.phonePng()
    val marks = SessionMarks(
        bookmarks = listOf(Bookmark(t0 + 950, "tapped Accept here, spinner never stopped"), Bookmark(t0 + 4_500)),
        shots = listOf(ShotMark(ScreenshotRef(t0 + 2_100, "shot-1.png"), bytes = png)),
    )
    val snap = TimelineSnapshot(1, Fixtures.store().snapshot())
    fun live(snapshot: TimelineSnapshot = snap) = ScreenState(
        ready, listOf(pixel), pixel.serial, running, snapshot, null, Placeholder.None, marks = marks,
    )

    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "m3-timeline-marks-$theme", dark) { LogPoseScreen(live(), ScreenActions()) }
    }
    shot(out, "m3-bookmark-prompt-dark", true) { LogPoseScreen(live().copy(pendingBookmark = t0 + 6_000), ScreenActions()) }
    shot(out, "m3-notice-black-screen-light", false) { LogPoseScreen(live().copy(notice = Notice(ScreenCapture.BLACK, error = true)), ScreenActions()) }

    // The share sheet over a session with real PII in its bodies.
    val source = ShareSource(
        capture = Capture(null, Fixtures.shareLines()),
        device = CaptureManifest.DeviceInfo("Pixel 9a", "15", pixel.serial),
        app = CaptureManifest.AppInfo(Fixtures.hello.pkg, Fixtures.hello.libVersion),
        startedAtMillis = t0 - 2_000, endedAtMillis = t0 + 12_000,
        bookmarks = marks.bookmarks, screenshots = marks.shots.map { it.ref },
        loadScreenshot = { png },
    )
    val share = ShareController(CoroutineScope(Dispatchers.Unconfined), "LogPose for Mac (render)")
    share.openWith("this session", source, selectedIds = setOf("h4", "h8"))
    val stopped = live().copy(session = SessionState.Idle)
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "m3-share-default-$theme", dark) { LogPoseScreen(stopped.copy(share = share.ui.value), ScreenActions()) }
    }
    share.setOptions { it.copy(includeBodies = false) }
    Thread.sleep(300)
    shot(out, "m3-share-no-bodies-light", false) { LogPoseScreen(stopped.copy(share = share.ui.value), ScreenActions()) }
    share.setOptions { it.copy(includeBodies = true, scope = io.github.siddharthjaswal.logpose.desktop.share.ShareScope.Whole) }
    Thread.sleep(300)
    val tmp = Files.createTempDirectory("logpose-render").toFile()
    val ui = share.ui.value!!
    val file = ShareIo.export(source, ui.preview!!, tmp, "LogPose for Mac (render)", t0 + 12_000)
    val summary = SlackSummary.format(SharePlanner.manifest(source, ui.preview!!, "x", t0 + 12_000), ui.preview!!.events, file.name)
    shot(out, "m3-share-exported-dark", true) {
        LogPoseScreen(stopped.copy(share = ui.copy(exported = file, summary = summary, copied = "Slack summary copied — paste it into the thread.")), ScreenActions())
    }
    File(out, "m3-slack-summary.txt").writeText(summary + "\n")

    // The M3 gate: a session shared to a .logpose, then opened read-only — the timeline FROM the file.
    val gate = M3Gate.run(File(tmp, "gate"), out)
    check(gate.plugin == gate.desktop) { "plugin and desktop timelines differ" }
    println("M3 gate: ${gate.file.name} — plugin ${gate.plugin.size} events, desktop ${gate.desktop.size} events, identical=${gate.plugin == gate.desktop}")
    val viewing = gate.model.viewing.value!!
    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "m3-viewing-file-$theme", dark) {
            LogPoseScreen(
                ScreenState(
                    ready, listOf(pixel), pixel.serial, SessionState.Idle, TimelineSnapshot(2, gate.desktop), null, Placeholder.None,
                    marks = gate.model.marks.value, viewing = viewing,
                ),
                ScreenActions(),
            )
        }
    }
    shot(out, "m3-viewing-file-detail-dark", true) {
        LogPoseScreen(
            ScreenState(
                ready, listOf(pixel), pixel.serial, SessionState.Idle, TimelineSnapshot(2, gate.desktop), "h8", Placeholder.None,
                marks = gate.model.marks.value, viewing = viewing,
            ),
            ScreenActions(),
        )
    }
}
