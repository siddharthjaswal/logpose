package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TimelineItemsTest {
    private val rows = Fixtures.store().snapshot().map { Rows.of(RowCollapse.Row.Single(it), emptyMap()) }
    private val t0 = rows.first().row.lead.timestampMillis

    private fun keys(items: List<TimelineItem>) = items.map {
        when (it) {
            is TimelineItem.Event -> it.cells.row.lead.id
            is TimelineItem.Mark -> "B${it.number}"
            is TimelineItem.Shot -> "S"
        }
    }

    @Test fun `no marks is just the rows`() {
        assertEquals(rows.map { it.row.lead.id }, keys(TimelineItems.merge(rows, SessionMarks.EMPTY)))
    }

    @Test fun `a marker goes before the first row that started after it, numbered in the order made`() {
        val marks = SessionMarks(
            bookmarks = listOf(Bookmark(t0 + 4_500, "accept"), Bookmark(t0 + 950)),
            shots = listOf(ShotMark(ScreenshotRef(t0 + 2_100, "s.png"))),
        )
        val k = keys(TimelineItems.merge(rows, marks))
        assertEquals(listOf("h1", "h2", "B2", "d1", "h3", "S", "w1", "h4", "B1", "f1"), k.take(10))
        assertEquals(rows.size + 3, k.size)
    }

    @Test fun `markers after every row trail the list`() {
        val k = keys(TimelineItems.merge(rows, SessionMarks(bookmarks = listOf(Bookmark(t0 + 99_000)))))
        assertEquals("B1", k.last())
    }
}
