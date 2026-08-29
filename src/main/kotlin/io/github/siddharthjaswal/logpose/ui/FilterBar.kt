package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * Filterable event families in the unified stream.
 *
 * The kinds LogPose understands get their own chip; [APP] covers *every* app-defined kind
 * rather than getting one each, because that set is open (the point of the framework) and
 * rebuilding the segmented control whenever a new kind appears would make the bar jump around
 * mid-capture. Narrowing to one specific app kind is what the search box is for.
 */
enum class EventType { NET, FCM, DB, WORK, CONF, ANALYTICS, APP }

/** Structured filter state — replaces the free-text grammar with one-click toggles. */
data class FilterState(
    val urlQuery: String = "",
    val methods: Set<String> = emptySet(),
    val statusClasses: Set<Int> = emptySet(), // 2,3,4,5 -> 2xx..5xx
    val hideNoise: Boolean = false,
    /** Restrict to these event kinds; empty = show all. */
    val types: Set<EventType> = emptySet(),
    /**
     * "Duplicates only" — applied by the panel (not [matches]), since duplicate membership
     * is a property of the whole capture, not of a single event in isolation.
     */
    val duplicatesOnly: Boolean = false,
) {
    fun matches(event: LogEvent): Boolean = when (event) {
        is LogEvent.Http -> types.allowsHttp() && matchesHttp(event)
        is LogEvent.Fcm -> types.allowsFcm() && matchesFcm(event)
        is LogEvent.Db -> types.allowsDb() && matchesStructured(event)
        is LogEvent.Worker -> types.allows(EventType.WORK) && matchesStructured(event)
        is LogEvent.Config -> types.allows(EventType.CONF) && matchesStructured(event)
        is LogEvent.Generic ->
            // Analytics gets its own chip; every other app-defined kind falls under APP.
            if (event.kind == Envelope.KIND_ANALYTICS) types.allows(EventType.ANALYTICS) && matchesStructured(event)
            else types.allowsApp() && matchesStructured(event)
    }

    private fun Set<EventType>.allowsHttp() = isEmpty() || EventType.NET in this
    private fun Set<EventType>.allowsFcm() = isEmpty() || EventType.FCM in this
    private fun Set<EventType>.allowsApp() = allows(EventType.APP)
    private fun Set<EventType>.allows(type: EventType) = isEmpty() || type in this

    /**
     * DB is the one kind that must be asked for. A Room query callback outproduces every other
     * source by an order of magnitude — a real capture ran 75 queries against 12 requests — so
     * defaulting it visible buries the traffic people opened LogPose to see. Capture is
     * unaffected: the events are stored and stay available to the DB chip and to MCP.
     */
    private fun Set<EventType>.allowsDb() = EventType.DB in this

    private fun matchesHttp(event: LogEvent.Http): Boolean {
        val tx = event.tx
        if (urlQuery.isNotBlank() && !tx.request.url.contains(urlQuery, ignoreCase = true)) return false
        if (methods.isNotEmpty() && tx.request.method.uppercase() !in methods) return false
        if (statusClasses.isNotEmpty()) {
            val cls = (tx.response?.code ?: 0) / 100
            if (cls !in statusClasses) return false
        }
        if (hideNoise && MutedEndpoints.isMuted(tx)) return false
        return true
    }

    private fun matchesFcm(event: LogEvent.Fcm): Boolean {
        // HTTP-only chips (method / status) narrow to HTTP, so an active selection hides FCM.
        if (methods.isNotEmpty() || statusClasses.isNotEmpty()) return false
        if (urlQuery.isNotBlank()) {
            val m = event.msg
            val haystack = listOfNotNull(
                m.notification?.title, m.notification?.body, m.from, m.messageId,
                m.collapseKey, m.token,
            ) + m.data.flatMap { listOf(it.key, it.value) }
            if (haystack.none { it.contains(urlQuery, ignoreCase = true) }) return false
        }
        return true
    }

    /**
     * Search over whatever the row actually shows, for every non-HTTP/FCM kind. Going through
     * [KindPresenter] means a query matches the SQL table, the worker name or the changed flag
     * keys — the same words on screen — rather than raw payload JSON.
     */
    private fun matchesStructured(event: LogEvent): Boolean {
        // HTTP-only chips (method / status) narrow to HTTP, so an active selection hides these.
        if (methods.isNotEmpty() || statusClasses.isNotEmpty()) return false
        if (urlQuery.isNotBlank()) {
            val presentation = KindPresenter.present(event)
            val haystack = listOfNotNull(
                presentation?.title, presentation?.subtitle, event.kind, event.traceId, event.id,
            ) + presentation?.badges?.map { it.text }.orEmpty() +
                presentation?.sections?.map { it.label }.orEmpty()
            if (haystack.none { it.contains(urlQuery, ignoreCase = true) }) return false
        }
        return true
    }
}

