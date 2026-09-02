package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.mock.DeviceCapability
import io.github.siddharthjaswal.logpose.mock.DeviceFeature
import io.github.siddharthjaswal.logpose.mock.ScenarioSnapshot
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.PushInject
import java.util.concurrent.ExecutorService

/**
 * The daemon's half of the MCP contract: one [McpSessions.Session] built out of a [Capture], plus
 * the four `McpTools` implementations the tool window keeps as inner classes.
 *
 * These are the panel's implementations with two substitutions and nothing else:
 * `executeOnPooledThread` becomes an [ExecutorService], and the UI refresh at the end of a
 * scenario load becomes nothing (there is no strip to repaint). The behaviour a caller sees —
 * every hint string, every report field — is deliberately identical, because parity with the
 * plugin is the product.
 *
 * ### The single-writer gate
 *
 * Without `--mocks` this daemon must not touch the device's rule set (PRD §7) — but "can't write"
 * is not "can't answer". The gate is therefore drawn per tool, not per surface:
 *
 *  - `push` is **null**: every push tool is a write, so there is nothing to keep.
 *  - `mocks` and `scenarios` are present but report `writable() == false`. `list_mocks` answers
 *    from the device handshake this daemon ingests anyway, `list_scenarios` reads the
 *    `.logpose/scenarios` directory the IDE shares with it, and `save_scenario` writes a file
 *    there — none of the three goes near the single-writer channel. `create_mock`,
 *    `set_mock_enabled`, `delete_mock` and `load_scenario` decline.
 *
 * Either way the refusal is `McpTools`' own result shape, worded for this host by [Unavailable]:
 * an IDE session says "open the tool window", a daemon says "restart with --mocks".
 */
