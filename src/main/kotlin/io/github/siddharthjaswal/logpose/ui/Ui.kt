package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import io.github.siddharthjaswal.logpose.model.Envelope
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The design tokens, as [JBColor] light/dark pairs, so the tool window matches the IDE theme (dark
 * values descend from the original "Studio" palette; light values mirror the JetBrains New UI light
 * theme). Everything paints through these, so the whole UI adapts.
 *
 * **One axis owns hue.** The redesign deleted seven of these tokens — five method hues, the 2xx
 * green and the 3xx blue-cyan — and added none, because a UI in which method, status and kind all
 * carry colour is a UI in which none of them means anything. What is left:
 *
 *  - **Kind** keeps its seven hues ([typeColor]), and may paint them at four sites only.
 *  - **Status** carries semantics: 2xx/3xx neutral, 4xx [warn], 5xx/ERR [danger].
 *  - **Method** carries no colour at all — read vs write is *weight* ([methodTextColor], [isRead]).
 *  - **[intervention]** — what LogPose itself did — is the one solid accent fill in a row.
 */
object Theme {
    private fun c(light: Int, dark: Int) = JBColor(light, dark)

    // surfaces
    private const val BG0_LIGHT = 0xF7F8FA
    private const val BG0_DARK = 0x1E1F22

    val bg0 = c(BG0_LIGHT, BG0_DARK)   // window
    val bg1 = c(0xFFFFFF, 0x26282C)   // cards / chips
    val bg2 = c(0xF0F1F4, 0x2B2D30)   // headers / inputs
    val bg3 = c(0xE3E5EA, 0x303236)   // segments
    val rowHover = c(0xEAECF1, 0x2F3136)

    // borders
    val borderStrong = c(0xC6C9D2, 0x393B40)
    val borderSubtle = c(0xE7E8EE, 0x2D2F33)

    // text
    val text = c(0x1E1F22, 0xDFE1E5)
    val textDim = c(0x6C707E, 0xA3A6AD)
    val textMuted = c(0x9296A1, 0x6B6E76)

    // accent
    val accent = c(0x3574F0, 0x3574F0)
    val accentHover = c(0x2E6AE0, 0x4A82F2)
    val accentTint: Color = rgba(0x3574F0, 0.15f)
    val onAccent = c(0xFFFFFF, 0xFFFFFF)

    // semantic accents (warnings / dangers) — also the 4xx / 5xx status colors
    val warn = c(0xA86A12, 0xE3B34C)
    val warnTint: Color = rgba(0xE3B34C, 0.17f)
    val danger = c(0xCF3030, 0xEC7A70)
    val dangerTint: Color = rgba(0xEC7A70, 0.17f)

    // ---- aliases: one axis owns hue ---------------------------------------------------------
    // The redesign gives every visual axis exactly one job. Kind keeps the 7 hues (see
    // [typeColor]); status carries only semantics (2xx/3xx neutral, 4xx amber, 5xx/ERR red);
    // method carries no hue at all — read vs write is weight; and anything LogPose itself caused
    // is the one solid-accent fill in a row. These are *aliases* — the same JBColor instances —
    // so every colour still has exactly two definitions (light, dark) and themes stay in sync.

    /** Text of a 2xx / 3xx / unknown status pill. Alias of [textDim]. */
    val statusNeutral = textDim

    /** Fill of a 2xx / 3xx / unknown status pill. Alias of [bg2]. */
    val statusNeutralBg = bg2

    /**
     * Anything **LogPose itself caused** — the MOCK and INJ pills, MERGE. The only solid-accent
     * fill allowed in a timeline row, so intervention never blurs with selection (a 15% tint).
     * Text on it is [onAccent]. Alias of [accent].
     */
    val intervention = accent

    /** POST / PUT / PATCH / DELETE and unknown verbs — painted **bold**. Alias of [text]. */
    val methodWrite = text

    /** GET / HEAD / OPTIONS — painted regular weight. Alias of [textDim]. */
    val methodRead = textDim

    /**
     * The one green left in the palette.
     *
     * The redesign deletes every other green (the POST method hue, the 2xx status hue) because a
     * green that means five different things means none of them. Two live indicators still need
     * "the system is running", and only these two may use this token:
     *
     *  1. [StatusDot] while capture is running;
     *  2. the mocks strip's sync dot once the device has acknowledged the current revision.
     *
     * Anything else — a status, a badge, a method, a banner — uses the semantic tokens instead.
     */
    val ok = c(0x1A8A3A, 0x5CC26F)

