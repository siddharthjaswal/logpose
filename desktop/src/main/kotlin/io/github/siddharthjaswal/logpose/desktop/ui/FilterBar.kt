package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.timeline.FilterEdits
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineView
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation
import io.github.siddharthjaswal.logpose.presentation.FilterState

/**
 * The filter bar — the plugin's `FilterBar`, in Compose: a **permanent row** (search, the seven
 * TYPE chips, `Filters` with its badge, the `shown/total` counter) and an **echo row** that exists
 * only while a hidden filter is active. Every word it says — the badge count, each chip's label and
 * tooltip, the mode-switch explainers, the narrow thresholds — is core's [FilterPresentation]; this
 * only places it. Every edit goes through [FilterEdits] and comes back as a new [FilterState].
 */
@Composable
fun FilterBar(
    state: FilterState,
    view: TimelineView,
    onChange: (FilterState) -> Unit,
    modifier: Modifier = Modifier,
    popoverInitiallyOpen: Boolean = false,
    /** The correlation chip (M4b) — a Grouping beside the FilterState, cleared through [onClearCorrelation]. */
    correlation: Grouping? = null,
    onClearCorrelation: () -> Unit = {},
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var narrow by remember { mutableStateOf(false) }
    var popover by remember { mutableStateOf(popoverInitiallyOpen) }
    // What the wide row needs at its tightest (search at its 56dp minimum): measured, not guessed.
    val measurer = rememberTextMeasurer()
    val chipStyle = Type.small.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    val wideNeed = remember(view.countLabel) {
        val chips = EventType.entries.sumOf { with(density) { measurer.measure(FilterPresentation.typeLabel(it), chipStyle).size.width.toDp().value.toInt() } + 26 }
        val count = with(density) { measurer.measure(view.countLabel, Type.monoSmall).size.width.toDp().value.toInt() }
        24 + 56 + 17 + chips + 6 * 8 + 17 + 110 + 8 + count
    }
    Column(
        modifier.fillMaxWidth().background(p.bg1).onSizeChanged { size ->
            // Core's hysteresis (440 in, 470 out) so a drag across the boundary can't strobe — the
            // width is dp and the thresholds logical px, the same unit. One addition: a list too
            // narrow for the seven worded chips at all (the detail pane open on a 13" screen) also
            // goes to glyphs, rather than clipping chips off the end of the row.
            val dp = with(density) { size.width.toDp().value.toInt() }
            if (dp > 0) narrow = FilterPresentation.isNarrow(dp, narrow) || dp < wideNeed
        },
    ) {
        // ---- permanent row ------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Everything but the counter lives in one strip; when the panel is tight the search box
            // is what gives (168dp down to 56dp), as the plugin's BoxLayout shrinks it to its
            // minimum before any chip moves. Below 440dp the chips become glyphs (narrow layout).
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    state.urlQuery, { onChange(state.copy(urlQuery = it)) },
                    if (narrow) Modifier.weight(1f).widthIn(min = 56.dp) else Modifier.weight(1f, fill = false).widthIn(min = 56.dp, max = 168.dp),
                )
                Spacer(Modifier.width(8.dp)); VDivider(); Spacer(Modifier.width(8.dp))
                EventType.entries.forEachIndexed { i, type ->
                    if (i > 0) Spacer(Modifier.width(if (narrow) 4.dp else 8.dp))
                    val kind = kindOf(type)
                    ToggleChip(
                        FilterPresentation.typeLabel(type), p.kind(kind), type in state.types,
                        onClick = { onChange(FilterEdits.toggleType(state, type)) },
                        glyphKind = if (narrow) kind else null,
                        tip = if (type == EventType.DB) DB_TIP else null,
                    )
                }
                Spacer(Modifier.width(8.dp)); VDivider(); Spacer(Modifier.width(8.dp))
                Box {
                    FiltersButton(FilterPresentation.badgeCount(state, correlationActive = correlation != null), narrow) { popover = !popover }
                    if (popover) {
                        Popup(
                            offset = IntOffset(0, with(density) { 28.dp.roundToPx() }),
                            onDismissRequest = { popover = false },
                            properties = PopupProperties(focusable = false),
                        ) { FiltersPopover(state, onChange) }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            T(view.countLabel, p.textMuted, style = Type.monoSmall.copy(fontSize = if (narrow) 10.sp else 11.sp))
        }
        // ---- echo row -----------------------------------------------------------------------
        val chips = FilterPresentation.echoChips(state, correlationLabel = correlation?.shortLabel, narrow = narrow)
        val explainer = FilterPresentation.explainer(state, view.hiddenDbMatch, narrow)
        if (chips.isNotEmpty() || explainer != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
            Row(
                Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                chips.forEach { chip ->
                    RemovableChip(chip.label, chip.tooltip) {
                        if (chip.id == FilterPresentation.FilterId.CORRELATION) onClearCorrelation() else onChange(FilterEdits.loosen(state, chip.id))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // The explainer is the one part allowed to shrink: a long one ellipsizes rather than
                // pushing `Clear all` off the edge.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (explainer != null) {
                        T(explainer.text, p.textMuted, Modifier.weight(1f, fill = false), style = Type.small)
                        Spacer(Modifier.width(4.dp))
                        LinkText(explainer.link) { onChange(FilterEdits.switchMode(state, explainer.action)) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                LinkText("Clear all") { onChange(FilterEdits.clearHidden(state)); onClearCorrelation() }
            }
        }
    }
}

private const val DB_TIP = "Database queries — hidden until you ask for them, since a busy screen can run " +
    "hundreds a minute. They are still captured and readable by a coding agent."

fun kindOf(type: EventType): String = when (type) {
    EventType.NET -> Envelope.KIND_HTTP
    EventType.FCM -> Envelope.KIND_FCM
    EventType.DB -> Envelope.KIND_DB
    EventType.WORK -> Envelope.KIND_WORKER
    EventType.CONF -> Envelope.KIND_CONFIG
    EventType.ANALYTICS -> Envelope.KIND_ANALYTICS
    EventType.APP -> Envelope.KIND_EVENT
}

/** bg2 fill, 1px borderStrong, radius 8, a drawn magnifier and a `URL or path…` placeholder. */
@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(26.dp).background(p.bg2, shape).border(1.dp, p.borderStrong, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val d = size.minDimension * 0.66f
            val stroke = 1.2.dp.toPx()
            drawCircle(p.textMuted, radius = d / 2, center = Offset(d / 2, d / 2), style = Stroke(stroke))
            drawLine(p.textMuted, Offset(d * 0.85f, d * 0.85f), Offset(size.width, size.height), strokeWidth = stroke)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) T("URL or path…", p.textMuted, style = Type.body.copy(fontSize = 12.sp))
            BasicTextField(
                value, { onValue(it.replace("\n", "")) }, singleLine = true,
                textStyle = Type.body.copy(fontSize = 12.sp, color = p.text),
                cursorBrush = SolidColor(p.text), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A ghost pill with an accent count badge; `⋯` in the narrow layout, where the badge stays. */
@Composable
private fun FiltersButton(badge: Int, compact: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Hint("Method, status, noise and duplicate filters") {
        Row(
            Modifier.height(22.dp).background(if (hovered) p.rowHover else p.bg2, shape).border(1.dp, p.borderStrong, shape)
                .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(start = 13.dp, end = if (badge > 0) 6.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(if (compact) "⋯" else "Filters", p.text, style = Type.small.copy(fontSize = 11.5.sp), weight = FontWeight.Bold)
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(15.dp).widthIn(min = 18.dp).background(p.accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { T(badge.toString(), p.onAccent, style = Type.monoTiny.copy(fontWeight = FontWeight.Bold)) }
            }
        }
    }
}

/**
 * Everything that no longer earns permanent space, applied immediately — no OK button: every
 * control *is* the filter, so the list behind updates as you click and the badge and echo row follow.
 */
@Composable
private fun FiltersPopover(state: FilterState, onChange: (FilterState) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.background(p.bg1, shape).border(1.dp, p.borderStrong, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        T("Method  (HTTP only — hides other kinds)", p.textMuted, style = Type.small)
        Spacer(Modifier.height(4.dp))
        MethodSegmented(listOf("GET", "POST", "PUT", "DELETE"), state.methods) { onChange(FilterEdits.toggleMethod(state, it)) }
        Spacer(Modifier.height(12.dp))
        T("Status", p.textMuted, style = Type.small)
        Spacer(Modifier.height(4.dp))
        Row {
            // Method and status are *selections*, so they light in accent, never in a status hue.
            listOf(2, 3, 4, 5).forEachIndexed { i, cls ->
                if (i > 0) Spacer(Modifier.width(6.dp))
                ToggleChip("${cls}xx", p.accent, cls in state.statusClasses, { onChange(FilterEdits.toggleStatus(state, cls)) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleSwitch(state.hideNoise) { onChange(state.copy(hideNoise = !state.hideNoise)) }
            Spacer(Modifier.width(8.dp))
            T("Hide noise", p.text, style = Type.body.copy(fontSize = 12.sp), weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToggleSwitch(state.duplicatesOnly) { onChange(state.copy(duplicatesOnly = !state.duplicatesOnly)) }
            Spacer(Modifier.width(8.dp))
            T("Duplicates only", p.text, style = Type.body.copy(fontSize = 12.sp), weight = FontWeight.Bold)
        }
    }
}

/** Multi-select segmented track (bg3, radius 8); each selected segment carries a bg0 thumb. */
@Composable
private fun MethodSegmented(items: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.background(p.bg3, RoundedCornerShape(8.dp)).padding(2.dp)) {
        items.forEach { m ->
            val on = m in selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier.height(22.dp).background(if (on) p.bg0 else Color.Transparent, RoundedCornerShape(6.dp))
                    .hoverable(interaction).clickable(interactionSource = interaction, indication = null) { onToggle(m) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { T(m, if (on || hovered) p.text else p.textDim, style = Type.small, weight = if (on) FontWeight.Bold else null) }
        }
    }
}

/**
 * The list area when the capture is full and the filter emptied it: the count, the mechanism that
 * is narrowing (core's sentence), `Clear filters`, and the one loosener core *measured* would bring
 * the most rows back.
 */
@Composable
fun FilteredToNothing(empty: FilterPresentation.EmptyState, onClearAll: () -> Unit, onLoosen: (FilterPresentation.FilterId) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.bg0).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(empty.total.toString(), p.text, style = Type.monoSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                T(" events captured · ", p.text, size = 13.sp)
                T("0", p.text, style = Type.monoSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                T(" match the current filter", p.text, size = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            T(
                empty.explanation, p.textDim, Modifier.width(268.dp),
                style = Type.body.copy(fontSize = 11.5.sp, textAlign = TextAlign.Center, lineHeight = 16.sp), maxLines = 6,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Clear filters", filled = true, onClick = onClearAll)
                empty.loosener?.let { l -> PillButton(l.label, filled = false) { onLoosen(l.id) } }
            }
        }
    }
}
