package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.advanced.MockDraft
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.advanced.PushDraft
import io.github.siddharthjaswal.logpose.desktop.correlation.Vocabulary
import io.github.siddharthjaswal.logpose.desktop.correlation.VocabularyLocation
import io.github.siddharthjaswal.logpose.desktop.session.CaptureHooks
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.PushController
import io.github.siddharthjaswal.logpose.mock.PushReplay
import io.github.siddharthjaswal.logpose.mock.ScenarioSnapshot
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.PushMessage
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A saved scenario as the strip lists it. */
data class ScenarioInfo(val name: String, val rules: Int, val note: String?)

/** What the mocks strip, the editor and the toolbar read. */
data class AdvancedUi(
    val advanced: Boolean = false,
    /** The device the mock-writer decision is about: the capturing one, else the selected one. */
    val serial: String? = null,
    val mode: MockWriter.Mode = MockWriter.Mode.HIDDEN,
    val rules: List<MockRule> = emptyList(),
    val device: MocksController.DeviceState? = null,
    val scenarios: List<ScenarioInfo> = emptyList(),
    val location: VocabularyLocation? = null,
    /** Open sheets. */
    val editor: MockEditorUi? = null,
    val compose: PushDraft? = null,
    val saveScenario: SaveScenarioUi? = null,
    val scenariosMenu: Boolean = false,
) {
    val writer: Boolean get() = mode == MockWriter.Mode.WRITER
}

data class MockEditorUi(val draft: MockDraft, val title: String, val baseBody: String?, val problem: String? = null)

data class SaveScenarioUi(val fromSession: Boolean, val name: String, val note: String = "", val successOnly: Boolean = false, val preview: String? = null, val problem: String? = null)

/**
 * The Advanced section (PRD §5.5): mocks, scenarios and push, wired over core's [MocksController] /
 * [PushController] / [ScenarioStore] exactly as the daemon's `Capture` wires them — plus the one rule
 * the daemon enforces with a flag and this app enforces with a per-device toggle: **one mock writer
 * per device** ([MockWriter]).
 *
 * Every rule edit, scenario read/write and push runs on [Dispatchers.IO]: the controller persists
 * to the settings file on each mutation and its broadcasts spawn adb, and none of that may happen
 * on the UI thread.
 */
