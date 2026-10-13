package io.github.siddharthjaswal.logpose.desktop.waterfall

import io.github.siddharthjaswal.logpose.analysis.CorrelationIndex
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which grouping each waterfall entry point opens, and which tabs come along. */
class FlowsTest {

    private val events = Fixtures.m4aStore().snapshot()
    private fun ev(id: String) = events.first { it.id == id }
    private val index = CorrelationIndex().apply { setKeys(listOf(CorrelationKey("order_id"))); events.forEach(::warm) }
    private val orderKey = Grouping(Grouping.Kind.KEY, "order_id", "OR-881")
    private val trace = Grouping(Grouping.Kind.TRACE, null, "trc-7f3a9c21")

    @Test fun `the flow button opens the configured key before the trace, with both as tabs`() {
        val r = Flows.fromRowButton(ev("d2"), index.matchable(ev("d2")))!!
        assertEquals(orderKey, r.grouping)
        assertEquals(listOf(orderKey, trace), r.alternatives)
    }

    @Test fun `with no key configured the flow button falls back to the trace, and a bare row opens nothing`() {
        assertEquals(WaterfallRequest(trace, listOf(trace)), Flows.fromRowButton(ev("d2"), emptyMap()))
        assertNull(Flows.fromRowButton(ev("e1"), emptyMap()))
    }

    @Test fun `a trace chip and find-by-value open alone, without the last row's tabs`() {
        assertEquals(WaterfallRequest(trace), Flows.fromTraceChip("trc-7f3a9c21"))
        val v = Grouping(Grouping.Kind.VALUE, null, "OR-883")
        assertEquals(WaterfallRequest(v), Flows.fromFind(v))
    }

    @Test fun `a menu item opens its grouping and the switcher keeps the row's tabs`() {
        val r = Flows.fromMenu(trace, ev("d2"), index.matchable(ev("d2")))
        assertEquals(trace, r.grouping)
        assertEquals(listOf(orderKey, trace), Flows.switchTo(r, orderKey).alternatives)
        assertEquals(orderKey, Flows.switchTo(r, orderKey).grouping)
    }

    @Test fun `members - a trace by equality, a key by its value anywhere (paths too)`() {
        assertEquals(listOf("h1", "d2"), Flows.membersOf(trace, events, index.keys(), index::textOf).map { it.id })
        val byKey = Flows.membersOf(orderKey, events, index.keys(), index::textOf).map { it.id }
        assertTrue(byKey.containsAll(listOf("h1", "s1", "d1", "d2")), "$byKey")
        assertFalse("f1" in byKey) // carries OR-883
        // A too-short pasted value groups nothing rather than over-grouping.
        assertEquals(emptyList<LogEvent>(), Flows.membersOf(Grouping(Grouping.Kind.VALUE, null, "88"), events, index.keys(), index::textOf))
    }

    @Test fun `the flow button paints only where the plugin paints it`() {
        val view = TimelineViews.build(events, FilterState(types = EventType.entries.toSet()), hasKey = index::hasCachedKeyValue)
        assertTrue("d2" in view.flowable)                 // traced + keyed
        assertTrue("s1" !in view.flowable)                // a collapsed run never paints buttons…
        assertTrue("x1" !in view.flowable)                // …nor a pending call (its time cell is the live timer)
        assertTrue("w-up" !in view.flowable)              // …nor a running worker
        assertTrue("f1" in view.flowable)                 // a traced push
        assertTrue("e1" !in view.flowable)                // nothing to group by
        assertFalse("d1" in TimelineViews.build(events, FilterState(types = EventType.entries.toSet())).flowable) // no key cached → no trace → nothing
    }

    @Test fun `show in flow is offered only when no key item already opens the view`() {
        assertTrue(Flows.offersShowInFlow(listOf(trace)))
        assertFalse(Flows.offersShowInFlow(listOf(orderKey, trace)))
        assertFalse(Flows.offersShowInFlow(emptyList()))
    }
}
