package io.github.siddharthjaswal.logpose.mock

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockRuleSet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the mock rule set on the IDE side and keeps the device in sync over the reverse
 * channel (adb broadcast → [MockCommandReceiver]). Rules persist per project; the device is
 * only ever pushed to while capture is live, and is cleared when capture stops.
 *
 * Threading: rule mutations happen on the EDT (UI), device control messages arrive on the
 * reader thread. State access is synchronized; every adb invocation runs on a short-lived
 * daemon thread — never the EDT (the project's hard rule).
 */
class MocksController(private val project: Project) {

    /** Snapshot of what the device reported, for the UI's sync indicator. */
    data class DeviceState(
        val pkg: String?,
        val libVersion: String?,
        val helloSeen: Boolean,
        val syncedRevision: Int,
        val hits: Map<String, Int>,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val props = PropertiesComponent.getInstance(project)

    // Rules are newest-first: the device matches first-in-list, so a newer rule for the same
    // endpoint overrides an older one.
    private val rules = mutableListOf<MockRule>()
    // ruleId -> the captured response the rule was seeded from, so the field-tree editor can be
    // rebuilt when editing later. Plugin-side only; never pushed to the device.
    private val baseBodies = mutableMapOf<String, String>()
    private var revision: Int = props.getInt(KEY_REVISION, 0)

    private var pkg: String? = props.getValue(KEY_PKG)
    private var libVersion: String? = null
    private var helloSeen = false
    private var syncedRevision = -1
    private var hits: Map<String, Int> = emptyMap()

    /** True while logcat capture is running — gates all device pushes. */
    private val live = AtomicBoolean(false)

    /** Device to target; null = default adb device. (Device picker will set this later.) */
    @Volatile var deviceSerial: String? = null

    private val listeners = mutableListOf<() -> Unit>()

    init {
        loadRules()
        loadBaseBodies()
    }

    // ---- Rule CRUD (EDT) ------------------------------------------------------------------

    @Synchronized fun rules(): List<MockRule> = rules.toList()

    @Synchronized fun activeCount(): Int = rules.count { it.enabled }

    /** The captured response a rule was seeded from, if known (for the field-tree editor). */
    @Synchronized fun baseBodyFor(id: String): String? = baseBodies[id]

    /** Adds a new rule (newest-first) or replaces an existing one with the same id. */
    fun addOrUpdate(rule: MockRule, baseBody: String? = null) {
        if (baseBody != null) synchronized(this) { baseBodies[rule.id] = baseBody }
        mutate {
            val i = rules.indexOfFirst { it.id == rule.id }
            if (i >= 0) rules[i] = rule else rules.add(0, rule)
        }
        persistBaseBodies()
    }

    fun remove(id: String) {
        synchronized(this) { baseBodies.remove(id) }
        mutate { rules.removeAll { it.id == id } }
        persistBaseBodies()
    }

    fun setEnabled(id: String, enabled: Boolean) = mutate {
        val i = rules.indexOfFirst { it.id == id }
        if (i >= 0) rules[i] = rules[i].copy(enabled = enabled)
    }

    fun disableAll() = mutate { for (i in rules.indices) rules[i] = rules[i].copy(enabled = false) }

    // ---- Capture lifecycle ----------------------------------------------------------------

    /** Called when capture starts: rules will be pushed once the device says Hello. */
    fun onCaptureStarted() {
        live.set(true)
    }

    /** Called when capture stops (or the panel disposes): clear the device, keep local rules. */
    fun onCaptureStopped() {
        live.set(false)
        val target = pkg ?: return
        // Bump revision so the empty set supersedes whatever the device holds.
        val rev = synchronized(this) { ++revision }
        persistRevision()
        broadcast(target, MockRuleSet(revision = rev, rules = emptyList()))
    }

    // ---- Reverse channel (reader thread) --------------------------------------------------

    fun onControl(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.DeviceHello -> onHello(msg.hello)
            is ControlMessage.MockApplied -> onAck(msg.ack)
        }
    }

    private fun onHello(hello: Hello) {
        synchronized(this) {
            pkg = hello.pkg
            libVersion = hello.libVersion
            helloSeen = true
        }
        props.setValue(KEY_PKG, hello.pkg)
        // A restarted app reports mockRevision 0 (its registry was wiped) — re-push if we hold
        // rules the device is missing.
        if (live.get() && hello.mockRevision < revision && rules().any { it.enabled }) pushNow()
        notifyChanged()
    }

    private fun onAck(ack: MockAck) {
        synchronized(this) {
            // An ack is proof the device is alive and running ≥ 1.1.0 — even stronger than a
            // Hello, which only fires once per process and can predate capture. So mark the
            // device confirmed here too; otherwise the bar reads "waiting" while mocks work.
            pkg = ack.pkg
            helloSeen = true
            syncedRevision = ack.revision
            hits = ack.hits
        }
        props.setValue(KEY_PKG, ack.pkg)
        notifyChanged()
    }

    // ---- Push -----------------------------------------------------------------------------

    /** Serializes the current rule set and pushes it to the device (off the EDT). */
    fun pushNow() {
        val target = pkg ?: return
        val snapshot = synchronized(this) { MockRuleSet(revision = revision, rules = rules.toList()) }
        broadcast(target, snapshot)
    }

    private fun broadcast(target: String, ruleSet: MockRuleSet) {
        val adb = Adb.resolve() ?: return
        val payload = Base64.getEncoder()
            .encodeToString(json.encodeToString(MockRuleSet.serializer(), ruleSet).toByteArray(Charsets.UTF_8))
        val slices = payload.chunked(SLICE_CHARS)
        val total = slices.size

        Thread({
            slices.forEachIndexed { seq, slice ->
                val cmd = Adb.baseCmd(adb, deviceSerial) + listOf(
                    "shell", "am", "broadcast",
                    "-n", "$target/$RECEIVER",
                    "-f", FLAG_INCLUDE_STOPPED_PACKAGES,
                    "--ei", "rev", ruleSet.revision.toString(),
                    "--ei", "seq", seq.toString(),
                    "--ei", "total", total.toString(),
                    "--es", "payload", slice,
                )
                runCatching {
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }, "logpose-mock-push").apply { isDaemon = true }.start()
    }

    // ---- UI plumbing ----------------------------------------------------------------------

    @Synchronized
    fun deviceState() = DeviceState(pkg, libVersion, helloSeen, syncedRevision, hits)

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyChanged() = listeners.forEach { it() }

    /** Applies [change], persists, pushes if live, and notifies the UI. */
    private inline fun mutate(change: () -> Unit) {
        synchronized(this) {
            change()
            revision++
        }
        persistRules()
        persistRevision()
        if (live.get()) pushNow()
        notifyChanged()
    }

    private fun loadRules() {
        val raw = props.getValue(KEY_RULES) ?: return
        runCatching {
            json.decodeFromString(ListSerializer(MockRule.serializer()), raw)
        }.getOrNull()?.let { rules.addAll(it) }
    }

    private fun persistRules() {
        props.setValue(KEY_RULES, json.encodeToString(ListSerializer(MockRule.serializer()), rules.toList()))
    }

    private fun persistRevision() = props.setValue(KEY_REVISION, revision, 0)

    private fun persistBaseBodies() {
        val serializer = MapSerializer(String.serializer(), String.serializer())
        props.setValue(KEY_BASES, json.encodeToString(serializer, synchronized(this) { HashMap(baseBodies) }))
    }

    private fun loadBaseBodies() {
        val raw = props.getValue(KEY_BASES) ?: return
        val serializer = MapSerializer(String.serializer(), String.serializer())
        runCatching { json.decodeFromString(serializer, raw) }.getOrNull()?.let { baseBodies.putAll(it) }
    }

    private companion object {
        const val RECEIVER = "io.github.siddharthjaswal.logpose.mock.MockCommandReceiver"
        const val FLAG_INCLUDE_STOPPED_PACKAGES = "0x00000020"
        const val SLICE_CHARS = 2000
        const val KEY_RULES = "logpose.mock.rules"
        const val KEY_REVISION = "logpose.mock.revision"
        const val KEY_PKG = "logpose.mock.pkg"
        const val KEY_BASES = "logpose.mock.baseBodies"
    }
}
