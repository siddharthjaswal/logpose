package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The desktop's timeline pipeline: it must decide nothing core doesn't — so these pin that the
 * folds, the counts and the filtered-to-nothing state come out as core's rules say, in the plugin
 * panel's order (duplicates over the whole capture, filter, then collapse).
 */
class TimelineViewTest {

    private val events = Fixtures.m4aStore().snapshot()
    private val all = FilterState(types = EventType.entries.toSet())

    @BeforeEach fun freshMutes() { MutedEndpoints.store = KeyValueStore.InMemory() }

    @Test fun `db stays opt-in and the counter says shown over total`() {
        val view = TimelineViews.build(events, FilterState())
        assertTrue(view.filtered.none { it.kind == "db" })
        assertEquals("${view.shown}/${events.size}", view.countLabel)
        assertNull(view.empty)
    }

    @Test fun `a polling wall folds, a transaction's ceremony folds, wrapped statements stay`() {
        val view = TimelineViews.build(events, all)
        val poll = view.rows.single { it.row.anchorId == Fixtures.POLL_COLLAPSED_ANCHOR }
        assertTrue(poll is RowCells.Http && poll.poll != null)
        assertEquals("×6", (poll as RowCells.Http).poll!!.count)
        assertEquals("~44ms", poll.poll!!.median)
        val txn = view.rows.single { it is RowCells.Txn } as RowCells.Txn
        assertEquals(" · 2 statements · 128ms", txn.fold.tail)
        assertTrue(view.rows.any { it.row.lead.id == "t2" } && view.rows.any { it.row.lead.id == "t3" })
        // Every event comes back out inside exactly one row.
        assertEquals(view.filtered.map { it.id }.toSet(), view.rows.flatMap { it.row.memberIds }.toSet())
    }

    @Test fun `expanding a group by its anchor key survives a rebuild, and pruning keeps it`() {
        val key = RowCollapse.keyOf(RowCollapse.GroupKind.NET_POLL, Fixtures.POLL_EXPANDED_ANCHOR)
        val view = TimelineViews.build(events, all, setOf(key))
        assertEquals(listOf("s1", "s2", "s3", "s4"), view.rows.map { it.row.lead.id }.filter { it.startsWith("s") })
        assertEquals(setOf(key), TimelineViews.prune(setOf(key, "NET_POLL:gone"), view))
        // The expanded run can still be collapsed from any of its members.
        val member = view.rows.first { it.row.lead.id == "s3" }.row
        assertEquals(key, TimelineViews.naturalGroupFor(view, member, setOf(key))?.key)
    }

    @Test fun `a strong double-submit is marked and never folded, the mocked one keeps its mark`() {
        val view = TimelineViews.build(events, all)
        val d2 = view.rows.single { it.row.lead.id == "d2" } as RowCells.Http
        assertEquals(DuplicateDetector.Severity.STRONG, d2.duplicate?.severity)
        assertTrue(d2.content.mocked)
        assertEquals(DuplicateDetector.Severity.MEDIUM, view.duplicateMarks["r2"]?.severity)
    }

    @Test fun `muting fades and shortens the row, and hide noise drops it`() {
        MutedEndpoints.toggle(events.first { it.id == "m1" }.let { (it as io.github.siddharthjaswal.logpose.model.LogEvent.Http).tx })
        val view = TimelineViews.build(events, all)
        val m1 = view.rows.single { it.row.lead.id == "m1" } as RowCells.Http
        assertTrue(m1.muted && m1.short)
        assertTrue(TimelineViews.build(events, all.copy(hideNoise = true)).rows.none { it.row.lead.id == "m1" })
    }

    @Test fun `live rows are the pending call and the running worker`() {
        val view = TimelineViews.build(events, all)
        assertEquals(setOf("x1", "w-up"), view.rows.filter { it.live }.map { it.row.lead.id }.toSet())
    }

    @Test fun `body search reaches the haystack hook only from three characters`() {
        val haystack = { e: io.github.siddharthjaswal.logpose.model.LogEvent -> if (e.id == "h1") "has_more eta_min" else "" }
        assertTrue(TimelineViews.build(events, FilterState(urlQuery = "eta", textOf = haystack)).filtered.any { it.id == "h1" })
        assertFalse(TimelineViews.build(events, FilterState(urlQuery = "et", textOf = haystack)).filtered.any { it.id == "h1" })
    }

    @Test fun `filtered to nothing offers the loosener core measured`() {
        val view = TimelineViews.build(events, FilterState(types = setOf(EventType.CONF), statusClasses = setOf(4)))
        val empty = assertNotNull(view.empty).let { view.empty!! }
        assertEquals(FilterPresentation.headline(events.size), empty.headline)
        // Dropping TYPES shows the one 4xx call; dropping STATUS shows the one config event — a tie,
        // so the first candidate in priority order wins.
        assertEquals(FilterPresentation.FilterId.STATUS, empty.loosener?.id)
    }

    @Test fun `filter edits clear what the plugin's controls clear`() {
        val busy = FilterState(urlQuery = "x", methods = setOf("POST"), statusClasses = setOf(5), hideNoise = true, duplicatesOnly = true, types = setOf(EventType.NET))
        assertEquals(FilterState(urlQuery = "x", types = setOf(EventType.NET)), FilterEdits.clearHidden(busy))
        assertEquals(FilterState(), FilterEdits.clearAll(busy))
        assertEquals(setOf(EventType.NET, EventType.DB), FilterEdits.loosen(busy, FilterPresentation.FilterId.DB_OPT_IN).types)
        val modeOff = FilterEdits.switchMode(busy, FilterPresentation.Explainer.Action.SHOW_ALL_KINDS)
        assertTrue(modeOff.methods.isEmpty() && modeOff.statusClasses.isEmpty())
        assertEquals(setOf(EventType.NET, EventType.FCM), FilterEdits.toggleType(busy, EventType.FCM).types)
    }
}
