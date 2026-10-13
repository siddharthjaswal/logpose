package io.github.siddharthjaswal.logpose.desktop.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.siddharthjaswal.logpose.model.Envelope

/**
 * The plugin's design tokens (`ui/Ui.kt`'s `Theme`) as Compose colours, one [Palette] per macOS
 * appearance.
 *
 * **Values only.** The plugin's `JBColor` pairs are copied here by value — the Mac app must not
 * depend on the plugin — and `ThemeParityTest` reads `Ui.kt` and fails if any pair drifts. The
 * same rule holds as in the IDE: **one axis owns hue.** Kind keeps its seven hues ([kind]); status
 * carries semantics only (2xx/3xx neutral, 4xx [warn], 5xx [danger]); method carries no colour at
 * all (read vs write is weight); and [intervention] — what LogPose itself did — is the one solid
 * accent fill a row may carry.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    // surfaces
    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val rowHover: Color,
    // borders
    val borderStrong: Color,
    val borderSubtle: Color,
    // text
    val text: Color,
    val textDim: Color,
    val textMuted: Color,
    // accent
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    // semantics
    val warn: Color,
    val danger: Color,
    val ok: Color,
    // JSON syntax
    val jsonKey: Color,
    val jsonString: Color,
    val jsonNumber: Color,
    val jsonBool: Color,
    val jsonNull: Color,
    val jsonPunct: Color,
    val jsonCount: Color,
    // kind hues — the only hue axis
    val kindNet: Color,
    val kindFcm: Color,
    val kindDb: Color,
    val kindWork: Color,
    val kindConf: Color,
    val kindAnly: Color,
    val kindApp: Color,
) {
    /** 15% accent: selection. Never a solid fill — that belongs to [intervention]. */
    val accentTint: Color get() = rgba(0x3574F0, 0.15f)

    val statusNeutral: Color get() = textDim
    val statusNeutralBg: Color get() = bg2
    val intervention: Color get() = accent
    val methodWrite: Color get() = text
    val methodRead: Color get() = textDim

    /** The 17% warn / danger tints pre-blended onto [bg0], exactly as the plugin resolves them, so
     *  a status pill keeps one ground on a hovered or selected row. */
    val warnPlate: Color get() = blend(rgba(0xE3B34C, 0.17f), bg0)
    val dangerPlate: Color get() = blend(rgba(0xEC7A70, 0.17f), bg0)

    /** The 17% tints the duplicate banner fills with (the plugin's `warnTint` / `dangerTint`). */
    val warnTint: Color get() = rgba(0xE3B34C, 0.17f)
    val dangerTint: Color get() = rgba(0xEC7A70, 0.17f)

    /** Find-in-body highlight (the plugin's `findAll`). */
    val findAll: Color get() = if (dark) rgba(0xE3B34C, 0.30f) else rgba(0xFFD54F, 0.55f)

    /** [c] at [alpha]/255 — the plugin's `Theme.tint`. */
    fun tint(c: Color, alpha: Int = 38): Color = c.copy(alpha = alpha / 255f)

    /**
     * [c] blended toward the window background, keeping [keep] of it — the plugin's `Theme.fade`,
     * which is what a muted endpoint and a folded transaction are drawn with (0.34, 0.7 hovered).
     */
    fun fade(c: Color, keep: Float): Color {
        fun mix(f: Float, t: Float) = (f * keep + t * (1 - keep)).coerceIn(0f, 1f)
        return Color(mix(c.red, bg0.red), mix(c.green, bg0.green), mix(c.blue, bg0.blue))
    }

    fun kind(kind: String): Color = when (kind) {
        Envelope.KIND_HTTP -> kindNet
        Envelope.KIND_FCM -> kindFcm
        Envelope.KIND_DB -> kindDb
        Envelope.KIND_WORKER -> kindWork
        Envelope.KIND_CONFIG -> kindConf
        Envelope.KIND_ANALYTICS -> kindAnly
        else -> kindApp
    }

    /** Status text: success is not an event, so 2xx/3xx stay neutral. */
    fun statusColor(code: Int?, error: String?): Color = when {
        error != null -> danger
        code == null -> statusNeutral
        code in 400..499 -> warn
        code >= 500 -> danger
        else -> statusNeutral
    }

    fun statusFill(code: Int?, error: String?): Color = when {
        error != null -> dangerPlate
        code == null -> statusNeutralBg
        code in 400..499 -> warnPlate
        code >= 500 -> dangerPlate
        else -> statusNeutralBg
    }
}

object DesktopTheme {

    private fun c(rgb: Int) = Color(0xFF000000 or rgb.toLong())

