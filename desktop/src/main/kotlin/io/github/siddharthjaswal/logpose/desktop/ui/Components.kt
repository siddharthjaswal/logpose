package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.model.Envelope

/** Type scale, matching the plugin's row fonts (label 12.5 / 11, mono 11 / 10). */
object Type {
    val mono = FontFamily.Monospace
    val body = TextStyle(fontSize = 12.5.sp)
    val small = TextStyle(fontSize = 11.sp)
    val pill = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    val monoSmall = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val monoTiny = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun T(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    weight: FontWeight? = null,
    size: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        fontWeight = weight,
        fontSize = size,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The plugin's `TagLabel`: a small pill, either filled ([fill]) or outlined ([stroke]). Filled
 * accent is reserved for interventions (MOCK / INJ); outlined is advice; tinted is status.
 */
@Composable
fun Pill(
    text: String,
    fg: Color,
    fill: Color? = null,
    stroke: Color? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.pill,
) {
    val shape = RoundedCornerShape(4.dp)
    var m = modifier.height(18.dp)
    if (fill != null) m = m.background(fill, shape)
    if (stroke != null) m = m.border(1.dp, stroke, shape)
    Box(m.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        T(text, fg, style = style)
    }
}

/** A plain text button in the toolbar's vocabulary: [primary] is the one accent-filled action. */
@Composable
fun Button(text: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        !enabled -> p.bg2
        primary -> if (hovered) p.accentHover else p.accent
        else -> if (hovered) p.bg3 else p.bg2
    }
    val fg = when {
        !enabled -> p.textMuted
        primary -> p.onAccent
        else -> p.text
    }
    Box(
        modifier
            .height(28.dp)
            .background(bg, shape)
            .border(1.dp, if (primary && enabled) Color.Transparent else p.borderStrong, shape)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, fg, style = Type.body, weight = FontWeight.Medium)
    }
}

/**
 * The seven kind glyphs, ported from the plugin's `icons/types/` SVGs. Each is a single-colour
 * stroke drawing, so it is tinted with the active theme's kind hue — the light theme's darker
 * values, which the plugin's single dark-hued SVGs never got.
 */
object KindGlyphs {
    private val files = mapOf(
        Envelope.KIND_HTTP to "net", Envelope.KIND_FCM to "fcm", Envelope.KIND_DB to "db",
        Envelope.KIND_WORKER to "work", Envelope.KIND_CONFIG to "conf", Envelope.KIND_ANALYTICS to "anly",
        Envelope.KIND_EVENT to "app",
    )
    private val cache = HashMap<Pair<String, Float>, Painter?>()

    @Synchronized
    fun painter(kind: String, density: Density): Painter? {
        val name = files[kind] ?: "app"
        return cache.getOrPut(name to density.density) {
            runCatching {
                KindGlyphs::class.java.getResourceAsStream("/icons/types/$name.svg")!!.use { loadSvgPainter(it, density) }
            }.getOrNull()
        }
    }
}

@Composable
fun KindGlyph(kind: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val painter = KindGlyphs.painter(kind, LocalDensity.current) ?: return
    Image(painter, contentDescription = kind, modifier = modifier.size(16.dp), colorFilter = ColorFilter.tint(p.kind(kind)))
}
