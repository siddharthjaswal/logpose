package io.github.siddharthjaswal.logpose.desktop.mcp

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.desktop.advanced.McpSurfaces
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedController
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.mcp.McpHttpTransport
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
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** The Serve MCP panel's state. */
data class McpUi(
    val serving: Boolean = false,
    val port: Int? = null,
    val token: String = "",
    /** Set when the preferred port was taken and another was chosen — said out loud. */
    val portNote: String? = null,
    val error: String? = null,
    val readOnly: Boolean = true,
) {
    val command: String? get() = port?.let { McpHttpTransport.connectCommand(it, token) }
}

/**
 * *Serve MCP* (PRD §7): the app's live capture over MCP on 127.0.0.1, so a developer can point
 * Claude Code at a QA tester's session while they test — no IDE, no daemon.
 *
 * It is the daemon's arrangement, hosted in the app: core's [McpRpc] for the JSON-RPC, core's
 * [McpHttpTransport] (the daemon's transport, moved to `:core` for exactly this) for the bytes,
 * and an [McpSessions.Session] built over this window's [CaptureSession]. Two differences:
 *
 *  - **The port is a preference, not a demand.** 63343 is the daemon's port too; when a daemon (or
 *    a second app) holds it, the next free one is taken and the panel says which — the connect
 *    line always carries the port that actually bound.
 *  - **The surfaces follow the mock-writer state live** ([McpSurfaces]): the session is rebuilt per
 *    call from the current [MockWriter.Mode], so handing the phone to the app (or taking it back)
 *    changes what `create_mock` / `inject_fcm` may do on the very next request — without a restart
 *    and without the agent reconnecting.
 */
