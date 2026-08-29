package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.model.LogEvent
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.ToolTipManager

/**
 * The fourth detail card: one flow, drawn as a waterfall.
 *
 * A flow answers "what did this push actually set off?", and the answer is mostly about *time* —
 * what blocked what, what overlapped, what is still running. A list can't show that, so this is a
 * custom-painted panel: one lane per event in arrival order, all sharing one time axis.
 *
 * What *makes* a flow is a [Grouping], not a trace id: `order_id 21053953` groups across traces
 * and across events that carry no trace at all, which is the case a trace structurally cannot
 * reach. The header states which grouping is on screen and offers the others the same row belongs
 * to, so widening (`trace`) or narrowing (`trip_id`) never means going back to the list.
 *
 * Four disciplines hold it together:
 *  - **All arithmetic lives in [WaterfallLayout]**, which imports no Swing and is unit-tested, and
 *    every non-pixel decision — the word in the duration column, what counts as a failure, the
 *    axis strings, the pulse curve — lives in [WaterfallPresentation]. This class turns those into
 *    pixels and nothing else.
 *  - **The data is an immutable snapshot**, handed in by the panel that owns the store. Nothing
 *    here reads the store, and in particular painting never does — the store's monitor is held by
 *    the logcat reader thread on every arriving event, and blocking a repaint on it is how a busy
 *    capture would turn into a stuttering IDE.
 *  - **Nothing is measured per lane that a fixed cell could hold instead.** The duration column is
 *    64px and right-aligned in a monospaced font, so its text is placed by arithmetic on one
 *    character advance, not by measuring seven strings on every frame.
 *  - **Colours come from [Theme] only**, so the card follows the active IDE theme.
 *
 * Clicking a lane selects that event's row in the timeline, which is also how you leave the card:
 * the selection switches the detail pane back to that row's own view. While the card is up the
 * selected row's lane carries the *same* treatment the list row does — an `accentTint` fill and a
 * 2px accent rail — because list and waterfall are one selection model, not two.
 */
