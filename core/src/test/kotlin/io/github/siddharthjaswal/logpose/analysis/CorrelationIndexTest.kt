package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cache that keeps correlation off the paint path.
 *
 * Being fast isn't the interesting part — being *honest* is. Events update in place, so a cached
 * answer must expire when the event it was computed from is replaced; a key change must expire
 * every extraction and nothing else; and the paint-safe read must be willing to say "don't know"
 * rather than start a payload scan inside a repaint.
 */
class CorrelationIndexTest {

    private val orderKey = CorrelationKey("order_id")

    private fun event(id: String, order: String? = null, note: String = "", at: Long = 1_000) =
        LogEvent.Generic(
            null,
            Envelope(
                kind = "app", id = id, at = at, endedAt = at,
                payload = buildJsonObject {
                    order?.let { put("order_id", it) }
                    put("note", note)
                },
            ),
        )

    // ---- extraction caching ------------------------------------------------------------------

    @Test fun `a warmed event answers from cache`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953")
        assertNull(index.cachedValues(e), "nothing is cached before the event arrives")
        index.warm(e)
        assertEquals(listOf(KeyValue("order_id", "21053953", true)), index.cachedValues(e))
        assertEquals(mapOf("order_id" to "21053953"), index.matchable(e))
        assertTrue(index.hasCachedKeyValue(e))
    }

    @Test fun `the paint-safe read never scans — it says it does not know`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953")
        assertNull(index.cachedValues(e))
        assertNull(index.cachedMatchable(e))
        assertFalse(index.hasCachedKeyValue(e))
        // The scanning read is a separate call, and it's the one an on-demand action uses.
        assertEquals(1, index.valuesOf(e).size)
        assertNotNull(index.cachedValues(e))
    }

    @Test fun `a value below the floor is extracted but not matchable`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21")
        index.warm(e)
        assertEquals(listOf(KeyValue("order_id", "21", false)), index.cachedValues(e))
        assertTrue(index.matchable(e).isEmpty())
        assertFalse(index.hasCachedKeyValue(e), "a short value must not offer a grouping")
    }

    @Test fun `no configured keys means no extraction at all`() {
        val index = CorrelationIndex()
        val e = event("e1", order = "21053953")
        index.warm(e)
        assertEquals(emptyList<KeyValue>(), index.cachedValues(e))
        assertFalse(index.hasCachedKeyValue(e))
    }

    // ---- invalidation ---------------------------------------------------------------------------

    @Test fun `changing the vocabulary expires every extraction`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953")
        index.warm(e)
        assertNotNull(index.cachedValues(e))

        index.setKeys(listOf(CorrelationKey("trip_id")))
        assertNull(index.cachedValues(e), "a stale extraction must not survive a key change")

        index.warmValues(e)
        assertEquals(emptyList<KeyValue>(), index.cachedValues(e))
    }

    @Test fun `a key change leaves the haystack alone`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953", note = "hello")
        index.warm(e)
        val before = index.size()
        index.setKeys(listOf(CorrelationKey("trip_id")))
        // The entry (and its text) is still there — only the extraction expired.
        assertEquals(before, index.size())
        assertTrue(index.textOf(e).contains("21053953"))
    }

    @Test fun `re-setting the same vocabulary is not a change`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953")
        index.warm(e)
        // Same keys, differently spelled input (a dialog OK with nothing edited).
        index.setKeys(listOf(CorrelationKey("order_id")))
        assertNotNull(index.cachedValues(e), "an unchanged key set must not throw the cache away")
    }

    @Test fun `an event replaced in place expires its own entry`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val request = event("e1", order = null, note = "request")
        index.warm(request)
        assertFalse(index.hasCachedKeyValue(request))

        // The store re-puts the same id when the response lands; that's a different instance with
        // different content, and both cached halves have to follow it.
        val completed = event("e1", order = "21053953", note = "response")
        assertNull(index.cachedValues(completed))
        assertEquals(mapOf("order_id" to "21053953"), index.matchable(completed))
        assertTrue(index.textOf(completed).contains("response"))
        assertFalse(index.textOf(completed).contains("\"request\""))
        assertEquals(1, index.size(), "the superseded instance must not leave a second entry")
    }

    // ---- haystacks --------------------------------------------------------------------------------

    @Test fun `the haystack is what grouping matches against`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val push = event("e1", order = "21053953")
        val unrelated = event("e2", note = "nothing to see")
        listOf(push, unrelated).forEach { index.warm(it) }

        val grouped = Correlation.group(
            listOf(push, unrelated), "order_id", "21053953", textOf = index::textOf,
        )
        assertEquals(listOf("e1"), grouped.map { it.id })
    }

    // ---- bounds ------------------------------------------------------------------------------------

    @Test fun `the entry count is bounded, oldest first`() {
        val index = CorrelationIndex(maxEntries = 2).apply { setKeys(listOf(orderKey)) }
        val events = (1..3).map { event("e$it", order = "2105395$it") }
        events.forEach { index.warm(it) }
        assertEquals(2, index.size())
        assertNull(index.cachedValues(events[0]), "the oldest entry is the one that goes")
        assertNotNull(index.cachedValues(events[2]))
    }

    @Test fun `the character budget is bounded too — haystacks are copies of bodies`() {
        val index = CorrelationIndex(maxChars = 64).apply { setKeys(listOf(orderKey)) }
        (1..8).forEach { index.warm(event("e$it", note = "x".repeat(40))) }
        assertTrue(index.size() < 8, "an unbounded cache would hold every body twice over")
        // A dropped entry is recomputed on demand, so eviction can never change an answer.
        val dropped = event("e1", order = "21053953", note = "x".repeat(40))
        assertEquals(mapOf("order_id" to "21053953"), index.matchable(dropped))
    }

    @Test fun `clear empties everything`() {
        val index = CorrelationIndex().apply { setKeys(listOf(orderKey)) }
        val e = event("e1", order = "21053953")
        index.warm(e)
        index.clear()
        assertEquals(0, index.size())
        assertNull(index.cachedValues(e))
        // The vocabulary is configuration, not capture — clearing the timeline doesn't forget it.
        assertEquals(listOf(orderKey), index.keys())
    }

    @Test fun `keys are normalized on the way in`() {
        val index = CorrelationIndex()
        index.setKeys(listOf(CorrelationKey("order_id"), CorrelationKey("orderId"), CorrelationKey("bad name")))
        assertEquals(listOf("order_id"), index.keys().map { it.name })
    }
}