/**
 * The filter bar: two rows, and a rule about which controls earn a permanent one.
 *
 * The **permanent row** holds only what is true of every capture — a search box, the seven kind
 * chips, and the count. Method, status, noise, dupes and correlation moved behind one `Filters`
 * button with a count badge, because in a 400px tool window they were pushing the kind chips off
 * the edge to state choices nobody had made.
 *
 * The **echo row** is what makes that safe: it exists only while a hidden filter is active, and
 * it says so as removable chips plus, when a narrowing has a side effect the controls don't show
 * (method/status hide every non-HTTP kind; db is opt-in), one line of plain English with a link
 * that switches the mode. A filter you can't see is a bug report waiting to happen.
 *
 * Below ~440px both rows compress: the kind chips become their glyphs and the search box takes
 * the freed width. Everything the bar *says* — badge count, chip labels, explainers — is derived
 * in [FilterPresentation], which is pure and unit-tested; this class only places it.
 */
class FilterBar : JPanel(BorderLayout()) {

    var onChange: () -> Unit = {}

    /** Opens "Find by value…" — a whole-capture search that needs no row to start from. */
    var onFindByValue: () -> Unit = {}

    /** Opens the correlation-keys dialog. */
    var onCorrelationKeys: () -> Unit = {}

    // ---- controls ------------------------------------------------------------------------------

    private val search = SearchField { changed() }

    // Method and status are *selections*, not statements of what a call was, so they light in
    // accent rather than in a per-verb or per-class hue: kind is the only axis that owns hue, and
    // a "2xx" chip lit in the (now neutral) 2xx colour would read as disabled.
    private val methods = MethodSegmented(listOf("GET", "POST", "PUT", "DELETE")) { changed() }

    private val statusChips = linkedMapOf(
        2 to chip("2xx", Theme.accent),
        3 to chip("3xx", Theme.accent),
        4 to chip("4xx", Theme.accent),
        5 to chip("5xx", Theme.accent),
    )

    private val hideNoise = ToggleSwitch { changed() }
    private val dupesOnly = ToggleSwitch { changed() }

    // Individual pills, each lit in its own type hue — the same colour as that kind's row gutter
    // icon, so filter state is legible from the rows alone. Below ~440px they render as the glyph
    // alone, which is why each one carries its icon.
    private val typeChips = linkedMapOf(
        EventType.NET to typeChip(EventType.NET),
        EventType.FCM to typeChip(EventType.FCM),
        EventType.DB to typeChip(
            EventType.DB,
            tip = "Database queries — hidden until you ask for them, since a busy screen can run " +
                "hundreds a minute. They are still captured and readable by a coding agent.",
        ),
        EventType.WORK to typeChip(EventType.WORK),
        EventType.CONF to typeChip(EventType.CONF),
        EventType.ANALYTICS to typeChip(EventType.ANALYTICS),
        EventType.APP to typeChip(EventType.APP),
    )