class DesktopMcp(
    private val settings: KeyValueStore,
    private val session: CaptureSession,
    private val advanced: AdvancedController,
    private val projectName: () -> String,
    private val warn: (String) -> Unit = { System.err.println("[logpose-mcp] $it") },
    /** `list_devices` / `attach_device`, over the window's device menu and Start (AppModel). */
    private val devices: McpTools.Devices? = null,
) {
    private val _ui = MutableStateFlow(McpUi())
    val ui: StateFlow<McpUi> = _ui.asStateFlow()

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "logpose-mcp-work").apply { isDaemon = true } }
    private var transport: McpHttpTransport? = null

    val token: String by lazy { McpSessions.tokenFor(settings) }

    /** Binds (off the UI thread — the caller's job) and starts serving. Returns the bound port. */
    @Synchronized
    fun start(preferred: Int = McpHttpTransport.DEFAULT_PORT): Int? {
        transport?.let { return _ui.value.port }
        val rpc = McpRpc(sessions = Lookup(), hint = AuthHint, unavailable = Unavailable)
        val won = runCatching {
            McpHttpTransport.firstFree(McpHttpTransport.candidatePorts(preferred)) { port ->
                McpHttpTransport(port, rpc, warn, ::health).also { it.start() }
            }
        }.getOrElse { e ->
            _ui.value = McpUi(token = token, error = "Couldn't start: ${e.message ?: e}")
            return null
        }
        if (won == null) {
            _ui.value = McpUi(token = token, error = "Ports $preferred–${preferred + 9} are all taken. Stop another LogPose server and try again.")
            return null
        }
        transport = won.second
        settings.setBoolean(SERVE_KEY, true, false)
        _ui.value = McpUi(
            serving = true, port = won.first, token = token,
            portNote = if (won.first != preferred) "Port $preferred is taken (a logpose daemon?), so this app is on ${won.first}." else null,
            readOnly = !McpSurfaces.of(advanced.mode()).mocksWritable,
        )
        return won.first
    }

    @Synchronized
    fun stop(remember: Boolean = true) {
        transport?.stop()
        transport = null
        if (remember) settings.setBoolean(SERVE_KEY, false, false)
        _ui.value = McpUi(token = token)
    }

    /** Whether the user left Serve MCP on last time — the app restarts it at launch. */
    fun wasServing(): Boolean = settings.getBoolean(SERVE_KEY, false)

    /** Re-derives the read-only flag the panel shows after a mock-writer change. */
    fun refresh() {
        val ui = _ui.value
        if (ui.serving) _ui.value = ui.copy(readOnly = !McpSurfaces.of(advanced.mode()).mocksWritable)
    }

    private fun health(): McpHttpTransport.Health = McpHttpTransport.Health(
        session.store.snapshot().size,
        if (session.state.value is SessionState.Running) "attached" else "waiting",
    )

    /** The session for this request, with surfaces from the mock-writer state right now. */
    fun currentSession(): McpSessions.Session = sessionFor(McpSurfaces.of(advanced.mode()))

    fun sessionFor(surfaces: McpSurfaces): McpSessions.Session = McpSessions.Session(
        projectName = projectName(),
        store = session.store,
        hostAgeMillis = { id -> session.store.elapsedMillis(id) },
        exposeBodies = { McpSessions.exposeBodies(settings) },
        captureRunning = { session.state.value is SessionState.Running },
        clearCapture = { session.store.clear() },
        mocks = Mocks(surfaces.mocksWritable),
        scenarios = Scenarios(surfaces.scenariosLoadable),
        push = if (surfaces.push) Push() else null,
        correlations = Correlations(),
        waits = McpTools.Waits { timeout, predicate -> session.store.addWaiter(timeout, predicate) },
        workerTransitions = McpTools.WorkerTransitions { session.workerLifecycle.transitions(session.workerLifecycle.keyOf(it)) },
        // Offered read-only too: attaching changes what the app records; only the writer re-pushes.
        devices = devices,
    )

    /** One capture, so the token is pure authentication. */
    private inner class Lookup : McpRpc.SessionLookup {
        override fun byToken(token: String): McpSessions.Session? =
            if (constantTimeEquals(token, this@DesktopMcp.token)) currentSession() else null

        override fun hasSessions(): Boolean = true
    }

    // ---- surfaces: the daemon's, over the app's controllers ------------------------------------

    private inner class Mocks(private val writable: Boolean) : McpTools.Mocks {
        private val c get() = advanced.mocks
        override fun writable() = writable
        override fun list() = c.rules()
        override fun hits() = c.deviceState().hits
        override fun deviceHint(): String {
            val device = c.deviceState()
            val name = device.pkg ?: "device"
            val withheld = if (device.withheldRules > 0)
                " · ${device.withheldRules} rule(s) withheld (need logpose-android ≥ ${DeviceFeature.RICH_MATCHERS.since})" else ""
            if (!device.helloSeen) {
                return "waiting for the app to announce itself — restart the app (or start the session " +
                    "before launching it); needs logpose-android ≥ 1.1.0. Rules won't serve yet."
            }
            return when (device.sync.phase) {
                SyncState.Phase.FAILED -> "$name · NOT synced: ${device.sync.message}. Rules may not be serving.$withheld"
                SyncState.Phase.PENDING -> "$name · pushing rev ${device.sync.revision}, device has not acknowledged it yet$withheld"
                else -> "$name · synced rev ${device.syncedRevision}$withheld"
            }
        }
        override fun deviceReady() = c.deviceState().helloSeen
        override fun deviceLibVersion() = c.deviceLibVersion()
        override fun create(rule: MockRule, baseBody: String?) = c.addOrUpdate(rule, baseBody)
        override fun setEnabled(id: String, enabled: Boolean) = c.setEnabled(id, enabled)
        override fun delete(id: String) = c.remove(id)
    }

    private inner class Push : McpTools.Push {
        private val c get() = advanced.mocks
        override fun deviceHint(): String {
            val device = c.deviceState()
            if (!device.helloSeen) return "waiting for the app to announce itself"
            return (device.pkg ?: "device") + (device.libVersion?.takeIf { it.isNotBlank() }?.let { " · logpose-android $it" } ?: "")
        }

        override fun notReady(): String? {
            val device = c.deviceState()
            if (!device.helloSeen) {
                return "the app hasn't announced itself to this capture. If the app was already running when " +
                    "the session started, RESTART IT (or start the session before launching it)."
            }
            if (!advanced.push.deviceSupportsPush()) {
                return "the device's library is too old — push injection needs logpose-android ≥ " +
                    "${DeviceFeature.PUSH_INJECTION.since}" +
                    (device.libVersion?.takeIf { it.isNotBlank() }?.let { " (it reports $it)" } ?: "") + "."
            }
            return null
        }

        override fun inject(inject: PushInject, onAck: (McpTools.Push.Ack?) -> Unit) {
            pool.execute {
                advanced.push.injectPush(inject) { outcome -> onAck(outcome?.let { McpTools.Push.Ack(it.delivered, it.error) }) }
            }
        }
    }

    private inner class Correlations : McpTools.Correlations {
        private val index get() = session.correlation
        override fun keys(): List<CorrelationKey> = index.keys()
        override fun textOf(event: LogEvent): String = index.textOf(event)
        override fun valuesOf(event: LogEvent) = index.valuesOf(event)

        /** The panel's `keyLabelFor`: the cache newest-first, scanning only what isn't warmed. */
        override fun keyLabelFor(events: List<LogEvent>, value: String): String? {
            val uncached = ArrayList<LogEvent>()
            for (i in events.indices.reversed()) {
                val cached = index.cachedValues(events[i])
                if (cached == null) { uncached.add(events[i]); continue }
                cached.firstOrNull { it.value.equals(value, ignoreCase = true) }?.let { return it.key }
            }
            if (uncached.isEmpty()) return null
            return Correlation.keyLabelFor(uncached.asReversed(), index.keys(), value)
        }

        override fun offThread(work: () -> Unit) = pool.execute(work)
    }

    private inner class Scenarios(private val loadable: Boolean) : McpTools.Scenarios {
        private val store: ScenarioStore get() = advanced.scenarioStore

        override fun writable() = loadable

        override fun list(onResult: (List<McpTools.Scenarios.Info>) -> Unit) = pool.execute {
            onResult(runCatching { store.list().map { McpTools.Scenarios.Info(it.name, it.rules.size, it.createdAt, it.note) } }.getOrDefault(emptyList()))
        }

        override fun load(name: String, replace: Boolean, onResult: (McpTools.Scenarios.LoadReport) -> Unit) = pool.execute {
            val scenario = runCatching { store.load(name) }.getOrNull()
            if (scenario == null) { onResult(McpTools.Scenarios.LoadReport(name, found = false)); return@execute }
            val c = advanced.mocks
            if (replace) c.replaceAll(scenario.rules) else c.merge(scenario.rules)
            val device = c.deviceState()
            onResult(
                McpTools.Scenarios.LoadReport(
                    name = name, found = true, rules = scenario.rules.size, replaced = replace,
                    activeRules = c.activeCount(), deviceHint = Mocks(true).deviceHint(), live = device.capturing,
                    withheld = scenario.rules.count { it.enabled && !DeviceCapability.canPush(it, device.libVersion) },
                ),
            )
            advanced.reloadScenarios()
        }

        override fun save(
            name: String,
            note: String?,
            fromSession: Boolean,
            successOnly: Boolean,
            onResult: (McpTools.Scenarios.SaveReport) -> Unit,
        ) = pool.execute {
            val snapshot = if (fromSession) ScenarioSnapshot.fromEvents(session.store.snapshot(), successOnly) else null
            val rules = snapshot?.rules ?: advanced.mocks.rules()
            if (rules.isEmpty()) {
                onResult(
                    McpTools.Scenarios.SaveReport(
                        name, error = if (fromSession) "Nothing to snapshot: no completed HTTP responses in the capture to build rules from. ${snapshot?.summary().orEmpty()}"
                        else "There are no mock rules to save. Create one with create_mock, or save from='session' to bottle the capture instead.",
                    ),
                )
                return@execute
            }
            val path = advanced.location.scenarioPath(name)
            val file = runCatching { store.save(ScenarioStore.Scenario(name, System.currentTimeMillis(), note, rules)) }.getOrNull()
            onResult(
                if (file == null) McpTools.Scenarios.SaveReport(name, error = "Could not write $path.")
                else McpTools.Scenarios.SaveReport(name = name, rules = rules.size, path = path, detail = snapshot?.summary()),
            )
            advanced.reloadScenarios()
        }
    }

    companion object {
        const val SERVE_KEY = "logpose.desktop.mcp.serve"

        val Unavailable = McpTools.Unavailable(
            mocks = "Mocking isn't writable — LogPose for Mac is read-only on this phone's mocks. In the app, turn on " +
                "Advanced and \"This app controls mocks\" for the device. Only ONE program may write a phone's rules: " +
                "an IDE and the app writing together overwrite each other. list_mocks still reads.",
            waits = "Waiting isn't available — LogPose for Mac has no capture wired.",
            push = "Push injection isn't available — LogPose for Mac is read-only on this phone. In the app, turn on " +
                "Advanced and \"This app controls mocks\" for the device to let it deliver injected pushes.",
            scenarios = "Scenarios can't be loaded — LogPose for Mac is read-only on this phone's mocks. Turn on " +
                "\"This app controls mocks\" in the app's Advanced section. list_scenarios and save_scenario work either way.",
            correlations = "Correlation isn't available — LogPose for Mac has no capture wired.",
            devices = "Re-attaching isn't available — LogPose for Mac has no capture wired. Pick the device and " +
                "press Start in the app.",
        )

        val AuthHint = McpRpc.AuthHint(
            missingToken = "Missing or unknown ${McpRpc.TOKEN_HEADER}. Copy the connect line (token included) from " +
                "LogPose for Mac → Serve MCP.",
            noCapture = "LogPose for Mac has no capture registered.",
        )

        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
