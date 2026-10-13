package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.adb.PlatformTools
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineFeed
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevices
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
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
import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.desktop.device.ScreenCapture
import io.github.siddharthjaswal.logpose.desktop.session.SessionFiles
import io.github.siddharthjaswal.logpose.desktop.session.SessionMeta
import io.github.siddharthjaswal.logpose.desktop.session.SessionRecorder
import io.github.siddharthjaswal.logpose.desktop.share.ShareIo
import io.github.siddharthjaswal.logpose.desktop.timeline.SessionMarks
import io.github.siddharthjaswal.logpose.desktop.timeline.ShotMark
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
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
/** A capture file open in the window, read-only (M3): the plugin's *Open capture file* twin. */
data class ViewingFile(val name: String, val file: File, val events: Int, val malformed: Int, val manifest: CaptureManifest?)

/** A one-line message under the toolbar — a screenshot that failed, a file that wouldn't open. */
data class Notice(val text: String, val error: Boolean = false, val id: Long = System.nanoTime())

class AppModel(
    private val scope: CoroutineScope,
    sessionsDir: File = SessionFiles.defaultDir(AdbLocator.appSupportDir()),
    sessionOverride: CaptureSession? = null,
    settings: KeyValueStore = RememberedDevices.forApp(AdbLocator.appSupportDir()),
) {
    /**
     * Every session is recorded to `sessions/<timestamp>-<device>.ndjson` (M3) — the store is only
     * the live view. The device's model names the file; its Android version is filled in from adb
     * once the session is under way (off the UI thread).
     */
    val session: CaptureSession = sessionOverride ?: CaptureSession(recorderFor = { serial, startedAt ->
        val model = _devices.value.firstOrNull { it.serial == serial }?.model
        SessionRecorder(
            SessionFiles(sessionsDir, SessionFiles.baseName(startedAt, model ?: serial)),
            SessionMeta(serial = serial, deviceModel = model?.replace('_', ' '), startedAtMillis = startedAt),
        )
    })

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

    private val _marks = MutableStateFlow(SessionMarks.EMPTY)
    /** Bookmarks and screenshots of the live session (or of the opened file). */
    val marks: StateFlow<SessionMarks> = _marks.asStateFlow()

    private val _pendingBookmark = MutableStateFlow<Long?>(null)
    /** Non-null while the note prompt is open: the instant ⌘B was pressed. */
    val pendingBookmark: StateFlow<Long?> = _pendingBookmark.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _viewing = MutableStateFlow<ViewingFile?>(null)
    val viewing: StateFlow<ViewingFile?> = _viewing.asStateFlow()
    private var viewedCapture: Capture? = null

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    /** Rows ⌘-clicked into the selection — what *Share → only selected rows* exports. */
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _screenshotBusy = MutableStateFlow(false)
    val screenshotBusy: StateFlow<Boolean> = _screenshotBusy.asStateFlow()

    val share = ShareController(scope, writtenBy = "LogPose for Mac ${appVersion()}")

    /** Wi-Fi pairing, *Switch to wireless*, remembered phones and their auto-reconnect (M2). */
    val wireless = WirelessController(scope, settings, onConnected = { serial -> if (session.state.value !is SessionState.Running) _selectedSerial.value = serial })

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
        if (_viewing.value != null) return
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
        _marks.value = SessionMarks.EMPTY
        _pendingBookmark.value = null
        session.start(serial)
        feed.publishIfDirty()
        // Device facts for the recording's metadata — adb, so off the UI thread.
        val rec = session.recorder ?: return
        scope.launch(Dispatchers.IO) {
            val release = AdbCommands.Real.shell(serial, "getprop ro.build.version.release")?.trim()?.lineSequence()?.firstOrNull()?.trim()
            if (!release.isNullOrBlank() && !release.contains(' ')) rec.updateMeta { it.copy(androidVersion = release) }
        }
    }

    fun stopSession() {
        commitBookmark(null)
        session.stop()
    }

    fun clearTimeline() {
        session.store.clear()
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
    }

    fun selectEvent(id: String?) {
        _selectedEventId.value = id
        _selectedIds.value = if (id == null) emptySet() else setOf(id)
    }

    /** ⌘-click: add a row to, or take it out of, the multi-row selection. */
    fun toggleSelected(id: String) {
        val next = _selectedIds.value.let { if (id in it) it - id else it + id }
        _selectedIds.value = next
        _selectedEventId.value = if (id in next) id else next.lastOrNull()
    }

    // ---- bookmarks (⌘B) ---------------------------------------------------------------------

    /** Whether ⌘B means anything right now: only a live session has a "now" to mark. */
    val canBookmark: Boolean get() = session.state.value is SessionState.Running && session.recorder != null

    /** ⌘B: the marker's time is *now*, not when the note is finished. Opens the note prompt. */
    fun beginBookmark() {
        if (!canBookmark) return
        if (_pendingBookmark.value != null) return
        _pendingBookmark.value = System.currentTimeMillis()
    }

    /** Enter saves with [note]; Esc passes null and saves the marker without one. */
    fun commitBookmark(note: String?) {
        val at = _pendingBookmark.value ?: return
        _pendingBookmark.value = null
        val b = Bookmark(at, note?.trim().orEmpty())
        session.recorder?.addBookmark(b)
        _marks.value = _marks.value.copy(bookmarks = _marks.value.bookmarks + b)
    }

    // ---- screenshots --------------------------------------------------------------------------

    fun takeScreenshot() {
        val running = session.state.value as? SessionState.Running ?: return
        val rec = session.recorder ?: return
        if (_screenshotBusy.value) return
        _screenshotBusy.value = true
        val at = System.currentTimeMillis()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val o = ScreenCapture.capture(running.serial)) {
                    is ScreenCapture.Outcome.Ok -> runCatching { rec.addScreenshot(o.png, at) }.fold(
                        onSuccess = { (ref, file) -> ShotMark(ref, file = file) },
                        onFailure = { e -> ScreenCapture.Outcome.Failed("Couldn't save the screenshot: ${e.message}") },
                    )
                    is ScreenCapture.Outcome.Failed -> o
                }
            }
            _screenshotBusy.value = false
            when (result) {
                is ShotMark -> _marks.value = _marks.value.copy(shots = _marks.value.shots + result)
                is ScreenCapture.Outcome.Failed -> say(result.message, error = true)
            }
        }
    }

    fun say(text: String, error: Boolean = false) {
        val n = Notice(text, error)
        _notice.value = n
        scope.launch {
            delay(8_000)
            if (_notice.value == n) _notice.value = null
        }
    }

    fun dismissNotice() { _notice.value = null }

    // ---- opening a capture file (read-only) ---------------------------------------------------

    /**
     * Opens a `.logpose` / `.ndjson` read-only: reads it off the UI thread, stops a running session
     * (already recorded to disk, so nothing is lost), and replays the file into the timeline the way
     * a live session fills it. [Back to live][backToLive] returns to capturing.
     */
    fun openCaptureFile(file: File) {
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { CaptureReader.read(file) } }
            val capture = read.getOrElse { e ->
                say("Couldn't open ${file.name}: ${e.message ?: e}", error = true)
                return@launch
            }
            if (session.state.value is SessionState.Running) stopSession()
            _pendingBookmark.value = null
            _selectedEventId.value = null
            _selectedIds.value = emptySet()
            viewedCapture = capture
            withContext(Dispatchers.IO) {
                session.store.clear()
                session.correlation.clear()
                capture.replayInto(session.store) { session.correlation.warm(it) }
            }
            _marks.value = SessionMarks(
                capture.bookmarks,
                capture.manifest?.screenshots.orEmpty().map { ShotMark(it, bytes = capture.screenshots[it.file]) },
            )
            _viewing.value = ViewingFile(file.name, file, capture.events.size, capture.malformedLines, capture.manifest)
            feed.publishIfDirty()
            if (capture.malformedLines > 0) say("${capture.malformedLines} unreadable lines in ${file.name} were skipped.")
        }
    }

    fun backToLive() {
        if (_viewing.value == null) return
        _viewing.value = null
        viewedCapture = null
        _marks.value = SessionMarks.EMPTY
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
        session.store.clear()
        session.correlation.clear()
        feed.publishIfDirty()
    }

    // ---- share ----------------------------------------------------------------------------------

    /** Whether there is anything to share: an opened file, or a recorded session. */
    val canShare: Boolean get() = _viewing.value != null || session.recorder != null

    fun openShare() {
        val viewed = viewedCapture
        val ids = _selectedIds.value
        if (viewed != null) {
            share.open(_viewing.value?.name ?: "file", ids) { ShareIo.fromCapture(viewed) }
            return
        }
        val rec = session.recorder ?: return
        share.open("this session", ids) { ShareIo.fromRecorder(rec, System.currentTimeMillis()) }
    }

    fun quietVerdict(nowMillis: Long): QuietDetector.Verdict? {
        val running = session.state.value as? SessionState.Running ?: return null
        return QuietDetector.verdict(running.startedAtMillis, nowMillis, session.firstLogPoseAtMillis)
    }

    /** Retries remembered Wi-Fi phones while the window is visible (launched beside [pollDevices]). */
    suspend fun reconnectRemembered() = wireless.reconnectLoop({ _devices.value })

    fun shutdown() {
        wireless.shutdown()
        session.stop()
        // Give the recorder a moment to flush its last lines; it is a daemon thread otherwise.
        session.recorder?.awaitClosed(2_000)
    }

    companion object {
        fun appVersion(): String = AppModel::class.java.`package`?.implementationVersion ?: "dev"
    }
}