    // find highlight
    val findAll: Color = JBColor(rgba(0xFFD54F, 0.55f), rgba(0xE3B34C, 0.30f))

    // JSON syntax
    val jsonKey = c(0x871094, 0xC77DBB)
    val jsonString = c(0x067D17, 0x7FB069)
    val jsonNumber = c(0x1750EB, 0x5B9DFF)
    val jsonBool = c(0x0033B3, 0xCC8A52)
    val jsonNull = c(0x808080, 0x8D9298)
    val jsonPunct = c(0x5B5E66, 0x787C84)
    val jsonCount = c(0x8C8F98, 0x6F737A)

    // aliases kept for existing widgets
    val cardBg get() = bg1
    val cardBorder get() = borderStrong
    val chipBg get() = bg1

    private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")

    /**
     * Whether [method] only reads. Reads render regular weight in [methodRead]; everything else —
     * including verbs LogPose has never heard of — renders bold in [methodWrite], because an
     * unrecognised verb is far more likely to change server state than not.
     */
    fun isRead(method: String): Boolean = method.trim().uppercase() in READ_METHODS

    /**
     * The text colour for a method label. Method carries **no hue**: this returns one of two
     * neutrals, and the caller pairs it with the matching weight (bold for writes).
     */
    fun methodTextColor(method: String): JBColor = if (isRead(method)) methodRead else methodWrite

    // event-type palette — one hue per kind, and the *only* hue axis left in the UI. Dark values
    // are the brand hues; light values are darkened for ≥4.5:1 on a white surface.
    private val tNet = c(0x2E6AE0, 0x5B9DFF)
    private val tFcm = c(0x8B3FD9, 0xC084FC)
    private val tDb = c(0xA86A12, 0xE0A740)
    private val tWork = c(0x0E9488, 0x2DD4BF)
    private val tConf = c(0x5B6472, 0x94A3B8)
    private val tAnly = c(0xC42E7A, 0xF472B6)
    private val tApp = c(0x1F9E4A, 0x4ADE80)

    /**
     * The hue for an event kind.
     *
     * **Scope restriction — kind hues may be painted ONLY on:**
     *  1. the timeline row's gutter glyph,
     *  2. the TYPE filter chip (text + stroke + 14–16% tint fill when selected),
     *  3. the detail-header kind pill,
     *  4. waterfall lane bars and dots.
     *
     * Nowhere else — never on banners, badges, status pills, method labels or the mocks strip.
     * One axis owns hue; a kind hue anywhere else makes the kind read as a status.
     */
    fun typeColor(kind: String): JBColor = when (kind) {
        Envelope.KIND_HTTP -> tNet
        Envelope.KIND_FCM -> tFcm
        Envelope.KIND_DB -> tDb
        Envelope.KIND_WORKER -> tWork
        Envelope.KIND_CONFIG -> tConf
        Envelope.KIND_ANALYTICS -> tAnly
        else -> tApp   // event / app-defined
    }

    /**
     * Status text colour. Status carries **semantics only** — success is not an event, so 2xx and
     * 3xx are neutral and the eye is free for the two codes that mean something went wrong.
     */
    fun statusColor(code: Int?, error: String?): JBColor = when {
        error != null -> danger
        code == null -> statusNeutral
        code in 400..499 -> warn
        code >= 500 -> danger
        else -> statusNeutral   // 1xx / 2xx / 3xx
    }

    /**
     * The pill fill paired with [statusColor] — **opaque**, and deliberately so.
     *
     * [warnTint] and [dangerTint] are 17% alpha, so painted as-is a status pill composites over
     * whatever the *row* is painted with: a hovered row and a selected row (a 15% accent tint of
     * their own) each pushed the pill's own text further down, and danger-on-selected fell under
     * 4:1 in both themes. Pre-blending the same two tints onto [bg0] once gives every status pill
     * one ground wherever it lands, so hovering a row can no longer change how legible its status
     * is. The tint tokens themselves are unchanged — this is only where they are resolved.
     */
    fun statusTint(code: Int?, error: String?): Color = when {
        error != null -> dangerPlate
        code == null -> statusNeutralBg
        code in 400..499 -> warnPlate
        code >= 500 -> dangerPlate
        else -> statusNeutralBg
    }

