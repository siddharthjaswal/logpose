package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The row bundle is core's words plus the host's clock; only the live text is computed here. */
class RowCellsTest {
    private val view = TimelineViews.build(Fixtures.m4aStore().snapshot(), FilterState(types = EventType.entries.toSet()))
    private fun cells(id: String) = view.rowFor(id)!!

    @Test fun `a pending call counts whole milliseconds, a running worker its run`() {
        assertEquals("1840ms", Rows.liveText(cells("x1"), 1_840))
        // w-up: run started 1.1s after the row was first seen, so 7.3s of age is 6.2s of run.
        assertEquals("6.2s", Rows.liveText(cells("w-up"), 7_300))
        assertEquals("0ms", Rows.liveText(cells("w-up"), 500))   // floored, never negative
        assertEquals("…", Rows.liveText(cells("x1"), null))
    }

    @Test fun `http, fcm and worker cells carry core's wording`() {
        val e1 = cells("e1") as RowCells.Http
        assertEquals("404", e1.content.status)
        assertEquals("88ms", e1.content.duration)
        val f1 = cells("f1") as RowCells.Fcm
        assertEquals("NOTIF", f1.content.label)
        assertTrue(f1.content.injected)
        val w = cells("w-up") as RowCells.Worker
        assertEquals("RETRY ×2", w.content.retry)
    }
}