    private val count = JBLabel().apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.create(MONO, 11)
        border = JBUI.Borders.emptyLeft(8)
    }

    private val filtersButton = FiltersButton { anchor -> showFiltersPopover(anchor) }

    /**
     * The active correlation grouping, when the timeline is narrowed to one key value.
     *
     * Kept beside the chips rather than inside [FilterState] for the same reason "duplicates
     * only" is: deciding it needs the whole capture (a value can hide in a body the row never
     * shows), so the panel applies it against its cached haystacks and this bar only *states* it.
     */
    private var correlation: Grouping? = null

    /** Derived on every filter change by the panel — never during a paint. */
    private var hiddenDbMatch = false

    private var narrow = false

    private var popup: JBPopup? = null
    private var popoverContent: JComponent? = null

    /** When the popover last closed — see the dismiss guard in [showFiltersPopover]. */
    private var popoverClosedAt = 0L

    // ---- layout --------------------------------------------------------------------------------

    private val typeGaps = ArrayList<Gap>()
    private val spacer = Spacer()
    private val echo = EchoRow()

    init {
        isOpaque = false
        border = JBUI.Borders.empty()
        add(permanentRow(), BorderLayout.NORTH)
        add(echo, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyWidth(width)
        })
        syncDerived()
    }

    private fun permanentRow(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(7, 12)
        add(search)
        add(strut(8)); add(divider()); add(strut(8))
        typeChips.values.forEachIndexed { i, chip ->
            if (i > 0) add(Gap(8).also { typeGaps.add(it) })
            add(chip)
        }
        add(strut(8)); add(divider()); add(strut(8))
        add(filtersButton)
        add(spacer)
        add(count)
    }

    // ---- public API ----------------------------------------------------------------------------

    /**
     * Narrows the timeline to one correlation grouping, shown as a removable chip on the echo row —
     * the same way filtering by trace reads today, except that a key can say what it is grouping
     * by. Null clears it.
     */
    fun setCorrelationFilter(grouping: Grouping?) {
        if (correlation == grouping) return
        correlation = grouping
        changed()
    }

    fun correlationFilter(): Grouping? = correlation

    fun state() = FilterState(
        urlQuery = search.text.trim(),
        methods = methods.selection(),
        statusClasses = statusChips.filterValues { it.selected }.keys,
        hideNoise = hideNoise.on,
        types = typeChips.filterValues { it.selected }.keys,
        duplicatesOnly = dupesOnly.on,
    )

    fun setCount(shown: Int, total: Int) { count.text = "$shown/$total" }

    /** Drive the search box from elsewhere (e.g. "filter by this trace" in the row menu). */
    fun setQuery(text: String) {
        if (search.text != text) search.text = text // fires the document listener → changed()
    }

    /**
     * Whether the current search would have matched db events the DB opt-in is hiding — computed by
     * the panel on each filter change (it needs the capture) and pushed here, so the explainer is
     * a stored answer rather than a scan the echo row runs while painting.
     */
    fun setHiddenDbMatch(value: Boolean) {
        if (hiddenDbMatch == value) return
        hiddenDbMatch = value
        syncDerived()
    }

    /** Drops every filter, including the visible ones — the "Clear filters" escape hatch. */
    fun clearAllFilters() {
        FilterPresentation.FilterId.entries.forEach { clearQuietly(it) }
        changed()
    }

    /** Drops one filter — the echo row's chips, and the empty state's contextual loosener. */
    fun loosen(id: FilterPresentation.FilterId) {
        // The db opt-in is granted, not dropped: db is hidden until asked for, so the loosening
        // that shows it is *selecting* the chip. Every other id is a selection to clear.
        if (id == FilterPresentation.FilterId.DB_OPT_IN) typeChips[EventType.DB]?.setSelected(true)
        else clearQuietly(id)
        changed()
    }

    // ---- derived state -------------------------------------------------------------------------

    /** One place every control reports through: recompute what the bar says, then tell the panel. */
    private fun changed() {
        syncDerived()
        onChange()
    }

    private fun syncDerived() {
        val state = state()
        filtersButton.badge = FilterPresentation.badgeCount(state, correlation != null)
        echo.show(
            FilterPresentation.echoChips(state, correlation?.shortLabel, narrow),
            FilterPresentation.explainer(state, hiddenDbMatch, narrow),
        )
        revalidate(); repaint()
    }

    /** Clears one filter without reporting — so a bulk clear fires [onChange] exactly once. */
    private fun clearQuietly(id: FilterPresentation.FilterId) = when (id) {
        FilterPresentation.FilterId.STATUS -> statusChips.values.forEach { it.setSelected(false) }
        FilterPresentation.FilterId.METHOD -> methods.clearSelection()
        FilterPresentation.FilterId.CORRELATION -> correlation = null
        FilterPresentation.FilterId.HIDE_NOISE -> hideNoise.set(false)
        FilterPresentation.FilterId.DUPES -> dupesOnly.set(false)
        FilterPresentation.FilterId.TYPES -> typeChips.values.forEach { it.setSelected(false) }
        // The document listener would report for us, so it's silenced and the caller reports once.
        FilterPresentation.FilterId.SEARCH -> search.setTextQuietly("")
        // Nothing to clear: db hidden *is* the cleared state, so a bulk "clear filters" must leave
        // it alone. Only [loosen] grants it, and only when the empty state offered that.
        FilterPresentation.FilterId.DB_OPT_IN -> Unit
    }

    // ---- narrow layout ---------------------------------------------------------------------------

    /**
     * Switches the bar between its wide and compact layouts, with hysteresis so a slow drag across
     * the boundary can't strobe (see [FilterPresentation.isNarrow]).
     */
    private fun applyWidth(w: Int) {
        if (w <= 0) return
        val next = FilterPresentation.isNarrow(w, narrow, JBUI::scale)
        if (next == narrow) return
        narrow = next
        search.flex = next
        typeChips.values.forEach { it.compact = next }
        typeGaps.forEach { it.px = if (next) 4 else 8 }
        filtersButton.compact = next
        spacer.flex = !next
        count.font = JBUI.Fonts.create(MONO, if (next) 10 else 11)
        syncDerived()
    }

    // ---- the Filters popover -----------------------------------------------------------------

    /**
     * Everything that no longer earns permanent space, in one popover that applies immediately:
     * no OK button, no staging — every control is the filter itself, so the list behind updates as
     * you click and the badge and echo row follow.
     */
    private fun showFiltersPopover(anchor: Component) {
        // The button toggles. Pressing it while the popover is up has to close it — that is the
        // first gesture anyone tries — and `setCancelOnClickOutside` makes that subtle: the press
        // outside the popup cancels it *before* the button's own click arrives, so by then the
        // field is already null and a naive re-open would make the popover impossible to dismiss
        // from the control that opened it. Hence both guards: an open popup closes, and a click
        // landing in the wake of a just-closed one is that same close, not a new open.
        popup?.takeIf { !it.isDisposed }?.let { it.cancel(); popup = null; return }
        if (System.currentTimeMillis() - popoverClosedAt < REOPEN_GUARD_MILLIS) return

        val content = popoverContent ?: buildPopover().also { popoverContent = it }
        val built = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        // However it closes — Esc, a click elsewhere, the button again — the field stops pointing
        // at a disposed popup, and the moment of the close is remembered for the guard above.
        built.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                popup = null
                popoverClosedAt = System.currentTimeMillis()
            }
        })
        popup = built
        if (anchor.isShowing) built.showUnderneathOf(anchor) else built.showInFocusCenter()
    }

    private fun buildPopover(): JComponent = JPanel().apply {
        isOpaque = true
        background = Theme.bg1
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
        add(caption("Method  (HTTP only — hides other kinds)"))
        add(vgap(4))
        add(left(methods))
        add(vgap(12))
        add(caption("Status"))
        add(vgap(4))
        add(left(hbox(statusChips[2]!!, strut(6), statusChips[3]!!, strut(6), statusChips[4]!!, strut(6), statusChips[5]!!)))
        add(vgap(12))
        add(left(hbox(hideNoise, strut(8), switchLabel("Hide noise"))))
        add(vgap(6))
        add(left(hbox(dupesOnly, strut(8), switchLabel("Duplicates only"))))
        add(vgap(12))
        add(left(hbox(
            LinkLabel("Find by value…") { closePopover(); onFindByValue() },
            strut(14),
            LinkLabel("Correlation keys…") { closePopover(); onCorrelationKeys() },
        )))
    }

    private fun closePopover() {
        popup?.cancel()
        popup = null
    }

    // ---- the echo row --------------------------------------------------------------------------

    /**
     * The second row: one removable chip per active hidden filter, the mode-switch explainer, and
     * `Clear all`. It never wraps — the explainer is the one component allowed to shrink, so a long
     * one ellipsizes instead of pushing `Clear all` off the edge.
     */
    private inner class EchoRow : JPanel() {

        private val explainerText = JBLabel().apply {
            foreground = Theme.textMuted
            font = JBUI.Fonts.label(11f)
            minimumSize = Dimension(0, 0)
        }
        private val explainerLink = LinkLabel()
        private val clearAll = LinkLabel("Clear all") {
            FilterPresentation.FilterId.entries
                .filter { it != FilterPresentation.FilterId.SEARCH && it != FilterPresentation.FilterId.TYPES }
                .forEach { clearQuietly(it) }
            changed()
        }

        init {
            isOpaque = false
            isVisible = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(6, 12)
        }

        fun show(chips: List<FilterPresentation.EchoChip>, explainer: FilterPresentation.Explainer?) {
            removeAll()
            chips.forEach { chip ->
                add(RemovableChip(chip.label) { loosen(chip.id) }.apply { toolTipText = chip.tooltip })
                add(strut(8))
            }
            if (explainer != null) {
                explainerText.text = explainer.text
                explainerLink.text = explainer.link
                explainerLink.onClick = { switchMode(explainer.action) }
                add(explainerText); add(strut(4)); add(explainerLink); add(strut(8))
            }
            add(Box.createHorizontalGlue())
            add(clearAll)
            isVisible = chips.isNotEmpty() || explainer != null
            revalidate(); repaint()
        }

        override fun paintComponent(g: Graphics) {
            g.color = Theme.bg1
            g.fillRect(0, 0, width, height)
            // A hairline against the permanent row: the two surfaces are one shade apart, and the
            // echo row appearing has to read as a new row rather than as the bar growing.
            g.color = Theme.borderSubtle
            g.fillRect(0, 0, width, JBUI.scale(1))
            super.paintComponent(g)
        }
    }

    /** The two mode switches the explainer offers, applied as the user would have applied them. */
    private fun switchMode(action: FilterPresentation.Explainer.Action) {
        when (action) {
            FilterPresentation.Explainer.Action.SHOW_ALL_KINDS -> {
                clearQuietly(FilterPresentation.FilterId.STATUS)
                clearQuietly(FilterPresentation.FilterId.METHOD)
            }
            FilterPresentation.Explainer.Action.SHOW_DB -> typeChips[EventType.DB]?.setSelected(true)
        }
        changed()
    }

    // ---- small builders ------------------------------------------------------------------------

    private fun chip(text: String, color: Color) = ToggleChip(text, color) { changed() }

    private fun typeChip(type: EventType, tip: String? = null) =
        ToggleChip(
            FilterPresentation.typeLabel(type),
            Theme.typeColor(kindOf(type)),
            glyph = TypeIcons.forKind(kindOf(type)),
            tip = tip,
        ) { changed() }

    private fun kindOf(type: EventType): String = when (type) {
        EventType.NET -> Envelope.KIND_HTTP
        EventType.FCM -> Envelope.KIND_FCM
        EventType.DB -> Envelope.KIND_DB
        EventType.WORK -> Envelope.KIND_WORKER
        EventType.CONF -> Envelope.KIND_CONFIG
        EventType.ANALYTICS -> Envelope.KIND_ANALYTICS
        EventType.APP -> Envelope.KIND_EVENT
    }

    private fun caption(text: String) = left(
        JBLabel(text).apply {
            foreground = Theme.textMuted
            font = JBUI.Fonts.label(11f)
        }
    )

    private fun switchLabel(text: String) = JBLabel(text).apply {
        foreground = Theme.text
        font = JBUI.Fonts.label(12f).asBold()
    }

    /** Pins a row to the popover's left edge — `BoxLayout.Y_AXIS` centres otherwise. */
    private fun left(c: JComponent): JComponent = c.apply { alignmentX = LEFT_ALIGNMENT }

    private fun vgap(px: Int) = Box.createVerticalStrut(JBUI.scale(px))

    private fun divider() = object : JComponent() {
        override fun getPreferredSize() = Dimension(1, JBUI.scale(18))
        override fun getMaximumSize() = Dimension(1, JBUI.scale(18))
        override fun getMinimumSize() = Dimension(1, JBUI.scale(18))
        override fun paintComponent(g: Graphics) {
            g.color = Theme.borderStrong
            g.fillRect(0, 0, 1, height)
        }
    }

    private fun hbox(vararg comps: Component) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun strut(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))

    /** A horizontal gap whose width can change when the bar switches layouts. */
    private class Gap(var px: Int) : JComponent() {
        override fun getPreferredSize() = Dimension(JBUI.scale(px), 0)
        override fun getMinimumSize() = preferredSize
        override fun getMaximumSize() = preferredSize
    }

    /** The flexible gap before the counter — switched off when the search box takes the slack. */
    private class Spacer : JComponent() {
        var flex = true
            set(value) { field = value; revalidate() }
        override fun getPreferredSize() = Dimension(JBUI.scale(8), 0)
        override fun getMinimumSize() = Dimension(JBUI.scale(8), 0)
        override fun getMaximumSize() = Dimension(if (flex) Int.MAX_VALUE else JBUI.scale(8), 0)
    }

    private companion object {
        const val MONO = "JetBrains Mono"

        /**
         * How long after a popover closes a click on the `Filters` button is read as that close
         * rather than as a new open. One press produces both events, milliseconds apart.
         */
        const val REOPEN_GUARD_MILLIS = 200L
    }
}