    /** [warnTint] / [dangerTint] resolved against [bg0] in each theme — see [statusTint]. */
    private val warnPlate: JBColor = overBg0(warnTint)
    private val dangerPlate: JBColor = overBg0(dangerTint)

    /** Flattens a translucent tint onto the window background, one composite per theme. */
    private fun overBg0(tint: Color): JBColor =
        JBColor(blend(tint, BG0_LIGHT), blend(tint, BG0_DARK))

    private fun blend(tint: Color, base: Int): Color {
        val a = tint.alpha / 255f
        fun mix(f: Int, shift: Int) = (f * a + ((base shr shift) and 0xFF) * (1 - a)).toInt().coerceIn(0, 255)
        return Color(mix(tint.red, 16), mix(tint.green, 8), mix(tint.blue, 0))
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

/**
 * A pill/badge label: rounded background (radius 6, padding 2/8) with centred coloured text.
 *
 * Normally fill-only. The optional [stroke] turns it into an *outlined* pill, which the design
 * system reserves for advice rather than fact: a filled amber pill states a 4xx, an outlined
 * amber pill (`DUP ×2`) advises that the call looks redundant.
 */
class TagLabel(arc: Int = 6) : JLabel("", SwingConstants.CENTER) {
    var pillBg: Color? = null
    var pillStroke: Color? = null
    private val arcPx = arc

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8)
        font = JBUI.Fonts.label(11f)
    }

    /** Sets text, foreground and fill; [stroke] defaults to none, so a reused label never keeps one. */
    fun set(text: String, fg: Color, bg: Color?, stroke: Color? = null) {
        this.text = text
        foreground = fg
        pillBg = bg
        pillStroke = stroke
    }

