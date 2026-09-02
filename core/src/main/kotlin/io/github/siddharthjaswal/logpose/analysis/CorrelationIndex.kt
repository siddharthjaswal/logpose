package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * The cache that keeps correlation off the paint path.
 *
 * [Correlation] is deliberately pure and deliberately expensive: [Correlation.searchableText]
 * walks an event's bodies, [Correlation.extract] walks its payload (and any JSON nested inside a
 * string). Both are O(payload). Neither may run while a row paints or on the 150 ms refresh tick —
 * that is the repaint-cost lesson from 1.8.0, and the risk the PRD calls out by name.
 *
 * So both halves are computed **once per event, when the event arrives** (on the logcat reader
 * thread, never the EDT) and read back as a map lookup afterwards:
 *
 *  - the **haystack** ([textOf]) depends on the event alone. It is fed to
 *    [Correlation.group]'s `textOf` parameter, which exists precisely so grouping never rescans.
 *  - the **key values** ([valuesOf]) depend on the event *and* the configured key set, so they
 *    carry a generation stamp that [setKeys] bumps — changing the vocabulary invalidates every
 *    cached extraction and nothing else.
 *
 * Two things make the cache honest rather than merely fast:
 *
 *  - **Events update in place.** The store re-puts an id when a response lands on its request,
 *    and that event's text and values change with it. Entries therefore remember the exact
 *    [LogEvent] instance they were computed from and recompute when it is replaced — identity,
 *    not id, is the freshness test.
 *  - **It is bounded, and a miss is not an error.** Haystacks are copies of bodies, so the cache
 *    holds a character budget as well as an entry count and evicts oldest-first. Every read path
 *    recomputes on a miss ([cachedValues] being the exception: it is the paint-safe read and
 *    answers "don't know" rather than scanning).
 *
 * No IntelliJ, no Swing. Callers may touch it from the reader thread and the EDT, so the small
 * bookkeeping is synchronized while the expensive scans deliberately run outside the lock — a
 * duplicated scan under a race is cheap; a payload scan holding a lock the EDT wants is not.
 */
class CorrelationIndex(
    private val maxEntries: Int = 2_048,
    /** Total cached haystack characters before the oldest entries are dropped (~16 M chars). */
    private val maxChars: Long = 16L shl 20,
) {

    private class Entry(var event: LogEvent) {
        var text: String? = null
        var values: List<KeyValue> = emptyList()
        var generation: Int = -1
    }

    // Insertion-ordered so eviction is oldest-first, which matches the store's own ring.
    private val entries = LinkedHashMap<String, Entry>(512)
    private var chars = 0L

    private var keys: List<CorrelationKey> = emptyList()
    private var generation = 0

    // ---- configuration -----------------------------------------------------------------------

    @Synchronized
    fun keys(): List<CorrelationKey> = keys

    /**
     * Swaps the configured vocabulary in, invalidating every cached extraction.
     *
     * Haystacks survive — they never depended on the keys — so a key change costs one re-extract
     * per event, not a full rescan of every body. Re-extraction is best done off the EDT with
     * [warmValues]; until then [cachedValues] simply reports "don't know", which downgrades a
     * row's affordance rather than blocking a repaint.
     */
    @Synchronized
    fun setKeys(keys: List<CorrelationKey>) {
        val normalized = CorrelationKeys.normalize(keys)
        if (normalized == this.keys) return
        this.keys = normalized
        generation++
    }

    // ---- warming (reader thread) ---------------------------------------------------------------

    /**
     * Computes and caches everything about an arriving event. Call this on the thread that reads
     * logcat, before the store's listeners drag the EDT into a refresh.
     */
    fun warm(event: LogEvent) {
        textOf(event)
        valuesOf(event)
    }

    /** Re-extracts key values for an already-known event — the after-a-key-change hop. */
    fun warmValues(event: LogEvent) {
        valuesOf(event)
    }

    // ---- reads ---------------------------------------------------------------------------------

    /**
     * This event's searchable text, computed on a miss.
     *
     * Pass this as [Correlation.group]'s `textOf` so grouping walks cached strings instead of
     * re-serializing every body on every tick.
     */
    fun textOf(event: LogEvent): String {
        lookup(event)?.text?.let { return it }
        val text = Correlation.searchableText(event)
        putText(event, text)
        return text
    }

    /** Every configured key this event carries, matchable or not; computed on a miss. */
    fun valuesOf(event: LogEvent): List<KeyValue> {
        val (keys, generation) = configuration()
        lookup(event)?.let { if (it.generation == generation) return it.values }
        val values = Correlation.extract(event, keys)
        putValues(event, values, generation)
        return values
    }

    /**
     * The paint-safe read: what's already cached, or null when nothing is.
     *
     * A renderer calls this and treats null as "no key", because the alternative — scanning a
     * payload inside `getListCellRendererComponent` — is the bug this class exists to prevent.
     */
    @Synchronized
    fun cachedValues(event: LogEvent): List<KeyValue>? {
        val entry = entries[event.id] ?: return null
        if (entry.event !== event || entry.generation != generation) return null
        return entry.values
    }

    /** The `key -> value` view used for grouping: matchable values only. */
    fun matchable(event: LogEvent): Map<String, String> =
        valuesOf(event).filter { it.matchable }.associate { it.key to it.value }

    /** [matchable] from cache alone — null when this event hasn't been extracted yet. */
    fun cachedMatchable(event: LogEvent): Map<String, String>? =
        cachedValues(event)?.filter { it.matchable }?.associate { it.key to it.value }

    /** Whether a row can offer a key grouping right now, answered without scanning anything. */
    fun hasCachedKeyValue(event: LogEvent): Boolean =
        cachedValues(event)?.any { it.matchable } == true

    @Synchronized
    fun clear() {
        entries.clear()
        chars = 0
    }

    /** Cached entries — for tests and for reasoning about the budget, never for behaviour. */
    @Synchronized
    fun size(): Int = entries.size

    // ---- bookkeeping ---------------------------------------------------------------------------

    @Synchronized
    private fun configuration(): Pair<List<CorrelationKey>, Int> = keys to generation

    /** The entry for this exact event instance, or null when absent or stale. */
    @Synchronized
    private fun lookup(event: LogEvent): Entry? =
        entries[event.id]?.takeIf { it.event === event }

    @Synchronized
    private fun putText(event: LogEvent, text: String) {
        val entry = freshEntry(event)
        chars -= entry.text?.length ?: 0
        entry.text = text
        chars += text.length
        evict()
    }

    @Synchronized
    private fun putValues(event: LogEvent, values: List<KeyValue>, generation: Int) {
        // A key change that landed while this extraction ran makes the result already stale;
        // dropping it costs one recompute and keeps the generation stamp truthful.
        if (generation != this.generation) return
        val entry = freshEntry(event)
        entry.values = values
        entry.generation = generation
        evict()
    }

    /**
     * The entry for [event], replacing one computed from a superseded instance of the same id —
     * a response landing on its request's row changes both halves of what we cached.
     */
    private fun freshEntry(event: LogEvent): Entry {
        val existing = entries[event.id]
        if (existing != null && existing.event === event) return existing
        if (existing != null) {
            chars -= existing.text?.length ?: 0
            entries.remove(event.id)
        }
        return Entry(event).also { entries[event.id] = it }
    }

    /** Oldest-first eviction on either bound. A dropped entry is recomputed if it's needed again. */
    private fun evict() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxEntries || chars > maxChars)) {
            chars -= iterator.next().value.text?.length ?: 0
            iterator.remove()
        }
    }
}
