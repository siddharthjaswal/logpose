package io.github.siddharthjaswal.logpose.ui

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Envelope

/**
 * The alignment rules every timeline row obeys, as plain numbers.
 *
 * The redesign replaced four fixed columns with **two shared rules** — a glyph edge and a content
 * edge — plus a right-aligned meta pair. HTTP still subdivides its content zone (method, status,
 * then text); FCM and generic rows start their text at the content edge instead of reserving two
 * empty 46px columns they never filled.
 *
 * Everything reads its numbers from here: the layout that *paints* a row, and the hit test that
 * *routes a click on it*. That is the point of the object — the action buttons are now real
 * painted cells rather than invisible pixel bands, so a button drawn somewhere a click can't find
 * it would be a silent, unreproducible bug. One source of truth makes that impossible.
 *
 * Values are logical px. Callers pass the scaling function ([JBUI.scale] in production) so the
 * arithmetic itself stays pure — and testable without an IDE.
 */
object RowGeometry {

    /** Rule 1: the row's left/right inset. */
    const val EDGE = 14

    /** Rule 1: the glyph cell, holding a 16×16 kind icon. */
    const val GLYPH = 18

    /** Rule 2: the gap after the glyph cell. The content edge is [EDGE] + [GLYPH] + [GAP] = 42. */
    const val GAP = 10

    /** HTTP only: the method column, inside the content zone. */
    const val METHOD = 46

    /** HTTP only: the status pill column. */
    const val STATUS = 46

    /** HTTP only: the gap between the status pill and the path. */
    const val TEXT_GAP = 12

    /** The gap between a row's trailing pills (MOCK, DUP, INJ) and the text they follow. */
    const val PILL_GAP = 8

    /** Right-hand pair, first cell: response size (HTTP) or a one-word fact (FCM). */
    const val SIZE = 64

    /** Generic rows carry no size, so their fact column takes the freed width — `+N` never again. */
    const val FACT = 120

    /**
     * Analytics names the screen it fired on in the fact column, and `NavDrawerFragment` does not
     * fit in 120.
     */
    const val FACT_ANALYTICS = 150

    /**
     * Below this row width the analytics fact column gives its extra 30px back to the title.
     *
     * The fixed structure of a widened analytics row is 42 (content edge) + 150 + 10 + 56 + 14 =
     * 272px. At the 300px low end that leaves the event name ~28px — an immediate ellipsis on the
     * one thing the row exists to say. The screen is still in full on the detail card, so the fact
     * cell is what yields.
     */
    const val FACT_WIDE_ABOVE = 380

    /**
     * The fact column width for a kind, given the row's width.
     *
     * Only the *left* cell of the meta pair varies: [TIME] is 56 for every kind and [timeCell] is
     * derived from the row's right inset, so a wider fact column can only ever eat into the
     * flexible text zone — it can never move a timestamp out of line with the row above it.
     *
     * There is deliberately no `factCell()` hit-test helper: the generic fact cell is never an
     * action button (only HTTP's [sizeCell] is), so nothing routes a click there.
     */
    fun fact(kind: String, rowWidth: Int = Int.MAX_VALUE, scale: (Int) -> Int = JBUI::scale): Int = when {
        kind != Envelope.KIND_ANALYTICS -> FACT
        rowWidth != Int.MAX_VALUE && rowWidth < scale(FACT_WIDE_ABOVE) -> FACT
        else -> FACT_ANALYTICS
    }

    /** Right-hand pair, second cell. Identical across kinds, so timestamps line up. */
    const val TIME = 56

    /**
     * The gap between the two right-hand cells — and so the dead band between the two action
     * buttons, which is what keeps a click near their boundary from being ambiguous.
     */
    const val META_GAP = 10

    /** Where every non-HTTP row's text starts, and where HTTP's method column starts. */
    fun contentEdge(scale: (Int) -> Int = JBUI::scale): Int = scale(EDGE) + scale(GLYPH) + scale(GAP)

    /**
     * Row-local x range of the time/duration cell — and so the `⇉ flow` button's hit target.
     *
     * Derived right-to-left from the row's own right inset, exactly as `BorderLayout.EAST` lays
     * the cells out, and by summing individually-scaled constants rather than scaling their sum,
     * because that is what the struts and fixed sizes in the layout do.
     */
    fun timeCell(rowWidth: Int, scale: (Int) -> Int = JBUI::scale): IntRange {
        val right = rowWidth - scale(EDGE)
        return (right - scale(TIME)) until right
    }

    /** Row-local x range of the size cell — and so the `⧉ cURL` button's hit target (HTTP only). */
    fun sizeCell(rowWidth: Int, scale: (Int) -> Int = JBUI::scale): IntRange {
        val right = timeCell(rowWidth, scale).first - scale(META_GAP)
        return (right - scale(SIZE)) until right
    }
}
