package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.Envelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The row's column arithmetic, pinned away from Swing.
 *
 * This is the contract that lets the timeline's action buttons be *painted* rather than be
 * invisible pixel bands: the layout places the two right-hand cells from these numbers, and the
 * click routing decides what was hit from the same ones. The cases that matter are the ones a
 * hand-written band used to get subtly wrong — the exact edges, the dead gap between the two
 * buttons, and the row inset past the right-hand one — because each of them is a click that lands
 * on nothing, or on the wrong thing, with no visible symptom.
 */
class RowGeometryTest {

    /** Identity scaling, so the assertions read as the spec's own logical px. */
    private val x1: (Int) -> Int = { it }

    /** A 200% display, where scaling the *sum* instead of the parts would drift. */
    private val x2: (Int) -> Int = { it * 2 }

    private val width = 400

    @Test
    fun `content edge is the glyph edge plus the glyph cell plus the gap`() {
        assertEquals(42, RowGeometry.contentEdge(x1))
        assertEquals(84, RowGeometry.contentEdge(x2))
    }

    @Test
    fun `http subdivides its content zone into method, status and text`() {
        // The one rule HTTP adds: 46 + 10 + 46 + 12 past the content edge is where the path starts,
        // and every HTTP row in the list agrees on it.
        val pathStart = RowGeometry.contentEdge(x1) +
            RowGeometry.METHOD + RowGeometry.GAP + RowGeometry.STATUS + RowGeometry.TEXT_GAP
        assertEquals(156, pathStart)
    }

    @Test
    fun `time cell sits one row inset in from the right edge`() {
        val time = RowGeometry.timeCell(width, x1)
        assertEquals(width - 14 - 56, time.first)
        assertEquals(width - 14 - 1, time.last)
    }

    @Test
    fun `size cell sits one meta gap left of the time cell`() {
        val size = RowGeometry.sizeCell(width, x1)
        assertEquals(width - 14 - 56 - 10 - 64, size.first)
        assertEquals(width - 14 - 56 - 10 - 1, size.last)
    }

    @Test
    fun `the two action cells never overlap and keep a dead gap between them`() {
        val size = RowGeometry.sizeCell(width, x1)
        val time = RowGeometry.timeCell(width, x1)
        assertTrue(size.last < time.first, "cells overlap: $size / $time")
        // Exactly the meta gap of dead space — a click near the boundary can only mean one of the
        // two, never both and never the wrong one.
        assertEquals(RowGeometry.META_GAP, time.first - size.last - 1)
    }

    @Test
    fun `the row inset past the time cell belongs to neither button`() {
        val time = RowGeometry.timeCell(width, x1)
        for (x in (width - RowGeometry.EDGE) until width) {
            assertFalse(x in time, "the right inset at $x must not be part of the flow button")
        }
    }

    @Test
    fun `cell edges are inclusive at the start and exclusive at the end`() {
        val size = RowGeometry.sizeCell(width, x1)
        assertTrue(size.first in size)
        assertTrue((size.last) in size)
        assertFalse((size.first - 1) in size)
        assertFalse((size.last + 1) in size)
        assertEquals(RowGeometry.SIZE, size.last - size.first + 1)
    }

    @Test
    fun `cells scale by summing scaled parts, not by scaling the sum`() {
        val time = RowGeometry.timeCell(width, x2)
        val size = RowGeometry.sizeCell(width, x2)
        assertEquals(width - 28 - 112, time.first)
        assertEquals(112, time.last - time.first + 1)
        assertEquals(128, size.last - size.first + 1)
        assertEquals(20, time.first - size.last - 1)
    }

    @Test
    fun `a generic row's wider fact column never moves the time column`() {
        // The whole point of widening it: `hyperlocal_feature_db` fits, and a db row's timestamp
        // still lines up with an HTTP row's duration directly above it.
        assertTrue(RowGeometry.FACT > RowGeometry.SIZE)
        assertEquals(RowGeometry.timeCell(width, x1), RowGeometry.timeCell(width, x1))
        assertEquals(width - 70, RowGeometry.timeCell(width, x1).first)
    }

    @Test
    fun `only analytics widens its fact column, and only when there is room`() {
        // §6 gives the screen name 150px; every other kind keeps 120. The clamp is what stops a
        // 300px tool window from spending half its row on a fact and ellipsizing the event name.
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS, width, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_ANALYTICS, 300, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_DB, width, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_WORKER, width, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_CONFIG, width, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_EVENT, width, x1))
        // The clamp is a *scaled* comparison: on a 200% display a 700px row is 350 logical px and
        // must clamp exactly as a 350px row does at 100%.
        assertEquals(120, RowGeometry.fact(Envelope.KIND_ANALYTICS, 700, x2))
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS, 800, x2))
    }

    @Test
    fun `the widened analytics fact column still leaves the row a text zone`() {
        val fixed = RowGeometry.contentEdge(x1) + RowGeometry.fact(Envelope.KIND_ANALYTICS, width, x1) +
            RowGeometry.META_GAP + RowGeometry.TIME + RowGeometry.EDGE
        assertTrue(fixed < width, "no room left for the event name")
        // And it cannot have moved the timestamp: timeCell is derived from the right inset alone.
        assertEquals(RowGeometry.timeCell(width, x1), RowGeometry.timeCell(width, x1))
        assertEquals(width - 70, RowGeometry.timeCell(width, x1).first)
    }

    @Test
    fun `a narrow panel still leaves the two cells in order`() {
        // 300px is the low end of the panel widths the redesign targets; the meta pair is fixed, so
        // it's the text zone that gives, and the cells must not invert.
        val narrow = RowGeometry.sizeCell(300, x1)
        val narrowTime = RowGeometry.timeCell(300, x1)
        assertTrue(narrow.first > RowGeometry.contentEdge(x1), "meta pair collided with the content edge")
        assertTrue(narrow.last < narrowTime.first)
    }

    /** Moved here with the :core split — [RowGeometry] keeps its JBUI-scaled defaults, so it
     *  stays plugin-side while the rest of the row's content model went to `presentation`. */
    @Test fun `only analytics widens the fact column`() {
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_DB))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_WORKER))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_CONFIG))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_EVENT))
    }
}
