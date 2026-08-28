package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.LogEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.ToolTipManager

/**
 * The fourth detail card: one trace, drawn as a waterfall.
 *
 * A trace answers "what did this push actually set off?", and the answer is mostly about *time* —
 * what blocked what, what overlapped, what is still running. A list can't show that, so this is a
 * custom-painted panel: one lane per event in arrival order, all sharing one time axis.
 *
 * Three disciplines hold it together:
 *  - **All arithmetic lives in [WaterfallLayout]**, which imports no Swing and is unit-tested.
 *    This class turns fractions into pixels and nothing else.
 *  - **The data is an immutable snapshot**, handed in by the panel that owns the store. Nothing
 *    here reads the store, and in particular painting never does — the store's monitor is held by
 *    the logcat reader thread on every arriving event, and blocking a repaint on it is how a busy
 *    capture would turn into a stuttering IDE.
 *  - **Colours come from [Theme] only**, so the card follows the active IDE theme.
 *
 * Clicking a lane selects that event's row in the timeline, which is also how you leave the card:
 * the selection switches the detail pane back to that row's own view.
 */
class TraceWaterfallPanel(
    /** How long the plugin has been watching an event id — used to project "now" for open spans. */
    private val hostAge: (String) -> Long,
    /** Selects an event's row in the timeline list. */
    private val onSelectEvent: (String) -> Unit,
) : JPanel(BorderLayout()) {

    /**
     * The laid-out trace. Swapped in wholesale by [relayout]; the paint path only ever reads it,
     * which is what keeps painting free of both the store and any recomputation.
     */
    private var model: WaterfallLayout.Layout? = null
    private var events: List<LogEvent> = emptyList()
    private var trace: String? = null

    /** Animation frame for the in-flight treatment, driven by the owner's live timer. */
    private var spinnerFrame = 0

    private val headline = JBLabel().apply {
        foreground = Theme.text
        font = JBUI.Fonts.label(12f).asBold()
    }
    private val subline = JBLabel().apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.create("JetBrains Mono", 11)
    }
    private val canvas = Canvas()

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty(8)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 8, 4)
            add(headline, BorderLayout.NORTH)
            add(subline, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(
            JBScrollPane(canvas).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = true
                viewport.background = Theme.bg0
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
        ToolTipManager.sharedInstance().registerComponent(canvas)
    }

    /** The trace currently on screen, or null when the card has never been shown. */
    fun traceId(): String? = trace

    /** True while any lane is still open — the owner's live timer only needs to tick then. */
    fun hasOpenSpans(): Boolean = model?.hasOpenSpans == true

    /**
     * Shows [events] — an immutable snapshot, already filtered to [traceId], in arrival order.
     * Safe to call on every refresh tick: it recomputes the layout and repaints, nothing more.
     */
    fun show(traceId: String, events: List<LogEvent>) {
        this.trace = traceId
        this.events = events
        relayout()
    }

    /** Re-projects "now" so open spans keep growing, and advances the spinner. */
    fun tick(frame: Int) {
        spinnerFrame = frame
        if (hasOpenSpans()) relayout() else canvas.repaint()
    }

    /**
     * Recomputes the layout. Deliberately *not* done while painting: [hostAge] reads the store,
     * which the reader thread holds while it records an event.
     */
    private fun relayout() {
        val traceId = trace ?: return
        val now = WaterfallLayout.projectedNow(events, hostAge)
        val computed = WaterfallLayout.of(traceId, events, now)
        model = computed
        headline.text = "Trace  $traceId"
        subline.text = summaryOf(computed)
        canvas.preferredSize = Dimension(JBUI.scale(320), canvasHeight(computed))
        canvas.revalidate()
        canvas.repaint()
    }

    /** The header's second line: the facts worth stating about a whole trace. */
    private fun summaryOf(layout: WaterfallLayout.Layout): String {
        if (layout.isEmpty) return "no events — the trace may have scrolled out of the capture buffer"
        val count = if (layout.eventCount == 1) "1 event" else "${layout.eventCount} events"
        val span = "spanning ${humanMillis(layout.wallSpanMillis)}"
        val slowest = layout.slowest?.let {
            val label = ellipsize(KindPresenter.rowLabel(it.event), 42)
            val running = if (it.isOpen) ", still running" else ""
            "  ·  slowest $label (${humanMillis(it.durationMillis)}$running)"
        } ?: ""
        return "$count  ·  $span$slowest"
    }

    private fun canvasHeight(layout: WaterfallLayout.Layout): Int =
        JBUI.scale(AXIS_HEIGHT) + layout.lanes.size * JBUI.scale(ROW_HEIGHT) + JBUI.scale(8)

    /**
     * A lane's hue: its kind, except for a failed HTTP call, which takes the danger colour. A
     * waterfall exists to find the thing that went wrong, so the thing that went wrong should not
     * be the same blue as everything else.
     */
    private fun laneColor(event: LogEvent): Color {
        if (event is LogEvent.Http) {
            val tx = event.tx
            if (tx.error != null || (tx.response?.code ?: 0) >= 400) return Theme.danger
        }
        return Theme.typeColor(event.kind)
    }

    private fun laneTooltip(lane: WaterfallLayout.Lane): String {
        val layout = model ?: return ""
        val offset = if (!lane.timed) "no device timestamp"
        else "+${humanMillis(lane.startMillis - layout.startMillis)} into the trace"
        val length = when {
            !lane.timed -> ""
            lane.isOpen -> "<br/>still running after ${humanMillis(lane.durationMillis)}"
            lane.isPoint -> ""
            else -> "<br/>took ${humanMillis(lane.durationMillis)}"
        }
        return "<html><b>${escape(KindPresenter.rowLabel(lane.event))}</b><br/>" +
            "${KindPresenter.kindLabel(lane.event)}  ·  $offset$length" +
            "<br/><i>click to open this row</i></html>"
    }

    // ---- painting -----------------------------------------------------------------------------

    /**
     * The drawing surface. Reads only [model] — an immutable value swapped in by [relayout] — so a
     * repaint can never block on the store.
     */
    private inner class Canvas : JComponent(), Scrollable {

        var hovered = -1

        init {
            isOpaque = true
            background = Theme.bg0
            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val idx = laneAt(e.y)
                    if (idx != hovered) {
                        hovered = idx
                        cursor = Cursor.getPredefinedCursor(
                            if (idx >= 0) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR,
                        )
                        repaint()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hovered != -1) { hovered = -1; repaint() }
                }

                override fun mouseClicked(e: MouseEvent) {
                    val lane = model?.lanes?.getOrNull(laneAt(e.y)) ?: return
                    onSelectEvent(lane.id)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        override fun getToolTipText(e: MouseEvent): String? =
            model?.lanes?.getOrNull(laneAt(e.y))?.let { laneTooltip(it) }

        // Lanes are full-width rows, so the canvas follows the viewport's width and only ever
        // scrolls vertically — a horizontal scrollbar under a time axis would be a lie.
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) =
            JBUI.scale(ROW_HEIGHT)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) =
            JBUI.scale(ROW_HEIGHT) * 4

        private fun laneAt(y: Int): Int {
            val lanes = model?.lanes ?: return -1
            val idx = (y - JBUI.scale(AXIS_HEIGHT)) / JBUI.scale(ROW_HEIGHT)
            return if (y >= JBUI.scale(AXIS_HEIGHT) && idx in lanes.indices) idx else -1
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
                g2.color = Theme.bg0
                g2.fillRect(0, 0, width, height)

                val layout = model ?: return
                if (layout.isEmpty) return

                val gutter = JBUI.scale(GUTTER_WIDTH)
                val trackX = gutter + JBUI.scale(8)
                val trackW = (width - trackX - JBUI.scale(TRAIL_WIDTH)).coerceAtLeast(JBUI.scale(40))

                paintAxis(g2, layout, trackX, trackW)
                layout.lanes.forEach { paintLane(g2, layout, it, gutter, trackX, trackW) }
            } finally {
                g2.dispose()
            }
        }

        /** Faint gridlines with relative-time labels: the axis every lane is read against. */
        private fun paintAxis(
            g2: Graphics2D,
            layout: WaterfallLayout.Layout,
            trackX: Int,
            trackW: Int,
        ) {
            val axisH = JBUI.scale(AXIS_HEIGHT)
            g2.font = JBUI.Fonts.create("JetBrains Mono", 9)
            for (i in 0..AXIS_TICKS) {
                val f = i.toDouble() / AXIS_TICKS
                val x = trackX + (trackW * f).toInt()
                g2.color = if (i == 0) Theme.borderStrong else Theme.borderSubtle
                g2.drawLine(x, axisH - JBUI.scale(4), x, height)
                val label = "+" + humanMillis((layout.wallSpanMillis * f).toLong())
                val w = g2.fontMetrics.stringWidth(label)
                g2.color = Theme.textMuted
                // The last tick's label would run off the right edge, so it hangs left of its line.
                g2.drawString(
                    label,
                    if (i == AXIS_TICKS) x - w else x + JBUI.scale(3),
                    axisH - JBUI.scale(7),
                )
            }
        }

        private fun paintLane(
            g2: Graphics2D,
            layout: WaterfallLayout.Layout,
            lane: WaterfallLayout.Lane,
            gutter: Int,
            trackX: Int,
            trackW: Int,
        ) {
            val rowH = JBUI.scale(ROW_HEIGHT)
            val top = JBUI.scale(AXIS_HEIGHT) + lane.index * rowH
            val midY = top + rowH / 2

            if (lane.index == hovered) {
                g2.color = Theme.rowHover
                g2.fillRect(0, top, width, rowH)
            }

            // Gutter: the kind glyph (which already carries its hue) plus the event's name.
            val icon = TypeIcons.forEvent(lane.event)
            icon.paintIcon(this, g2, JBUI.scale(4), midY - icon.iconHeight / 2)
            g2.font = JBUI.Fonts.label(11f)
            g2.color = if (lane.timed) Theme.text else Theme.textMuted
            val textX = JBUI.scale(4) + icon.iconWidth + JBUI.scale(6)
            g2.drawString(
                fit(g2, KindPresenter.rowLabel(lane.event), gutter - textX),
                textX,
                midY + g2.fontMetrics.ascent / 2 - JBUI.scale(1),
            )

            val color = laneColor(lane.event)
            val startX = trackX + (trackW * lane.startFraction).toInt()
            val endX = trackX + (trackW * lane.endFraction).toInt()

            when {
                // No device timestamp: a hollow marker at the origin, never a confident dot.
                !lane.timed -> {
                    val d = JBUI.scale(POINT_SIZE)
                    g2.color = Theme.textMuted
                    g2.drawOval(startX - d / 2, midY - d / 2, d, d)
                }
                lane.isPoint -> {
                    val d = JBUI.scale(POINT_SIZE)
                    g2.color = color
                    g2.fillOval(startX - d / 2, midY - d / 2, d, d)
                }
                else -> {
                    val barH = JBUI.scale(BAR_HEIGHT)
                    val barY = midY - barH / 2
                    val w = (endX - startX).coerceAtLeast(JBUI.scale(2))
                    val arc = JBUI.scale(4)
                    // An open span breathes, so "still running" reads the same here as it does on
                    // the row and the status dot.
                    g2.color = Theme.tint(color, if (lane.isOpen) pulseAlpha() else 70)
                    g2.fillRoundRect(startX, barY, w, barH, arc, arc)
                    g2.color = color
                    g2.drawRoundRect(startX, barY, w, barH, arc, arc)
                }
            }

            // Trailing text: the duration, or a spinner for something still in flight.
            g2.font = JBUI.Fonts.create("JetBrains Mono", 10)
            val trail = when {
                !lane.timed -> "—"
                lane.isOpen -> "${spinnerChar(spinnerFrame)} ${humanMillis(lane.durationMillis)}"
                lane.isPoint -> "+${humanMillis(lane.startMillis - layout.startMillis)}"
                else -> humanMillis(lane.durationMillis)
            }
            g2.color = if (lane.isOpen) Theme.accent else Theme.textDim
            // Clamped so a bar that reaches the right edge doesn't push its own label off-screen.
            val trailX = (endX + JBUI.scale(6))
                .coerceAtMost(width - JBUI.scale(TRAIL_WIDTH) + JBUI.scale(6))
            g2.drawString(trail, trailX, midY + g2.fontMetrics.ascent / 2 - JBUI.scale(1))
        }

        /** The breathing alpha for an open span — the same cadence as the status dot's pulse. */
        private fun pulseAlpha(): Int {
            val offset = (spinnerFrame % 20) - 10
            val phase = (offset * offset) / 100.0   // 1 at the extremes, 0 in the middle
            return (36 + phase * 44).toInt()
        }

        /** Truncates [text] with an ellipsis so it fits [maxWidth] pixels in the current font. */
        private fun fit(g2: Graphics2D, text: String, maxWidth: Int): String {
            val fm = g2.fontMetrics
            if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && fm.stringWidth(text.take(end) + "…") > maxWidth) end--
            return text.take(end) + "…"
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    companion object {
        private const val ROW_HEIGHT = 24
        private const val AXIS_HEIGHT = 20
        private const val GUTTER_WIDTH = 190
        private const val TRAIL_WIDTH = 78
        private const val BAR_HEIGHT = 10
        private const val POINT_SIZE = 8
        private const val AXIS_TICKS = 4

        /** Compact, honest durations: sub-second in millis, above that in seconds. */
        fun humanMillis(millis: Long): String = when {
            millis < 0 -> "—"
            millis < 1_000 -> "${millis}ms"
            millis < 60_000 -> "%.2fs".format(millis / 1000.0)
            else -> "%dm %02ds".format(millis / 60_000, (millis % 60_000) / 1000)
        }
    }
}
