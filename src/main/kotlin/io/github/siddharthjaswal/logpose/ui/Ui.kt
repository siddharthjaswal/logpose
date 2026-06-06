package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Design tokens approximating the JetBrains "New UI" palette used in the LogPose
 * mockup, expressed as [JBColor] pairs so they also read sensibly in light theme.
 */
object Theme {
    val cardBg = JBColor(0xFFFFFF, 0x2B2D30)
    val cardBorder = JBColor(0xE3E5EB, 0x393B40)
    val chipBg = JBColor(0xF1F2F4, 0x303236)
    val text = JBColor(0x1E1F22, 0xDFE1E5)
    val textDim = JBColor(0x818594, 0x9DA0A8)
    val textFaint = JBColor(0xC2C5CC, 0x55585F)
    val accent = JBColor(0x3574F0, 0x3574F0)
    val accentHover = JBColor(0x4A82F2, 0x4A82F2)
    val onAccent = JBColor(0xFFFFFF, 0xFFFFFF)

    private val GET = JBColor(0x3574F0, 0x5AA9D6)
    private val POST = JBColor(0x2E7D32, 0x62B97C)
    private val PUT = JBColor(0xB8772A, 0xE0A740)
    private val DELETE = JBColor(0xC93B3B, 0xE8736A)
    private val OTHER = JBColor(0x8D5BB5, 0xC08CF0)

    fun methodColor(method: String): JBColor = when (method.uppercase()) {
        "GET" -> GET
        "POST" -> POST
        "PUT", "PATCH" -> PUT
        "DELETE" -> DELETE
        else -> OTHER
    }

    fun statusColor(code: Int?, error: String?): JBColor = when {
        error != null -> DELETE
        code == null -> OTHER
        code in 200..299 -> POST
        code in 300..399 -> GET
        code in 400..499 -> PUT
        else -> DELETE
    }

    /** A translucent tint of [c], for pill backgrounds. */
    fun tint(c: Color, alpha: Int = 38): Color = Color(c.red, c.green, c.blue, alpha)

    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}

private fun Graphics2D.aa(): Graphics2D {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    return this
}

/** A pill/badge label: rounded tinted background with centered colored text. */
class TagLabel(arc: Int = 9) : JLabel("", SwingConstants.CENTER) {
    var pillBg: Color? = null
    private val arcPx = arc

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)
        font = JBUI.Fonts.label(11f)
    }

    fun set(text: String, fg: Color, bg: Color?) {
        this.text = text
        foreground = fg
        pillBg = bg
    }

    override fun paintComponent(g: Graphics) {
        pillBg?.let {
            val g2 = g.create() as Graphics2D
            g2.aa().color = it
            g2.fillRoundRect(0, 0, width, height, arcPx, arcPx)
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** A rounded card surface with a subtle border. */
open class CardPanel(layout: java.awt.LayoutManager? = java.awt.BorderLayout()) : JPanel(layout) {
    var arc = 14
    var fill: Color = Theme.cardBg
    var stroke: Color? = Theme.cardBorder

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.aa().color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        stroke?.let { g2.color = it; g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc) }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** A small key/value "stat" chip used in the overview hero. */
class StatChip(caption: String, value: String) : CardPanel(java.awt.GridLayout(2, 1, 0, 1)) {
    init {
        arc = 10
        fill = Theme.chipBg
        stroke = null
        border = JBUI.Borders.empty(6, 10)
        add(JLabel(caption.uppercase()).apply { foreground = Theme.textDim; font = JBUI.Fonts.label(9.5f) })
        add(JLabel(value).apply { foreground = Theme.text; font = JBUI.Fonts.label(12f).asBold() })
    }
}

/** Rounded action button — filled (accent) or outlined. */
class PillButton(text: String, private val filled: Boolean) : JButton(text) {
    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(6, 14)
        foreground = if (filled) Theme.onAccent else Theme.text
        font = JBUI.Fonts.label(12f).asBold()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.aa()
        if (filled) {
            g2.color = if (model.isRollover) Theme.accentHover else Theme.accent
            g2.fillRoundRect(0, 0, width, height, 10, 10)
        } else {
            g2.color = if (model.isRollover) Theme.chipBg else Theme.cardBg
            g2.fillRoundRect(0, 0, width, height, 10, 10)
            g2.color = Theme.cardBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        }
        g2.dispose()
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width, maxOf(d.height, JBUI.scale(28)))
    }
}

fun Transaction.statusText(): String = response?.code?.toString() ?: if (error != null) "ERR" else "···"
