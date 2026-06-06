package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
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

/** Structured filter state — replaces the free-text grammar with one-click toggles. */
data class FilterState(
    val urlQuery: String = "",
    val methods: Set<String> = emptySet(),
    val statusClasses: Set<Int> = emptySet(), // 2,3,4,5 -> 2xx..5xx
    val hideNoise: Boolean = false,
) {
    fun matches(tx: Transaction): Boolean {
        if (urlQuery.isNotBlank() && !tx.request.url.contains(urlQuery, ignoreCase = true)) return false
        if (methods.isNotEmpty() && tx.request.method.uppercase() !in methods) return false
        if (statusClasses.isNotEmpty()) {
            val cls = (tx.response?.code ?: 0) / 100
            if (cls !in statusClasses) return false
        }
        if (hideNoise && MutedEndpoints.isMuted(tx)) return false
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
    private val count = JBLabel().apply { foreground = Theme.textMuted }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 10)

        search.textEditor.emptyText.text = "Filter by URL or path…"
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onChange()
        })

        add(capped(search))
        add(vGap(8))
        add(capped(controlsRow()))
        add(vGap(8))
        add(capped(noiseRow()))
    }

    fun state() = FilterState(
        urlQuery = search.text.trim(),
        methods = methodChips.filterValues { it.selected }.keys,
        statusClasses = statusChips.filterValues { it.selected }.keys,
        hideNoise = hideNoise.on,
    )

    fun setCount(shown: Int, total: Int) { count.text = "$shown/$total" }

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

        return hbox(
            label("METHOD"), strut(8), methodGroup,
            strut(14), divider(), strut(14),
            label("STATUS"), strut(8),
            statusChips[2]!!, strut(6), statusChips[3]!!, strut(6), statusChips[4]!!, strut(6), statusChips[5]!!,
        )
    }

    private fun noiseRow(): JComponent {
        val left = hbox(
            hideNoise, strut(8),
            JBLabel("Hide noise").apply { foreground = Theme.text; font = JBUI.Fonts.label(12f).asBold() },
        )
        return JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(left, java.awt.BorderLayout.WEST)
            add(count, java.awt.BorderLayout.EAST)
        }
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

    private fun capped(c: Component): JPanel = object : JPanel(java.awt.BorderLayout()) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(c, java.awt.BorderLayout.CENTER)
    }

    private fun strut(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))
    private fun vGap(px: Int) = Box.createVerticalStrut(JBUI.scale(px))
}

/** A toggle pill. `flat` = no own border (for use inside a segmented group). */
class ToggleChip(text: String, private val color: Color, private val flat: Boolean, onToggle: () -> Unit) :
    JLabel(text, SwingConstants.CENTER) {

    var selected = false
        private set

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 12)
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
            g2.fillRoundRect(0, 0, width - 1, height - 1, 7, 7)
            g2.color = color
            g2.drawRoundRect(0, 0, width - 1, height - 1, 7, 7)
        } else if (!flat) {
            g2.color = Theme.borderStrong
            g2.drawRoundRect(0, 0, width - 1, height - 1, 7, 7)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A small on/off switch. */
class ToggleSwitch(private val onToggle: () -> Unit) : JComponent() {
    var on = false
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
