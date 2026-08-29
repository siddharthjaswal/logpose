package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
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
 * The Studio filter bar: a URL search box, a Method segmented control, Status class
 * pills, and a "Hide noise" switch — all one-click, no typing required.
 */
class FilterBar : JPanel() {

    var onChange: () -> Unit = {}

    private val search = SearchTextField(false)
    // Method and status chips are *selections*, not statements of what a call was, so they light
    // in accent rather than in a per-verb or per-class hue: kind is the only axis that owns hue,
    // and a "2xx" chip lit in the (now neutral) 2xx colour would read as disabled.
    private val methodChips = linkedMapOf(
        "GET" to chip("GET", Theme.accent, flat = true),
        "POST" to chip("POST", Theme.accent, flat = true),
        "PUT" to chip("PUT", Theme.accent, flat = true),
        "DELETE" to chip("DELETE", Theme.accent, flat = true),
    )
    private val statusChips = linkedMapOf(
        2 to chip("2xx", Theme.accent, flat = false),
        3 to chip("3xx", Theme.accent, flat = false),
        4 to chip("4xx", Theme.accent, flat = false),
        5 to chip("5xx", Theme.accent, flat = false),
    )
    private val hideNoise = ToggleSwitch { onChange() }
    private val dupChip = chip("⚠ Dupes", Theme.warn, flat = false)
    // Individual pills (not a segmented group), each lit in its own type hue — the same colour as
    // that kind's row gutter icon, so filter state is legible from the rows alone.
    private val typeChips = linkedMapOf(
        EventType.NET to chip("NET", Theme.typeColor(Envelope.KIND_HTTP), flat = false),
        EventType.FCM to chip("FCM", Theme.typeColor(Envelope.KIND_FCM), flat = false),
        EventType.DB to chip("DB", Theme.typeColor(Envelope.KIND_DB), flat = false).apply {
            toolTipText = "Database queries — hidden until you ask for them, since a busy screen " +
                "can run hundreds a minute. They are still captured and readable by a coding agent."
        },
        EventType.WORK to chip("WORK", Theme.typeColor(Envelope.KIND_WORKER), flat = false),
        EventType.CONF to chip("CONF", Theme.typeColor(Envelope.KIND_CONFIG), flat = false),
        EventType.ANALYTICS to chip("ANLY", Theme.typeColor(Envelope.KIND_ANALYTICS), flat = false),
        EventType.APP to chip("APP", Theme.typeColor(Envelope.KIND_EVENT), flat = false),
    )
    private val count = JBLabel().apply { foreground = Theme.textMuted }

    /**
     * The active correlation grouping, when the timeline is narrowed to one key value.
     *
     * Kept beside the chips rather than inside [FilterState] for the same reason "duplicates
     * only" is: deciding it needs the whole capture (a value can hide in a body the row never
     * shows), so the panel applies it against its cached haystacks and this bar only *states* it.
     */
    private var correlation: Grouping? = null
    private val correlationChip = RemovableChip { setCorrelationFilter(null) }

    /** Opens the bar's overflow menu — correlation keys, find by value. */
    var onOverflow: (Component) -> Unit = {}