class TraceWaterfallPanel(
    /** How long the plugin has been watching an event id — used to project "now" for open spans. */
    private val hostAge: (String) -> Long,
    /** Selects an event's row in the timeline list. */
    private val onSelectEvent: (String) -> Unit,
    /** Re-opens the card for another grouping the same row belongs to. */
    private val onSwitchGrouping: (Grouping) -> Unit = {},
    /** Opens the keys dialog — the header is the second entry point the PRD asks for. */
    private val onEditKeys: () -> Unit = {},
    /** Opens "Find by value…", which is how an empty group stops being a dead end. */
    private val onFindByValue: () -> Unit = {},
) : JPanel(BorderLayout()) {

    /**
     * The laid-out flow. Swapped in wholesale by [relayout]; the paint path only ever reads it,
     * which is what keeps painting free of both the store and any recomputation.
     */
    private var model: WaterfallLayout.Layout? = null
    private var events: List<LogEvent> = emptyList()
    private var grouping: Grouping? = null

    /** The list's selected row, so its lane can wear the same selection the row does. */
    private var selectedId: String? = null

    /** Animation frame for the braille spinner, driven by the owner's 250ms live timer. */
    private var spinnerFrame = 0

    /**
     * The open-span breath.
     *
     * A 1.6s ease can't be read off a 250ms tick — six samples a cycle is a stutter, not a breath —
     * so the pulse gets its own fast timer and its own clock. [pulseAnchor] is reset each time the
     * timer starts, so a span that appears mid-cycle begins at the bottom of the curve rather than
     * wherever the phase happened to be.
     */
    private var pulseAnchor = System.nanoTime()
    private val pulseTimer = Timer(PULSE_INTERVAL_MILLIS) { canvas.repaint() }.apply { isRepeats = true }

    // ---- header ---------------------------------------------------------------------------------

    /** `order_id` — the *name* of the grouping, in the UI font. */
    private val headlineKey = JBLabel().apply {
        foreground = Theme.text
        font = JBUI.Fonts.label(13f).asBold()
    }

    /** `21053953` — the value, in mono accent, because it's an identifier you may want to read off. */
    private val headlineValue = JBLabel().apply {
        foreground = Theme.accent
        font = JBUI.Fonts.create("JetBrains Mono", 13).asBold()
    }

    private val subline = JBLabel().apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
    }

    private val switcher = Switcher { onSwitchGrouping(it) }

    /** Hidden with the switcher, so a single grouping leaves no gap where a control isn't. */
    private val switcherGap = Box.createHorizontalStrut(JBUI.scale(12))

    private val canvas = Canvas()

    /** The identity block: what this flow is, and what it's made of. */
    private val identity = JPanel(BorderLayout())

    /** The controls: two links and the grouping switcher. */
    private val actions = JPanel()

    private val header = JPanel(BorderLayout())

    /** True while [actions] sits on its own row under [identity]. */
    private var headerStacked = false

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty()

        val headline = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            headlineKey.alignmentY = Component.CENTER_ALIGNMENT
            headlineValue.alignmentY = Component.CENTER_ALIGNMENT
            add(headlineKey)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(headlineValue)
            add(Box.createHorizontalGlue())
        }
        identity.apply {
            isOpaque = false
            add(headline, BorderLayout.NORTH)
            add(subline, BorderLayout.CENTER)
        }
        actions.apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            // Leading glue, so the controls stay right-aligned in both header shapes.
            add(Box.createHorizontalGlue())
            add(link("Find by value…") { onFindByValue() })
            add(Box.createHorizontalStrut(JBUI.scale(12)))
            add(link("Correlation keys…") { onEditKeys() })
            add(switcherGap)
            add(switcher)
        }
        switcherGap.isVisible = false
        header.apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Theme.borderStrong, 0, 0, 1, 0),
                JBUI.Borders.empty(12, 16),
            )
            // The identity block takes the slack, so a long path is what ellipsizes rather than the
            // controls being pushed off the edge.
            add(identity, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) = reflowHeader()
            })
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

        // The pulse must not burn a timer behind a hidden card. A card switch, a collapsed tool
        // window and a closed project all surface here as "no longer showing".
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) syncPulse()
        }
    }

    /**
     * Puts the controls on their own row when the two halves stop fitting side by side.
     *
     * A 380px tool window can't hold `order_id 21053953` *and* two links *and* a three-tab switcher
     * on one line, and `BorderLayout` resolves that by giving the east block its full width and the
     * identity nothing — which erases the one thing the header exists to say. Stacking costs a row
     * of height and keeps both.
     */
    private fun reflowHeader() {
        val insets = header.insets
        // Not "does everything fit" — the subline is long by nature — but "does the identity still
        // have room to name the flow". Below that, the row is worth spending.
        val forIdentity = header.width - insets.left - insets.right -
            actions.preferredSize.width - JBUI.scale(16)
        val stack = header.width > 0 && forIdentity < JBUI.scale(MIN_IDENTITY_WIDTH)
        if (stack == headerStacked) return
        headerStacked = stack
        header.remove(actions)
        actions.border = if (stack) JBUI.Borders.emptyTop(8) else JBUI.Borders.empty()
        header.add(actions, if (stack) BorderLayout.SOUTH else BorderLayout.EAST)
        header.revalidate()
        header.repaint()
    }

    /** The grouping currently on screen, or null when the card has never been shown. */
    fun grouping(): Grouping? = grouping

    /** True while any lane is still open — the owner's live timer only needs to tick then. */
    fun hasOpenSpans(): Boolean = model?.hasOpenSpans == true

    /**
     * Shows [events] — an immutable snapshot, already grouped by [grouping], in arrival order.
     * Safe to call on every refresh tick: it recomputes the layout and repaints, nothing more.
     *
     * [alternatives] are the other groupings the originating row belongs to; the switcher appears
     * only when there is more than one, since a single tab is a label, not a control.
     *
     * [selectedId] is the timeline's selected row, so the lane that *is* that row reads as selected.
     */
    fun show(
        grouping: Grouping,
        events: List<LogEvent>,
        alternatives: List<Grouping> = emptyList(),
        selectedId: String? = null,
    ) {
        this.grouping = grouping
        this.events = events
        this.selectedId = selectedId
        switcher.set(alternatives, grouping)
        switcherGap.isVisible = switcher.isVisible
        // The switcher appearing changes what fits on the header's one row.
        reflowHeader()
        relayout()
    }

    /**
     * Re-points the selection highlight without touching the layout.
     *
     * Selection is a paint decision and nothing else here depends on it, so this repaints and
     * stops — it must stay cheap enough to call from the list's selection listener.
     */
    fun setSelectedId(id: String?) {
        if (selectedId == id) return
        selectedId = id
        canvas.repaint()
    }

    /** Re-projects "now" so open spans keep growing, and advances the spinner. */
    fun tick(frame: Int) {
        spinnerFrame = frame
        if (hasOpenSpans()) relayout() else canvas.repaint()
    }

    /** Stops the animation timer; called when the owning tool window goes away. */
    fun dispose() = pulseTimer.stop()

    /**
     * Recomputes the layout. Deliberately *not* done while painting: [hostAge] reads the store,
     * which the reader thread holds while it records an event.
     */
    private fun relayout() {
        val grouping = grouping ?: return
        val now = WaterfallLayout.projectedNow(events, hostAge)
        // The layout is pure geometry and has no opinion about grouping — it takes the value the
        // lanes were gathered by, and the panel owns everything about *why* they were gathered.
        val computed = WaterfallLayout.of(grouping.value, events, now)
        model = computed
        headlineKey.text = grouping.tab
        headlineValue.text = grouping.value
        val tip = when (grouping.kind) {
            Grouping.Kind.KEY -> "Grouped by the correlation key ${grouping.key} — every event carrying ${grouping.value}"
            Grouping.Kind.VALUE -> "Every event carrying ${grouping.value}"
            Grouping.Kind.TRACE -> "Grouped by the device's trace id"
        }
        headlineKey.toolTipText = tip
        headlineValue.toolTipText = tip
        subline.text = summaryOf(computed)
        canvas.preferredSize = Dimension(JBUI.scale(320), canvasHeight(computed))
        canvas.revalidate()
        canvas.repaint()
        syncPulse()
    }

    /** Starts the breath only where it can be seen, and stops it everywhere else. */
    private fun syncPulse() {
        val wanted = isShowing && hasOpenSpans()
        if (wanted && !pulseTimer.isRunning) {
            pulseAnchor = System.nanoTime()
            pulseTimer.start()
        } else if (!wanted && pulseTimer.isRunning) {
            pulseTimer.stop()
        }
    }

    /**
     * The header's second line: the facts worth stating about a whole flow.
     *
     * Mixed fonts on one line, so it's built as HTML — the path is an identifier and reads as one
     * only in mono. This runs once per relayout, never per frame, which is what makes an HTML label
     * an acceptable way to get two fonts into one line.
     */
    private fun summaryOf(layout: WaterfallLayout.Layout): String {
        if (layout.isEmpty) {
            val copy = if (grouping?.isTrace == false) "no events carry that value"
            else "no events — the trace may have scrolled out of the capture buffer"
            return "<html>${escape(copy)}</html>"
        }
        val count = if (layout.eventCount == 1) "1 event" else "${layout.eventCount} events"
        val span = "spanning ${WaterfallPresentation.humanMillis(layout.wallSpanMillis)}"
        val slowest = layout.slowest?.let {
            val label = escape(ellipsize(KindPresenter.rowLabel(it.event), 42))
            val running = if (it.isOpen) ", still running" else ""
            val took = WaterfallPresentation.humanMillis(it.durationMillis)
            " &nbsp;·&nbsp; slowest <font face='JetBrains Mono'>$label</font> ($took$running)"
        } ?: ""
        return "<html>$count &nbsp;·&nbsp; $span$slowest</html>"
    }

    private fun canvasHeight(layout: WaterfallLayout.Layout): Int {
        if (layout.isEmpty) return JBUI.scale(PAD_TOP + PAD_BOTTOM)
        return JBUI.scale(PAD_TOP) +
            layout.lanes.size * JBUI.scale(ROW_HEIGHT) +
            JBUI.scale(AXIS_GAP + AXIS_STRIP + PAD_BOTTOM)
    }

    /**
     * A lane's hue: its kind, except for a failed HTTP call, which takes the danger colour.
     *
     * **Failure is the only recolor in the waterfall.** A lane that is red is a lane that broke —
     * nothing else in this card may borrow the colour, or red stops being an answer.
     */
    private fun laneColor(event: LogEvent): Color =
        if (WaterfallPresentation.isFailure(event)) Theme.danger else Theme.typeColor(event.kind)

    private fun laneTooltip(lane: WaterfallLayout.Lane): String {
        val layout = model ?: return ""
        val offset = if (!lane.timed) "no device timestamp"
        else "+${WaterfallPresentation.humanMillis(lane.startMillis - layout.startMillis)} into the trace"
        val length = when {
            !lane.timed -> ""
            lane.isOpen -> "<br/>still running after ${WaterfallPresentation.humanMillis(lane.durationMillis)}"
            lane.isPoint -> ""
            else -> "<br/>took ${WaterfallPresentation.humanMillis(lane.durationMillis)}"
        }
        return "<html><b>${escape(KindPresenter.rowLabel(lane.event))}</b><br/>" +
            "${KindPresenter.kindLabel(lane.event)}  ·  $offset$length" +
            "<br/><i>click to open this row</i></html>"
    }

    // ---- glyphs ---------------------------------------------------------------------------------

    /** Cache key for a lane glyph: which icon, tinted to what, at which device scale. */
    private data class GlyphKey(val kind: String, val tint: Int, val scale: Double)

    private val glyphs = HashMap<GlyphKey, BufferedImage>()

    /**
     * The kind glyph as a 16px image, optionally flooded with [tint] through `SrcAtop` — which
     * keeps the icon's own alpha (and so its antialiased edges) while replacing the hue baked into
     * the SVG. Only a failed HTTP lane is tinted; every other lane draws the icon's own kind hue.
     *
     * Cached on kind, tint and device scale, so a pulsing repaint at 12fps never re-renders an
     * icon, and a theme or display change simply misses the cache.
     */
    private fun glyph(kind: String, tint: Color?, g2: Graphics2D, host: JComponent): BufferedImage? {
        val icon = TypeIcons.forKind(kind)
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return null
        val key = GlyphKey(kind, tint?.rgb ?: 0, g2.transform.scaleX)
        glyphs[key]?.let { return it }
        val image = ImageUtil.createImage(g2, w, h, BufferedImage.TYPE_INT_ARGB)
        val ig = image.createGraphics()
        try {
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            icon.paintIcon(host, ig, 0, 0)
            if (tint != null) {
                ig.composite = AlphaComposite.SrcAtop
                ig.color = tint
                ig.fillRect(0, 0, w, h)
            }
        } finally {
            ig.dispose()
        }
        glyphs[key] = image
        return image
    }

    // ---- painting -----------------------------------------------------------------------------

    /** The horizontal bands every lane shares, computed once per paint. */
    private class Geom(
        /** Left edge of the time axis — the gutter ends here. */
        val trackX: Int,
        /**
         * `trackRight - trackX`, and deliberately **not** floored at 1: it reaches zero on a panel
         * too narrow for the gutter minimum and the duration column together, which is precisely
         * the case a mark has to recognise instead of clamping itself into the column.
         */
        val trackW: Int,
        /** Right edge of the time axis. No mark may cross it into the duration column. */
        val trackRight: Int,
        /** Right edge of the fixed duration column; its text is right-aligned to it. */
        val durRight: Int,
        val lanesTop: Int,
    )

    /** The fonts and the one character advance the paint pass needs, resolved once per paint. */
    private class Fonts(host: JComponent) {
        val monoLane: Font = mono(10.5f)
        val labelLane: Font = JBUI.Fonts.label(11.5f)
        val duration: Font = mono(10f)
        val durationBold: Font = duration.deriveFont(Font.BOLD)
        val spinner: Font = mono(11f).deriveFont(Font.BOLD)
        val axis: Font = mono(9f)

        /**
         * One character's advance in the duration font — all the column ever prints is ASCII in a
         * monospaced face, so right-aligning it is `right - advance * length` and never a
         * `stringWidth` call per lane. This is the measurement the fixed column exists to delete.
         */
        val durationAdvance: Int = host.getFontMetrics(duration).charWidth('0')
        val durationBoldAdvance: Int = host.getFontMetrics(durationBold).charWidth('0')
        val spinnerAdvance: Int = host.getFontMetrics(spinner).charWidth('0')

        private companion object {
            /** JetBrains Mono at a fractional design size, scaled once for the display. */
            fun mono(size: Float): Font =
                JBUI.Fonts.create("JetBrains Mono", size.toInt()).deriveFont(JBUIScale.scale(size))
        }
    }

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
            val top = JBUI.scale(PAD_TOP)
            val idx = (y - top) / JBUI.scale(ROW_HEIGHT)
            return if (y >= top && idx in lanes.indices) idx else -1
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

                val pad = JBUI.scale(PAD_X)
                val durRight = width - pad
                // The duration column is inviolable. The gutter yields first — a clipped name
                // still reads and a bar drawn under a number does not — and when even that isn't
                // enough the track goes to nothing and [paintMark] drops the mark entirely rather
                // than letting it land on the column. So [Geom.trackW] is reported honestly, zero
                // or negative included: clamping it to 1 hid the case where trackX had overtaken
                // trackRight, and the mark clamps then resolved to the gutter side of the column.
                val trackRight = durRight - JBUI.scale(DURATION_WIDTH)
                val trackX = (pad + JBUI.scale(GUTTER_WIDTH))
                    .coerceAtMost(trackRight - JBUI.scale(MIN_TRACK_WIDTH))
                    .coerceAtLeast(pad)
                val geom = Geom(
                    trackX = trackX,
                    trackW = trackRight - trackX,
                    trackRight = trackRight,
                    durRight = durRight,
                    lanesTop = JBUI.scale(PAD_TOP),
                )
                val fonts = Fonts(this)

                // Backgrounds first, gridlines over them, content last: a selected lane's tint must
                // not swallow the axis it is read against.
                layout.lanes.forEach { paintLaneBackground(g2, it, geom) }
                paintGridlines(g2, layout, geom)
                layout.lanes.forEach { paintLane(g2, layout, it, geom, fonts) }
                paintAxisLabels(g2, layout, geom, fonts)
            } finally {
                g2.dispose()
            }
        }

        /** Hover and selection, both full-width — the lane is the row, so it highlights like one. */
        private fun paintLaneBackground(g2: Graphics2D, lane: WaterfallLayout.Lane, geom: Geom) {
            val rowH = JBUI.scale(ROW_HEIGHT)
            val top = geom.lanesTop + lane.index * rowH
            val arc = JBUI.scale(6)
            val selected = lane.id == selectedId
            when {
                selected -> {
                    g2.color = Theme.accentTint
                    g2.fillRoundRect(0, top, width, rowH, arc, arc)
                    // The same rail the list row paints: 2px, inset, radius 2. One selection model.
                    val inset = JBUI.scale(3)
                    val railArc = JBUI.scale(2)
                    g2.color = Theme.accent
                    g2.fillRoundRect(0, top + inset, JBUI.scale(2), rowH - inset * 2, railArc, railArc)
                }
                lane.index == hovered -> {
                    g2.color = Theme.rowHover
                    g2.fillRoundRect(0, top, width, rowH, arc, arc)
                }
            }
        }

        /** Five faint verticals across the lanes only — the axis strip below carries the numbers. */
        private fun paintGridlines(g2: Graphics2D, layout: WaterfallLayout.Layout, geom: Geom) {
            val bottom = geom.lanesTop + layout.lanes.size * JBUI.scale(ROW_HEIGHT)
            g2.color = Theme.borderSubtle
            for (i in 0..AXIS_TICKS) {
                val x = geom.trackX + (geom.trackW * WaterfallPresentation.axisFraction(i, AXIS_TICKS)).toInt()
                g2.drawLine(x, geom.lanesTop, x, bottom)
            }
        }

        /**
         * The relative-time strings, in a strip *under* the canvas rather than a header band over
         * it — a scale belongs beneath what it measures, and moving it there gives the lanes the
         * full height of the card.
         *
         * Each label is left-aligned on its gridline, the last one included: the duration column
         * leaves 64px of empty strip to its right, which is where that label lands. Positioning by
         * arithmetic rather than by measuring keeps the paint pass free of `stringWidth`.
         */
        private fun paintAxisLabels(
            g2: Graphics2D,
            layout: WaterfallLayout.Layout,
            geom: Geom,
            fonts: Fonts,
        ) {
            val strip = geom.lanesTop + layout.lanes.size * JBUI.scale(ROW_HEIGHT) + JBUI.scale(AXIS_GAP)
            g2.font = fonts.axis
            g2.color = Theme.textMuted
            val baseline = strip + g2.fontMetrics.ascent
            WaterfallPresentation.axisLabels(layout.wallSpanMillis, AXIS_TICKS).forEachIndexed { i, label ->
                val x = geom.trackX + (geom.trackW * WaterfallPresentation.axisFraction(i, AXIS_TICKS)).toInt()
                g2.drawString(label, x + JBUI.scale(2), baseline)
            }
        }

        private fun paintLane(
            g2: Graphics2D,
            layout: WaterfallLayout.Layout,
            lane: WaterfallLayout.Lane,
            geom: Geom,
            fonts: Fonts,
        ) {
            val rowH = JBUI.scale(ROW_HEIGHT)
            val top = geom.lanesTop + lane.index * rowH
            val midY = top + rowH / 2
            val failed = WaterfallPresentation.isFailure(lane.event)
            val color = laneColor(lane.event)

            paintGutter(g2, lane, geom, fonts, midY, failed)
            paintMark(g2, lane, geom, color, failed, midY, fonts)
            paintDuration(g2, layout, lane, geom, fonts, midY)
        }

        /** The glyph and the event's name. A failed HTTP call says so in both. */
        private fun paintGutter(
            g2: Graphics2D,
            lane: WaterfallLayout.Lane,
            geom: Geom,
            fonts: Fonts,
            midY: Int,
            failed: Boolean,
        ) {
            val pad = JBUI.scale(PAD_X)
            val side = JBUI.scale(GLYPH_SIZE)
            val image = glyph(lane.event.kind, if (failed) Theme.danger else null, g2, this)
            if (image != null) {
                runCatching {
                    UIUtil.drawImage(
                        g2, image,
                        Rectangle(pad, midY - side / 2, side, side),
                        Rectangle(0, 0, image.width, image.height),
                        null,
                    )
                }
            }

            val label = WaterfallPresentation.laneLabel(lane.event)
            g2.font = if (label.mono) fonts.monoLane else fonts.labelLane
            val fm = g2.fontMetrics
            val textX = pad + side + JBUI.scale(8)
            val available = geom.trackX - textX - JBUI.scale(8)
            val statusWidth = label.status?.let { fm.stringWidth(" $it") } ?: 0
            val text = fit(fm, label.text, available - statusWidth)
            val baseline = midY + fm.ascent / 2 - JBUI.scale(1)
            g2.color = if (lane.timed) Theme.text else Theme.textMuted
            g2.drawString(text, textX, baseline)
            label.status?.let {
                g2.color = Theme.danger
                g2.drawString(it, textX + fm.stringWidth("$text "), baseline)
            }
        }

        /** The mark itself: a bar for a span, a dot for a moment, a ring for a clockless event. */
        private fun paintMark(
            g2: Graphics2D,
            lane: WaterfallLayout.Lane,
            geom: Geom,
            color: Color,
            failed: Boolean,
            midY: Int,
            fonts: Fonts,
        ) {
            // Nothing left of the track: the duration column has taken the width, so there is no
            // mark to draw. Dropping it is the point — the clamps below would otherwise resolve to
            // `trackX`, which on such a canvas already sits inside the column.
            if (geom.trackW < JBUI.scale(2)) return
            val startX = geom.trackX + (geom.trackW * lane.startFraction).toInt()
            val endX = geom.trackX + (geom.trackW * lane.endFraction).toInt()
            // A dot never wider than the track it lives on, so its clamp can't invert.
            val dot = JBUI.scale(POINT_SIZE).coerceAtMost(geom.trackW)

            when {
                // No device timestamp: a hollow marker at the origin, never a confident dot.
                !lane.timed -> {
                    g2.color = Theme.textMuted
                    g2.drawOval(dotX(startX, dot, geom), midY - dot / 2, dot - 1, dot - 1)
                }
                lane.isPoint -> {
                    g2.color = color
                    g2.fillOval(dotX(startX, dot, geom), midY - dot / 2, dot, dot)
                }
                else -> {
                    val barH = JBUI.scale(BAR_HEIGHT)
                    val barY = midY - barH / 2
                    // A span of no measurable width still gets 2px, and no span is ever wider than
                    // the track: clamping the *width* as well as the position is what keeps the
                    // position clamp from inverting on a canvas with almost no track left.
                    val w = (endX - startX).coerceAtLeast(JBUI.scale(2)).coerceAtMost(geom.trackW)
                    val barX = startX.coerceAtMost(geom.trackRight - w).coerceAtLeast(geom.trackX)
                    val arc = JBUI.scale(4)
                    val alpha = when {
                        // An open span breathes, so "still running" reads the same here as it does
                        // on the row and the status dot.
                        lane.isOpen -> WaterfallPresentation.pulseAlpha(
                            (System.nanoTime() - pulseAnchor) / 1_000_000L,
                        )
                        failed -> WaterfallPresentation.FAILED_ALPHA
                        else -> WaterfallPresentation.SPAN_ALPHA
                    }
                    g2.color = Theme.tint(color, alpha)
                    g2.fillRoundRect(barX, barY, w, barH, arc, arc)
                    g2.color = color
                    g2.drawRoundRect(barX, barY, w - 1, barH - 1, arc, arc)
                    // The spinner rides the bar's trailing edge — the growing end is where the eye
                    // already is — and is held inside the track so it can't sit on top of the
                    // duration column. A track with no room for the glyph simply doesn't get one.
                    if (lane.isOpen && geom.trackW >= fonts.spinnerAdvance) {
                        g2.font = fonts.spinner
                        val sx = (barX + w + JBUI.scale(4))
                            .coerceAtMost(geom.trackRight - fonts.spinnerAdvance)
                            .coerceAtLeast(geom.trackX)
                        g2.color = color
                        g2.drawString(
                            spinnerChar(spinnerFrame).toString(),
                            sx,
                            midY + g2.fontMetrics.ascent / 2 - JBUI.scale(1),
                        )
                    }
                }
            }
        }

        /**
         * A dot is centred on its instant, but never allowed to hang outside the track. [d] has
         * already been capped at the track width, so `trackRight - d` can't fall left of [trackX]
         * and collapse the range onto the wrong side of the duration column.
         */
        private fun dotX(x: Int, d: Int, geom: Geom): Int =
            (x - d / 2).coerceIn(geom.trackX, maxOf(geom.trackX, geom.trackRight - d))

        /**
         * The fixed 64px duration column.
         *
         * Right-aligned by arithmetic on one monospaced advance — no bar can reach it (the track
         * stops 64px short) and no string is measured to place it.
         *
         * Two of the four voices are a shade off the spec, both for contrast on `bg0`: the
         * ordinary one is `textDim` rather than `textMuted` (which reads 2.78:1 light / 3.23:1
         * dark — it is what this text was before the redesign), and a running one is `accentHover`
         * rather than `accent` (4.03/3.85). Both are existing tokens and neither changes a value.
         */
        private fun paintDuration(
            g2: Graphics2D,
            layout: WaterfallLayout.Layout,
            lane: WaterfallLayout.Lane,
            geom: Geom,
            fonts: Fonts,
            midY: Int,
        ) {
            val cell = WaterfallPresentation.durationCell(lane, layout.startMillis, layout.slowest?.id)
            val bold = cell.tone == WaterfallPresentation.Tone.SLOWEST
            g2.font = if (bold) fonts.durationBold else fonts.duration
            g2.color = when (cell.tone) {
                WaterfallPresentation.Tone.SLOWEST -> Theme.text
                WaterfallPresentation.Tone.RUNNING -> Theme.accentHover
                WaterfallPresentation.Tone.FAILED -> Theme.danger
                WaterfallPresentation.Tone.MUTED -> Theme.textDim
            }
            val advance = if (bold) fonts.durationBoldAdvance else fonts.durationAdvance
            val x = geom.durRight - advance * cell.text.length
            g2.drawString(cell.text, x, midY + g2.fontMetrics.ascent / 2 - JBUI.scale(1))
        }

        /** Truncates [text] with an ellipsis so it fits [maxWidth] pixels in [fm]'s font. */
        private fun fit(fm: FontMetrics, text: String, maxWidth: Int): String {
            if (maxWidth <= 0) return ""
            if (fm.stringWidth(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && fm.stringWidth(text.take(end) + "…") > maxWidth) end--
            return text.take(end) + "…"
        }
    }

    /** A small accent-coloured action in the header — the card's own way back out to a dialog. */
    private fun link(text: String, action: () -> Unit) = LinkLabel(text, action)

    /**
     * The grouping switcher: `order_id` / `trip_id` / `trace`.
     *
     * A row usually belongs to several groupings at once, and the useful move after reading one
     * is almost always to widen or narrow it. Doing that in place — rather than back in the list,
     * on a row you have to find again — is the point.
     *
     * Drawn as the design system's **Segmented** control: one `bg3` track with a `bg0` thumb under
     * the selected tab, rather than three separate chips. Three chips read as three independent
     * toggles; a segmented track reads as one choice with three positions, which is what it is.
     */
    private class Switcher(private val onPick: (Grouping) -> Unit) : JPanel() {

        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(2)
            isVisible = false
        }

        fun set(options: List<Grouping>, selected: Grouping?) {
            removeAll()
            // One option is a statement, not a choice — the headline already says it.
            isVisible = options.size > 1
            if (isVisible) options.forEach { add(tab(it, it == selected)) }
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Theme.bg3
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }

        private fun tab(option: Grouping, selected: Boolean) = object : JBLabel(option.tab) {
            override fun paintComponent(g: Graphics) {
                if (selected) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Theme.bg0
                    val arc = JBUI.scale(6)
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 12)
            font = if (selected) JBUI.Fonts.label(11f).asBold() else JBUI.Fonts.label(11f)
            foreground = if (selected) Theme.text else Theme.textDim
            toolTipText = option.shortLabel
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (!selected) addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onPick(option)
                override fun mouseEntered(e: MouseEvent) { foreground = Theme.text }
                override fun mouseExited(e: MouseEvent) { foreground = Theme.textDim }
            })
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    companion object {
        private const val ROW_HEIGHT = 24
        private const val GUTTER_WIDTH = 190
        private const val DURATION_WIDTH = 64

        /** Below this the axis has nothing left to say, so the gutter gives up its width first. */
        private const val MIN_TRACK_WIDTH = 24
        private const val PAD_X = 16
        private const val PAD_TOP = 10
        private const val PAD_BOTTOM = 6
        private const val AXIS_GAP = 6
        private const val AXIS_STRIP = 14
        private const val BAR_HEIGHT = 10
        private const val POINT_SIZE = 8
        private const val GLYPH_SIZE = 13
        private const val AXIS_TICKS = 4

        /** ~12fps: enough samples for a 1.6s ease to read as a breath, cheap enough to ignore. */
        private const val PULSE_INTERVAL_MILLIS = 80

        /** Room for `order_id 21053953` and a few words of the subline; below it, the header stacks. */
        private const val MIN_IDENTITY_WIDTH = 180
    }
}
