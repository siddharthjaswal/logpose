package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.adb.PlatformTools
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineFeed
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.logcat.Adb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where adb stands — the first thing the window decides. */
sealed interface AdbStatus {
    data object Checking : AdbStatus
    /** Nothing on this Mac; the first-run screen asks to download platform-tools. */
    data object Missing : AdbStatus
    data class Downloading(val stage: String, val progress: Float?) : AdbStatus
    data class Ready(val path: String, val downloaded: Boolean) : AdbStatus
    data class Failed(val message: String) : AdbStatus
}

/**
 * The window's state and the only place that touches adb, the network or the disk — always on
 * [Dispatchers.IO]. Composables read the flows and call the verbs; they never block.
 */
class AppModel(
    private val scope: CoroutineScope,
    val session: CaptureSession = CaptureSession(),
) {
    private val _adb = MutableStateFlow<AdbStatus>(AdbStatus.Checking)
    val adb: StateFlow<AdbStatus> = _adb.asStateFlow()

    private val _devices = MutableStateFlow<List<Adb.DeviceInfo>>(emptyList())
    val devices: StateFlow<List<Adb.DeviceInfo>> = _devices.asStateFlow()

    private val _selectedSerial = MutableStateFlow<String?>(null)
    val selectedSerial: StateFlow<String?> = _selectedSerial.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    val feed = TimelineFeed(session.store)
    val snapshots: StateFlow<TimelineSnapshot> get() = feed.snapshots
    val sessionState: StateFlow<SessionState> get() = session.state

    private var feedJob: Job? = null

    fun start() {
        if (feedJob == null) feedJob = feed.launchIn(scope)
        locateAdb()
    }

    fun locateAdb() {
        scope.launch {
            _adb.value = AdbStatus.Checking
            val found = withContext(Dispatchers.IO) { AdbLocator.locate() }
            _adb.value = if (found == null) {
                AdbStatus.Missing
            } else {
                AdbLocator.install(found)
                AdbStatus.Ready(found, downloaded = found == AdbLocator.downloadedAdb().path)
            }
        }
    }

    /** Called only after the tester consented on the first-run screen. */
    fun downloadPlatformTools() {
        scope.launch {
            _adb.value = AdbStatus.Downloading("Reading Google's SDK manifest…", null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val archive = PlatformTools.parseManifest(PlatformTools.fetchManifest())
                        ?: error("Google's manifest lists no macOS platform-tools.")
                    val appSupport = AdbLocator.appSupportDir()
                    val zip = File(appSupport, "download/${archive.fileName}")
                    try {
                        PlatformTools.download(archive, zip) { p ->
                            _adb.value = AdbStatus.Downloading("Downloading ${archive.fileName}…", p)
                        }
                        _adb.value = AdbStatus.Downloading("Verifying checksum…", null)
                        PlatformTools.verify(zip, archive)?.let { error(it) }
                        _adb.value = AdbStatus.Downloading("Installing…", null)
                        PlatformTools.install(zip, appSupport).path
                    } finally {
                        zip.delete()
                    }
                }
            }
            result.fold(
                onSuccess = { path ->
                    AdbLocator.install(path)
                    _adb.value = AdbStatus.Ready(path, downloaded = true)
                },
                onFailure = { _adb.value = AdbStatus.Failed(it.message ?: it.toString()) },
            )
        }
    }

    /**
     * Polls `adb devices -l` every [periodMillis] for as long as the calling coroutine lives — the
     * window launches it only while it is visible (not minimized), so a hidden app never spawns adb.
     */
    suspend fun pollDevices(periodMillis: Long = 2_000) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val list = withContext(Dispatchers.IO) { Adb.devices() }
            _devices.value = list
            val selected = _selectedSerial.value
            if (selected == null || list.none { it.serial == selected }) {
                // One ready phone is the common case: select it so Start is one click.
                val ready = list.filter { it.ready }
                if (ready.size == 1 && session.state.value !is SessionState.Running) _selectedSerial.value = ready.single().serial
            }
            delay(periodMillis)
        }
    }

    fun selectDevice(serial: String) {
        if (session.state.value is SessionState.Running) return
        _selectedSerial.value = serial
    }

    fun startSession() {
        val serial = _selectedSerial.value ?: return
        _selectedEventId.value = null
        session.start(serial)
        feed.publishIfDirty()
    }

    fun stopSession() = session.stop()

    fun clearTimeline() {
        session.store.clear()
        _selectedEventId.value = null
    }

    fun selectEvent(id: String?) {
        _selectedEventId.value = id
    }

    fun quietVerdict(nowMillis: Long): QuietDetector.Verdict? {
        val running = session.state.value as? SessionState.Running ?: return null
        return QuietDetector.verdict(running.startedAtMillis, nowMillis, session.firstLogPoseAtMillis)
    }

    fun shutdown() {
        session.stop()
    }
}
