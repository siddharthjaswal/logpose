package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
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
     */
    data class Session(
        val index: Int,
        val startedAt: Long,
        val processId: String,
        val pkg: String,
        val libVersion: String,
    )

    // Insertion-ordered, keyed by event id: O(1) add + dedup. Re-putting an existing id
    // (e.g. a response arriving after its request) updates in place and keeps its original
    // position. Oldest entries evict once over capacity.
    private val all = object : LinkedHashMap<String, LogEvent>(256, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LogEvent>): Boolean =
            size > capacity
    }
    // Host-clock timestamp when each id was first seen — used for the live timer, since
    // the device's startedAtMillis can't be diffed against the host clock (skew).
    private val firstSeen = HashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val sessionList = ArrayList<Session>()
    /** event id → the session it arrived in. Events seen before any hello belong to session 0. */
    private val sessionOf = HashMap<String, Int>()

    /**
     * Records [event] and wakes anything waiting for it.
     *
     * Two steps on purpose: the store mutation and listener dispatch happen under the monitor
     * (unchanged), then waiters are matched **outside** it. A waiter's completion runs arbitrary
     * downstream code (an MCP response write), and running that while holding this lock would
     * put a network write between the reader thread and every other reader of the capture.
     */
    fun add(event: LogEvent) {
        record(event)
        dispatchWaiters(event)
    }

    @Synchronized
    private fun record(event: LogEvent) {
        val isNew = event.id !in all
        firstSeen.getOrPut(event.id) { System.currentTimeMillis() }
        // First seen wins: a response landing on its request's row keeps the request's session,
        // which is what stops a call that straddles a restart from being counted twice.
        sessionOf.getOrPut(event.id) { sessionList.lastOrNull()?.index ?: 0 }
        all[event.id] = keepInjection(all[event.id], event)
        // The chatty kinds (a geofence-happy analytics feed, a busy Room callback) would otherwise
        // fill the whole buffer and evict the HTTP calls and the one accept/reject you opened
        // LogPose to see. Cap them per-kind so they can only crowd out their own kind's history.
        if (isNew) PER_KIND_CAP[event.kind]?.let { cap -> evictOldestOfKind(event.kind, cap) }
        listeners.forEach { it() }
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
        }
    }

    /**
     * Record a device handshake, starting a new session when it comes from a different app run.
     *
     * The library emits a hello at process start *and* on the first intercept, so the same run
     * announces itself more than once; [processId] is what tells those apart. Libraries older
     * than 1.5.0 send no process id — there we can only assume a hello that arrives after events
     * already landed marks a restart, which is right for the common relaunch case and wrong only
     * when an old library re-announces mid-session (costing a spurious boundary, never a merge).
     */
    @Synchronized
    fun noteHello(processId: String, pkg: String, libVersion: String) {
        val current = sessionList.lastOrNull()
        val sameRun = when {
            current == null -> false
            processId.isNotBlank() -> processId == current.processId
            else -> sessionOf.values.none { it == current.index }
        }
        if (sameRun) return

        sessionList.add(
            Session(
                index = sessionList.size + 1,
                startedAt = System.currentTimeMillis(),
                processId = processId,
                pkg = pkg,
                libVersion = libVersion,
            )
        )
        listeners.forEach { it() }
    }

    @Synchronized
    fun clear() {
        all.clear()
        firstSeen.clear()
        sessionList.clear()
        sessionOf.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = all.values.toList()

    /** Sessions seen this capture, oldest first. Empty until the first hello arrives. */
    @Synchronized
    fun sessions(): List<Session> = sessionList.toList()

    /** Which session an event arrived in; 0 for events that predate any handshake. */
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
