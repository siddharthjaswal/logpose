package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.model.MockRule
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Collapsible strip under the filter bar listing active mock rules — styled to match the
 * timeline rows (colored method badge, status pill, latency/hit chips) so mocks read as
 * first-class. Enable toggle, edit / delete, "Disable all", and the device sync state.
 * Rebuilt wholesale on each [refresh]; the rule count is small, so simplicity beats diffing.
 */
class MocksBar(
    private val onEdit: (MockRule) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onToggle: (String, Boolean) -> Unit,
    private val onDisableAll: () -> Unit,
    private val onDiff: (MockRule) -> Unit,
) : JPanel(BorderLayout()) {

    private val rowsHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyTop(4)
    }
    private val syncDot = JBLabel("●").apply { font = JBUI.Fonts.label(9f) }
    private val syncText = JBLabel().apply { font = JBUI.Fonts.label(11f) }
    private val disableAll = linkLabel("Disable all") { onDisableAll() }

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Theme.borderStrong, 0, 0, 1, 0),
            JBUI.Borders.empty(7, 10, 8, 10),
        )

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(7), 0)).apply {
                isOpaque = false
                add(JBLabel("MOCKS").apply {
                    foreground = Theme.methodColor("PATCH"); font = JBUI.Fonts.label(10.5f).asBold()
                })
                add(syncDot)
                add(syncText)
            }
            add(left, BorderLayout.WEST)
            add(disableAll, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(rowsHost, BorderLayout.CENTER)
        isVisible = false
    }

    /** Rebuilds the strip from the current rules + device state. Auto-hides when empty. */
    fun refresh(rules: List<MockRule>, device: MocksController.DeviceState) {
        rowsHost.removeAll()
        rules.forEach { rowsHost.add(ruleRow(it, device.hits[it.id] ?: 0)) }
        applySync(device)
        disableAll.isVisible = rules.any { it.enabled }
        isVisible = rules.isNotEmpty()
        revalidate(); repaint()
    }

    private fun applySync(device: MocksController.DeviceState) {
        if (device.helloSeen) {
            syncDot.foreground = Theme.methodColor("POST") // green
            syncText.foreground = Theme.textDim
            syncText.text = "${device.pkg ?: "device"}  ·  synced rev ${device.syncedRevision}"
        } else {
            syncDot.foreground = Theme.warn
            syncText.foreground = Theme.textMuted
            syncText.text = "waiting for device — needs logpose-android ≥ 1.1.0 + capture running"
        }
    }

    private fun ruleRow(rule: MockRule, hits: Int): Component {
        val on = rule.enabled
        val toggle = ToggleSwitch(initialOn = on) { onToggle(rule.id, !on) }

        val method = JLabel(rule.method, SwingConstants.LEFT).apply {
            font = JBUI.Fonts.label(11f).asBold()
            foreground = fade(Theme.methodColor(rule.method), on)
            val d = Dimension(JBUI.scale(46), JBUI.scale(20))
            preferredSize = d; minimumSize = d; maximumSize = d
        }
        val path = JLabel(rule.pathPattern).apply {
            font = JBUI.Fonts.label(12f)
            foreground = if (on) Theme.text else Theme.textMuted
            border = JBUI.Borders.emptyLeft(10)
        }

        val outcome = outcomePill(rule, on)
        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(outcome)
            if (rule.latencyMillis > 0) {
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(chip("${rule.latencyMillis}ms", Theme.textMuted, on))
            }
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(JBLabel(if (hits > 0) "${hits}×" else "").apply {
                foreground = Theme.textMuted
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                val d = Dimension(JBUI.scale(34), JBUI.scale(20)); preferredSize = d; minimumSize = d
                horizontalAlignment = SwingConstants.RIGHT
            })
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            // Quick diff: original captured response vs what this rule serves, without opening the
            // editor. Only meaningful when the rule actually has a body to compare.
            if (!rule.body.isNullOrBlank()) {
                add(iconLabel(AllIcons.Actions.Diff, "Diff vs original") { onDiff(rule) })
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            add(iconLabel(AllIcons.Actions.Edit, "Edit") { onEdit(rule) })
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(iconLabel(AllIcons.General.Remove, "Delete") { onDelete(rule.id) })
        }

        val west = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(toggle); add(method)
        }

        return CardRow().apply {
            border = JBUI.Borders.empty(3, 10)
            add(west, BorderLayout.WEST)
            add(path, BorderLayout.CENTER)
            add(meta, BorderLayout.EAST)
        }
    }

    private fun outcomePill(rule: MockRule, on: Boolean): TagLabel = TagLabel().apply {
        font = JBUI.Fonts.label(11f).asBold()
        border = JBUI.Borders.empty(2, 8)
        when {
            // Patch rules keep the backend's status — the response is merged, not replaced.
            rule.mode == MockRule.MODE_PATCH -> {
                val c = Theme.methodColor("PATCH")
                set("MERGE", fade(c, on), tintFor(c, on))
            }
            rule.behavior == MockRule.BEHAVIOR_TIMEOUT ->
                set("TIMEOUT", fade(Theme.warn, on), tintFor(Theme.warn, on))
            rule.behavior == MockRule.BEHAVIOR_CONNECTION_FAILURE ->
                set("FAILED", fade(Theme.danger, on), tintFor(Theme.danger, on))
            else -> {
                val c = Theme.statusColor(rule.status, null)
                set(rule.status.toString(), fade(c, on), if (on) Theme.statusTint(rule.status, null) else Theme.tint(c, 14))
            }
        }
    }

    private fun chip(text: String, color: Color, on: Boolean) = TagLabel().apply {
        font = JBUI.Fonts.create("JetBrains Mono", 10)
        border = JBUI.Borders.empty(2, 7)
        set(text, fade(color, on), tintFor(color, on))
    }

    /** Dims a color when the rule is disabled, so the whole row reads as "off". */
    private fun fade(c: Color, on: Boolean): Color = if (on) c else Theme.fade(c, 0.5f)
    private fun tintFor(c: Color, on: Boolean): Color = Theme.tint(c, if (on) 26 else 12)

    private fun iconLabel(icon: javax.swing.Icon, tip: String, onClick: () -> Unit) =
        JLabel(icon).apply {
            toolTipText = tip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

    private fun linkLabel(text: String, onClick: () -> Unit) =
        JBLabel(text).apply {
            foreground = Theme.accent
            font = JBUI.Fonts.label(11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

    /** A rounded card surface for one rule, with a little vertical breathing room below it. */
    private class CardRow : JPanel(BorderLayout()) {
        init { isOpaque = false }

        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width, d.height + JBUI.scale(4))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height - JBUI.scale(4)
            g2.color = Theme.bg1
            g2.fillRoundRect(0, 0, width - 1, h, JBUI.scale(8), JBUI.scale(8))
            g2.color = Theme.borderSubtle
            g2.drawRoundRect(0, 0, width - 1, h, JBUI.scale(8), JBUI.scale(8))
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
