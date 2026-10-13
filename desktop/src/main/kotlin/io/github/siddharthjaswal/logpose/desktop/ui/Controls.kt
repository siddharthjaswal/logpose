package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import io.github.siddharthjaswal.logpose.presentation.RowContent

/**
 * The plugin's small controls (`ui/Ui.kt`, `ui/FilterBar.kt`) in Compose — same sizes, radii and
 * fill/stroke grammar, so the two hosts look like one product.
 */

/** A hover hint, styled like the IDE's tooltip. Wraps [content]; no-op when [text] is null. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Hint(text: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isNullOrBlank()) { Box(modifier) { content() }; return }
    val p = LocalPalette.current
    TooltipArea(
        tooltip = {
            Box(
                Modifier.widthIn(max = 360.dp).background(p.bg1, RoundedCornerShape(6.dp))
                    .border(1.dp, p.borderStrong, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            ) { T(text, p.text, style = Type.small.copy(lineHeight = 15.sp), maxLines = 12) }
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(8.dp, 16.dp)),
    ) { content() }
}

/** A [RowContent.Hint] as tooltip text (the plugin bolds the lead; a plain tooltip can't). */
fun hintText(h: RowContent.Hint): String = (listOf(h.bold + h.rest) + h.lines).joinToString("\n")

/** The plugin's `StatChip`: caption 9.5 uppercase over a mono 14 bold value, radius 8, bg2 + borderStrong. */
@Composable
fun StatChipView(chip: DetailContent.Chip, value: String = chip.value, onLink: ((String) -> Unit)? = null) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val link = chip.traceLink != null && onLink != null
    val shape = RoundedCornerShape(8.dp)
    Hint(chip.tip ?: chip.traceLink?.let { "LogPose trace $it" }) {
        Column(
            Modifier.background(p.bg2, shape)
                .border(1.dp, if (link && hovered) p.accent else p.borderStrong, shape)
                .hoverable(interaction)
                .then(if (link) Modifier.clickable { onLink?.invoke(chip.traceLink!!) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            T(chip.caption.uppercase(), p.textMuted, style = Type.small.copy(fontSize = 9.5.sp))
            T(
                value,
                if (chip.traceLink != null) (if (hovered && link) p.accentHover else p.accent) else p.text,
                style = Type.monoSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * The plugin's `ToggleChip`: label 11.5 bold, padding 3/13, radius 10. Unselected is a borderStrong
 * outline over textDim (hover: bg2 fill, text); selected fills with [color] at 40 alpha and strokes
 * and letters in [color] (hover doubles the ring). [glyph] makes it icon-only (the narrow layout):
 * a 14px glyph in a padding-4 radius-8 box on a neutral plate.
 */
@Composable
fun ToggleChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    glyphKind: String? = null,
    tip: String? = null,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val compact = glyphKind != null
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
    val fg = when {
        selected -> color
        hovered -> p.text
        else -> p.textDim
    }
    var m = Modifier.height(if (compact) 22.dp else 22.dp)
    m = when {
        selected -> m.background(if (compact) p.bg1 else p.tint(color, 40), shape).border(if (hovered) 2.dp else 1.dp, color, shape)
        hovered -> m.background(p.bg2, shape).border(1.dp, p.borderStrong, shape)
        else -> m.border(1.dp, p.borderStrong, shape)
    }
    Hint(if (compact) listOfNotNull(label, tip).joinToString(" — ") else tip) {
        Box(
            m.hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .then(if (compact) Modifier.size(22.dp) else Modifier.padding(horizontal = 13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (glyphKind != null) KindGlyph(glyphKind, Modifier.size(14.dp), tint = fg)
            else T(label, fg, style = Type.small.copy(fontSize = 11.5.sp), weight = FontWeight.Bold)
        }
    }
}

/**
 * The plugin's `RemovableChip`: `status 4xx–5xx ✕` — accentTint fill, accent stroke, `text` label
 * (accent-on-accentTint is unreadable), the ✕ at 80% as a statement; the whole chip is the target.
 */
@Composable
fun RemovableChip(label: String, tooltip: String, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Hint(tooltip) {
        Row(
            Modifier.height(20.dp).background(p.accentTint, shape).border(1.dp, p.accent, shape)
                .clickable(onClick = onRemove).padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(label, p.text, style = Type.small, weight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            T("✕", p.text, Modifier.alpha(0.8f), style = Type.small)
        }
    }
}

/** The plugin's `ToggleSwitch`: a 34×20 stadium, 14px knob inset 3, accent on / bg3 off. */
@Composable
fun ToggleSwitch(on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val track = when {
        on -> if (hovered) p.accentHover else p.accent
        hovered -> p.borderStrong
        else -> p.bg3
    }
    Box(
        Modifier.size(34.dp, 20.dp).background(track, RoundedCornerShape(10.dp)).hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(14.dp).background(p.onAccent, CircleShape)) }
}

/** The plugin's `LinkLabel`: accent text that underlines its intent with a hover colour. */
@Composable
fun LinkText(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    T(
        text, if (hovered) p.accentHover else p.accent,
        modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        style = Type.small, weight = FontWeight.Medium,
    )
}

/** The plugin's `PillButton`: filled (accent) or ghost (bg2 + borderStrong), radius 8, label 12 bold. */
@Composable
fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val bg = if (filled) (if (hovered) p.accentHover else p.accent) else (if (hovered) p.rowHover else p.bg2)
    Box(
        Modifier.height(26.dp).background(bg, shape)
            .then(if (filled) Modifier else Modifier.border(1.dp, p.borderStrong, shape))
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, if (filled) p.onAccent else p.text, style = Type.body.copy(fontSize = 12.sp, textAlign = TextAlign.Center), weight = FontWeight.Bold) }
}

/** A small square icon-ish text button (‹ › ✕ ⌕ ⧉) with a hover plate. */
@Composable
fun GlyphButton(glyph: String, tip: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Hint(tip) {
        Box(
            Modifier.size(22.dp).background(if (hovered && enabled) p.rowHover else Color.Transparent, RoundedCornerShape(5.dp))
                .hoverable(interaction)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { T(glyph, if (enabled) (if (hovered) p.text else p.textDim) else p.textMuted.copy(alpha = 0.5f), style = Type.body) }
    }
}

/** A 1×18 vertical hairline in borderStrong — the permanent row's separators. */
@Composable
fun VDivider() {
    val p = LocalPalette.current
    Box(Modifier.width(1.dp).height(18.dp).background(p.borderStrong))
}