    /** Light values mirror the JetBrains New UI light theme, as in the plugin. */
    val Light = Palette(
        dark = false,
        bg0 = c(0xF7F8FA), bg1 = c(0xFFFFFF), bg2 = c(0xF0F1F4), bg3 = c(0xE3E5EA), rowHover = c(0xEAECF1),
        borderStrong = c(0xC6C9D2), borderSubtle = c(0xE7E8EE),
        text = c(0x1E1F22), textDim = c(0x6C707E), textMuted = c(0x9296A1),
        accent = c(0x3574F0), accentHover = c(0x2E6AE0), onAccent = c(0xFFFFFF),
        warn = c(0xA86A12), danger = c(0xCF3030), ok = c(0x1A8A3A),
        jsonKey = c(0x871094), jsonString = c(0x067D17), jsonNumber = c(0x1750EB), jsonBool = c(0x0033B3),
        jsonNull = c(0x808080), jsonPunct = c(0x5B5E66), jsonCount = c(0x8C8F98),
        kindNet = c(0x2E6AE0), kindFcm = c(0x8B3FD9), kindDb = c(0xA86A12), kindWork = c(0x0E9488),
        kindConf = c(0x5B6472), kindAnly = c(0xC42E7A), kindApp = c(0x1F9E4A),
    )

    /** Dark values descend from the original "Studio" palette. */
    val Dark = Palette(
        dark = true,
        bg0 = c(0x1E1F22), bg1 = c(0x26282C), bg2 = c(0x2B2D30), bg3 = c(0x303236), rowHover = c(0x2F3136),
        borderStrong = c(0x393B40), borderSubtle = c(0x2D2F33),
        text = c(0xDFE1E5), textDim = c(0xA3A6AD), textMuted = c(0x6B6E76),
        accent = c(0x3574F0), accentHover = c(0x4A82F2), onAccent = c(0xFFFFFF),
        warn = c(0xE3B34C), danger = c(0xEC7A70), ok = c(0x5CC26F),
        jsonKey = c(0xC77DBB), jsonString = c(0x7FB069), jsonNumber = c(0x5B9DFF), jsonBool = c(0xCC8A52),
        jsonNull = c(0x8D9298), jsonPunct = c(0x787C84), jsonCount = c(0x6F737A),
        kindNet = c(0x5B9DFF), kindFcm = c(0xC084FC), kindDb = c(0xE0A740), kindWork = c(0x2DD4BF),
        kindConf = c(0x94A3B8), kindAnly = c(0xF472B6), kindApp = c(0x4ADE80),
    )

    /**
     * Every token pair by its plugin name (`Ui.kt`'s `Theme.<name>`, or the private `tNet`… for
     * kind hues) — what `ThemeParityTest` compares against the plugin source.
     */
    fun pairs(): Map<String, Pair<Color, Color>> {
        fun p(get: (Palette) -> Color) = get(Light) to get(Dark)
        return mapOf(
            "bg0" to p { it.bg0 }, "bg1" to p { it.bg1 }, "bg2" to p { it.bg2 }, "bg3" to p { it.bg3 },
            "rowHover" to p { it.rowHover },
            "borderStrong" to p { it.borderStrong }, "borderSubtle" to p { it.borderSubtle },
            "text" to p { it.text }, "textDim" to p { it.textDim }, "textMuted" to p { it.textMuted },
            "accent" to p { it.accent }, "accentHover" to p { it.accentHover }, "onAccent" to p { it.onAccent },
            "warn" to p { it.warn }, "danger" to p { it.danger }, "ok" to p { it.ok },
            "jsonKey" to p { it.jsonKey }, "jsonString" to p { it.jsonString }, "jsonNumber" to p { it.jsonNumber },
            "jsonBool" to p { it.jsonBool }, "jsonNull" to p { it.jsonNull }, "jsonPunct" to p { it.jsonPunct },
            "jsonCount" to p { it.jsonCount },
            "tNet" to p { it.kindNet }, "tFcm" to p { it.kindFcm }, "tDb" to p { it.kindDb },
            "tWork" to p { it.kindWork }, "tConf" to p { it.kindConf }, "tAnly" to p { it.kindAnly },
            "tApp" to p { it.kindApp },
        )
    }
}

val LocalPalette = staticCompositionLocalOf { DesktopTheme.Dark }

@Composable
fun LogPoseTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (dark) DesktopTheme.Dark else DesktopTheme.Light) {
        content()
    }
}

internal fun rgba(rgb: Int, alpha: Float): Color =
    Color(red = (rgb shr 16) and 0xFF, green = (rgb shr 8) and 0xFF, blue = rgb and 0xFF, alpha = (alpha * 255).toInt())

/** Flattens a translucent [tint] onto an opaque [base] — the plugin's `Theme.blend`, same rounding. */
internal fun blend(tint: Color, base: Color): Color {
    fun byte(v: Float) = (v * 255f + 0.5f).toInt()
    val a = byte(tint.alpha) / 255f
    fun mix(f: Float, b: Float) = (byte(f) * a + byte(b) * (1 - a)).toInt().coerceIn(0, 255)
    return Color(mix(tint.red, base.red), mix(tint.green, base.green), mix(tint.blue, base.blue))
}
