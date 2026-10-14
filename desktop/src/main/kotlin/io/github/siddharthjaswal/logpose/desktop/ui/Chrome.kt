package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import java.awt.Cursor

/**
 * The window chrome's controls: a native-Mac toolbar vocabulary (28px icon buttons on a hover
 * plate, segmented groups, a focus ring), anchored popovers and pane splitters. Colours are palette
 * tokens only.
 */

/** A focus ring for keyboard users: 2px accent, drawn only while [focused]. */
fun Modifier.focusRing(focused: Boolean, shape: RoundedCornerShape, color: Color): Modifier =
    if (focused) this.border(2.dp, color, shape) else this

/**
 * A 28×28 toolbar icon button. [active] is an on toggle: an accent glyph with no plate,
 * the Xcode pane-toggle convention. Every one has a tooltip.
 */
@Composable
fun IconButton(
    ico: Ico,
    tip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color? = null,
    size: Dp = 28.dp,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        !enabled -> Color.Transparent
        pressed -> p.bg3
        hovered -> p.rowHover
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> p.textMuted.copy(alpha = 0.55f)
        active -> p.accent
        tint != null -> tint
        hovered -> p.text
        else -> p.textDim
    }
    Hint(tip) {
        Box(
            Modifier.size(size).background(bg, shape).focusRing(focused, shape, p.accent)
                .hoverable(interaction)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(ico, fg) }
    }
}

/**
 * A toolbar button with an icon and a label (the actions whose words matter: Start / Stop, Share).
 * [primary] is the one accent-filled action in the bar.
 */
@Composable
fun ToolButton(
    text: String,
    ico: Ico?,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    tip: String? = null,
    icoTint: Color? = null,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        !enabled -> p.bg2
        primary -> if (hovered || pressed) p.accentHover else p.accent
        pressed -> p.bg3
        hovered -> p.rowHover
        else -> p.bg2
    }
    val fg = when {
        !enabled -> p.textMuted
        primary -> p.onAccent
        else -> p.text
    }
    Hint(tip) {
        Row(
            Modifier.height(28.dp).background(bg, shape)
                .border(1.dp, if (primary && enabled) Color.Transparent else p.borderStrong, shape)
                .focusRing(focused, shape, p.accent)
                .hoverable(interaction)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(start = if (ico != null) 8.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ico != null) {
                Icon(ico, if (enabled) icoTint ?: fg else fg, size = 14.dp)
                Spacer(Modifier.width(6.dp))
            }
            T(text, fg, style = Type.body, weight = FontWeight.Medium)
        }
    }
}

/** A run of icon buttons that belong together, 2px apart. */
@Composable
fun ToolGroup(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp), content = content)
}

/** A 1×20 separator between toolbar groups. */
@Composable
fun ToolSeparator() {
    val p = LocalPalette.current
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(20.dp).background(p.borderSubtle))
}

/** Places a popover below (or [above]) its anchor, start- or end-aligned, kept inside the window. */
private class AnchorPosition(private val above: Boolean, private val alignEnd: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 6
        val x = (if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left)
            .coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
        val y = (if (above) anchorBounds.top - gap - popupContentSize.height else anchorBounds.bottom + gap)
            .coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

/**
 * A popover card anchored to the composable it is placed in: bg1, radius 10, a strong border and a
 * soft shadow. Focusable, so its search field takes keys and a click outside or Esc dismisses it.
 */
@Composable
fun Popover(
    onDismiss: () -> Unit,
    width: Dp,
    above: Boolean = false,
    alignEnd: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val shape = RoundedCornerShape(10.dp)
    Popup(
        popupPositionProvider = remember(above, alignEnd, gap) { AnchorPosition(above, alignEnd, gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        Column(
            Modifier.padding(16.dp).width(width).shadow(12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
                .background(p.bg1, shape).border(1.dp, p.borderStrong, shape),
            content = content,
        )
    }
}

/** Ignores the click that lands on a popover's own button right after an outside-click dismissed it. */
class PopoverToggle {
    private var dismissedAt = 0L
    fun dismissed() { dismissedAt = System.currentTimeMillis() }
    fun allow(): Boolean = System.currentTimeMillis() - dismissedAt > 250
}

/** A small caps section caption inside popovers and the welcome cards. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text.uppercase(), p.textMuted, style = Type.small.copy(fontSize = 10.sp), weight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        trailing?.let { T(it, p.textMuted, style = Type.monoTiny) }
    }
}


/** A full-width menu line: optional icon, label, optional shortcut; hover plate. */
@Composable
fun MenuRow(text: String, ico: Ico? = null, shortcut: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(28.dp)
            .background(if (hovered && enabled) p.rowHover else Color.Transparent, RoundedCornerShape(6.dp))
            .hoverable(interaction).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ico != null) { Icon(ico, if (enabled) p.textDim else p.textMuted, size = 14.dp); Spacer(Modifier.width(10.dp)) }
        T(text, if (enabled) p.text else p.textMuted, Modifier.weight(1f), style = Type.body)
        shortcut?.let { T(it, p.textMuted, style = Type.small) }
    }
}

/** A hairline across a popover. */
@Composable
fun PopoverDivider() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

/**
 * A draggable boundary between two panes. 5px of hit area around a 1px line that turns accent
 * while hovered or dragged; the resize cursor; double-click resets. [vertical] = a vertical line
 * (panes side by side). [onDrag] gets the pointer's movement in dp along the drag axis.
 */
@Composable
fun Splitter(vertical: Boolean, onDrag: (Float) -> Unit, onReset: () -> Unit, tip: String? = null) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val drag by rememberUpdatedState(onDrag)
    val reset by rememberUpdatedState(onReset)
    val line = if (hovered || dragging) p.accent else p.borderSubtle
    val cursor = PointerIcon(Cursor(if (vertical) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    val m = (if (vertical) Modifier.width(5.dp).fillMaxHeight() else Modifier.height(5.dp).fillMaxWidth())
        .hoverable(interaction)
        .pointerHoverIcon(cursor)
        .pointerInput(vertical) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, amount ->
                change.consume()
                drag(with(density) { (if (vertical) amount.x else amount.y).toDp().value })
            }
        }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
    Hint(tip) {
        Box(m, contentAlignment = Alignment.Center) {
            Box(if (vertical) Modifier.width(1.dp).fillMaxHeight().background(line) else Modifier.height(1.dp).fillMaxWidth().background(line))
        }
    }
}

/** `/Users/qa/Library/…/platform-tools/adb`: the home folder as `~`, the middle elided past [max] chars. */
fun shortPath(path: String, max: Int = 44, home: String = System.getProperty("user.home").orEmpty()): String {
    val tilde = if (home.isNotEmpty() && path.startsWith("$home/")) "~" + path.removePrefix(home) else path
    if (tilde.length <= max) return tilde
    val tail = tilde.substring(tilde.length - (max * 2 / 3))
    val head = tilde.take(max - tail.length - 1)
    return "$head…$tail"
}