/**
 * The permanent row's search box: `bg2` fill, 1px `borderStrong`, radius 8, padding 4/8, a painted
 * magnifier and a `URL or path…` placeholder in `textMuted`.
 *
 * This is a plain [JBTextField] rather than the platform's `SearchTextField`, which paints its own
 * (differently-rounded, differently-filled) frame that no amount of border setting brings into the
 * redesign's grammar. The trade is the platform field's search *history* dropdown, which LogPose
 * never used: the box exists to narrow a live capture, and a query worth keeping is a correlation
 * key, not a recent-searches entry.
 */
private class SearchField(private val onEdit: () -> Unit) : JBTextField() {

    /** Fixed 168px in the wide layout; below ~440px it takes whatever the chips leave. */
    var flex = false
        set(value) { field = value; revalidate() }

    private var reporting = true

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8 + GLYPH + 6, 4, 8)
        font = JBUI.Fonts.label(12f)
        foreground = Theme.text
        caretColor = Theme.text
        emptyText.clear()
        emptyText.appendText(
            "URL or path…",
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Theme.textMuted),
        )
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { if (reporting) onEdit() }
        })
    }

    /** Clears the box as part of a bulk clear that reports once for all of them. */
    fun setTextQuietly(value: String) {
        reporting = false
        try { text = value } finally { reporting = true }
    }

    private fun heightPx() = maxOf(super.getPreferredSize().height, JBUI.scale(24))

    override fun getPreferredSize() = Dimension(JBUI.scale(168), heightPx())
    override fun getMinimumSize() = Dimension(JBUI.scale(56), heightPx())
    override fun getMaximumSize() =
        Dimension(if (flex) Int.MAX_VALUE else JBUI.scale(168), heightPx())

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(8)
        g2.color = Theme.bg2
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = Theme.borderStrong
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        paintGlyph(g2)
        g2.dispose()
        super.paintComponent(g)
    }

    /** A magnifier drawn from two primitives, so there's no icon to load or tint. */
    private fun paintGlyph(g2: Graphics2D) {
        val box = JBUI.scale(GLYPH)
        val x = JBUI.scale(8)
        val y = (height - box) / 2
        val d = (box * 0.66f).toInt()
        g2.color = Theme.textMuted
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat() * 1.2f)
        g2.drawOval(x, y, d, d)
        g2.drawLine(x + (d * 0.78f).toInt(), y + (d * 0.78f).toInt(), x + box - 1, y + box - 1)
    }

    private companion object {
        const val GLYPH = 12
    }
}

