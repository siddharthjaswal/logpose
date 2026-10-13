package io.github.siddharthjaswal.logpose.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.AppModel
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.device.DeviceStatuses
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.theme.LogPoseTheme
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import kotlinx.coroutines.delay

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
    Window(onCloseRequest = ::exitApplication, title = "LogPose", state = windowState) {
        val scope = rememberCoroutineScope()
        val model = remember { AppModel(scope).also { it.start() } }
        DisposableEffect(Unit) { onDispose { model.shutdown() } }

        val adb by model.adb.collectAsState()
        // Poll devices only while the window can be seen and adb exists — a minimized app never
        // spawns adb. Leaving composition (or minimizing) cancels the loop.
        if (!windowState.isMinimized && adb is AdbStatus.Ready) {
            LaunchedEffect(Unit) { model.pollDevices() }
        }
        LogPoseTheme(dark = isSystemInDarkTheme()) {
            App(model, adb)
        }
    }
}

@Composable
private fun App(model: AppModel, adb: AdbStatus) {
    val devices by model.devices.collectAsState()
    val selectedSerial by model.selectedSerial.collectAsState()
    val session by model.sessionState.collectAsState()
    val snapshot by model.snapshots.collectAsState()
    val selectedEventId by model.selectedEventId.collectAsState()
    // A one-second clock, only while a session runs: it is what lets "no LogPose events" appear
    // 20 s in without any event arriving to trigger a recomposition.
    val now by produceState(System.currentTimeMillis(), session) {
        while (session is SessionState.Running) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val selectedName = devices.firstOrNull { it.serial == selectedSerial }?.let(DeviceStatuses::displayName)
    val placeholder = Placeholder.resolve(
        adbReady = adb is AdbStatus.Ready,
        devices = devices,
        selectedName = selectedName,
        session = session,
        verdict = model.quietVerdict(now),
        eventCount = snapshot.events.size,
    )
    LogPoseScreen(
        ScreenState(adb, devices, selectedSerial, session, snapshot, selectedEventId, placeholder),
        ScreenActions(
            onDownloadAdb = model::downloadPlatformTools,
            onRecheckAdb = model::locateAdb,
            onSelectDevice = model::selectDevice,
            onStart = model::startSession,
            onStop = model::stopSession,
            onClear = model::clearTimeline,
            onSelectEvent = model::selectEvent,
        ),
    )
}