    private val overflow = JBLabel("⋯", SwingConstants.CENTER).apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.label(15f).asBold()
        border = JBUI.Borders.empty(0, 8)
        toolTipText = "More — correlation keys, find by value"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onOverflow(this@apply)
        })
    }

    init {
        isOpaque = false
        layout = java.awt.BorderLayout()
        border = JBUI.Borders.empty(7, 12)

        search.textEditor.emptyText.text = "URL or path…"
        val sd = Dimension(JBUI.scale(168), search.preferredSize.height)
        search.preferredSize = sd; search.maximumSize = sd; search.minimumSize = sd
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onChange()
        })
        count.border = JBUI.Borders.empty(0, 10)

        add(controlsRow(), java.awt.BorderLayout.WEST)
        add(hbox(correlationChip, strut(6), overflow, count), java.awt.BorderLayout.EAST)
    }

    /**
     * Narrows the timeline to one correlation grouping, shown as a removable chip — the same way
     * filtering by trace reads today, except that a key can say what it is grouping by.
     * Null clears it.
     */
    fun setCorrelationFilter(grouping: Grouping?) {
        if (correlation == grouping) return
        correlation = grouping
        // The bar's width belongs to the chips on the left, so a long id is trimmed here rather
        // than allowed to squeeze them; the tooltip still carries the whole thing.
        correlationChip.show(grouping?.let { "${ellipsize(it.shortLabel, 34)}   ✕" })
        correlationChip.toolTipText = grouping?.let { "Filtered by ${it.shortLabel} — click to remove" }
        revalidate(); repaint()
        onChange()
    }

    fun correlationFilter(): Grouping? = correlation

    fun state() = FilterState(
        urlQuery = search.text.trim(),
        methods = methodChips.filterValues { it.selected }.keys,
        statusClasses = statusChips.filterValues { it.selected }.keys,
        hideNoise = hideNoise.on,
        types = typeChips.filterValues { it.selected }.keys,
        duplicatesOnly = dupChip.selected,
    )

    fun setCount(shown: Int, total: Int) { count.text = "$shown/$total" }

    /** Drive the search box from elsewhere (e.g. "filter by this trace" in the row menu). */
    fun setQuery(text: String) {
        if (search.text != text) search.text = text // fires the document listener → onChange
    }

    private fun controlsRow(): JComponent {
        val methodGroup = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Theme.borderStrong
                g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(1)
            methodChips.values.forEach { add(it) }
        }

        // Individual pills with breathing room between them — no segmented frame.
        val typeGroup = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            typeChips.values.forEachIndexed { i, chip ->
                if (i > 0) add(strut(6))
                add(chip)
            }
        }

        return hbox(
            search, strut(12),
            label("TYPE"), strut(6), typeGroup,
            strut(12), divider(), strut(12),
            label("METHOD"), strut(6), methodGroup,
            strut(12), divider(), strut(12),
            label("STATUS"), strut(6),
            statusChips[2]!!, strut(6), statusChips[3]!!, strut(6), statusChips[4]!!, strut(6), statusChips[5]!!,
            strut(12), divider(), strut(12),
            hideNoise, strut(6),
            JBLabel("Hide noise").apply { foreground = Theme.text; font = JBUI.Fonts.label(12f).asBold() },
            strut(12), divider(), strut(12),
            dupChip,
        )
    }

    private fun chip(text: String, color: Color, flat: Boolean) = ToggleChip(text, color, flat) { onChange() }

    private fun label(text: String) = JBLabel(text).apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(10.5f).asBold()
    }

    private fun divider() = object : JComponent() {
        override fun getPreferredSize() = Dimension(1, JBUI.scale(18))
        override fun getMaximumSize() = Dimension(1, JBUI.scale(18))
        override fun paintComponent(g: Graphics) {
            g.color = Theme.borderStrong
            g.fillRect(0, 0, 1, height)
        }
    }

    private fun hbox(vararg comps: Component) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
    }

    private fun strut(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))

    private fun ellipsize(text: String, max: Int) =
        if (text.length <= max) text else text.take(max - 1) + "…"
}

/**
 * A filter the user can see and take off again: `order_id 21053953  ✕`.
 *
 * Distinct from [ToggleChip] because it isn't a mode you switch — it's one specific narrowing,
 * created by a row menu or a pasted value, and the only thing to do with it is remove it.
 */
class RemovableChip(private val onRemove: () -> Unit) : JLabel("", SwingConstants.CENTER) {

    init {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(3, 11)
        font = JBUI.Fonts.label(11.5f).asBold()
        foreground = Theme.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onRemove()
        })
    }

    /** Shows [label], or hides the chip entirely when it's null. */
    fun show(label: String?) {
        text = label.orEmpty()
        isVisible = label != null
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Theme.accentTint
        g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
        g2.color = Theme.accent
        g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * A toggle pill: label 11.5 bold, padding 3/13, radius 10.
 *
 * Unselected is a `borderStrong` outline over `textDim`; selected fills with [color] at 40 alpha
 * and strokes and letters in [color]. Hover is a single swap with no transition — unselected
 * gains a `bg2` fill and lifts to `text`, selected deepens its tint by 8 alpha — so a chip
 * answers "am I a control?" before anyone clicks it.
 *
 * `flat` = no own border **and no hover**, for chips embedded in a segmented frame where the
 * frame, not the chip, owns the rollover.
 */
class ToggleChip(text: String, private val color: Color, private val flat: Boolean, onToggle: () -> Unit) :
    JLabel(text, SwingConstants.CENTER) {

    var selected = false
        private set

    private var hovered = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 13)
        font = JBUI.Fonts.label(11.5f).asBold()
        foreground = Theme.textDim
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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

    private fun applyForeground() {
        foreground = when {
            selected -> color
            hovered -> Theme.text
            else -> Theme.textDim
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (selected) {
            g2.color = Theme.tint(color, if (hovered) 48 else 40)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = color
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        } else if (!flat) {
            if (hovered) {
                g2.color = Theme.bg2
                g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            }
            g2.color = Theme.borderStrong
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        }
        g2.dispose()
        super.paintComponent(g)
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