class DaemonSession(
    private val capture: Capture,
    private val options: Cli.ServeOptions,
    private val scenarioStore: ScenarioStore?,
    private val pool: ExecutorService,
    private val log: Log,
) {

    fun session(): McpSessions.Session = McpSessions.Session(
        projectName = options.projectName,
        store = capture.store,
        hostAgeMillis = { id -> capture.store.elapsedMillis(id) },
        exposeBodies = { options.exposeBodies },
        captureRunning = { capture.isRunning() },
        clearCapture = { capture.store.clear() },
        // The single-writer gate (PRD §7), drawn per tool: these two surfaces answer their read
        // tools always and decline their write tools unless --mocks was passed.
        mocks = DaemonMocks(),
        scenarios = scenarioStore?.let { DaemonScenarios(it) },
        // Every push tool is a write, so this surface is all-or-nothing.
        push = if (options.mocks) DaemonPush() else null,
        // Reads, so never gated: correlation groups a flow, it doesn't touch the device.
        correlations = DaemonCorrelations(),
        waits = McpTools.Waits { timeout, predicate -> capture.store.addWaiter(timeout, predicate) },
    )

    /** One capture, so the token is pure authentication — it no longer selects anything. */
    inner class Lookup(private val token: String, private val session: McpSessions.Session) :
        McpRpc.SessionLookup {
        override fun byToken(token: String): McpSessions.Session? =
            session.takeIf { constantTimeEquals(token, this.token) }

        override fun hasSessions(): Boolean = true
    }

    // ---- the four write/read surfaces ----------------------------------------------------------

    private inner class DaemonMocks : McpTools.Mocks {
        override fun writable() = options.mocks
        override fun list() = capture.mocks.rules()
        override fun hits() = capture.mocks.deviceState().hits
        override fun deviceHint(): String {
            val device = capture.mocks.deviceState()
            val name = device.pkg ?: "device"
            val withheld = if (device.withheldRules > 0)
                " · ${device.withheldRules} rule(s) withheld (need logpose-android ≥ " +
                    "${DeviceFeature.RICH_MATCHERS.since})"
            else ""
            if (!device.helloSeen) {
                return "waiting for the app to announce itself — restart the app (or start capture " +
                    "before launching it); needs logpose-android ≥ 1.1.0. Rules won't serve yet."
            }
            return when (device.sync.phase) {
                SyncState.Phase.FAILED ->
                    "$name · NOT synced: ${device.sync.message}. Rules may not be serving.$withheld"
                SyncState.Phase.PENDING ->
                    "$name · pushing rev ${device.sync.revision}, device has not acknowledged it yet$withheld"
                else -> "$name · synced rev ${device.syncedRevision}$withheld"
            }
        }
        override fun deviceReady() = capture.mocks.deviceState().helloSeen
        override fun deviceLibVersion() = capture.mocks.deviceLibVersion()
        override fun create(rule: MockRule, baseBody: String?) = capture.mocks.addOrUpdate(rule, baseBody)
        override fun setEnabled(id: String, enabled: Boolean) = capture.mocks.setEnabled(id, enabled)
        override fun delete(id: String) = capture.mocks.remove(id)
    }

    private inner class DaemonPush : McpTools.Push {
        override fun deviceHint(): String {
            val device = capture.mocks.deviceState()
            if (!device.helloSeen) return "waiting for the app to announce itself"
            return (device.pkg ?: "device") +
                (device.libVersion?.takeIf { it.isNotBlank() }?.let { " · logpose-android $it" } ?: "")
        }

        override fun notReady(): String? {
            val device = capture.mocks.deviceState()
            if (!device.helloSeen) {
                return "the app hasn't announced itself to this capture. The gate is the app→IDE " +
                    "handshake, not just capture: if the app was already running when capture " +
                    "started, RESTART IT (or start capture before launching it)."
            }
            if (!capture.push.deviceSupportsPush()) {
                return "the device's library is too old — push injection needs logpose-android ≥ " +
                    "${DeviceFeature.PUSH_INJECTION.since}" +
                    (device.libVersion?.takeIf { it.isNotBlank() }?.let { " (it reports $it)" } ?: "") +
                    ". An older library has no receiver for the command, so the push would be " +
                    "silently dropped."
            }
            return null
        }

        override fun inject(inject: PushInject, onAck: (McpTools.Push.Ack?) -> Unit) {
            // Off the HTTP thread before anything resolves adb or touches the filesystem.
            pool.execute {
                capture.push.injectPush(inject) { outcome ->
                    onAck(outcome?.let { McpTools.Push.Ack(it.delivered, it.error) })
                }
            }
        }
    }

    private inner class DaemonCorrelations : McpTools.Correlations {
        override fun keys(): List<CorrelationKey> = capture.correlation.keys()
        override fun textOf(event: LogEvent): String = capture.correlation.textOf(event)
        override fun valuesOf(event: LogEvent) = capture.correlation.valuesOf(event)

        /** The panel's `keyLabelFor`, verbatim: read the cache newest-first, and only scan what
         *  the reader thread hasn't warmed yet. */
        override fun keyLabelFor(events: List<LogEvent>, value: String): String? {
            val uncached = ArrayList<LogEvent>()
            for (index in events.indices.reversed()) {
                val cached = capture.correlation.cachedValues(events[index])
                if (cached == null) { uncached.add(events[index]); continue }
                cached.firstOrNull { it.value.equals(value, ignoreCase = true) }?.let { return it.key }
            }
            if (uncached.isEmpty()) return null
            return Correlation.keyLabelFor(uncached.asReversed(), capture.correlation.keys(), value)
        }

        override fun offThread(work: () -> Unit) {
            pool.execute(work)
        }
    }

    private inner class DaemonScenarios(private val scenarios: ScenarioStore) : McpTools.Scenarios {

        /** Gates `load_scenario` only — see the class comment; list and save touch files, not the
         *  device. */
        override fun writable() = options.mocks

        override fun list(onResult: (List<McpTools.Scenarios.Info>) -> Unit) = offThread {
            onResult(
                runCatching {
                    scenarios.list().map {
                        McpTools.Scenarios.Info(it.name, it.rules.size, it.createdAt, it.note)
                    }
                }.getOrDefault(emptyList())
            )
        }

        override fun load(
            name: String,
            replace: Boolean,
            onResult: (McpTools.Scenarios.LoadReport) -> Unit,
        ) = offThread {
            val scenario = runCatching { scenarios.load(name) }.getOrNull()
            if (scenario == null) {
                onResult(McpTools.Scenarios.LoadReport(name, found = false))
                return@offThread
            }
            if (replace) capture.mocks.replaceAll(scenario.rules) else capture.mocks.merge(scenario.rules)
            val device = capture.mocks.deviceState()
            val withheld = scenario.rules.count {
                it.enabled && !DeviceCapability.canPush(it, device.libVersion)
            }
            onResult(
                McpTools.Scenarios.LoadReport(
                    name = name,
                    found = true,
                    rules = scenario.rules.size,
                    replaced = replace,
                    activeRules = capture.mocks.activeCount(),
                    deviceHint = DaemonMocks().deviceHint(),
                    live = device.capturing,
                    withheld = withheld,
                )
            )
        }

        override fun save(
            name: String,
            note: String?,
            fromSession: Boolean,
            successOnly: Boolean,
            onResult: (McpTools.Scenarios.SaveReport) -> Unit,
        ) = offThread {
            // Snapshot semantics are the shared ones: rows LogPose itself served are always
            // skipped, so a caller can't bottle mocked output and pass it off as the backend's.
            val snapshot = if (fromSession) {
                ScenarioSnapshot.fromEvents(capture.store.snapshot(), successOnly)
            } else null
            val rules = snapshot?.rules ?: capture.mocks.rules()
            if (rules.isEmpty()) {
                onResult(
                    McpTools.Scenarios.SaveReport(
                        name, error = if (fromSession)
                            "Nothing to snapshot: no completed HTTP responses in the capture to " +
                                "build rules from. ${snapshot?.summary().orEmpty()}"
                        else
                            "There are no mock rules to save. Create one with create_mock, or " +
                                "save from='session' to bottle the capture instead.",
                    )
                )
                return@offThread
            }
            val file = runCatching {
                scenarios.save(ScenarioStore.Scenario(name, System.currentTimeMillis(), note, rules))
            }.getOrNull()
            if (file == null) log.warn("save_scenario '$name': nothing written")
            onResult(
                if (file == null) McpTools.Scenarios.SaveReport(
                    name, error = "Could not write ${ScenarioStore.REL_DIR}/$name.json.",
                ) else McpTools.Scenarios.SaveReport(
                    name = name,
                    rules = rules.size,
                    path = "${ScenarioStore.REL_DIR}/$name.json",
                    detail = snapshot?.summary(),
                )
            )
        }

        private fun offThread(work: () -> Unit) {
            pool.execute(work)
        }
    }

    companion object {
        /** The daemon's not-available texts: same wire shape as the IDE's, different fix — there
         *  is no tool window to open, so they name the flag that would grant the write. */
        val Unavailable = McpTools.Unavailable(
            mocks = "Mocking isn't writable — this LogPose daemon is running read-only. Restart it " +
                "with --mocks to let it write rules. Only ONE process may: the device holds a " +
                "single rule set, so a daemon and an IDE writing it together overwrite each " +
                "other. list_mocks still reads.",
            waits = "Waiting isn't available — this LogPose daemon has no capture wired. Check its " +
                "stderr.",
            push = "Push injection isn't available — this LogPose daemon is running read-only. " +
                "Restart it with --mocks to let it deliver injected pushes (only one process may " +
                "write to a device).",
            scenarios = "Scenarios can't be loaded — this LogPose daemon is running read-only. " +
                "Restart it with --mocks to let load_scenario push a scenario's rules to the " +
                "device. list_scenarios and save_scenario work either way; they read and write " +
                ".logpose/scenarios, which is shared with the IDE.",
            correlations = "Correlation isn't available — this LogPose daemon has no capture " +
                "wired. Check its stderr.",
        )

        /** The daemon's 401 texts: same wire shape as the IDE's, different fix — there is no tool
         *  window to open, so they name the flag and the startup line instead. */
        val AuthHint = McpRpc.AuthHint(
            missingToken =
                "Missing or unknown ${McpRpc.TOKEN_HEADER}. The LogPose daemon prints the exact " +
                    "`claude mcp add` line (token included) when it starts; re-run it with " +
                    "--token to pin a token of your own.",
            noCapture =
                "This LogPose daemon has no capture registered — it is still starting up, or it " +
                    "failed to start. Check its stderr.",
        )

        /** Length-independent enough for a 32-hex token, and constant-time over equal lengths. */
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
