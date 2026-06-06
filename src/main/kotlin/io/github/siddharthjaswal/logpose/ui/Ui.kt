package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Exact "Studio" design tokens (dark-only, per spec). Colors are wrapped in
 * [JBColor] with identical light/dark values so the panel renders the same dark
 * surface regardless of the IDE theme.
 */
object Theme {
    private fun c(rgb: Int) = JBColor(rgb, rgb)

    // surfaces
    val bg0 = c(0x1E1F22)   // window
    val bg1 = c(0x26282C)   // cards / chips
    val bg2 = c(0x2B2D30)   // headers / inputs
    val bg3 = c(0x303236)   // segments
    val rowHover = c(0x2F3136)

    // borders
    val borderStrong = c(0x393B40)
    val borderSubtle = c(0x2D2F33)

    // text
    val text = c(0xDFE1E5)
    val textDim = c(0xA3A6AD)
    val textMuted = c(0x6B6E76)

    // accent
    val accent = c(0x3574F0)
    val accentHover = c(0x4A82F2)
    val accentTint: Color = rgba(0x3574F0, 0.17f)
    val onAccent = c(0xFFFFFF)

    // method palette
    private val mGet = c(0x5B9DFF)
    private val mPost = c(0x5CC26F)
    private val mPut = c(0xE0A740)
    private val mDelete = c(0xE8736A)
    private val mPatch = c(0xC08CF0)

    // status palette (text + bg tint)
    private val s2 = c(0x62B97C); private val s2bg = rgba(0x62B97C, 0.15f)
    private val s3 = c(0x5AA9D6); private val s3bg = rgba(0x5AA9D6, 0.15f)
    private val s4 = c(0xE3B34C); private val s4bg = rgba(0xE3B34C, 0.17f)
    private val s5 = c(0xEC7A70); private val s5bg = rgba(0xEC7A70, 0.17f)

    // JSON syntax
    val jsonKey = c(0xC77DBB)
    val jsonString = c(0x7FB069)
    val jsonNumber = c(0x5B9DFF)
    val jsonBool = c(0xCC8A52)
    val jsonNull = c(0x8D9298)
    val jsonPunct = c(0x787C84)
    val jsonCount = c(0x6F737A)

    // aliases kept for existing widgets
    val cardBg get() = bg1
    val cardBorder get() = borderStrong
    val chipBg get() = bg1

    fun methodColor(method: String): JBColor = when (method.uppercase()) {
        "GET" -> mGet
        "POST" -> mPost
        "PUT" -> mPut
        "DELETE" -> mDelete
        "PATCH" -> mPatch
        else -> mPatch
    }

    fun statusColor(code: Int?, error: String?): JBColor = when {
        error != null -> s5
        code == null -> textDim
        code in 200..299 -> s2
        code in 300..399 -> s3
        code in 400..499 -> s4
        else -> s5
    }

    fun statusTint(code: Int?, error: String?): Color = when {
        error != null -> s5bg
        code == null -> rgba(0xA3A6AD, 0.12f)
        code in 200..299 -> s2bg
        code in 300..399 -> s3bg
        code in 400..499 -> s4bg
        else -> s5bg
    }

    fun tint(c: Color, alpha: Int = 38): Color = Color(c.red, c.green, c.blue, alpha)

    fun rgba(rgb: Int, a: Float): Color =
        Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, (a * 255).toInt())

    /** Blend [c] toward the window background by [keep] (1f = full color, 0f = invisible). */
    fun fade(c: Color, keep: Float): Color {
        val b = bg0
        fun mix(f: Int, t: Int) = (f * keep + t * (1 - keep)).toInt().coerceIn(0, 255)
        return Color(mix(c.red, b.red), mix(c.green, b.green), mix(c.blue, b.blue))
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}

/** A small fading toast confirming an action (e.g. a copy). */
object Toast {
    fun show(near: JComponent, text: String) {
        if (!near.isShowing) return
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
            .setFadeoutTime(1400L)
            .createBalloon()
            .show(RelativePoint.getCenterOf(near), Balloon.Position.above)
    }
}

private fun Graphics2D.aa(): Graphics2D {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    return this
}

/** A pill/badge label: rounded tinted background with centered colored text. */
class TagLabel(arc: Int = 6) : JLabel("", SwingConstants.CENTER) {
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
    var arc = 10
    var fill: Color = Theme.bg1
    var stroke: Color? = Theme.borderSubtle

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

/** A small key/value "stat" card used in the overview hero. */
class StatChip(caption: String, value: String) : CardPanel(java.awt.GridLayout(2, 1, 0, 1)) {
    init {
        arc = 8
        fill = Theme.bg1
        stroke = Theme.borderSubtle
        border = JBUI.Borders.empty(6, 10)
        add(JLabel(caption.uppercase()).apply { foreground = Theme.textMuted; font = JBUI.Fonts.label(9.5f) })
        add(JLabel(value).apply {
            foreground = Theme.text
            font = JBUI.Fonts.create("JetBrains Mono", 14).asBold()
        })
    }
}

/** Rounded action button — filled (accent) or ghost (outlined). */
class PillButton(text: String, private val filled: Boolean) : JButton(text) {
    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4, 12)
        foreground = if (filled) Theme.onAccent else Theme.text
        font = JBUI.Fonts.label(12f).asBold()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.aa()
        if (filled) {
            g2.color = if (model.isRollover) Theme.accentHover else Theme.accent
            g2.fillRoundRect(0, 0, width, height, 8, 8)
        } else {
            g2.color = if (model.isRollover) Theme.bg3 else Theme.bg2
            g2.fillRoundRect(0, 0, width, height, 8, 8)
            g2.color = Theme.borderStrong
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        }
        g2.dispose()
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width, maxOf(d.height, JBUI.scale(24)))
    }
}

/** A pulsing status dot: green (capturing) or red (stopped). */
class StatusDot : JComponent() {
    var capturing = false
        set(value) { field = value; pulse = 1f }
    private var pulse = 1f
    private val timer = javax.swing.Timer(60) { tick() }

    init {
        preferredSize = Dimension(JBUI.scale(12), JBUI.scale(12))
        timer.start()
    }

    private var rising = false
    private fun tick() {
        if (capturing) {
            pulse += if (rising) 0.06f else -0.06f
            if (pulse <= 0.45f) { pulse = 0.45f; rising = true }
            if (pulse >= 1f) { pulse = 1f; rising = false }
        } else {
            pulse = 1f
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.aa()
        val base: Color = if (capturing) Theme.statusColor(200, null) else Theme.statusColor(500, null)
        g2.color = Color(base.red, base.green, base.blue, (pulse * 255).toInt())
        val d = JBUI.scale(8)
        g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
        g2.dispose()
    }

    fun dispose() = timer.stop()
}

fun Transaction.statusText(): String = response?.code?.toString() ?: if (error != null) "ERR" else "···"