    override fun paintComponent(g: Graphics) {
        if (pillBg != null || pillStroke != null) {
            val g2 = g.create() as Graphics2D
            g2.aa()
            pillBg?.let { g2.color = it; g2.fillRoundRect(0, 0, width, height, arcPx, arcPx) }
            pillStroke?.let { g2.color = it; g2.drawRoundRect(0, 0, width - 1, height - 1, arcPx, arcPx) }
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * A single line of text made of several **runs**, each with its own font and colour, ellipsized as
 * one.
 *
 * A `JLabel` carries exactly one font and one colour, which is why the generic row used to paint
 * `title  ·  subtitle` in a single flat run — and why a db row could not put its table in label 12.5
 * `text` and the statement behind it in mono 11 `textMuted` (§2.4, §6). This does, with a bounded
 * cost: one `stringWidth` per run that fits, plus a binary search over the one run that doesn't.
 * A `JLabel`'s own ellipsis already measures, so this is not new work in a repaint — but the string
 * handed in must still be capped by the caller, never the untruncated payload.
 */
class RunLabel : JComponent() {

    data class Run(val text: String, val font: Font, val color: Color)

    var runs: List<Run> = emptyList()

    init { isOpaque = false }

    override fun getPreferredSize(): Dimension = Dimension(0, JBUI.scale(20))
    override fun getMinimumSize(): Dimension = Dimension(0, JBUI.scale(20))

    override fun paintComponent(g: Graphics) {
        if (runs.isEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.aa()
        val insets = insets
        val available = width - insets.left - insets.right
        // One shared baseline, taken from the first run, so a mono 11 tail sits on the same line as
        // the label 12.5 name it follows rather than drifting by the second font's ascent.
        val primary = getFontMetrics(runs[0].font)
        val baseline = (height + primary.ascent - primary.descent) / 2
        var x = insets.left
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val fm = getFontMetrics(run.font)
            val remaining = insets.left + available - x
            if (remaining <= 0) break
            g2.font = run.font
            g2.color = run.color
            val w = fm.stringWidth(run.text)
            if (w <= remaining) {
                g2.drawString(run.text, x, baseline)
                x += w
            } else {
                g2.drawString(clip(run.text, fm, remaining), x, baseline)
                break
            }
        }
        g2.dispose()
    }

    /** Longest prefix of [text] that fits in [max] with an ellipsis, found by binary search. */
    private fun clip(text: String, fm: java.awt.FontMetrics, max: Int): String {
        val ell = "…"
        val ellW = fm.stringWidth(ell)
        if (ellW > max) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fm.stringWidth(text.substring(0, mid)) + ellW <= max) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + ell
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

/**
 * A small key/value "stat" card used in the overview hero: caption 9.5 uppercase over a mono 14
 * bold value, radius 8, `bg2` fill, `borderStrong` stroke, padding 6/12.
 *
 * The value **never wraps** — HTML rendering is disabled on it, so a value that happens to start
 * with a tag can't turn the chip into a paragraph; over-long values ellipsize instead.
 */
class StatChip(caption: String, value: String, tip: String? = null) : CardPanel(java.awt.GridLayout(2, 1, 0, 1)) {
    private val valueLabel = JLabel(value).apply {
        foreground = Theme.text
        font = JBUI.Fonts.create("JetBrains Mono", 14).asBold()
        putClientProperty("html.disable", true)
    }
    private val captionLabel =
        JLabel(caption.uppercase()).apply { foreground = Theme.textMuted; font = JBUI.Fonts.label(9.5f) }

    init {
        arc = 8
        fill = Theme.bg2
        stroke = Theme.borderStrong
        border = JBUI.Borders.empty(6, 12)
        tip?.let { toolTipText = it }
        add(captionLabel)
        add(valueLabel)
    }

    /** Update the value in place (used for the live duration of a pending request). */
    fun value(v: String) { valueLabel.text = v }

    /**
     * Turns the chip into a link: accent-coloured value, hand cursor, [action] on click.
     *
     * A trace id isn't only a fact about the selected event — it's the way into that flow's
     * waterfall — so the chip that states it is also the control that opens it. The listener goes
     * on the labels too, since a click lands on whichever child is under the pointer.
     */
    fun clickable(tooltip: String? = null, action: () -> Unit): StatChip {
        tooltip?.let { toolTipText = it }
        valueLabel.foreground = Theme.accent
        val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val listener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = action()
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                stroke = Theme.accent; valueLabel.foreground = Theme.accentHover; repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                // Crossing from a label to the panel exits one and enters the other in the same
                // gesture, so the highlight simply re-arms rather than needing bounds arithmetic.
                stroke = Theme.borderStrong; valueLabel.foreground = Theme.accent; repaint()
            }
        }
        listOf<JComponent>(this, valueLabel, captionLabel).forEach {
            it.cursor = hand
            it.addMouseListener(listener)
        }
        return this
    }
}

/**
 * Rounded action button — filled (accent) or ghost (outlined). Radius 8, min height 24,
 * label 12 bold.
 *
 * Hover is **fill-only** in both variants: filled swaps `accent` → `accentHover`, ghost swaps
 * `bg2` → `rowHover`. The stroke and the label never move, so a row of buttons doesn't shimmer
 * as the pointer crosses it.
 */
class PillButton(text: String, private val filled: Boolean) : JButton(text) {
    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        // Swing only publishes rollover to the model when the button asks for it, and no LaF the
        // plugin runs under sets `Button.rollover`, so without this the hover below is dead code —
        // `model.isRollover` would stay false for the life of the button.
        isRolloverEnabled = true
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
            g2.color = if (model.isRollover) Theme.rowHover else Theme.bg2
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

/** A pulsing status dot: [Theme.ok] (capturing) or [Theme.danger] (stopped). */
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
        val base: Color = if (capturing) Theme.ok else Theme.danger
        g2.color = Color(base.red, base.green, base.blue, (pulse * 255).toInt())
        val d = JBUI.scale(8)
        g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
        g2.dispose()
    }

    fun dispose() = timer.stop()
}

/**
 * A text action that reads as a link: `accent`, label 11, hand cursor; on hover `accentHover`
 * plus an underline.
 *
 * The underline is the point. Seven places in the tool window used to hand-roll "accent text with
 * a hand cursor", which is only discoverable once the pointer is already on it; the underline
 * makes the rollover say *this is clickable* rather than *this is highlighted*. Deriving the
 * underline as a font attribute keeps the advance widths identical, so nothing reflows.
 *
 * [onClick] is a `var` so a caller that needs the component itself (a menu anchored under the
 * label, say) can wire the action after construction.
 */
class LinkLabel(text: String = "", action: () -> Unit = {}) : JBLabel(text) {

    var onClick: () -> Unit = action

    private val plainFont: Font = JBUI.Fonts.label(11f)
    private val underlinedFont: Font =
        plainFont.deriveFont(java.util.Collections.singletonMap(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON))

