package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.withAbandoned
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Holds captured timeline events of every kind — HTTP, FCM, and app-defined — and notifies
 * listeners on change. Filtering is a pure function over the captured list (see `FilterState`),
 * so the UI can re-query cheaply as the user types. A capped ring keeps memory bounded during
 * long sessions.
 *
 * Order is arrival order, deliberately not the device-supplied `at`: sorting by device time
 * would let rows jump around mid-capture (clock skew, a delayed span close) and would fight
 * the in-place update below.
 *
 * Beyond listeners (which say "something changed", for a repaint) the store can also be *waited
 * on*: [addWaiter] parks a predicate that completes when a matching event next arrives. That's
 * what turns an agent's poll-and-hope loop into trigger → await → assert (`await_event` over
 * MCP), and it's why completion is deliberately handed to [completer] — a waiter is resolved by
 * whichever thread happened to call [add], and must never run caller code while this store's
 * monitor is held.
 *
 * @param completer runs waiter completions; injectable so tests can make them same-thread.
 * @param scheduler arms waiter timeouts; injectable for the same reason.
 */
class EventStore(
    private val capacity: Int = 2_000,
    private val completer: Executor = sharedCompleter,
    private val scheduler: ScheduledExecutorService = sharedScheduler,
) {

    /**
     * One app run. A capture that spans a restart holds several, and reporting them as one
     * timeline corrupts every aggregate over it: a span that reads "6 hours" for two 50-second
     * bursts, duplicate detection matching across a restart, endpoint tallies merging unrelated
     * runs.
     *
     * @param index    1-based, in the order the plugin saw them.
     * @param startedAt host-clock millis when the session's first hello arrived.
     * @param processId the app's per-run id; blank from libraries older than 1.5.0.
     * @param pid      the OS process that announced it, from logcat's `-v brief` prefix; null when
     *   the hello arrived without one (a capture file, a test, an older host).
     */
    data class Session(
        val index: Int,
        val startedAt: Long,
        val processId: String,
        val pkg: String,
        val libVersion: String,
        val pid: Int? = null,
        /** The OS process name (`com.acme`, `com.acme:service`) when [processNames] could read it. */
        val processName: String? = null,
    )

    /**
     * How the store tells a restart from a second process of the same app. Null (tests, capture
     * files, hosts without adb) keeps the plain rule: a newer run of a package supersedes the older.
     * Set, a newer run supersedes an older one only when the older run's process is gone — its pid
     * no longer runs it — or the new run *is* that process again (same process name, new pid).
     * `com.acme:service` announcing beside a live `com.acme` abandons nothing.
     */
    @Volatile var processNames: io.github.siddharthjaswal.logpose.logcat.ProcessNames? = null

    // Insertion-ordered, keyed by event id: O(1) add + dedup. Re-putting an existing id
    // (e.g. a response arriving after its request) updates in place and keeps its original
    // position — unless the update comes from a different app run (see [record]). Oldest entries
    // evict once over capacity.
    private val all = object : LinkedHashMap<String, LogEvent>(256, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LogEvent>): Boolean =
            size > capacity
    }
    // Host-clock timestamp when each id was first seen — used for the live timer, since
    // the device's startedAtMillis can't be diffed against the host clock (skew).
    private val firstSeen = HashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val sessionList = ArrayList<Session>()
    /** event id → the session it belongs to. Events no hello accounts for (yet) belong to session 0. */
    private val sessionOf = HashMap<String, Int>()
    /** event id → the pid of the process that last emitted it, when the line said. */
    private val pidOf = HashMap<String, Int>()
    /** Sessions a newer run of the same package superseded: their open spans can never close. */
    private val endedSessions = HashSet<Int>()
    /** Ids currently carrying an [LogEvent.abandoned] mark — so reviving never scans the whole store. */
    private val abandonedIds = HashSet<String>()

    /**
     * Records [event] and wakes anything waiting for it.
     *
     * [pid] is the process that wrote the line ([io.github.siddharthjaswal.logpose.logcat.TransactionParser.lastPid]);
     * null when unknown, which keeps the pre-pid behaviour exactly: the event joins the latest run.
     *
     * Two steps on purpose: the store mutation and listener dispatch happen under the monitor
     * (unchanged), then waiters are matched **outside** it. A waiter's completion runs arbitrary
     * downstream code (an MCP response write), and running that while holding this lock would
     * put a network write between the reader thread and every other reader of the capture.
     */
    fun add(event: LogEvent, pid: Int? = null) {
        record(event, pid)
        dispatchWaiters(event)
    }

    @Synchronized
    private fun record(event: LogEvent, pid: Int?) {
        val isNew = event.id !in all
        val target = sessionFor(pid)
        val previous = sessionOf[event.id]
        if (previous != null && previous != target) {
            // The same id re-emitted by a *different* run. WorkManager keeps a request's id across
            // process death and every new process replays its persisted work on attach, so a
            // periodic worker's row is re-emitted — with a fresh `at` — by each run. Leaving it in
            // the run that first showed it stretched that run's span to whenever the latest run
            // replayed it: two runs "ending" a millisecond apart, a 12-event run spanning 42
            // minutes. The update is the new run's observation, so it moves there — and to the
            // end of the timeline, where that run's rows are. (A same-run update — a response
            // landing on its request — still updates in place.)
            all.remove(event.id)
            firstSeen[event.id] = System.currentTimeMillis()
        }
        firstSeen.getOrPut(event.id) { System.currentTimeMillis() }
        sessionOf[event.id] = target
        if (pid != null) pidOf[event.id] = pid
        all[event.id] = keepInjection(all[event.id], event)
        // A fresh emission carries no mark of its own; the stored copy's mark goes with it.
        abandonedIds.remove(event.id)
        // A line from a run we had written off proves it is still alive — a second process of the
        // same app announced itself, or the device came back mid-run. Its open spans can finish.
        revive(target, byPid = pid != null && sessionList.any { it.pid == pid })
        // The chatty kinds (a geofence-happy analytics feed, a busy Room callback) would otherwise
        // fill the whole buffer and evict the HTTP calls and the one accept/reject you opened
        // LogPose to see. Cap them per-kind so they can only crowd out their own kind's history.
        if (isNew) PER_KIND_CAP[event.kind]?.let { cap -> evictOldestOfKind(event.kind, cap) }
        listeners.forEach { it() }
    }

    /**
     * The run a line from [pid] belongs to.
     *
     * With a pid, and at least one run that announced its pid, the answer is that process's latest
     * run — or 0 when no hello from it has been seen, which is what lets a later hello claim it
     * (see [noteHello]). Without one, the pre-pid rule: the latest run.
     */
    private fun sessionFor(pid: Int?): Int {
        if (pid != null && sessionList.any { it.pid != null }) {
            return sessionList.lastOrNull { it.pid == pid }?.index ?: 0
        }
        return sessionList.lastOrNull()?.index ?: 0
    }

    /**
     * Clears abandonment that a sign of life from [session] disproves. [byPid] says the sign was
     * attributed by process — the only evidence strong enough to undo "app restarted"; a pid-less
     * line can only undo "device disconnected" (the device is evidently talking again).
     */
    private fun revive(session: Int, byPid: Boolean) {
        if (session == 0) return
        val undoRestart = byPid && endedSessions.remove(session)
        if (abandonedIds.isEmpty()) return
        val stale = abandonedIds.filter { id ->
            val ev = all[id]
            ev != null && sessionOf[id] == session &&
                (ev.abandoned == LogEvent.REASON_DEVICE_DISCONNECTED || undoRestart)
        }
        stale.forEach { id -> mark(id, null) }
    }

    /** Sets (or, with null, clears) [id]'s abandonment, keeping [abandonedIds] in step. */
    private fun mark(id: String, reason: String?) {
        val ev = all[id] ?: return
        // A plain put on an existing key keeps the row's position.
        all[id] = ev.withAbandoned(reason)
        if (reason == null) abandonedIds.remove(id) else abandonedIds.add(id)
    }

    /**
     * An injected push may not be un-injected by the app's own re-log of it.
     *
     * LogPose delivers an injected push by handing it to the app, so the app's messaging service
     * normally calls `logFcmMessage` for it moments later. That re-log shares the injected row's
     * envelope id on purpose — one updating row, not an unmarked twin — but it is emitted from the
     * app's own call site, so it carries the app's ambient trace (usually none) and, on device
     * libraries before 1.7.1, `injected = false`. Taking it verbatim would erase both: the row
     * would stop saying it was injected, and `get_trace(trace_id)` on the trace `inject_fcm`
     * returned would come back empty a few milliseconds after the injection.
     *
     * So an update to an injected FCM row keeps the injection's own trace and flag, and takes
     * everything else the re-log brings (the app's view of the message). Narrow on purpose: it
     * only fires for an FCM row already marked injected, which only the injector emits — no other
     * in-place update (a response landing on its request, a worker changing state) is affected.
     */
    private fun keepInjection(existing: LogEvent?, incoming: LogEvent): LogEvent {
        if (existing !is LogEvent.Fcm || incoming !is LogEvent.Fcm) return incoming
        if (!existing.msg.injected) return incoming
        val trace = existing.envelope.traceId ?: incoming.envelope.traceId
        if (incoming.msg.injected && incoming.envelope.traceId == trace) return incoming
        val msg = incoming.msg.copy(injected = true)
        return incoming.copy(
            msg = msg,
            envelope = incoming.envelope.copy(
                traceId = trace,
                // Re-serialized, not left as the device sent it: `get_event` hands the raw payload
                // back, and a row that reads `injected: true` above a payload saying `false` is
                // worse than either answer alone.
                payload = runCatching { PAYLOAD_JSON.encodeToJsonElement(FcmMessage.serializer(), msg) }
                    .getOrDefault(incoming.envelope.payload),
            ),
        )
    }

    /** Drops the oldest event of [kind] once that kind exceeds [cap], leaving other kinds alone. */
    private fun evictOldestOfKind(kind: String, cap: Int) {
        var count = 0
        var oldestId: String? = null
        for ((id, ev) in all) if (ev.kind == kind) { count++; if (oldestId == null) oldestId = id }
        if (count > cap && oldestId != null) {
            all.remove(oldestId); firstSeen.remove(oldestId); sessionOf.remove(oldestId)
            pidOf.remove(oldestId); abandonedIds.remove(oldestId)
        }
    }

    /**
     * Record a device handshake, starting a new session when it comes from a different app run.
     *
     * The library emits a hello at process start *and* on the first intercept (and, from 1.7.4,
     * whenever a host asks — see `BroadcastCommand.CMD_HELLO`), so the same run announces itself
     * more than once; [processId] is what tells those apart, matched against **every** run seen,
     * not just the latest, so two live processes of one app announcing alternately don't split a
     * new session per hello. Libraries older than 1.5.0 send no process id — there the [pid]
     * decides when it is known, and otherwise a hello that arrives after events already landed
     * is assumed to mark a restart (right for the common relaunch case, wrong only when an old
     * library re-announces mid-session, costing a spurious boundary, never a merge).
     *
     * With a [pid], two things happen that a pid-less hello can't do:
     *  - **Retroactive attribution.** Events that process emitted before its hello reached us —
     *    capture started after the launch, so the launch hello was never seen — sat in session 0.
     *    The first run to announce that pid claims them. Only the first: under pid reuse a later
     *    run with the same pid must not inherit a dead predecessor's rows, and it can't, because
     *    the predecessor claimed them (or they were attributed to it on arrival).
     *  - **Abandonment.** A new run of the same package whose older run's process is gone (see
     *    [processNames]) marks that run's open spans [LogEvent.REASON_APP_RESTARTED] instead of
     *    leaving them "in flight" forever; so are session-0 spans from a dead pid no run announced.
     */
    @Synchronized
    fun noteHello(processId: String, pkg: String, libVersion: String, pid: Int? = null) {
        val current = sessionList.lastOrNull()
        val same: Session? = when {
            processId.isNotBlank() -> sessionList.lastOrNull { it.processId == processId }
            current == null -> null
            pid != null && current.pid != null -> current.takeIf { it.pid == pid }
            else -> current.takeIf { sessionOf.values.none { it == current.index } }
        }

        if (same != null) {
            var session = same
            if (session.pid == null && pid != null) {
                session = session.copy(pid = pid)
                sessionList[session.index - 1] = session
                claimUnattributed(session)
            }
            // The run re-announced itself, so it is alive: whatever we wrote off for it can finish.
            revive(session.index, byPid = true)
            listeners.forEach { it() }
            return
        }

        val session = Session(
            index = sessionList.size + 1,
            startedAt = System.currentTimeMillis(),
            processId = processId,
            pkg = pkg,
            libVersion = libVersion,
            pid = pid,
        )
        sessionList.add(session)
        claimUnattributed(session)
        abandonSupersededBy(session)
        listeners.forEach { it() }
    }

    /** Moves session-0 events from [session]'s pid into it — unless an earlier run owns that pid. */
    private fun claimUnattributed(session: Session) {
        val pid = session.pid ?: return
        if (sessionList.any { it.pid == pid && it.index != session.index }) return
        sessionOf.entries.forEach { e ->
            if (e.value == 0 && pidOf[e.key] == pid) e.setValue(session.index)
        }
    }

    private fun abandonSupersededBy(session: Session) {
        val candidates = sessionList.filter {
            it.index != session.index && samePackage(it, session) && it.index !in endedSessions
        }
        // Session-0 spans from a pid no run announced: abandoned once a newer process announces,
        // if that pid is gone.
        val orphans: Set<Int> = if (session.pid == null) emptySet() else all.entries
            .filter { (id, ev) -> ev.isOpen && ev.abandoned == null && (sessionOf[id] ?: 0) == 0 }
            .mapNotNull { pidOf[it.key] }
            .filterTo(HashSet()) { owner -> owner != session.pid && sessionList.none { it.pid == owner } }

        val probe = processNames
        val newPid = session.pid
        if (probe == null || newPid == null) {
            settle(candidates.mapTo(HashSet()) { it.index }, orphans)
            return
        }
        // A run that never said its pid can't be probed: the plain rule for it.
        val (probed, blind) = candidates.partition { it.pid != null }
        settle(blind.mapTo(HashSet()) { it.index }, emptySet())
        val pids = probed.mapNotNullTo(HashSet()) { it.pid } + orphans + newPid
        probe.lookup(pids) { names -> onProcessNames(session.index, probed.map { it.index }, orphans, names) }
    }

    /** The answer to [abandonSupersededBy]'s probe — possibly on another thread, possibly later. */
    @Synchronized
    private fun onProcessNames(newIndex: Int, candidates: List<Int>, orphans: Set<Int>, names: Map<Int, String?>) {
        val fresh = sessionList.getOrNull(newIndex - 1)?.takeIf { it.index == newIndex } ?: return // cleared meanwhile
        val newName = fresh.pid?.let { names[it] }
        if (fresh.processName == null && newName != null) sessionList[newIndex - 1] = fresh.copy(processName = newName)

        fun gone(old: Session): Boolean {
            val pid = old.pid ?: return true
            val now = names[pid]
            return when {
                pid == fresh.pid -> true                                    // pid reuse: a new run on it
                old.processName != null && old.processName == newName -> true // the same process, again
                now == null -> true                                         // the pid is dead
                old.processName != null && now != old.processName -> true   // the pid runs something else
                else -> false                                               // alive: a second process
            }
        }
        val dead = candidates.mapNotNull { sessionList.getOrNull(it - 1) }
            .filter { it.index !in endedSessions && gone(it) }
            .mapTo(HashSet()) { it.index }
        val deadOrphans = orphans.filterTo(HashSet()) { pid -> names[pid] == null && sessionList.none { it.pid == pid } }
        settle(dead, deadOrphans)
        listeners.forEach { it() }
    }

    /** Ends [sessions] and abandons their open spans, plus session-0 spans from [orphanPids]. */
    private fun settle(sessions: Set<Int>, orphanPids: Set<Int>) {
        if (sessions.isEmpty() && orphanPids.isEmpty()) return
        endedSessions += sessions
        val dead = all.entries.filter { (id, ev) ->
            if (!ev.isOpen || ev.abandoned != null) return@filter false
            val s = sessionOf[id] ?: 0
            if (s != 0) s in sessions else pidOf[id] in orphanPids
        }
        dead.map { it.key }.forEach { id -> mark(id, LogEvent.REASON_APP_RESTARTED) }
    }

    private fun samePackage(a: Session, b: Session): Boolean =
        a.pkg.isBlank() || b.pkg.isBlank() || a.pkg == b.pkg

    /**
     * The capture lost the device (the logcat stream ended on its own — never the user's Stop, which
     * leaves the app running and its requests able to finish). Every span still open is marked
     * [LogEvent.REASON_DEVICE_DISCONNECTED]; a later line from the same run (a reattach) clears it.
     */
    @Synchronized
    fun noteDeviceGone() {
        val open = all.entries.filter { (_, ev) -> ev.isOpen && ev.abandoned == null }
        if (open.isEmpty()) return
        open.map { it.key }.forEach { id -> mark(id, LogEvent.REASON_DEVICE_DISCONNECTED) }
        listeners.forEach { it() }
    }

    /**
     * True when [pid] has written events but no run has claimed it — the hello that would tie its
     * events to a run was missed. The host's cue to ask the app to say it again
     * ([io.github.siddharthjaswal.logpose.mock.HelloRequester]).
     */
    @Synchronized
    fun needsHello(pid: Int?): Boolean = pid != null && sessionList.none { it.pid == pid }

    /** The pid of the process that last emitted [eventId], when logcat said. */
    @Synchronized
    fun pidOf(eventId: String): Int? = pidOf[eventId]

    @Synchronized
    fun clear() {
        all.clear()
        firstSeen.clear()
        sessionList.clear()
        sessionOf.clear()
        pidOf.clear()
        endedSessions.clear()
        abandonedIds.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = all.values.toList()

    /** Sessions seen this capture, oldest first. Empty until the first hello arrives. */
    @Synchronized
    fun sessions(): List<Session> = sessionList.toList()

    /** Which session an event belongs to; 0 for events no handshake accounts for. */
    @Synchronized
    fun sessionOf(eventId: String): Int = sessionOf[eventId] ?: 0

    /** Wall-clock millis since this id was first seen by the plugin (for in-flight timing). */
    @Synchronized
    fun elapsedMillis(id: String): Long =
        firstSeen[id]?.let { System.currentTimeMillis() - it } ?: 0L

    fun addListener(l: () -> Unit) { listeners.add(l) }

    // ---- waiters ----------------------------------------------------------------------------

    private class Waiter(
        val predicate: (LogEvent) -> Boolean,
        val future: CompletableFuture<LogEvent?>,
    ) {
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    // Copy-on-write so [dispatchWaiters] can walk it from the reader thread without a lock; the
    // list is tiny (capped at MAX_WAITERS) and written far less often than it's read.
    private val waiters = CopyOnWriteArrayList<Waiter>()

    /**
     * Parks a waiter for the first event **arriving after this call** that satisfies [predicate].
     *
     * The returned future completes with the matching event, or with `null` when [timeoutMillis]
     * elapses first — a timeout is an answer ("nothing happened"), not a failure. Returns null
     * when [MAX_WAITERS] are already outstanding, which is the caller's cue to say so rather than
     * let an agent accumulate parked requests.
     *
     * [predicate] is evaluated on the thread that calls [add] (the logcat reader), so it must be
     * pure and quick; completions are handed to the store's executor.
     */
    fun addWaiter(timeoutMillis: Long, predicate: (LogEvent) -> Boolean): CompletableFuture<LogEvent?>? {
        val waiter = Waiter(predicate, CompletableFuture())
        synchronized(waiters) {
            if (waiters.size >= MAX_WAITERS) return null
            waiters.add(waiter)
        }
        // Armed after registration, so a match that lands in between simply finds the waiter and
        // the timer then finds nothing left to time out.
        waiter.timeout = scheduler.schedule(
            { if (waiters.remove(waiter)) completer.execute { waiter.future.complete(null) } },
            timeoutMillis.coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        )
        if (waiter.future.isDone) waiter.timeout?.cancel(false)
        return waiter.future
    }

    /** Waiters parked right now — for the concurrency cap's error message and for tests. */
    fun waiterCount(): Int = waiters.size

    private fun dispatchWaiters(event: LogEvent) {
        if (waiters.isEmpty()) return
        for (waiter in waiters) {
            // A broken predicate must not take the capture down with it: an event that can't be
            // matched simply isn't a match.
            if (!runCatching { waiter.predicate(event) }.getOrDefault(false)) continue
            // remove() is the claim: whoever removes it owns the completion, so a match racing
            // the timeout can only resolve the future once.
            if (!waiters.remove(waiter)) continue
            waiter.timeout?.cancel(false)
            completer.execute { waiter.future.complete(event) }
        }
    }

    companion object {
        /** Per-kind ceilings for the chattiest kinds, so a flood can't evict everything else. */
        /** Only used to re-encode a corrected FCM payload; the wire's own settings, not new ones. */
        private val PAYLOAD_JSON = Json { encodeDefaults = true; explicitNulls = false }

        private val PER_KIND_CAP = mapOf(
            Envelope.KIND_ANALYTICS to 400,
            Envelope.KIND_DB to 400,
        )

        /**
         * How many waits can be outstanding on one capture. Eight is far more than the
         * trigger→await→assert loop needs and few enough that a misbehaving client parks a
         * bounded number of requests instead of an unbounded queue of them.
         */
        const val MAX_WAITERS = 8

        /**
         * One daemon pool per IDE for waiter completions and timeouts. Completions are short
         * (serialize a response, write it to a channel) and rare, so a small shared pool beats a
         * per-store executor that would need its own disposal path.
         */
        private val sharedCompleter: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "logpose-await").apply { isDaemon = true }
        }

        private val sharedScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "logpose-await-timeout").apply { isDaemon = true }
            }
    }
}
