package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.LogEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds captured events (HTTP transactions and FCM messages) and notifies listeners on
 * change. Filtering is a pure function over the captured list (see `FilterState`), so the
 * UI can re-query cheaply as the user types. A capped ring keeps memory bounded during long
 * sessions.
 */
class TransactionStore(private val capacity: Int = 2_000) {

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

    @Synchronized
    fun add(event: LogEvent) {
        firstSeen.getOrPut(event.id) { System.currentTimeMillis() }
        all[event.id] = event
        listeners.forEach { it() }
    }

    @Synchronized
    fun clear() {
        all.clear()
        firstSeen.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = all.values.toList()

    /** Wall-clock millis since this id was first seen by the plugin (for in-flight timing). */
    @Synchronized
    fun elapsedMillis(id: String): Long =
        firstSeen[id]?.let { System.currentTimeMillis() - it } ?: 0L

    fun addListener(l: () -> Unit) { listeners.add(l) }
}
