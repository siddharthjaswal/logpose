package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The App actions as every host runs them — plugin, Mac app, daemon — so the three can't drift:
 * pick the target, run [AppControl] off the caller's thread, drop the [AppMarker] on the timeline at
 * the run boundary, wait for the relaunched app to announce its new run, and check what happened to
 * the mock rules.
 *
 * **Mocks after a restart.** `am force-stop` and `pm clear` both kill the process that held the
 * device's rules in memory (`MockRegistry`). Nothing special is needed to restore them: the new run's
 * `Hello` reports zero rules, and an armed [MocksController] answers that by re-pushing (see
 * `SyncState.onHello`). This class only *observes* that, so a host that is read-only on mocks still
 * reports the truth: it didn't push, the writer will.
 *
 * Also [McpTools.Apps], so `restart_app` / `clear_app_data` are this same code path.
 *
 * @param serial the device capture is on (or set to); null when there isn't one.
 * @param mocks the host's rule controller, for the after-restart report; null when it has none.
 * @param settings where the per-device typed package is remembered.
 * @param writable false on a host that must not change the device (see [McpTools.Apps.writable]).
 * @param record how a marker reaches the timeline — the store, plus the host's recording when it keeps one.
 */
class AppControlService(
    private val store: EventStore,
    private val serial: () -> String?,
    private val mocks: MocksController? = null,
    private val settings: KeyValueStore? = null,
    private val writable: () -> Boolean = { true },
    private val record: (LogEvent) -> Unit = { store.add(it) },
    private val control: AppControl = AppControl(),
    private val runAsync: (Runnable) -> Unit = { task -> Thread(task, "logpose-app-action").apply { isDaemon = true }.start() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How long to look for the mock re-push's acknowledgement once the new run said hello. */
    private val mockAckMillis: Long = 5_000,
) : McpTools.Apps {

    /** What happened to the device's mock rules across the restart. */
    data class MocksAfter(
        /** This host is the device's rule writer (armed) — so it is the one that re-pushes. */
        val writer: Boolean,
        val activeRules: Int,
        /** The writer re-pushed its rules to the new run (its hello reported them lost). */
        val rePushed: Boolean,
        /** The device acknowledged the re-pushed set within the short wait. */
        val acknowledged: Boolean,
    )

    data class Outcome(
        val report: AppControl.Report,
        /** The timeline marker's id, when one was recorded. */
        val markerId: String? = null,
        /** The relaunched app's run, once it announced itself; null when it didn't within the wait. */
        val newRun: EventStore.Session? = null,
        val waitedMillis: Long = 0,
        val mocks: MocksAfter? = null,
        /** Set when the action never started (another was still running). */
        val busy: Boolean = false,
    ) {
        /** True when it did what was asked. */
        val ok: Boolean get() = report.ok && !busy

        /** One or two sentences for a notification or a status strip — the same words in every host. */
        val summary: String
            get() {
                val r = report
                if (busy) return r.error ?: "Another app action is still running."
                if (r.error != null) return r.error
                return buildList {
                    add(
                        when (r.action) {
                            AppAction.RESTART -> "Restarted ${r.pkg}."
                            AppAction.CLEAR_CACHE -> "Cleared ${r.pkg}'s cache and restarted it."
                            AppAction.CLEAR_DATA -> "Cleared all of ${r.pkg}'s data and restarted it."
                        },
                    )
                    if (r.regranted.isNotEmpty()) add("Re-granted ${r.regranted.size} permission(s).")
                    if (r.regrantFailed.isNotEmpty()) add("Couldn't re-grant ${r.regrantFailed.joinToString()}.")
                    add(newRun?.let { "New run #${it.index}." } ?: "It hasn't announced a new run yet.")
                    mocks?.let { m ->
                        if (m.rePushed) add(if (m.acknowledged) "Mocks re-pushed (${m.activeRules})." else "Mocks re-pushed, not yet acknowledged.")
                    }
                }.joinToString(" ")
            }
    }

    private val running = AtomicBoolean(false)

    /** True while an action is in progress — the hosts grey their menus out. */
    val busy: Boolean get() = running.get()

    override fun writable(): Boolean = writable.invoke()

    override fun serial(): String? = serial.invoke()

    /** The packages this capture saw announced, newest run first, plus the remembered one. Cheap — no adb. */
    override fun target(explicit: String?): AppTarget.Choice =
        AppTarget.choose(AppTarget.announced(store.sessions()), AppTarget.remembered(settings, serial()), explicit)

    /** Remembers [pkg] for the current device — what the user typed or picked. */
    fun remember(pkg: String) = AppTarget.remember(settings, serial(), pkg)

    override fun perform(
        action: AppAction,
        pkg: String,
        options: AppControl.Options,
        awaitRunMillis: Long,
        progress: (String) -> Unit,
        onResult: (Outcome) -> Unit,
    ) {
        val device = serial()
        if (device == null) {
            onResult(Outcome(AppControl.Report(action, "", pkg, emptyList(), AppControl.Failure.DEVICE_NOT_FOUND,
                "No device is selected — pick one (or attach capture) first.")))
            return
        }
        if (!running.compareAndSet(false, true)) {
            onResult(Outcome(AppControl.Report(action, device, pkg, emptyList(), null, "Another app action is still running."), busy = true))
            return
        }
        val beforeRun = store.sessions().maxOfOrNull { it.index } ?: 0
        val armedBefore = mocks?.deviceState()?.capturing == true
        val task = Runnable {
            try {
                onResult(execute(action, device, pkg, options, awaitRunMillis, beforeRun, armedBefore, progress))
            } catch (t: Throwable) {
                onResult(Outcome(AppControl.Report(action, device, pkg, emptyList(), AppControl.Failure.TIMEOUT,
                    "LogPose couldn't run the action: ${t.message ?: t}")))
            } finally {
                running.set(false)
            }
        }
        runCatching { runAsync(task) }.onFailure { running.set(false); throw it }
    }

    private fun execute(
        action: AppAction,
        device: String,
        pkg: String,
        options: AppControl.Options,
        awaitRunMillis: Long,
        beforeRun: Int,
        armedBefore: Boolean,
        progress: (String) -> Unit,
    ): Outcome {
        var markerId: String? = null
        val report = control.run(action, device, pkg, options, progress) { soFar ->
            val marker = AppMarker.of(soFar, clock())
            markerId = marker.id
            runCatching { record(marker) }
        }
        if (report.ok || report.launched) AppTarget.remember(settings, device, pkg)
        if (!report.launched) return Outcome(report, markerId)

        progress("Waiting for $pkg to announce itself…")
        val started = clock()
        val newRun = if (awaitRunMillis <= 0) null else runCatching {
            store.awaitSession(awaitRunMillis) { it.index > beforeRun && it.pkg == pkg }
                .get(awaitRunMillis + 1_000, TimeUnit.MILLISECONDS)
        }.getOrNull()
        val waited = clock() - started
        return Outcome(report, markerId, newRun, waited, mocksAfter(newRun, armedBefore))
    }

    private fun mocksAfter(newRun: EventStore.Session?, armedBefore: Boolean): MocksAfter? {
        val c = mocks ?: return null
        val writer = armedBefore || c.deviceState().capturing
        val active = c.activeCount()
        if (!writer || active == 0 || newRun == null) return MocksAfter(writer, active, rePushed = false, acknowledged = false)
        // The hello that re-pushes reaches the controller beside the store, not after it: give the
        // ack a moment instead of reading the old process's "synced" as the new one's.
        val deadline = clock() + mockAckMillis
        var acknowledged = false
        Thread.sleep(SETTLE_MILLIS)
        while (clock() < deadline) {
            val sync = c.deviceState().sync
            if (sync.phase == SyncState.Phase.SYNCED && sync.deviceRules == sync.expectedRules) { acknowledged = true; break }
            if (sync.phase == SyncState.Phase.FAILED) break
            Thread.sleep(POLL_MILLIS)
        }
        return MocksAfter(writer, active, rePushed = true, acknowledged = acknowledged)
    }

    private companion object {
        const val SETTLE_MILLIS = 400L
        const val POLL_MILLIS = 150L
    }
}