/**
 * The `Filters` button: a ghost pill with a count badge for the filters it hides.
 *
 * The badge is the whole reason the button can hide anything. Below ~440px the label collapses to
 * `⋯` and the badge stays, because the number is the part that can't be inferred from the row
 * behind it.
 */
private class FiltersButton(private val onClick: (Component) -> Unit) : JComponent() {

    var badge = 0
        set(value) { field = value; revalidate(); repaint() }

    var compact = false
        set(value) { field = value; revalidate(); repaint() }

    private var hovered = false

    init {
        isOpaque = false
        toolTipText = "Method, status, noise, duplicates and correlation filters"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick(this@FiltersButton)
            override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }

    private fun labelText() = if (compact) "⋯" else "Filters"
    private fun labelFont() = JBUI.Fonts.label(11.5f).asBold()
    private fun badgeFont() = JBUI.Fonts.create("JetBrains Mono", 10).asBold()

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(labelFont())
        var w = JBUI.scale(13) * 2 + fm.stringWidth(labelText())
        if (badge > 0) w += JBUI.scale(6) + badgeWidth()
        return Dimension(w, maxOf(fm.height + JBUI.scale(6), JBUI.scale(22)))
    }

    override fun getMinimumSize() = preferredSize
    override fun getMaximumSize() = preferredSize

    private fun badgeWidth(): Int =
        getFontMetrics(badgeFont()).stringWidth(badge.toString()) + JBUI.scale(12)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(10)
        g2.color = if (hovered) Theme.rowHover else Theme.bg2
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = Theme.borderStrong
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

        g2.font = labelFont()
        val fm = g2.fontMetrics
        val baseline = (height + fm.ascent - fm.descent) / 2
        g2.color = Theme.text
        g2.drawString(labelText(), JBUI.scale(13), baseline)

        if (badge > 0) {
            val bw = badgeWidth()
            val bh = JBUI.scale(15)
            val bx = width - JBUI.scale(6) - bw
            val by = (height - bh) / 2
            g2.color = Theme.accent
            g2.fillRoundRect(bx, by, bw, bh, JBUI.scale(6), JBUI.scale(6))
            g2.font = badgeFont()
            val bfm = g2.fontMetrics
            g2.color = Theme.onAccent
            g2.drawString(
                badge.toString(),
                bx + (bw - bfm.stringWidth(badge.toString())) / 2,
                by + (bh + bfm.ascent - bfm.descent) / 2,
            )
        }
        g2.dispose()
    }
}

