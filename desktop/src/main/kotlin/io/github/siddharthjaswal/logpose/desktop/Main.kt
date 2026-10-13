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
import io.github.siddharthjaswal.logpose.desktop.ui.WirelessActions
import kotlinx.coroutines.delay
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.desktop.ui.ShareActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Files macOS asked us to open — a `.logpose` double-clicked in Finder (the bundle's document type,
 * see `nativeDistributions` in the build) or passed on the command line. The open-file event can
 * arrive before the window exists, so it lands here and the window drains it.
 */
private val openRequests = MutableStateFlow<List<File>>(emptyList())

private fun requestOpen(files: List<File>) {
    val ok = files.filter { it.isFile && CaptureReader.accepts(it.name) }
    if (ok.isNotEmpty()) openRequests.update { it + ok }
}

fun main(args: Array<String>) {
    // Must be installed before the app finishes launching, or macOS's first open-file event is lost.
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
            Desktop.getDesktop().setOpenFileHandler { e -> requestOpen(e.files) }
        }
    }
    requestOpen(args.map(::File))
    app()
}

private fun app() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp), position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
    lateinit var model: AppModel
    Window(
        onCloseRequest = ::exitApplication, title = "LogPose", state = windowState,
        // ⌘B anywhere in the window drops a bookmark at "now".
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.isMetaPressed && e.key == Key.B && model.canBookmark) { model.beginBookmark(); true } else false
        },
    ) {
        val scope = rememberCoroutineScope()
        model = remember { AppModel(scope).also { it.start() } }
        DisposableEffect(Unit) { onDispose { model.shutdown() } }
        val requests by openRequests.collectAsState()
        LaunchedEffect(requests) {
            val next = requests.lastOrNull() ?: return@LaunchedEffect
            openRequests.value = emptyList()
            model.openCaptureFile(next)
        }

        val adb by model.adb.collectAsState()
        // Poll devices only while the window can be seen and adb exists — a minimized app never
        // spawns adb. Leaving composition (or minimizing) cancels the loop.
        if (!windowState.isMinimized && adb is AdbStatus.Ready) {
            LaunchedEffect(Unit) { model.pollDevices() }
            // Remembered Wi-Fi phones: retried on the same visibility rule (a hidden app dials nothing).
            LaunchedEffect(Unit) { model.reconnectRemembered() }
            LaunchedEffect(Unit) { model.wireless.showOnboardingIfFirstRun() }
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
    val wireless by model.wireless.ui.collectAsState()
    val marks by model.marks.collectAsState()
    val viewing by model.viewing.collectAsState()
    val pendingBookmark by model.pendingBookmark.collectAsState()
    val notice by model.notice.collectAsState()
    val share by model.share.ui.collectAsState()
    val selectedIds by model.selectedIds.collectAsState()
    val screenshotBusy by model.screenshotBusy.collectAsState()
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
        ScreenState(
            adb, devices, selectedSerial, session, snapshot, selectedEventId, placeholder, wireless,
            marks = marks, viewing = viewing, pendingBookmark = pendingBookmark, notice = notice, share = share,
            selectedIds = selectedIds, screenshotBusy = screenshotBusy,
            canBookmark = session is SessionState.Running, canShare = viewing != null || session !is SessionState.Idle || snapshot.events.isNotEmpty(),
        ),
        ScreenActions(
            onDownloadAdb = model::downloadPlatformTools,
            onRecheckAdb = model::locateAdb,
            onSelectDevice = model::selectDevice,
            onStart = model::startSession,
            onStop = model::stopSession,
            onClear = model::clearTimeline,
            onSelectEvent = model::selectEvent,
            wireless = WirelessActions(
                onAddDevice = { model.wireless.openAddDevice() },
                onHowToConnect = model.wireless::openHowToConnect,
                onCloseSheet = model.wireless::closeSheet,
                onSelectTab = model.wireless::selectTab,
                onNewQr = model.wireless::startQr,
                onFormChange = model.wireless::updateForm,
                onPairWithCode = model.wireless::pairWithCode,
                onSwitchToWireless = model.wireless::switchToWireless,
                onForget = { id -> wireless.remembered.firstOrNull { it.id == id }?.let { model.wireless.forget(it, devices) } },
                onDismissLegacy = model.wireless::dismissLegacyStatus,
            ),
            onToggleSelect = model::toggleSelected,
            onBookmark = model::beginBookmark,
            onCommitBookmark = model::commitBookmark,
            onScreenshot = model::takeScreenshot,
            onOpenFile = { chooseCaptureFile()?.let(model::openCaptureFile) },
            onBackToLive = model::backToLive,
            onShare = model::openShare,
            onDismissNotice = model::dismissNotice,
            share = ShareActions(
                onClose = model.share::close,
                onOptions = model.share::setOptions,
                onChooseFolder = { chooseFolder(share?.folder)?.let(model.share::setFolder) },
                onExport = model.share::export,
                onReveal = model.share::reveal,
                onCopyFile = model.share::copyFile,
                onCopySummary = model.share::copySummary,
            ),
        ),
    )
}

/** The native macOS open panel, filtered to captures. Runs on the AWT thread (it is modal). */
private fun chooseCaptureFile(): File? {
    val dialog = FileDialog(null as Frame?, "Open capture file", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> CaptureReader.accepts(name) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** A folder picker for the share sheet's output (macOS shows folders only with this property set). */
private fun chooseFolder(current: File?): File? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = FileDialog(null as Frame?, "Save captures to", FileDialog.LOAD)
        current?.let { dialog.directory = it.path }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name).takeIf { it.isDirectory }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
