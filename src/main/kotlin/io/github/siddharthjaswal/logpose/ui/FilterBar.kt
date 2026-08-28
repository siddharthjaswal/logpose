package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
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
    private val methodChips = linkedMapOf(
        "GET" to chip("GET", Theme.methodColor("GET"), flat = true),
        "POST" to chip("POST", Theme.methodColor("POST"), flat = true),
        "PUT" to chip("PUT", Theme.methodColor("PUT"), flat = true),
        "DELETE" to chip("DELETE", Theme.methodColor("DELETE"), flat = true),
    )
    private val statusChips = linkedMapOf(
        2 to chip("2xx", Theme.statusColor(200, null), flat = false),
        3 to chip("3xx", Theme.statusColor(300, null), flat = false),
        4 to chip("4xx", Theme.statusColor(400, null), flat = false),
        5 to chip("5xx", Theme.statusColor(500, null), flat = false),
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
        add(count, java.awt.BorderLayout.EAST)
    }

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
}

/** A toggle pill. `flat` = no own border (for use inside a segmented group). */
class ToggleChip(text: String, private val color: Color, private val flat: Boolean, onToggle: () -> Unit) :
    JLabel(text, SwingConstants.CENTER) {

    var selected = false
        private set

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 13)
        font = JBUI.Fonts.label(11.5f).asBold()
        foreground = Theme.textDim
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                selected = !selected
                foreground = if (selected) color else Theme.textDim
                repaint()
                onToggle()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (selected) {
            g2.color = Theme.tint(color, 40)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = color
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        } else if (!flat) {
            g2.color = Theme.borderStrong
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A small on/off switch. */
class ToggleSwitch(initialOn: Boolean = false, private val onToggle: () -> Unit) : JComponent() {
    var on = initialOn
        private set

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(34), JBUI.scale(20))
        preferredSize = d; minimumSize = d; maximumSize = d
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { on = !on; repaint(); onToggle() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (on) Theme.accent else Theme.bg3
        g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
        val knob = height - JBUI.scale(6)
        val x = if (on) width - knob - JBUI.scale(3) else JBUI.scale(3)
        g2.color = Theme.onAccent
        g2.fillOval(x, JBUI.scale(3), knob, knob)
        g2.dispose()
    }
}
