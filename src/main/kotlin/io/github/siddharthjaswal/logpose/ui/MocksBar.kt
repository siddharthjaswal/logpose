package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.model.MockRule
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Collapsible strip under the filter bar listing active mock rules with enable / edit / delete,
 * a "Disable all" action, and the device sync state. Rebuilt wholesale on each [refresh] — the
 * rule count is small, so simplicity beats diffing.
 */
class MocksBar(
    private val onEdit: (MockRule) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onToggle: (String, Boolean) -> Unit,
    private val onDisableAll: () -> Unit,
) : JPanel(BorderLayout()) {

    private val rowsHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val syncLabel = JBLabel().apply { font = JBUI.Fonts.label(11f) }
    private val disableAll = linkLabel("Disable all") { onDisableAll() }

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Theme.borderStrong, 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10),
        )

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBLabel("MOCKS").apply { foreground = Theme.textMuted; font = JBUI.Fonts.label(10.5f).asBold() })
                add(syncLabel)
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
        syncLabel.text = syncText(device)
        syncLabel.foreground = if (device.helloSeen) Theme.methodColor("POST") else Theme.textMuted
        disableAll.isVisible = rules.any { it.enabled }
        isVisible = rules.isNotEmpty()
        revalidate(); repaint()
    }

    private fun syncText(device: MocksController.DeviceState): String = when {
        !device.helloSeen -> "waiting for device — needs logpose-android ≥ 1.1.0 + capture running"
        device.pkg != null -> "● ${device.pkg} · synced rev ${device.syncedRevision}"
        else -> "connected"
    }

    private fun ruleRow(rule: MockRule, hits: Int): Component {
        val enable = JBCheckBox("", rule.enabled).apply {
            isOpaque = false
            addActionListener { onToggle(rule.id, isSelected) }
        }
        val name = JBLabel(ruleTitle(rule)).apply {
            foreground = if (rule.enabled) Theme.text else Theme.textMuted
            font = JBUI.Fonts.label(12f)
        }
        val hitsLabel = JBLabel(if (hits > 0) "$hits×" else "").apply {
            foreground = Theme.textMuted; font = JBUI.Fonts.create("JetBrains Mono", 11)
        }
        val edit = iconLabel(AllIcons.Actions.Edit, "Edit") { onEdit(rule) }
        val delete = iconLabel(AllIcons.General.Remove, "Delete") { onDelete(rule.id) }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(enable); add(name)
            }
            val right = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(hitsLabel); add(Box.createHorizontalStrut(JBUI.scale(10)))
                add(edit); add(Box.createHorizontalStrut(JBUI.scale(8))); add(delete)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun ruleTitle(rule: MockRule): String {
        val outcome = when (rule.behavior) {
            MockRule.BEHAVIOR_TIMEOUT -> "timeout"
            MockRule.BEHAVIOR_CONNECTION_FAILURE -> "conn fail"
            else -> rule.status.toString()
        }
        val limit = if (rule.serveLimit > 0) " (×${rule.serveLimit})" else ""
        return "${rule.method} ${rule.pathPattern}  →  $outcome$limit"
    }

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
}
