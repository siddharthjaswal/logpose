package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class M4bMenuAndChipTest {

    private val events = Fixtures.m4aStore().snapshot()
    private val all = FilterState(types = EventType.entries.toSet())
    private val view = TimelineViews.build(events, all)
    private fun row(id: String) = view.rowFor(id)!!.row
    private val key = Grouping(Grouping.Kind.KEY, "order_id", "OR-881")
    private val trace = Grouping(Grouping.Kind.TRACE, null, "trc-7f3a9c21")

    @BeforeEach fun freshMutes() { MutedEndpoints.store = KeyValueStore.InMemory() }

    @Test fun `key items lead, an http row with a key gets no Show in flow, Advanced adds Mock this endpoint`() {
        val d2 = row("d2")
        val labels = RowMenu.items(d2, d2.lead, null, MenuContext(listOf(key, trace), advanced = true)).map { it.label }
        assertEquals("Show waterfall  —  order_id OR-881", labels[0])
        assertEquals("Filter by order_id OR-881", labels[1])
        assertTrue("Show in flow" !in labels)
        assertTrue("Mock this endpoint…" in labels)
        assertTrue(labels.none { it.contains("trace") }, "http rows have no trace pair: $labels")
    }

    @Test fun `a traced http row with no key offers Show in flow, and no Advanced item while Advanced is off`() {
        val h1 = row("h1")
        val items = RowMenu.items(h1, h1.lead, null, MenuContext(listOf(trace)))
        val flow = items.single { it.label == "Show in flow" }
        assertEquals(trace, (flow.command as RowCommand.ShowWaterfall).request.grouping)
        assertTrue(items.none { it.label == "Mock this endpoint…" })
    }

    @Test fun `a push offers re-send and compose under Advanced, then its trace pair`() {
        val f1 = row("f1")
        val t = Grouping(Grouping.Kind.TRACE, null, "trc-91be004d")
        val labels = RowMenu.items(f1, f1.lead, null, MenuContext(listOf(t), advanced = true)).map { it.label }
        assertEquals(listOf("Re-send this push", "Compose push…", "Copy as JSON", "Copy data payload", "Show waterfall  —  trace trc-91be004d", "Filter by trace trc-91be004d"), labels)
    }

    @Test fun `the correlation chip narrows the list, echoes, and is measured as a loosener`() {
        val chip = TimelineViews.build(
            events, all, correlation = key,
            inGroup = { e, g -> Correlation.containsValue(Correlation.searchableText(e), g.value) },
        )
        assertTrue(chip.shown in 2 until view.shown, "${chip.shown}")
        assertTrue(chip.filtered.all { Correlation.matches(it, "OR-881") })
        assertEquals(key, chip.correlation)
        val echo = FilterPresentation.echoChips(all, key.shortLabel, narrow = false)
        assertEquals(FilterPresentation.FilterId.CORRELATION, echo.single().id)
        assertEquals("order_id OR-881", echo.single().label)

        // Filtered to nothing with the chip on: the chip is one of the measured looseners.
        val empty = TimelineViews.build(
            events, FilterState(types = setOf(EventType.CONF)), correlation = key,
            inGroup = { e, g -> Correlation.containsValue(Correlation.searchableText(e), g.value) },
        ).empty!!
        assertTrue(empty.loosener != null)
    }
}
