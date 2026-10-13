package io.github.siddharthjaswal.logpose.ui

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.presentation.RowGeometry as Shared

/**
 * The plugin's handle on the shared row geometry ([io.github.siddharthjaswal.logpose.presentation.RowGeometry]
 * in `:core`), with [JBUI.scale] as the default scaling function.
 *
 * The numbers and the arithmetic live in core, so the Mac app's rows and the tool window's rows
 * read the same constants (PRD §11, "paint parity drift"). This object only binds the IDE's
 * scaling: every call site in the renderer and the click routing keeps reading `RowGeometry.X` and
 * keeps getting HiDPI-correct results without passing a scale itself.
 */
object RowGeometry {
    const val EDGE = Shared.EDGE
    const val GLYPH = Shared.GLYPH
    const val GAP = Shared.GAP
    const val METHOD = Shared.METHOD
    const val STATUS = Shared.STATUS
    const val TEXT_GAP = Shared.TEXT_GAP
    const val PILL_GAP = Shared.PILL_GAP
    const val SIZE = Shared.SIZE
    const val FACT = Shared.FACT
    const val FACT_ANALYTICS = Shared.FACT_ANALYTICS
    const val FACT_WIDE_ABOVE = Shared.FACT_WIDE_ABOVE
    const val TIME = Shared.TIME
    const val META_GAP = Shared.META_GAP

    fun fact(kind: String, rowWidth: Int = Int.MAX_VALUE, scale: (Int) -> Int = JBUI::scale): Int =
        Shared.fact(kind, rowWidth, scale)

    fun contentEdge(scale: (Int) -> Int = JBUI::scale): Int = Shared.contentEdge(scale)

    fun timeCell(rowWidth: Int, scale: (Int) -> Int = JBUI::scale): IntRange = Shared.timeCell(rowWidth, scale)

    fun sizeCell(rowWidth: Int, scale: (Int) -> Int = JBUI::scale): IntRange = Shared.sizeCell(rowWidth, scale)
}
