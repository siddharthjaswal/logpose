package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.TagLabel
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.isPending
import io.github.siddharthjaswal.logpose.ui.spinnerChar
import io.github.siddharthjaswal.logpose.ui.statusText
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Studio list row, laid out in FIXED columns so method / status / path align across
 * every row regardless of method length or muted state:
 *
 *   `METHOD   [status]   path … …                 size   duration`
 *
 * Per spec §4, METHOD is plain colored bold text in a fixed column; only the status
 * is a pill. Muted rows are shorter (26px) and faded (~0.34, 0.7 on hover); hover
 * reveals a cURL affordance in place of the size.
 */
class TransactionListRenderer : ListCellRenderer<Transaction> {

    var hoveredIndex: Int = -1

    /** Animation frame for the in-flight spinner; bumped by the panel's timer. */
    var spinnerFrame: Int = 0

    /** Live wall-clock elapsed (ms) for a pending transaction, or null if not pending. */
    var elapsedProvider: (Transaction) -> Long? = { null }

    private val methodLabel = JLabel("", SwingConstants.LEFT).fixed(JBUI.scale(46), JBUI.scale(20))
    private val statusTag = TagLabel().fixed(JBUI.scale(46), JBUI.scale(20))
    private val path = JLabel()
    private val sizeLabel = JLabel("", SwingConstants.RIGHT)
    private val duration = JLabel("", SwingConstants.RIGHT)

    private val row = RowPanel().apply {
        border = JBUI.Borders.empty(0, 14)

        methodLabel.font = JBUI.Fonts.label(11f).asBold()
        statusTag.font = JBUI.Fonts.label(11f).asBold()

        val badges = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(methodLabel)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(statusTag)
        }
        path.border = JBUI.Borders.emptyLeft(12)
        path.font = JBUI.Fonts.label(12.5f)
        sizeLabel.font = JBUI.Fonts.create("JetBrains Mono", 11)
        duration.font = JBUI.Fonts.create("JetBrains Mono", 11)

        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sizeLabel.fixed(JBUI.scale(64), JBUI.scale(20)))
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(duration.fixed(JBUI.scale(56), JBUI.scale(20)))
        }

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

        fun shade(c: Color): Color = if (!muted) c else Theme.fade(c, if (hovered) 0.7f else 0.34f)

        val mColor = Theme.methodColor(value.request.method)
        methodLabel.text = value.request.method
        methodLabel.foreground = shade(mColor)

        val pending = value.isPending()
        val code = value.response?.code
        if (pending) {
            statusTag.set(spinnerChar(spinnerFrame).toString(), Theme.accent, Theme.tint(Theme.accent, 22))
        } else {
            val sColor = Theme.statusColor(code, value.error)
            val sBg = if (muted) Theme.tint(sColor, 14) else Theme.statusTint(code, value.error)
            statusTag.set(value.statusText(), shade(sColor), sBg)
        }

        path.text = value.request.path.ifBlank { value.request.url }
        path.foreground = shade(Theme.text)

        when {
            pending -> {
                sizeLabel.text = ""
                duration.text = elapsedProvider(value)?.let { "${it}ms" } ?: "…"
                duration.foreground = Theme.accent
            }
            hovered && !muted -> {
                sizeLabel.text = "⧉ cURL"
                sizeLabel.foreground = Theme.accent
                duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
                duration.foreground = shade(Theme.textMuted)
            }
            else -> {
                sizeLabel.text = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) } ?: ""
                sizeLabel.foreground = shade(Theme.textMuted)
                duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
                duration.foreground = shade(Theme.textMuted)
            }
        }

        return row
    }

    /** The "⧉ cURL" affordance occupies the size column; match that band, not the whole right edge. */
    fun isInCurlZone(rowWidth: Int, x: Int): Boolean =
        x in (rowWidth - JBUI.scale(146))..(rowWidth - JBUI.scale(74))

    private fun <T : JLabel> T.fixed(w: Int, h: Int): T = apply {
        val d = Dimension(w, h); preferredSize = d; minimumSize = d; maximumSize = d
    }

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
                    g2.fillRoundRect(m, y, JBUI.scale(2), h, 2, 2)
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
