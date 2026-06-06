package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.TagLabel
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.statusText
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Renders a transaction list row as: `[METHOD] [status]  path …………  size  duration`
 * with pill badges, a rounded selection highlight, and faded muted rows.
 */
class TransactionListRenderer : ListCellRenderer<Transaction> {

    private val methodTag = TagLabel().apply { preferredSize = Dimension(JBUI.scale(54), JBUI.scale(20)) }
    private val statusTag = TagLabel().apply { preferredSize = Dimension(JBUI.scale(44), JBUI.scale(20)) }
    private val path = JLabel()
    private val sizeLabel = JLabel("", SwingConstants.RIGHT)
    private val duration = JLabel("", SwingConstants.RIGHT)

    private val row = RowPanel().apply {
        border = JBUI.Borders.empty(3, 10, 3, 12)

        val badges = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(methodTag); add(statusTag)
        }
        val meta = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            add(sizeLabel); add(duration)
        }
        path.border = JBUI.Borders.emptyLeft(8)
        path.font = JBUI.Fonts.label(12.5f)
        sizeLabel.font = JBUI.Fonts.label(11f)
        duration.font = JBUI.Fonts.label(11f)

        add(badges, BorderLayout.WEST)
        add(path, BorderLayout.CENTER)
        add(meta, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out Transaction>,
        value: Transaction,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val muted = MutedEndpoints.isMuted(value)
        row.selected = isSelected

        val method = value.request.method
        val mColor = if (muted) Theme.textFaint else Theme.methodColor(method)
        methodTag.set(method, mColor, if (muted) null else Theme.tint(Theme.methodColor(method)))

        val code = value.response?.code
        val sColor = if (muted) Theme.textFaint else Theme.statusColor(code, value.error)
        statusTag.set(value.statusText(), sColor, if (muted) null else Theme.tint(Theme.statusColor(code, value.error)))

        path.text = value.request.path.ifBlank { value.request.url }
        path.foreground = if (muted) Theme.textFaint else Theme.text

        sizeLabel.text = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) } ?: ""
        sizeLabel.foreground = Theme.textFaint
        duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
        duration.foreground = if (muted) Theme.textFaint else Theme.textDim

        return row
    }

    private class RowPanel : JPanel(BorderLayout()) {
        var selected = false
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            if (selected) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Theme.tint(Theme.accent, 46)
                g2.fillRoundRect(JBUI.scale(4), JBUI.scale(1), width - JBUI.scale(8), height - JBUI.scale(2), 10, 10)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
