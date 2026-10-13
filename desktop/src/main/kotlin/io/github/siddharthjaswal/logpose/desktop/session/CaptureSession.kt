package io.github.siddharthjaswal.logpose.desktop.session

import io.github.siddharthjaswal.logpose.analysis.CorrelationIndex
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Something that streams LogPose-tag lines from one device. [LogcatReader] in production. */
interface LineSource {
    fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit)
    fun stop()
}

/**
 * One capture: logcat → parser → correlation → store, wired the way the daemon's `Capture.kt` does
 * it, minus mocks and push (Advanced, M4) and minus the reattach loop (a QA tester sees *Ended* and
 * presses Start again; silent reattach arrives with wireless in M2, where drops are routine).
 *
 * Two rules carried over unchanged from the daemon:
 *  - **No `adb logcat -c`.** The clear is global to the device, so it would wipe a coexisting
 *    IDE's backlog (PRD §7). The cost is a short backlog replay at start, which is harmless.
 *  - **Parse → warm → add on the reader thread.** Correlation is computed as the event arrives,
 *    before the UI's next snapshot reads it.
 *
 * Thread-safety: [start]/[stop] are called from the UI; the line callbacks run on the reader thread.
 * The store is itself synchronized and the UI only reads it through [TimelineFeed]'s snapshots.
 */
class CaptureSession(
    val store: EventStore = EventStore(capacity = STORE_CAPACITY),
    private val sourceFor: (serial: String) -> LineSource = { serial -> LogcatLineSource(serial) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val correlation = CorrelationIndex()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Host millis the first LogPose line of the current session arrived; null until one does. */
    @Volatile var firstLogPoseAtMillis: Long? = null
        private set

    @Volatile private var source: LineSource? = null
    @Volatile private var parser = TransactionParser()
    /** Bumped per start, so a reader we already replaced cannot end the session that replaced it. */
    @Volatile private var generation = 0

    @Synchronized
    fun start(serial: String) {
        val before = _state.value
        val after = SessionReducer.reduce(before, SessionEvent.Start(serial, clock()))
        if (after === before) return
        // A new session is a new timeline: a QA pass that starts over should not inherit the last
        // one's rows (recording sessions to disk, so nothing is lost, is M3).
        store.clear()
        correlation.clear()
        firstLogPoseAtMillis = null
        val p = TransactionParser().also { parser = it }
        p.onControl = { msg ->
            // Same as the daemon and the panel: a hello marks an app run, so two launches never
            // read as one timeline. Mock acks are ignored — M1 has no mock writer.
            if (msg is ControlMessage.DeviceHello) {
                store.noteHello(msg.hello.processId, msg.hello.pkg, msg.hello.libVersion)
            }
        }
        _state.value = after
        val gen = ++generation
        val s = sourceFor(serial)
        source = s
        s.start(
            onLine = { line ->
                if (firstLogPoseAtMillis == null && QuietDetector.isLogPoseLine(line)) firstLogPoseAtMillis = clock()
                p.accept(line)?.let { event ->
                    correlation.warm(event)
                    store.add(event)
                }
            },
            onError = { msg -> if (gen == generation) transition(SessionEvent.Failed(serial, msg)) },
            onStopped = { if (gen == generation) transition(SessionEvent.StreamEnded(serial)) },
        )
    }

    @Synchronized
    fun stop() {
        val before = _state.value
        val after = SessionReducer.reduce(before, SessionEvent.Stop)
        if (after === before) return
        // State first, so the reader's onStopped (which follows) finds nothing running to end.
        _state.value = after
        source?.stop()
        source = null
    }

    private fun transition(event: SessionEvent) {
        _state.update { SessionReducer.reduce(it, event) }
    }

    companion object {
        /** 5× the plugin's live view; disk-backed recording (M3) is what makes long passes lossless. */
        const val STORE_CAPACITY = 10_000
    }
}

/** [LineSource] over core's [LogcatReader], never clearing the device buffer. */
class LogcatLineSource(serial: String) : LineSource {
    private val reader = LogcatReader(deviceSerial = serial, clearOnStart = false)
    override fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) =
        reader.start(onLine, onError, onStopped)
    override fun stop() = reader.stop()
}