/**
 * The method control inside the popover: a segmented track (`bg3`, radius 8) whose selected
 * segments carry a `bg0` thumb.
 *
 * Multi-select, unlike the two-state segmented in the JSON viewer — `GET` and `POST` together is a
 * real filter — so the thumb is per segment rather than one sliding highlight.
 */
private class MethodSegmented(items: List<String>, private val onToggle: () -> Unit) : JPanel() {

    private val selected = LinkedHashSet<String>()
    private var hovered = -1
    private val plain = JBUI.Fonts.label(11f)
    private val bold = JBUI.Fonts.label(11f).asBold()

    private val labels = items.mapIndexed { i, text ->
        JLabel(text, SwingConstants.CENTER).apply {
            font = plain
            border = JBUI.Borders.empty(3, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle(text)
                override fun mouseEntered(e: MouseEvent) { hovered = i; update() }
                override fun mouseExited(e: MouseEvent) { hovered = -1; update() }
            })
        }
    }

    init {
        isOpaque = false
        layout = java.awt.GridLayout(1, items.size, 0, 0)
        border = JBUI.Borders.empty(2)
        labels.forEach { add(it) }
        update()
    }

    fun selection(): Set<String> = LinkedHashSet(selected)

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        update()
    }

    private fun toggle(text: String) {
        if (!selected.remove(text)) selected.add(text)
        update()
        onToggle()
    }

    private fun update() {
        labels.forEachIndexed { i, l ->
            val on = l.text in selected
            l.foreground = if (on || i == hovered) Theme.text else Theme.textDim
            l.font = if (on) bold else plain
        }
        repaint()
    }

    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Theme.bg3
        g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
        g2.color = Theme.bg0
        labels.filter { it.text in selected }.forEach {
            g2.fillRoundRect(it.x + 1, it.y + 1, it.width - 2, it.height - 2, JBUI.scale(6), JBUI.scale(6))
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * A filter the user can see and take off again: `order_id 21053953 ✕`.
 *
 * Distinct from [ToggleChip] because it isn't a mode you switch — it's one specific narrowing, and
 * the only thing to do with it is remove it. The whole chip is the hit target, so the `✕` is a
 * *statement* (drawn at 80% alpha, in the right gutter) rather than a 7px button.
 */
class RemovableChip(label: String = "", private val onRemove: () -> Unit) :
    JLabel(label, SwingConstants.CENTER) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 10, 2, 10 + CROSS)
        font = JBUI.Fonts.label(11f).asBold()
        // The label is `text`, not `accent`: accent-on-accentTint is structurally doomed, because
        // the tint *is* the accent, so the fill tracks the text (3.54:1 light / 2.96:1 dark on the
        // echo row's bg1). The accent stays on the 1px stroke, where 3:1 is the bar and it clears
        // it, so the chip still reads as an accent-coloured one.
        foreground = Theme.text
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onRemove()
        })
    }

    override fun getMinimumSize() = preferredSize
    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(10)
        g2.color = Theme.accentTint
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = Theme.accent
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        val fm = g2.fontMetrics
        g2.color = foreground
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
        g2.drawString(
            "✕",
            width - JBUI.scale(10) - fm.stringWidth("✕"),
            (height + fm.ascent - fm.descent) / 2,
        )
        g2.dispose()
        super.paintComponent(g)
    }

    private companion object {
        /** Right-hand gutter reserved for the `✕`, so the label centres in what's left. */
        const val CROSS = 14
    }
}

