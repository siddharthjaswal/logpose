package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockRuleSet
import io.github.siddharthjaswal.logpose.model.PushAck
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the mock rule set on the IDE side and keeps the device in sync over the reverse
 * channel (adb broadcast → `MockCommandReceiver`). Rules persist per project; the device is
 * only ever pushed to while capture is live, and is cleared when capture stops.
 *
 * "In sync" is not assumed — it's proven, by [SyncState]: the broadcast's exit code says the
 * command left the machine, the ack's revision **and rule count** say the device applied what we
 * sent, and a `Hello` reporting zero rules after a reinstall says it lost them. Anything else is
 * pending or failed, and failures are handed to [onProblem] (an IDE notification the panel owns)
 * rather than the detail pane.
 *
 * Threading: rule mutations happen on the EDT (UI), device control messages arrive on the
 * reader thread, ack deadlines on a shared scheduler thread. State access is synchronized;
 * every adb invocation runs on a short-lived daemon thread — never the EDT (the project's hard
 * rule).
 */
class MocksController(private val props: KeyValueStore) {

    /** Snapshot of what the device reported, for the UI's sync indicator. */
    data class DeviceState(
        val pkg: String?,
        val libVersion: String?,
        val helloSeen: Boolean,
        val syncedRevision: Int,
        val hits: Map<String, Int>,
        /** Revision/ack/transport truth for the sync dot — see [SyncState]. */
        val sync: SyncState.Snapshot,
        /** Enabled rules withheld from the device because its library is too old (PRD D3). */
        val withheldRules: Int = 0,
        /** True while capture is running — nothing is pushed or acknowledged when it isn't. */
        val capturing: Boolean = false,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // Rules are newest-first: the device matches first-in-list, so a newer rule for the same
    // endpoint overrides an older one.
    private val rules = mutableListOf<MockRule>()
    // ruleId -> the captured response the rule was seeded from, so the field-tree editor can be
    // rebuilt when editing later. Plugin-side only; never pushed to the device.
    private val baseBodies = mutableMapOf<String, String>()
    private var revision: Int = props.getInt(KEY_REVISION, 0)

    private var pkg: String? = props.get(KEY_PKG)
    private var libVersion: String? = null
    private var helloSeen = false
    private var lastProcessId: String? = null
    private var syncedRevision = -1
    private var hits: Map<String, Int> = emptyMap()
    private var withheldRules = 0

    private val sync = SyncState()

    /** True while logcat capture is running — gates all device pushes. */
    private val live = AtomicBoolean(false)

    /** Device to target; null = default adb device. (Device picker will set this later.) */
    @Volatile var deviceSerial: String? = null

    /**
     * Sink for user-visible sync problems (broadcast failure, ack timeout, version skew). The
     * panel wires this to an IDE notification — keeping the platform notification API out of
     * this class, and errors out of the detail pane (flow-driver PRD, cross-cutting item 3).
     */
    @Volatile var onProblem: (String, String) -> Unit = { _, _ -> }

    /**
     * Sink for `push_ack` control messages. Rules and pushes share the reverse channel but not
     * their state machines, so the ack is handed straight to [PushController] rather than being
     * interpreted here.
     */
    @Volatile var onPushAck: (PushAck) -> Unit = {}

    private val listeners = mutableListOf<() -> Unit>()

    // The last problem text surfaced, so a repeated failure (a dead adb hit on every edit) is
    // reported once instead of once per push.
    private var lastProblem: String? = null
    // Version-skew notice is per device build: notify once, again only if the device (or the set
    // of rules it can't take) changes.
    private var skewNotice: String? = null

    init {
        loadRules()
        loadBaseBodies()
        sync.onLocalRevision(revision)
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

    /**
     * Swaps the whole rule set for [newRules] — "load this scenario and nothing else". One
     * revision, one push: a scenario has to arrive at the device atomically, or a load would
     * briefly serve a mixture of the old and new sets.
     */
    fun replaceAll(newRules: List<MockRule>) = mutate {
        rules.clear()
        rules.addAll(newRules)
    }

    /**
     * Merges [newRules] into the active set, replacing any rule sharing an id (which is how a
     * snapshot of the same endpoint updates in place rather than stacking a second, shadowed
     * rule behind the first). Also one revision, one push.
     */
    fun merge(newRules: List<MockRule>) = mutate {
        for (rule in newRules) {
            val i = rules.indexOfFirst { it.id == rule.id }
            if (i >= 0) rules[i] = rule else rules.add(0, rule)
        }
    }

    // ---- Device capability ------------------------------------------------------------------

    /**
     * Whether the device's library is new enough for [feature]. Push/compose UI and the richer
     * matcher rules gate on this rather than sending fields an old library would ignore.
     */
    @Synchronized
    fun deviceSupports(feature: DeviceFeature): Boolean =
        DeviceCapability.supports(libVersion, feature)

    /** The library version the device reported in its `Hello`, if one has been seen. */
    @Synchronized fun deviceLibVersion(): String? = libVersion

    // ---- Capture lifecycle ----------------------------------------------------------------

    /** Called when capture starts: rules will be pushed once the device says Hello. */
    fun onCaptureStarted() {
        live.set(true)
    }

    /** Called when capture stops (or the panel disposes): clear the device, keep local rules. */
    fun onCaptureStopped() {
        live.set(false)
        lastProblem = null
        skewNotice = null
        val target = pkg
        if (target != null) {
            // Bump revision so the empty set supersedes whatever the device holds.
            val rev = synchronized(this) { ++revision }
            persistRevision()
            // Untracked: capture is going away, so no ack will be read back and a failure to
            // reach a device that may already be gone is noise, not news.
            broadcast(target, MockRuleSet(revision = rev, rules = emptyList()), tracked = false)
        }
        // Sync claims nothing once we're not listening: the next capture proves it again.
        sync.reset()
    }

    // ---- Reverse channel (reader thread) --------------------------------------------------

    fun onControl(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.DeviceHello -> onHello(msg.hello)
            is ControlMessage.MockApplied -> onAck(msg.ack)
            is ControlMessage.PushDelivered -> onPushAck(msg.ack)
        }
    }

    private fun onHello(hello: Hello) {
        val freshRun = synchronized(this) {
            pkg = hello.pkg
            libVersion = hello.libVersion
            helloSeen = true
            // A different process id means a new app run (library 1.5.0+). Blank = old library:
            // treat as the same run so we don't prune on every re-announce.
            val fresh = hello.processId.isNotBlank() && hello.processId != lastProcessId
            if (hello.processId.isNotBlank()) lastProcessId = hello.processId
            fresh
        }
        props.set(KEY_PKG, hello.pkg)
        // On a new app run, drop leftover *disabled* rules so a stale mock from a previous session
        // (or someone else's) can't poison this one. Enabled, in-use rules are kept and re-pushed.
        if (freshRun) pruneDisabledRules()

        // What the device *should* be holding, after version gating — comparing its ruleCount
        // against that is what catches "the app was reinstalled and lost every rule".
        val expected = pushableRules().size
        val effect = sync.onHello(
            deviceRevision = hello.mockRevision,
            deviceRuleCount = hello.ruleCount,
            expectedRuleCount = expected,
            freshProcess = freshRun,
        )
        applyEffect(effect)
        notifyChanged()
    }

    private fun pruneDisabledRules() {
        val removed = synchronized(this) {
            val before = rules.size
            rules.removeAll { !it.enabled }
            before != rules.size
        }
        if (removed) { persistRules(); notifyChanged() }
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
        props.set(KEY_PKG, ack.pkg)
        applyEffect(sync.onAck(ack.revision, ack.ruleCount))
        notifyChanged()
    }

    // ---- Push -----------------------------------------------------------------------------

    /** Serializes the current rule set and pushes it to the device (off the EDT). */
    fun pushNow() {
        val target = pkg ?: return
        val pushable = pushableRules()
        val rev = synchronized(this) { revision }
        sync.onPush(rev, pushable.size, System.currentTimeMillis())
        reportSkew()
        // Pin the attempt this push belongs to now, so the ack deadline armed for it can't be
        // matched against a state a later retry has already moved on.
        val attempt = sync.snapshot().attempt
        broadcast(target, MockRuleSet(revision = rev, rules = pushable), tracked = true, attempt = attempt)
    }

    /**
     * The rules that may go to *this* device: rules using a field the device's library predates
     * are withheld, not downgraded. An old library ignores unknown fields, so pushing a rule
     * whose whole point is `only when ?debug=1` would have it match every call to that path —
     * a mock matching more broadly than it reads is the one failure LogPose can't afford.
     */
    @Synchronized
    private fun pushableRules(): List<MockRule> {
        val lib = libVersion
        val (ok, gated) = rules.partition { DeviceCapability.canPush(it, lib) }
        withheldRules = gated.count { it.enabled }
        return ok
    }

    /** Notifies (once per device/rule-set change) that some rules can't be sent to this device. */
    private fun reportSkew() {
        val (count, lib, known) = synchronized(this) {
            Triple(withheldRules, libVersion, helloSeen)
        }
        // Say nothing until the device has told us what it is: rules are still withheld (fail
        // closed), but "your device is too old" is not a claim to make about a device that hasn't
        // spoken yet — the handshake is usually a second away, and it re-pushes.
        if (!known) return
        if (count <= 0) { skewNotice = null; return }
        val notice = "$count|${lib.orEmpty()}"
        if (notice == skewNotice) return
        skewNotice = notice
        onProblem(
            "LogPose: $count mock rule(s) not sent",
            "$count rule(s) use query/header/body matching or sequential responses, which need " +
                "logpose-android ≥ ${DeviceFeature.RICH_MATCHERS.since} on the device" +
                (lib?.let { " (it reports $it)" } ?: "") +
                ". They were withheld rather than sent to a library that would ignore the " +
                "constraint and match too broadly.",
        )
    }

    private fun broadcast(target: String, ruleSet: MockRuleSet, tracked: Boolean, attempt: Int = 0) {
        val revision = ruleSet.revision
        val adb = Adb.resolve()
        if (adb == null) {
            if (tracked) {
                applyEffect(sync.onBroadcastFailure(revision, "adb not found (set ANDROID_HOME or put adb on PATH)"))
                notifyChanged()
            }
            return
        }
        val slices = BroadcastCommand.slices(json.encodeToString(MockRuleSet.serializer(), ruleSet))
        val total = slices.size
        val serial = deviceSerial

        Thread({
            var failure: String? = null
            for ((seq, slice) in slices.withIndex()) {
                val cmd = Adb.baseCmd(adb, serial) + BroadcastCommand.args(
                    target = target,
                    // Deliberately no `cmd` extra: a rule set is what the receiver did before the
                    // extra existed, so an older library on the device keeps working unchanged.
                    cmd = null,
                    revision = revision,
                    seq = seq,
                    total = total,
                    payload = slice,
                )
                failure = AdbCommand.run(cmd)
                if (failure != null) break
            }
            if (!tracked) return@Thread
            if (failure != null) {
                applyEffect(sync.onBroadcastFailure(revision, failure))
            } else {
                armAckDeadline(revision, attempt)
            }
            notifyChanged()
        }, "logpose-mock-push").apply { isDaemon = true }.start()
    }

    /**
     * Arms the "the device never answered" check for this push. Only when a device has actually
     * announced itself — before the first Hello there is nothing to time out, and the bar already
     * says it's waiting for one.
     */
    private fun armAckDeadline(revision: Int, attempt: Int) {
        if (!synchronized(this) { helloSeen }) return
        scheduler.schedule({
            applyEffect(sync.onAckDeadline(revision, attempt))
            notifyChanged()
        }, ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Performs whatever the state machine decided: a bounded re-push, or one notification. */
    private fun applyEffect(effect: SyncState.Effect) {
        when (effect) {
            is SyncState.Effect.None -> Unit
            is SyncState.Effect.Repush -> if (live.get()) pushNow()
            is SyncState.Effect.Fail -> report(effect.message)
        }
    }

    private fun report(message: String) {
        if (message == lastProblem) return
        lastProblem = message
        onProblem(
            "LogPose: mock rules may not be live",
            "$message.\n\nThe timeline shows what the app actually received — until the device " +
                "acknowledges this rule set, treat the mocks as not applied.",
        )
    }

    // ---- UI plumbing ----------------------------------------------------------------------

    @Synchronized
    fun deviceState() = DeviceState(
        pkg = pkg,
        libVersion = libVersion,
        helloSeen = helloSeen,
        syncedRevision = syncedRevision,
        hits = hits,
        sync = sync.snapshot(),
        withheldRules = withheldRules,
        capturing = live.get(),
    )

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun notifyChanged() = listeners.forEach { it() }

    /** Applies [change], persists, pushes if live, and notifies the UI. */
    private inline fun mutate(change: () -> Unit) {
        val rev = synchronized(this) {
            change()
            ++revision
        }
        sync.onLocalRevision(rev)
        persistRules()
        persistRevision()
        if (live.get()) pushNow()
        notifyChanged()
    }

    private fun loadRules() {
        val raw = props.get(KEY_RULES) ?: return
        runCatching {
            json.decodeFromString(ListSerializer(MockRule.serializer()), raw)
        }.getOrNull()?.let { rules.addAll(it) }
    }

    private fun persistRules() {
        props.set(KEY_RULES, json.encodeToString(ListSerializer(MockRule.serializer()), rules.toList()))
    }

    private fun persistRevision() = props.setInt(KEY_REVISION, revision, 0)

    private fun persistBaseBodies() {
        val serializer = MapSerializer(String.serializer(), String.serializer())
        props.set(KEY_BASES, json.encodeToString(serializer, synchronized(this) { HashMap(baseBodies) }))
    }

    private fun loadBaseBodies() {
        val raw = props.get(KEY_BASES) ?: return
        val serializer = MapSerializer(String.serializer(), String.serializer())
        runCatching { json.decodeFromString(serializer, raw) }.getOrNull()?.let { baseBodies.putAll(it) }
    }

    private companion object {
        const val KEY_RULES = "logpose.mock.rules"
        const val KEY_REVISION = "logpose.mock.revision"
        const val KEY_PKG = "logpose.mock.pkg"
        const val KEY_BASES = "logpose.mock.baseBodies"

        /** How long the device gets to acknowledge a rule set before sync is called into doubt.
         *  Generous: the ack rides back on logcat, behind whatever the app is logging. */
        const val ACK_TIMEOUT_MILLIS = 6_000L

        /**
         * One daemon timer for every project's ack deadlines. Tasks are a few seconds long and
         * hold nothing but a revision number, so a shared idle thread beats a per-project
         * executor that would need its own disposal path.
         */
        val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "logpose-mock-sync").apply { isDaemon = true }
        }
    }
}