    init {
        foreground = Theme.accent
        font = plainFont
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                foreground = Theme.accentHover; font = underlinedFont
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                foreground = Theme.accent; font = plainFont
            }
        })
    }
}

/**
 * A 16px icon in a 26px hit box, radius 6 — the one control for every icon-plus-tooltip action.
 *
 * At rest the box is transparent and the icon is tinted `textDim`; hover fills `bg2` and lifts the
 * icon to `text`; pressed fills `bg3`. The box is what makes it a button: the bare `JLabel(icon)`
 * it replaces had a 16px target and no rollover at all, so an action you could see was an action
 * you had to guess at.
 *
 * `isEnabled = false` gives the same treatment §5 specifies for a disabled ToggleSwitch — the whole
 * control at 40% alpha, no cursor, no hover, no click. An end of the occurrence stepper is the
 * first place that was needed: an arrow that silently does nothing is worse than one that says it
 * cannot.
 *
 * The tint is done here rather than through `IconUtil.colorize`: that function carries Kotlin
 * default arguments whose synthetic `colorize$default` bridge changed arity between 2024.1 and
 * 2025.2, so a call to it fails the plugin verifier on the newer IDEs. The re-colour below uses
 * only long-stable Java statics, and is cached so a repaint never re-renders the icon.
 */
class IconButton(private val icon: javax.swing.Icon, tooltip: String, action: () -> Unit = {}) : JComponent() {

    var onClick: () -> Unit = action

    private var hovered = false
    private var pressed = false
    private var cacheKey: Pair<Int, Double>? = null
    private var cached: BufferedImage? = null

    init {
        isOpaque = false
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = Dimension(JBUI.scale(26), JBUI.scale(26))
        preferredSize = d; minimumSize = d; maximumSize = d
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { if (isEnabled) { hovered = true; repaint() } }
            override fun mouseExited(e: java.awt.event.MouseEvent) { hovered = false; pressed = false; repaint() }
            override fun mousePressed(e: java.awt.event.MouseEvent) { if (isEnabled) { pressed = true; repaint() } }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { pressed = false; repaint() }
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (isEnabled) onClick() }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        // A rollover left behind by the state change would outlive the pointer, so both flags are
        // dropped rather than trusted to a mouseExited that may never come.
        hovered = false
        pressed = false
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        repaint()
    }

    /**
     * The icon painted into an offscreen image and flooded with [color] through `SrcAtop`, which
     * keeps the glyph's own alpha (and so its antialiased edges) while replacing every hue in it.
     *
     * Keyed on the colour *and* the device scale, so the cache survives a repaint but not a move
     * to a differently-scaled display or a theme change.
     */
    private fun tinted(color: Color, g2: Graphics2D): BufferedImage? {
        val w = icon.iconWidth
        val h = icon.iconHeight
        if (w <= 0 || h <= 0) return null
        val key = color.rgb to g2.transform.scaleX
        cached?.let { if (cacheKey == key) return it }

        val img = ImageUtil.createImage(g2, w, h, BufferedImage.TYPE_INT_ARGB)
        val ig = img.createGraphics()
        try {
            ig.aa()
            icon.paintIcon(this, ig, 0, 0)
            ig.composite = AlphaComposite.SrcAtop
            ig.color = color
            ig.fillRect(0, 0, w, h)
        } finally {
            ig.dispose()
        }
        cacheKey = key
        cached = img
        return img
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.aa()
        val arc = JBUI.scale(6)
        val active = isEnabled && (pressed || hovered)
        if (!isEnabled) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
        if (active) {
            g2.color = if (pressed) Theme.bg3 else Theme.bg2
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        }
        val x = (width - icon.iconWidth) / 2
        val y = (height - icon.iconHeight) / 2
        val img = tinted(if (active) Theme.text else Theme.textDim, g2)
        if (img != null) UIUtil.drawImage(g2, img, x, y, null) else icon.paintIcon(this, g2, x, y)
        g2.dispose()
    }
}

fun Transaction.statusText(): String = response?.code?.toString() ?: if (error != null) "ERR" else "···"

/** True while the request is in flight (request emitted, no response/error yet). */
fun Transaction.isPending(): Boolean = response == null && error == null

private const val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

/** A braille spinner glyph for the given animation frame. */
fun spinnerChar(frame: Int): Char = SPINNER[((frame % SPINNER.length) + SPINNER.length) % SPINNER.length]