/**
 * A toggle pill: label 11.5 bold, padding 3/13, radius 10.
 *
 * Unselected is a `borderStrong` outline over `textDim`; selected fills with [color] at 40 alpha
 * and strokes and letters in [color]. Hover is a single swap with no transition — unselected
 * gains a `bg2` fill and lifts to `text`, selected doubles its 1px ring — so a chip answers
 * "am I a control?" before anyone clicks it.
 *
 * A chip given a [glyph] can also render **icon-only** ([compact]): a 14px kind glyph in a padding-4
 * radius-8 box, same fill/stroke grammar, the label moving to the tooltip. That's how seven kind
 * chips survive a 400px tool window without any of them being dropped.
 *
 * `flat` = no own border **and no hover**, for chips embedded in a frame that owns the rollover.
 */
class ToggleChip(
    private val label: String,
    private val color: Color,
    private val flat: Boolean = false,
    private val glyph: Icon? = null,
    private val tip: String? = null,
    onToggle: () -> Unit,
) : JLabel(label, SwingConstants.CENTER) {

    var selected = false
        private set

    /** Icon-only rendering for narrow panels; ignored when the chip has no [glyph]. */
    var compact = false
        set(value) {
            if (field == value || glyph == null) return
            field = value
            text = if (value) "" else label
            border = if (value) JBUI.Borders.empty(4) else JBUI.Borders.empty(3, 13)
            applyTooltip()
            revalidate(); repaint()
        }

    private var hovered = false
    private var cacheKey: Pair<Int, Double>? = null
    private var cached: BufferedImage? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 13)
        font = JBUI.Fonts.label(11.5f).asBold()
        foreground = Theme.textDim
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        applyTooltip()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                selected = !selected
                applyForeground()
                repaint()
                onToggle()
            }

            override fun mouseEntered(e: MouseEvent) {
                if (flat) return
                hovered = true; applyForeground(); repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                if (flat) return
                hovered = false; applyForeground(); repaint()
            }
        })
    }

    /** Sets the chip without firing its toggle — for a bulk clear that reports once. */
    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        applyForeground()
        repaint()
    }

    private fun applyTooltip() {
        // In compact mode the label is gone from the screen, so it has to be in the tooltip; the
        // DB chip's own explanation is kept beside it rather than replaced by it.
        toolTipText = when {
            compact -> listOfNotNull(label, tip).joinToString(" — ")
            else -> tip
        }
    }

    private fun applyForeground() {
        foreground = when {
            selected -> color
            hovered -> Theme.text
            else -> Theme.textDim
        }
    }

    override fun getPreferredSize(): Dimension {
        if (!compact || glyph == null) return super.getPreferredSize()
        val side = JBUI.scale(GLYPH) + JBUI.scale(4) * 2
        return Dimension(side, side)
    }

    override fun getMinimumSize() = preferredSize
    override fun getMaximumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(if (compact) 8 else 10)
        if (selected) {
            // Two contrast rules shape this fill.
            //
            // *Compact* chips are a glyph in the kind hue, and a tint fill of that same hue leaves
            // the two only 2.7–2.9:1 apart in the light theme for `worker` and `app` — the glyph is
            // the sole kind indicator at ≤440px, so it takes a neutral `bg1` plate and the hue
            // identifies the kind through the ring and the glyph itself.
            //
            // The tint also stays at 40 alpha on hover: deepening it to 48 pushed the http chip's
            // own label from 4.67:1 to 4.42:1 in dark. Rollover is a doubled 1px stroke instead,
            // which is a stronger ring for the same click target and costs the label nothing.
            g2.color = if (compact) Theme.bg1 else Theme.tint(color, 40)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = color
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (hovered) {
                val i = JBUI.scale(1)
                g2.drawRoundRect(i, i, width - 1 - 2 * i, height - 1 - 2 * i, arc, arc)
            }
        } else if (!flat) {
            if (hovered) {
                g2.color = Theme.bg2
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            g2.color = Theme.borderStrong
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        if (compact && glyph != null) paintGlyph(g2, glyph)
        g2.dispose()
        super.paintComponent(g)
    }

    /**
     * The kind glyph, scaled to 14px and flooded with the chip's current text colour through
     * `SrcAtop` — which keeps the icon's own alpha (and so its antialiased edges) while replacing
     * the hue baked into the SVG. Cached on colour and device scale, so a repaint never re-renders.
     */
    private fun paintGlyph(g2: Graphics2D, icon: Icon) {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return
        val color = foreground
        val key = color.rgb to g2.transform.scaleX
        val img = cached?.takeIf { cacheKey == key } ?: run {
            val image = ImageUtil.createImage(g2, w, h, BufferedImage.TYPE_INT_ARGB)
            val ig = image.createGraphics()
            try {
                ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                icon.paintIcon(this, ig, 0, 0)
                ig.composite = AlphaComposite.SrcAtop
                ig.color = color
                ig.fillRect(0, 0, w, h)
            } finally {
                ig.dispose()
            }
            cacheKey = key
            cached = image
            image
        }
        val side = JBUI.scale(GLYPH)
        val x = (width - side) / 2
        val y = (height - side) / 2
        runCatching {
            UIUtil.drawImage(g2, img, Rectangle(x, y, side, side), Rectangle(0, 0, w, h), null)
        }.onFailure {
            icon.paintIcon(this, g2, (width - w) / 2, (height - h) / 2)
        }
    }

    private companion object {
        const val GLYPH = 14
    }
}

