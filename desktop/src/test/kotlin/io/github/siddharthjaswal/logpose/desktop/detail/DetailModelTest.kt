package io.github.siddharthjaswal.logpose.desktop.detail

import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailModelTest {
    private val events = Fixtures.m4aStore().snapshot()
    private val view = TimelineViews.build(events, FilterState(types = EventType.entries.toSet()))
    private fun resolve(id: String?, pin: String? = null) = DetailModel.resolve(view, id, pin) { x -> events.firstOrNull { it.id == x } }

    @Test fun `a collapsed run opens on its latest call with a stepper`() {
        val t = resolve(Fixtures.POLL_COLLAPSED_ANCHOR)!!
        assertTrue(t.stepper)
        assertEquals("p6", t.event.id)
        assertEquals(5, t.index)
        assertEquals(6, t.occurrences!!.size)
    }

    @Test fun `a pin moves the card, a pin the run lost falls back to the latest`() {
        assertEquals("p2", resolve(Fixtures.POLL_COLLAPSED_ANCHOR, pin = "p2")!!.event.id)
        assertEquals("p6", resolve(Fixtures.POLL_COLLAPSED_ANCHOR, pin = "gone")!!.event.id)
    }

    @Test fun `a folded member resolves to its row, a folded transaction has no stepper`() {
        assertEquals("p6", resolve("p4")!!.event.id)
        val txn = resolve("t1")!!
        assertFalse(txn.stepper)
        assertEquals("t1", txn.event.id)
        assertNull(resolve(null))
    }

    @Test fun `a filtered-away selection still shows from the snapshot`() {
        val narrow = TimelineViews.build(events, FilterState())
        assertEquals("t2", DetailModel.resolve(narrow, "t2", null) { x -> events.firstOrNull { it.id == x } }!!.event.id)
    }
}
