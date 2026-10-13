package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.TimelineFeed
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.File
import kotlin.system.exitProcess

/**
 * The M1 gate without a display: the app's own session layer (not the UI) against a real device,
 * then one off-screen frame of the real screen populated from that store.
 *
 *   ./gradlew :desktop:liveGate -PshotsDir=… -PgateSeconds=20
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/screens" }).apply { mkdirs() }
    val seconds = args.getOrElse(1) { "20" }.toLong()

    val adb = AdbLocator.locate() ?: fail("no adb on this machine")
    AdbLocator.install(adb)
    val device = Adb.devices().firstOrNull { it.ready } ?: fail("no ready device (adb devices is empty)")
    println("adb: $adb  device: ${device.label}")

    val session = CaptureSession()
    val feed = TimelineFeed(session.store)
    val startedAt = System.currentTimeMillis()
    session.start(device.serial)

    // Bring the app forward, as a tester would by tapping its icon.
    runCatching {
        ProcessBuilder(adb, "-s", device.serial, "shell", "monkey", "-p", "in.shadowfax.gandalf.debug",
            "-c", "android.intent.category.LAUNCHER", "1").redirectErrorStream(true).start().waitFor()
    }

    var publishes = 0
    val deadline = startedAt + seconds * 1000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(TimelineFeed.DEFAULT_PERIOD_MS)
        if (feed.publishIfDirty()) publishes++
    }
    feed.publishIfDirty()
    val events = feed.snapshots.value.events
    val live = events.count { it.timestampMillis >= startedAt - 2_000 }
    val byKind = events.groupingBy { it.kind }.eachCount()
    println("state=${session.state.value}  events=${events.size} (live since start≈$live, backlog=${events.size - live})  " +
        "publishes=$publishes  kinds=$byKind  sessions=${session.store.sessions().map { "${it.pkg}@${it.libVersion}" }}")
    println("first LogPose line after ${session.firstLogPoseAtMillis?.minus(startedAt)}ms")

    val snapshot = feed.snapshots.value
    val selected = events.lastOrNull { it.kind == "http" }?.id
    shot(out, "live-gate-timeline-dark", true) {
        LogPoseScreen(
            ScreenState(
                AdbStatus.Ready(adb, downloaded = false), listOf(device), device.serial, session.state.value, snapshot, null,
                Placeholder.resolve(true, listOf(device), device.model, session.state.value, QuietDetector.Verdict.RECEIVING, events.size),
            ),
            ScreenActions(),
        )
    }
    shot(out, "live-gate-detail-light", false) {
        LogPoseScreen(
            ScreenState(
                AdbStatus.Ready(adb, downloaded = false), listOf(device), device.serial, session.state.value, snapshot, selected,
                Placeholder.resolve(true, listOf(device), device.model, session.state.value, QuietDetector.Verdict.RECEIVING, events.size),
            ),
            ScreenActions(),
        )
    }
    session.stop()
    if (events.isEmpty()) fail("no events landed in the store")
    println("LIVE GATE PASSED — rendered to ${out.absolutePath}")
    exitProcess(0)
}

private fun fail(msg: String): Nothing {
    System.err.println("LIVE GATE FAILED: $msg")
    exitProcess(1)
}