/**
 * A small on/off switch: 34×20 stadium, 14px knob inset 3, `accent` on / `bg3` off.
 *
 * Hover deepens the track (`accentHover` when on, `borderStrong` when off) so the switch reads as
 * throwable before it's thrown. [setEnabled]`(false)` paints the whole control at 40% alpha and
 * drops the hand cursor, the hover and the click — the alpha goes on the composite rather than on
 * each colour, so the knob/track relationship survives the fade.
 */
class ToggleSwitch(initialOn: Boolean = false, private val onToggle: () -> Unit) : JComponent() {
    var on = initialOn
        private set

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(34), JBUI.scale(20))
        preferredSize = d; minimumSize = d; maximumSize = d
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!isEnabled) return
                on = !on; repaint(); onToggle()
            }

            override fun mouseEntered(e: MouseEvent) {
                if (!isEnabled) return
                hovered = true; repaint()
            }

            override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
        })
    }

    /** Sets the switch without firing [onToggle] — for a bulk clear that reports once. */
    fun set(value: Boolean) {
        if (on == value) return
        on = value
        repaint()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        if (!enabled) hovered = false
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (!isEnabled) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
        }
        g2.color = when {
            on -> if (hovered) Theme.accentHover else Theme.accent
            hovered -> Theme.borderStrong
            else -> Theme.bg3
        }
        g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
        val knob = height - JBUI.scale(6)
        val x = if (on) width - knob - JBUI.scale(3) else JBUI.scale(3)
        g2.color = Theme.onAccent
        g2.fillOval(x, JBUI.scale(3), knob, knob)
        g2.dispose()
    }
}

/**
 * What the list area shows when the capture is full and the filter has emptied it.
 *
 * The setup guide used to render here — "No requests captured yet", followed by three steps to
 * install a library that is demonstrably already installed, because 218 events came from it. This
 * says the true thing instead: how many events exist, that none match, *which* filter is doing it,
 * and the one loosening that would bring the most rows back.
 */
class FilteredToNothingPanel(
    private val onClearFilters: () -> Unit,
    private val onLoosen: (FilterPresentation.FilterId) -> Unit,
) : JPanel(GridBagLayout()) {

    private val total = JLabel().apply {
        foreground = Theme.text
        font = JBUI.Fonts.create("JetBrains Mono", 13).asBold()
    }
    private val captured = JLabel(" events captured · ").apply {
        foreground = Theme.text
        font = JBUI.Fonts.label(13f)
    }
    private val zero = JLabel("0").apply {
        foreground = Theme.text
        font = JBUI.Fonts.create("JetBrains Mono", 13).asBold()
    }
    private val matching = JLabel(" match the current filter").apply {
        foreground = Theme.text
        font = JBUI.Fonts.label(13f)
    }
    private val explanation = JLabel("", SwingConstants.CENTER).apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.label(11.5f)
    }
    private val clear = PillButton("Clear filters", filled = true)
    private val loosen = PillButton("", filled = false)
    private var loosenId: FilterPresentation.FilterId? = null

    init {
        isOpaque = true
        background = Theme.bg0
        clear.addActionListener { onClearFilters() }
        loosen.addActionListener { loosenId?.let(onLoosen) }

        val column = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(total, captured, zero, matching))
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(explanation.apply { alignmentX = CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(16)))
            add(
                JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    alignmentX = CENTER_ALIGNMENT
                    add(clear)
                    add(loosen)
                }
            )
        }
        add(column)
    }

    private fun row(vararg parts: Component) = JPanel().apply {
        isOpaque = false
        alignmentX = CENTER_ALIGNMENT
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        parts.forEach { add(it) }
    }

    fun show(state: FilterPresentation.EmptyState) {
        total.text = state.total.toString()
        // The 44ch measure is a wrap width, so the sentence is laid out by the HTML view rather
        // than pre-broken here; the text is escaped because it can quote the user's own search.
        explanation.text =
            "<html><div style='width:${JBUI.scale(268)}px;text-align:center'>${escape(state.explanation)}</div></html>"
        loosenId = state.loosener?.id
        loosen.isVisible = state.loosener != null
        state.loosener?.let { loosen.text = it.label }
        revalidate(); repaint()
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
