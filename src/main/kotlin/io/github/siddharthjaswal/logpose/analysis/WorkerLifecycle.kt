package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * The state sequence a background work request was **observed** to pass through.
 *
 * This exists because the store cannot answer the question. The library emits every state of a
 * request under the same envelope id (its `workId`), and
 * [EventStore][io.github.siddharthjaswal.logpose.store.EventStore] re-puts that id in place — which
 * is exactly what makes enqueued → running → succeeded one mutating row instead of three, and
 * exactly what destroys the earlier payloads. So §6's "Show state transitions" has nothing to expand
 * unless something remembers, and this is the smallest thing that can.
 *
 * Two honesty rules are baked in:
 *
 *  - These are the transitions **LogPose saw**, not WorkManager's history. A `WorkInfo` observer can
 *    coalesce two changes into one emission, and work enqueued before the observer attached is first
 *    seen mid-life. The UI must say "observed" wherever it renders this.
 *  - Consecutive identical `(state, runAttempt)` emissions collapse. The documented integration
 *    re-emits every `WorkInfo` on every change, so the same state arrives many times; an un-deduped
 *    log would be almost entirely noise.
 *
 * Bounded on both axes — [perIdCap] entries per request (the first is always kept, since "when did
 * this start" is the entry most worth having) and [idCap] requests — so a long session cannot leak.
 * Written on the reader thread, read on the EDT, hence `@Synchronized`.
 */
class WorkerLifecycle(
    private val perIdCap: Int = 32,
    private val idCap: Int = 400,
) {

    /**
     * @param atMillis device clock the emission carried.
     * @param hostMillis plugin wall clock when the line was read — carries logcat delivery latency,
     *   so it orders reliably but does not measure.
     */
    data class Transition(
        val state: String,
        val runAttempt: Int,
        val atMillis: Long,
        val hostMillis: Long,
    )

    private val byId = LinkedHashMap<String, ArrayList<Transition>>()

    /** The id a worker's history is filed under — its `workId`, or the envelope id when it has none. */
    fun keyOf(event: LogEvent.Worker): String = event.work.workId ?: event.id

    @Synchronized
    fun note(event: LogEvent.Worker, hostMillis: Long) {
        val key = keyOf(event)
        val entries = byId.getOrPut(key) { ArrayList() }
        val state = event.work.state.lowercase()
        val last = entries.lastOrNull()
        if (last != null && last.state == state && last.runAttempt == event.work.runAttempt) return
        entries += Transition(state, event.work.runAttempt, event.timestampMillis, hostMillis)
        // Drop from the middle: the first sighting and the most recent ones are what a reader wants.
        if (entries.size > perIdCap) entries.removeAt(1)
        if (byId.size > idCap) byId.remove(byId.keys.first())
    }

    @Synchronized
    fun transitions(workId: String): List<Transition> = byId[workId]?.toList() ?: emptyList()

    /**
     * Whether there is a *sequence* to show. One sighting is not a transition — a row replayed from
     * WorkManager's store on attach is the common case — and a menu item that opens an empty view is
     * worse than no menu item.
     */
    @Synchronized
    fun hasTransitions(workId: String): Boolean = (byId[workId]?.size ?: 0) > 1

    @Synchronized
    fun clear() = byId.clear()
}
