package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.analysis.CorrelationIndex
import io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.logcat.DeviceChoice
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.PushController
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The daemon's capture: logcat → parser → store, plus the reverse channel, plus the reattach loop.
 *
 * This is the tool window's `startCapture`/`attachReader` with the EDT taken out. Two behaviours
 * differ deliberately, both from PRD §7:
 *
 *  - **No `adb logcat -c` unless asked.** The clear is global to the device, so a daemon that ran
 *    it would wipe a coexisting IDE's backlog and truncate its half-reassembled chunks. `--clear`
 *    opts in for someone who knows they are the only reader.
 *  - **The retry loop never gives up.** The plugin stops after five attempts because a human is
 *    sitting there and can press ▶ again; nobody is watching a daemon, and a device that comes
 *    back an hour later should be picked up. So the delay backs off to a ceiling and keeps going,
 *    and every state change is one line on stderr.
 *
 * Threading: one reader thread (owned by [LogcatReader]) does parse → correlate → store, exactly
 * as the panel does it, so a cache hit is waiting for the first read of any row. One scheduler
 * thread runs the reattach timer. Nothing here is called from the HTTP threads except
 * [isRunning] and [store].
 */
class Capture(
    private val options: Cli.ServeOptions,
    private val settings: KeyValueStore,
    private val log: Log,
) {

    val store = EventStore()
    private val parser = TransactionParser()
    val correlation = CorrelationIndex().apply { setKeys(CorrelationSettings.keys(settings)) }
    val workerLifecycle = WorkerLifecycle()

    /** Mock state and the device handshake. Always constructed — the handshake and hit counts are
     *  *reads* the MCP tools want even in read-only mode; only the writes are gated (see [Session]). */
    val mocks = MocksController(settings)

    val push = PushController(
        packageName = { mocks.deviceState().pkg },
        deviceSerial = { mocks.deviceSerial },
        libVersion = { mocks.deviceLibVersion() },
    )

    private val active = AtomicBoolean(false)
    @Volatile private var reader: LogcatReader? = null
    @Volatile private var attachedSerial: String? = null
    @Volatile private var sawAnyLine = false
    private var backoffMillis = MIN_BACKOFF_MS

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "logpose-daemon-reattach").apply { isDaemon = true }
        }

    /** True while logcat is being tailed — what the MCP tools report as `capture_running`. */
    fun isRunning(): Boolean = active.get() && reader?.isRunning() == true

    /** For `/health`: whether we have a live stream or are still looking for a device. */
    fun state(): String = if (isRunning()) "attached" else "waiting"

    fun start() {
        if (!active.compareAndSet(false, true)) return
        wireReverseChannel()
        mocks.onCaptureStarted()
        attach()
    }

    private fun wireReverseChannel() {
        parser.onControl = { msg ->
            // A hello is both a mock-sync signal and the only reliable marker of an app restart,
            // so the store sees it too — that is what keeps two launches from reading as one
            // timeline. Same order as the panel.
            if (msg is ControlMessage.DeviceHello) {
                store.noteHello(msg.hello.processId, msg.hello.pkg, msg.hello.libVersion)
            }
            mocks.onControl(msg)
        }
        mocks.onPushAck = { ack -> push.onAck(ack) }
        // The panel routes these to IDE balloons; a daemon has stderr, and that is the whole
        // difference — the controllers were already reporting through a lambda.
        mocks.onProblem = { title, detail -> log.warn("$title — $detail") }
        push.onProblem = { title, detail -> log.warn("$title — $detail") }
    }

    /**
     * Resolves a device and tails it. Called on the caller's thread at startup and on the
     * scheduler thread for every retry; never concurrently with itself, because a retry is only
     * armed from [LogcatReader]'s `onStopped`.
     */
    private fun attach() {
        if (!active.get()) return

        val ready = Adb.devices().filter { it.ready }

        // `--device` is not the plugin's saved preference, and must not behave like one.
        //
        // [DeviceChoice] is written for a serial remembered from a previous session: when that
        // device is gone it falls back to what *is* attached, silently when there is only one,
        // because a stale preference should never cost a user their capture. A serial typed on
        // this command line an instant ago is the opposite kind of claim — the caller named a
        // device, and quietly tailing a different one would hand a CI job another device's
        // verdicts. So the mismatch is refused here, before the choice is made, and retried like
        // any other absent device (the named one may still be booting).
        val wanted = options.device
        if (wanted != null && ready.isNotEmpty() && ready.none { it.serial == wanted }) {
            log.stateChange(
                "--device $wanted is not attached (adb reports ${ready.joinToString { it.serial }})" +
                    " — waiting for it, retrying in ${backoffMillis / 1000}s"
            )
            retryLater()
            return
        }

        val choice = DeviceChoice.choose(wanted, ready)
        choice.notice?.let { log.info(it) }

        if (ready.isEmpty()) {
            // Not fatal: a daemon started before the emulator boots should pick it up when it
            // arrives, and the MCP surface is already answering (with capture_running false).
            log.stateChange("no device attached — waiting (retrying in ${backoffMillis / 1000}s)")
            retryLater()
            return
        }

        val serial = choice.serial
        if (serial != attachedSerial || !sawAnyLine) {
            log.stateChange("attaching to ${serial ?: "the default device"}" + if (options.clear) " (clearing logcat)" else "")
        }
        attachedSerial = serial
        mocks.deviceSerial = serial

        val current = LogcatReader(deviceSerial = serial, clearOnStart = options.clear)
        reader = current
        current.start(
            onLine = { line ->
                if (!sawAnyLine) {
                    sawAnyLine = true
                    backoffMillis = MIN_BACKOFF_MS
                    log.stateChange("capture attached — receiving events")
                }
                parser.accept(line)?.let { event ->
                    // Correlation is computed here, on the reader thread, as the event arrives —
                    // before the store's listeners see it, exactly as in the tool window.
                    correlation.warm(event)
                    // The store is about to overwrite this worker's previous state in place, so
                    // the sequence is recorded here or nowhere.
                    if (event is LogEvent.Worker) workerLifecycle.note(event, System.currentTimeMillis())
                    store.add(event)
                }
            },
            onError = { msg -> log.warn("capture error: $msg") },
            onStopped = {
                // `active` is cleared by stop() before reader.stop(), so if it is still set the
                // stream ended on its own — a device drop, an app reinstall, an adb restart.
                if (active.get()) {
                    log.stateChange("logcat stream ended — reattaching in ${backoffMillis / 1000}s")
                    sawAnyLine = false
                    retryLater()
                }
            },
        )
    }

    private fun retryLater() {
        if (!active.get()) return
        val delay = backoffMillis
        backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MS)
        runCatching {
            scheduler.schedule({ if (active.get()) attach() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Stops tailing.
     *
     * [clearDeviceRules] is the panel's `onCaptureStopped` — the fail-safe that stops a forgotten
     * mock from outliving the session. It is passed false unless the daemon owns the mock channel:
     * that broadcast pushes an **empty rule set**, so a read-only daemon doing it on shutdown
     * would silently delete a running IDE's live mocks (PRD §7).
     */
    fun stop(clearDeviceRules: Boolean) {
        if (!active.compareAndSet(true, false)) return
        reader?.stop()
        reader = null
        if (clearDeviceRules) {
            log.info("clearing mock rules from the device")
            mocks.onCaptureStopped()
        }
        // Acks ride back on logcat, so a push still in flight can no longer be answered.
        push.reset()
        scheduler.shutdownNow()
    }

    private companion object {
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
