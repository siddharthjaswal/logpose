package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.Envelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared row arithmetic, as every host reads it. The plugin keeps its own `RowGeometryTest`
 * against its JBUI-scaled facade; this pins the core object itself — the numbers the Mac app's
 * Compose rows lay out with (identity scale, dp) and the plugin's Swing rows (JBUI.scale) alike.
 */
class RowGeometryTest {

    private val x1 = RowGeometry.UNSCALED
    private val x2: (Int) -> Int = { it * 2 }
    private val width = 400

    @Test fun `content edge is the glyph edge plus the glyph cell plus the gap`() {
        assertEquals(42, RowGeometry.contentEdge())
        assertEquals(84, RowGeometry.contentEdge(x2))
    }

    @Test fun `http path starts after method, gap, status and text gap`() {
        assertEquals(156, RowGeometry.contentEdge() + RowGeometry.METHOD + RowGeometry.GAP + RowGeometry.STATUS + RowGeometry.TEXT_GAP)
    }

    @Test fun `time and size cells sit right-to-left from the inset with a dead gap`() {
        val time = RowGeometry.timeCell(width)
        val size = RowGeometry.sizeCell(width)
        assertEquals(width - 14 - 56, time.first)
        assertEquals(width - 14 - 1, time.last)
        assertEquals(width - 14 - 56 - 10 - 64, size.first)
        assertEquals(RowGeometry.META_GAP, time.first - size.last - 1)
        for (x in (width - RowGeometry.EDGE) until width) assertFalse(x in time)
    }

    @Test fun `scaled cells sum scaled parts`() {
        val time = RowGeometry.timeCell(width, x2)
        assertEquals(width - 28 - 112, time.first)
        assertEquals(128, RowGeometry.sizeCell(width, x2).let { it.last - it.first + 1 })
    }

    @Test fun `only analytics widens its fact column, and only with room`() {
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS))
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS, width, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_ANALYTICS, 300, x1))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_ANALYTICS, 700, x2))
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS, 800, x2))
        listOf(Envelope.KIND_DB, Envelope.KIND_WORKER, Envelope.KIND_CONFIG, Envelope.KIND_EVENT).forEach {
            assertEquals(120, RowGeometry.fact(it, width, x1))
        }
    }

    @Test fun `row heights are the normal and the folded`() {
        assertEquals(34, RowGeometry.HEIGHT)
        assertEquals(26, RowGeometry.FOLD_HEIGHT)
        assertTrue(RowGeometry.FOLD_HEIGHT < RowGeometry.HEIGHT)
    }
}
