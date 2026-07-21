package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.LogEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds captured timeline events of every kind — HTTP, FCM, and app-defined — and notifies
 * listeners on change. Filtering is a pure function over the captured list (see `FilterState`),
 * so the UI can re-query cheaply as the user types. A capped ring keeps memory bounded during
 * long sessions.
 *
 * Order is arrival order, deliberately not the device-supplied `at`: sorting by device time
 * would let rows jump around mid-capture (clock skew, a delayed span close) and would fight
 * the in-place update below.
 */
class EventStore(private val capacity: Int = 2_000) {

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

    @Synchronized
    fun add(event: LogEvent) {
        firstSeen.getOrPut(event.id) { System.currentTimeMillis() }
        // First seen wins: a response landing on its request's row keeps the request's session,
        // which is what stops a call that straddles a restart from being counted twice.
        sessionOf.getOrPut(event.id) { sessionList.lastOrNull()?.index ?: 0 }
        all[event.id] = event
        listeners.forEach { it() }
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
}