class AdvancedController(
    private val scope: CoroutineScope,
    private val settings: KeyValueStore,
    private val appSupport: File,
    /** The capturing device if a session runs, else the selected one. */
    private val currentSerial: () -> String?,
    /** Whether a session is capturing [serial] right now. */
    private val capturing: (String) -> Boolean,
    private val say: (String, Boolean) -> Unit,
) : CaptureHooks {

    /** Rules, revision and the device's package live in the app settings file (one writer per Mac). */
    val mocks = MocksController(settings)

    val push = PushController(
        packageName = { mocks.deviceState().pkg },
        deviceSerial = { mocks.deviceSerial },
        libVersion = { mocks.deviceLibVersion() },
    )

    private val _ui = MutableStateFlow(AdvancedUi())
    val ui: StateFlow<AdvancedUi> = _ui.asStateFlow()

    @Volatile private var advanced = settings.getBoolean(MockWriter.ADVANCED_KEY, false)
    @Volatile private var writers = MockWriter.parse(settings.get(MockWriter.WRITERS_KEY))
    @Volatile var location: VocabularyLocation = Vocabulary.resolve(settings.get(Vocabulary.PROJECT_DIR_KEY), appSupport)
        private set

    /** Whether the controller is armed (pushes) for the session in progress. */
    @Volatile private var armed = false

    val scenarioStore: ScenarioStore get() = ScenarioStore(location.scenariosDir)

    init {
        mocks.addListener { refresh() }
        // Problems go to the notice strip; the plugin routes these to IDE balloons, the daemon to stderr.
        mocks.onProblem = { title, detail -> say("${title.removePrefix("LogPose: ")} — ${detail.lineSequence().first()}", true) }
        push.onProblem = { title, detail -> say("${title.removePrefix("LogPose: ")} — ${detail.lineSequence().first()}", true) }
        mocks.onPushAck = { ack -> push.onAck(ack) }
        refresh()
        reloadScenarios()
    }

    fun mode(serial: String? = currentSerial()): MockWriter.Mode = MockWriter.mode(advanced, writers, serial)

    /** Re-reads the current device and rule state into [ui]; cheap, any thread. */
    fun refresh() {
        val serial = currentSerial()
        _ui.update {
            it.copy(
                advanced = advanced, serial = serial, mode = mode(serial),
                rules = mocks.rules(), device = mocks.deviceState(), location = location,
            )
        }
    }

    // ---- toggles ----------------------------------------------------------------------------

    fun setAdvanced(on: Boolean) {
        if (on == advanced) return
        // Hiding the section must not leave a writer armed behind it: release first.
        if (!on) currentSerial()?.takeIf { it in writers && armed }?.let { release() }
        advanced = on
        scope.launch(Dispatchers.IO) { settings.setBoolean(MockWriter.ADVANCED_KEY, on, false) }
        refresh()
    }

    /**
     * Hands [serial]'s rule set to this app, or takes it back. Taking over mid-capture arms the
     * controller and pushes the app's rules at once; giving it up mid-capture clears them from the
     * phone (the same fail-safe as stopping), so the next writer starts from a clean device.
     */
    fun setWriter(serial: String, on: Boolean) {
        writers = if (on) writers + serial else writers - serial
        val serialized = MockWriter.serialize(writers)
        scope.launch(Dispatchers.IO) {
            settings.set(MockWriter.WRITERS_KEY, serialized)
            if (capturing(serial)) {
                if (on) arm(serial) else if (armed) release()
            }
        }
        refresh()
    }

    fun setProjectDir(dir: File?) {
        scope.launch(Dispatchers.IO) {
            settings.set(Vocabulary.PROJECT_DIR_KEY, dir?.absolutePath)
            location = Vocabulary.resolve(dir?.absolutePath, appSupport)
            refresh()
            reloadScenarios()
        }
    }

    // ---- capture hooks (UI / reader thread) ----------------------------------------------------

    override fun started(serial: String) {
        mocks.deviceSerial = serial
        if (MockWriter.armsDevice(mode(serial))) scope.launch(Dispatchers.IO) { arm(serial) }
        refresh()
    }

    /** Always ingested — reads, never writes: an unarmed controller cannot push, not even a retry. */
    override fun control(msg: ControlMessage) = mocks.onControl(msg)

    override fun ended(serial: String) {
        val clear = armed && MockWriter.clearsOnStop(mode(serial))
        scope.launch(Dispatchers.IO) {
            if (clear) release() else armed = false
            push.reset()
            refresh()
        }
    }

    private fun arm(serial: String) {
        mocks.deviceSerial = serial
        armed = true
        mocks.onCaptureStarted()
        // A device that already said hello this session won't say it again: push now.
        if (mocks.deviceState().helloSeen) mocks.pushNow()
        refresh()
    }

    /** The empty-set broadcast — only ever reached as the writer. */
    private fun release() {
        armed = false
        mocks.onCaptureStopped()
        refresh()
    }

    // ---- rules ----------------------------------------------------------------------------------

    private fun requireWriter(): Boolean {
        if (mode().let { it == MockWriter.Mode.WRITER }) return true
        say("Mocks are read-only for this phone — turn on \"This app controls mocks\" first.", true)
        return false
    }

    fun openMockEditor(tx: Transaction) {
        if (!requireWriter()) return
        _ui.update { it.copy(editor = MockEditorUi(MockDraft.fromTransaction(tx), "Mock endpoint", tx.response?.body?.text)) }
    }

    fun editRule(rule: MockRule) {
        if (!requireWriter()) return
        _ui.update { it.copy(editor = MockEditorUi(MockDraft.of(rule), "Edit mock rule", mocks.baseBodyFor(rule.id))) }
    }

    fun updateEditor(draft: MockDraft) = _ui.update { it.copy(editor = it.editor?.copy(draft = draft, problem = null)) }

    fun closeEditor() = _ui.update { it.copy(editor = null) }

    fun saveEditor() {
        val editor = _ui.value.editor ?: return
        val problem = editor.draft.problem()
        if (problem != null) { _ui.update { it.copy(editor = editor.copy(problem = problem)) }; return }
        if (!requireWriter()) return
        val rule = editor.draft.toRule()
        _ui.update { it.copy(editor = null) }
        scope.launch(Dispatchers.IO) { mocks.addOrUpdate(rule, editor.baseBody) }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) { if (requireWriter()) scope.launch(Dispatchers.IO) { mocks.setEnabled(id, enabled) } }

    fun deleteRule(id: String) { if (requireWriter()) scope.launch(Dispatchers.IO) { mocks.remove(id) } }

    fun disableAll() { if (requireWriter()) scope.launch(Dispatchers.IO) { mocks.disableAll() } }

    // ---- scenarios ------------------------------------------------------------------------------

    fun reloadScenarios() {
        scope.launch(Dispatchers.IO) {
            val infos = runCatching { scenarioStore.list().map { ScenarioInfo(it.name, it.rules.size, it.note) } }.getOrDefault(emptyList())
            _ui.update { it.copy(scenarios = infos) }
        }
    }

    fun toggleScenariosMenu(open: Boolean = !_ui.value.scenariosMenu) = _ui.update { it.copy(scenariosMenu = open) }

    fun openSaveScenario(fromSession: Boolean, events: () -> List<LogEvent>) {
        _ui.update { it.copy(scenariosMenu = false, saveScenario = SaveScenarioUi(fromSession, suggestName())) }
        if (fromSession) previewSnapshot(events)
    }

    fun updateSaveScenario(next: SaveScenarioUi, events: () -> List<LogEvent>) {
        val before = _ui.value.saveScenario
        _ui.update { it.copy(saveScenario = next.copy(problem = null)) }
        if (next.fromSession && before?.successOnly != next.successOnly) previewSnapshot(events)
    }

    private fun previewSnapshot(events: () -> List<LogEvent>) {
        scope.launch(Dispatchers.Default) {
            val ui = _ui.value.saveScenario ?: return@launch
            val summary = ScenarioSnapshot.fromEvents(events(), ui.successOnly).summary()
            _ui.update { s -> s.copy(saveScenario = s.saveScenario?.copy(preview = summary)) }
        }
    }

    fun closeSaveScenario() = _ui.update { it.copy(saveScenario = null) }

    fun commitSaveScenario(events: () -> List<LogEvent>) {
        val ui = _ui.value.saveScenario ?: return
        val name = ScenarioStore.sanitize(ui.name)
        if (name == null) { _ui.update { it.copy(saveScenario = ui.copy(problem = "Use letters, digits, - or _ (up to ${ScenarioStore.MAX_NAME}).")) }; return }
        scope.launch(Dispatchers.IO) {
            val snapshot = if (ui.fromSession) ScenarioSnapshot.fromEvents(events(), ui.successOnly) else null
            val rules = snapshot?.rules ?: mocks.rules()
            if (rules.isEmpty()) {
                _ui.update { s -> s.copy(saveScenario = ui.copy(problem = if (ui.fromSession) "Nothing to snapshot: no completed HTTP responses in the capture." else "There are no mock rules to save yet.")) }
                return@launch
            }
            val file = runCatching { scenarioStore.save(ScenarioStore.Scenario(name, System.currentTimeMillis(), ui.note.ifBlank { null }, rules)) }.getOrNull()
            _ui.update { it.copy(saveScenario = null) }
            if (file == null) say("Couldn't write ${location.scenarioPath(name)}.", true)
            else say("Scenario '$name' saved · ${rules.size} rule(s) → ${location.scenarioPath(name)}. It contains captured response bodies — review before sharing.", false)
            reloadScenarios()
        }
    }

    fun loadScenario(name: String, replace: Boolean) {
        _ui.update { it.copy(scenariosMenu = false) }
        if (!requireWriter()) return
        scope.launch(Dispatchers.IO) {
            val scenario = runCatching { scenarioStore.load(name) }.getOrNull()
            if (scenario == null) { say("Couldn't read ${location.scenarioPath(name)}.", true); reloadScenarios(); return@launch }
            if (replace) mocks.replaceAll(scenario.rules) else mocks.merge(scenario.rules)
            say(
                "Scenario '$name' · ${scenario.rules.size} rule(s) " + (if (replace) "replaced the active rules." else "merged into the active rules.") +
                    if (armed) " Pushing to the phone — the MOCKS dot turns green once it's acknowledged." else " Start a session to push them.",
                false,
            )
        }
    }

    fun deleteScenario(name: String) {
        _ui.update { it.copy(scenariosMenu = false) }
        scope.launch(Dispatchers.IO) { runCatching { scenarioStore.delete(name) }; reloadScenarios() }
    }

    private fun suggestName(): String {
        val taken = _ui.value.scenarios.map { it.name }.toSet()
        val base = "qa-" + java.text.SimpleDateFormat("MMdd-HHmm").format(java.util.Date())
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    // ---- push -----------------------------------------------------------------------------------

    /** Sends a captured push back to the app, verbatim but for a fresh id, send time and trace. */
    fun resendPush(msg: FcmMessage) {
        if (!requireWriter() || !PushReplay.canReplay(msg)) return
        scope.launch(Dispatchers.IO) {
            if (push.readyToInject() == null) return@launch
            sendPush(PushReplay.toMessage(msg, PushReplay.newId(), System.currentTimeMillis()))
        }
    }

    fun openCompose(seed: FcmMessage? = null) {
        if (!requireWriter()) return
        val message = seed?.let { PushReplay.toMessage(it, messageId = "", sentTimeMillis = 0).copy(messageId = null, sentTimeMillis = null) } ?: PushMessage()
        _ui.update { it.copy(compose = PushDraft.of(message)) }
    }

    fun updateCompose(draft: PushDraft) = _ui.update { it.copy(compose = draft) }

    fun closeCompose() = _ui.update { it.copy(compose = null) }

    fun sendCompose() {
        val draft = _ui.value.compose ?: return
        if (draft.problem() != null || !requireWriter()) return
        _ui.update { it.copy(compose = null) }
        scope.launch(Dispatchers.IO) { if (push.readyToInject() != null) sendPush(draft.toMessage()) }
    }

    private fun sendPush(message: PushMessage) {
        val stamped = message.copy(
            messageId = message.messageId?.takeIf { it.isNotBlank() } ?: PushReplay.newId(),
            sentTimeMillis = System.currentTimeMillis(),
        )
        push.injectPush(PushReplay.inject(stamped)) { outcome ->
            if (outcome != null && outcome.reachedApp) say("Push delivered (${outcome.delivered})", false)
        }
    }

    /**
     * Window closing. The coroutine scope is going away with the window, so a writer's fail-safe
     * clear can't wait for [ended]'s IO hop: it is started here, synchronously — the broadcast
     * itself still runs on the controller's own adb thread — before the session stops.
     */
    fun shutdown() {
        if (armed && MockWriter.clearsOnStop(mode())) release()
        armed = false
        push.reset()
    }
}
