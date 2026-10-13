package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RowMenuTest {

    private val events = Fixtures.m4aStore().snapshot()
    private val view = TimelineViews.build(events, FilterState(types = EventType.entries.toSet()))
    private fun row(id: String) = view.rowFor(id)!!.row

    @BeforeEach fun freshMutes() { MutedEndpoints.store = KeyValueStore.InMemory() }

    @Test fun `an http row offers the plugin's copies and the mute toggle`() {
        val h1 = row("h1")
        val items = RowMenu.items(h1, h1.lead, null)
        assertEquals(listOf("Copy as cURL", "Copy as JSON", "Copy URL", "Copy response body", "Mute  /v2/orders/active"), items.map { it.label })
        assertEquals(CurlBuilder.build((h1.lead as LogEvent.Http).tx), (items[0].command as RowCommand.Copy).text)
        MutedEndpoints.toggle((h1.lead as LogEvent.Http).tx)
        assertEquals(listOf("Unmute  /v2/orders/active", "Clear all mutes"), RowMenu.items(h1, h1.lead, null).map { it.label }.takeLast(2))
    }

    @Test fun `a collapsed run leads with expand, an expanded member with collapse`() {
        val poll = row(Fixtures.POLL_COLLAPSED_ANCHOR) as RowCollapse.Row.Group
        assertEquals("Expand 6 occurrences", RowMenu.items(poll, poll.lead, poll to false).first().label)
        assertEquals("Collapse 6 repeated calls", RowMenu.items(poll, poll.lead, poll to true).first().label)
        val txn = view.rows.first { it is RowCells.Txn }.row as RowCollapse.Row.Group
        assertEquals("Expand transaction", RowMenu.items(txn, txn.lead, txn to false).first().label)
    }

    @Test fun `fcm and structured rows copy their own json`() {
        assertEquals(listOf("Copy as JSON", "Copy data payload"), RowMenu.items(row("f1"), row("f1").lead, null).map { it.label })
        assertEquals(listOf("Copy as JSON", "Copy payload"), RowMenu.items(row("c1"), row("c1").lead, null).map { it.label })
    }

    @Test fun `copy timeline expands a fold into every member`() {
        val poll = row(Fixtures.POLL_COLLAPSED_ANCHOR)
        val lines = RowMenu.timelineLines(listOf(row("h1"), poll), events.associateBy { it.id })
        assertEquals(7, lines.size)
        assertEquals("Copy timeline (7 rows)", RowMenu.timelineItem(lines).label)
        assertTrue(lines.drop(1).all { it.startsWith("GET /v1/rider/heartbeat") })
    }

    @Test fun `selection toggles whole rows and extends ranges in list order`() {
        val poll = row(Fixtures.POLL_COLLAPSED_ANCHOR)
        val sel = Selection.toggle(emptySet(), poll)
        assertEquals(poll.memberIds.toSet(), sel)
        assertTrue(Selection.toggle(sel, poll).isEmpty())
        val rows = view.rows.map { it.row }
        val range = Selection.range(rows, "h1", rows[3])
        assertEquals(rows.take(4).flatMap { it.memberIds }.toSet(), range)
        assertTrue(Selection.isSelected(poll, null, setOf("p3")))
    }
}
