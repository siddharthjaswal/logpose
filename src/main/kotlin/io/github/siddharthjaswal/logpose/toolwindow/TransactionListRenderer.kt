package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.TagLabel
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.statusText
import java.awt.BorderLayout
import java.awt.Color
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
 * Studio list row: `[METHOD] [status]  path …  size  duration`, pill badges,
 * accent-bar selection, faded 26px muted rows, and a hover-revealed cURL hint.
 */
class TransactionListRenderer : ListCellRenderer<Transaction> {

    /** Index currently hovered (set by the list's mouse-motion handler). */
    var hoveredIndex: Int = -1

    private val methodTag = TagLabel().apply { preferredSize = Dimension(JBUI.scale(46), JBUI.scale(18)) }
    private val statusTag = TagLabel().apply { preferredSize = Dimension(JBUI.scale(44), JBUI.scale(18)) }
    private val path = JLabel()
    private val sizeLabel = JLabel("", SwingConstants.RIGHT)
    private val duration = JLabel("", SwingConstants.RIGHT)

    private val row = RowPanel().apply {
        border = JBUI.Borders.empty(0, 12)
        val badges = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false; add(methodTag); add(statusTag)
        }
        val meta = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply {
            isOpaque = false; add(sizeLabel); add(duration)
        }
        path.border = JBUI.Borders.emptyLeft(8)
        path.font = JBUI.Fonts.label(12.5f)
        sizeLabel.font = JBUI.Fonts.create("JetBrains Mono", 11)
        duration.font = JBUI.Fonts.create("JetBrains Mono", 11)
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
        val hovered = index == hoveredIndex
        row.selected = isSelected
        row.hovered = hovered && !isSelected
        row.rowHeight = if (muted) 26 else 34

        // opacity: full for normal rows; 0.34 muted, 0.7 muted+hover
        fun shade(c: Color): Color = if (!muted) c else Theme.fade(c, if (hovered) 0.7f else 0.34f)

        val method = value.request.method
        val mColor = Theme.methodColor(method)
        methodTag.font = JBUI.Fonts.label(11f).asBold()
        methodTag.set(method, shade(mColor), if (muted) null else Theme.tint(mColor, 30))

        val code = value.response?.code
        val sColor = Theme.statusColor(code, value.error)
        statusTag.font = JBUI.Fonts.label(11f).asBold()
        statusTag.set(value.statusText(), shade(sColor), if (muted) null else Theme.statusTint(code, value.error))

        path.text = value.request.path.ifBlank { value.request.url }
        path.foreground = shade(Theme.text)

        // Hover (non-muted) reveals a cURL affordance in place of the size.
        if (hovered && !muted) {
            sizeLabel.text = "⧉ cURL"
            sizeLabel.foreground = Theme.accent
        } else {
            sizeLabel.text = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) } ?: ""
            sizeLabel.foreground = shade(Theme.textMuted)
        }
        duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
        duration.foreground = shade(Theme.textMuted)

        return row
    }

    /** True if [point]'s x falls in the right-hand cURL hit zone of a row. */
    fun isInCurlZone(rowWidth: Int, x: Int): Boolean = x >= rowWidth - JBUI.scale(72)

    private class RowPanel : JPanel(BorderLayout()) {
        var selected = false
        var hovered = false
        var rowHeight = 34

        init { isOpaque = false }

        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width, JBUI.scale(rowHeight))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val m = JBUI.scale(6)
            val w = width - 2 * m
            val h = height - JBUI.scale(2)
            val y = JBUI.scale(1)
            when {
                selected -> {
                    g2.color = Theme.accentTint
                    g2.fillRoundRect(m, y, w, h, 8, 8)
                    g2.color = Theme.accent
                    g2.fillRoundRect(m, y, JBUI.scale(2), h, 2, 2) // 2px accent bar inset-left
                }
                hovered -> {
                    g2.color = Theme.rowHover
                    g2.fillRoundRect(m, y, w, h, 8, 8)
                }
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
