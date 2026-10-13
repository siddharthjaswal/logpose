package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallView
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.presentation.RowContent
import io.github.siddharthjaswal.logpose.presentation.WaterfallLayout
import io.github.siddharthjaswal.logpose.presentation.WaterfallPresentation
import kotlinx.coroutines.delay

/** What the waterfall card can ask the screen to do. */
class WaterfallActions(
    /** A lane click: select that event's row (which also leaves the card, as in the plugin). */
    val onSelectLane: (String) -> Unit = {},
    val onSwitch: (Grouping) -> Unit = {},
    val onEditKeys: () -> Unit = {},
    val onFindByValue: () -> Unit = {},
    val onClose: () -> Unit = {},
)

// The plugin's `TraceWaterfallPanel` metrics, unchanged.
private const val ROW_HEIGHT = 24
private const val GUTTER_WIDTH = 190
private const val DURATION_WIDTH = 64
private const val MIN_TRACK_WIDTH = 24
private const val PAD_X = 16
private const val PAD_TOP = 10
private const val PAD_BOTTOM = 6
private const val AXIS_GAP = 6
private const val AXIS_STRIP = 14
private const val BAR_HEIGHT = 10
private const val POINT_SIZE = 8
private const val GLYPH_SIZE = 13
private const val AXIS_TICKS = 4
/** ~12fps: enough samples for the 1.6s ease to read as a breath. */
private const val PULSE_INTERVAL_MS = 80L
/** The 250ms live tick that re-projects "now" so open spans grow. */
private const val GROW_TICK_MS = 250L
private const val MIN_IDENTITY_WIDTH = 180

/**
 * One flow, drawn as a waterfall — the plugin's `TraceWaterfallPanel` in Compose.
 *
 * Every number comes from core: lane geometry from [WaterfallLayout] (time→fraction, point / span /
 * open, the slowest lane, "now" projected from host ages), every word and voice from
 * [WaterfallPresentation] (the duration column, failure, axis strings, the pulse curve). This file
 * places pixels and nothing else, and it reads only the immutable [WaterfallView] snapshot it is
 * handed — never the store.
 *
 * The 1.6s breath and the 250ms growth run only while this card is composed (i.e. visible) **and**
 * the flow has an open span; a finished flow costs nothing.
 */
@Composable
fun WaterfallCard(
    view: WaterfallView,
    selectedId: String?,
    hostAge: (String) -> Long,
    modifier: Modifier = Modifier,
    actions: WaterfallActions = WaterfallActions(),
) {
    val p = LocalPalette.current
    var grow by remember { mutableLongStateOf(0L) }
    var pulseMs by remember { mutableLongStateOf(0L) }
    val layout = remember(view, grow) {
        WaterfallLayout.of(view.grouping.value, view.events, WaterfallLayout.projectedNow(view.events, hostAge))
    }
    val open = layout.hasOpenSpans
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        // Anchored when the breath starts, so a span appearing mid-cycle begins at the bottom of the curve.
        val anchor = System.nanoTime()
        var lastGrow = 0L
        while (true) {
            delay(PULSE_INTERVAL_MS)
            pulseMs = (System.nanoTime() - anchor) / 1_000_000L
            if (pulseMs - lastGrow >= GROW_TICK_MS) { lastGrow = pulseMs; grow = System.currentTimeMillis() }
        }
    }
    Column(modifier.background(p.bg0)) {
        WaterfallHeader(view, layout, actions)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderStrong))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (!layout.isEmpty) Lanes(layout, selectedId, pulseMs, (pulseMs / GROW_TICK_MS).toInt(), actions)
        }
    }
}

@Composable
private fun WaterfallHeader(view: WaterfallView, layout: WaterfallLayout.Layout, actions: WaterfallActions) {
    val p = LocalPalette.current
    val grouping = view.grouping
    val tip = when (grouping.kind) {
        Grouping.Kind.KEY -> "Grouped by the correlation key ${grouping.key} — every event carrying ${grouping.value}"
        Grouping.Kind.VALUE -> "Every event carrying ${grouping.value}"
        Grouping.Kind.TRACE -> "Grouped by the device's trace id"
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val switcher = view.alternatives.size > 1
        // Not "does everything fit" but "does the identity still have room to name the flow" — the
        // plugin's reflow rule: below it the controls take a row of their own.
        val controlsWidth = 112 + 12 + 116 + if (switcher) 12 + view.alternatives.sumOf { it.tab.length * 7 + 26 } else 0
        val stacked = maxWidth.value - controlsWidth - 16 - 24 < MIN_IDENTITY_WIDTH
        val identity = @Composable { mod: Modifier ->
            Column(mod) {
                Hint(tip) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(grouping.tab, p.text, style = Type.body.copy(fontSize = 13.sp), weight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        T(grouping.value, p.accent, style = Type.monoSmall.copy(fontSize = 13.sp), weight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subline(layout, grouping, p), color = p.textMuted, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val controls = @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkText("Find by value…", onClick = actions.onFindByValue)
                Spacer(Modifier.width(12.dp))
                LinkText("Correlation keys…", onClick = actions.onEditKeys)
                if (switcher) {
                    Spacer(Modifier.width(12.dp))
                    Switcher(view.alternatives, grouping, actions.onSwitch)
                }
            }
        }
        if (stacked) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    identity(Modifier.weight(1f))
                    GlyphButton("✕", "Close the waterfall", onClick = actions.onClose)
                }
                Spacer(Modifier.height(8.dp))
                Row { Spacer(Modifier.weight(1f)); controls() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                identity(Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                controls()
                Spacer(Modifier.width(6.dp))
                GlyphButton("✕", "Close the waterfall", onClick = actions.onClose)
            }
        }
    }
}

/** `6 events · spanning 2.40s · slowest GET /orders/42 (1.31s)` — the path in mono, as the plugin. */
private fun subline(layout: WaterfallLayout.Layout, grouping: Grouping, p: Palette) = buildAnnotatedString {
    if (layout.isEmpty) {
        append(if (!grouping.isTrace) "no events carry that value" else "no events — the trace may have scrolled out of the capture buffer")
        return@buildAnnotatedString
    }
    append(if (layout.eventCount == 1) "1 event" else "${layout.eventCount} events")
    append("  ·  spanning ${WaterfallPresentation.humanMillis(layout.wallSpanMillis)}")
    layout.slowest?.let {
        append("  ·  slowest ")
        val label = KindPresenter.rowLabel(it.event).let { l -> if (l.length <= 42) l else l.take(41) + "…" }
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(label) }
        append(" (${WaterfallPresentation.humanMillis(it.durationMillis)}${if (it.isOpen) ", still running" else ""})")
    }
}

/** The design system's Segmented control: one bg3 track, a bg0 thumb under the selected tab. */
@Composable
private fun Switcher(options: List<Grouping>, selected: Grouping, onPick: (Grouping) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.background(p.bg3, RoundedCornerShape(8.dp)).padding(2.dp)) {
        options.forEach { option ->
            val on = option == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Hint(option.shortLabel) {
                Box(
                    Modifier.height(20.dp).background(if (on) p.bg0 else Color.Transparent, RoundedCornerShape(6.dp))
                        .hoverable(interaction)
                        .then(if (on) Modifier else Modifier.clickable(interactionSource = interaction, indication = null) { onPick(option) })
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { T(option.tab, if (on || hovered) p.text else p.textDim, style = Type.small, weight = if (on) FontWeight.Bold else null) }
            }
        }
    }
}

/** Band geometry, in dp, shared by the canvas and the lane rows. */
private class Geom(width: Float) {
    val durRight = width - PAD_X
    val trackRight = durRight - DURATION_WIDTH
    // The duration column is inviolable: the gutter yields first, then the track goes to nothing.
    val trackX = (PAD_X + GUTTER_WIDTH).toFloat().coerceAtMost(trackRight - MIN_TRACK_WIDTH).coerceAtLeast(PAD_X.toFloat())
    val trackW = trackRight - trackX
}

private const val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

@Composable
private fun Lanes(
    layout: WaterfallLayout.Layout,
    selectedId: String?,
    pulseMs: Long,
    spinnerFrame: Int,
    actions: WaterfallActions,
) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    var hovered by remember { mutableIntStateOf(-1) }
    val n = layout.lanes.size
    val height = (PAD_TOP + n * ROW_HEIGHT + AXIS_GAP + AXIS_STRIP + PAD_BOTTOM).dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val g = Geom(maxWidth.value)
        val axisStyle = Type.monoTiny.copy(fontSize = 9.sp, color = p.textMuted)
        val spinStyle = Type.monoSmall.copy(fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxSize()) {
            val u = density // px per dp
            val rowH = ROW_HEIGHT * u
            val top0 = PAD_TOP * u
            // Backgrounds first, gridlines over them, marks last: a selected lane's tint must not
            // swallow the axis it is read against.
            layout.lanes.forEach { lane ->
                val top = top0 + lane.index * rowH
                when {
                    lane.id == selectedId -> {
                        drawRoundRect(p.accentTint, Offset(0f, top), Size(size.width, rowH), CornerRadius(6 * u))
                        drawRoundRect(p.accent, Offset(0f, top + 3 * u), Size(2 * u, rowH - 6 * u), CornerRadius(2 * u))
                    }
                    lane.index == hovered -> drawRoundRect(p.rowHover, Offset(0f, top), Size(size.width, rowH), CornerRadius(6 * u))
                }
            }
            val bottom = top0 + n * rowH
            for (i in 0..AXIS_TICKS) {
                val x = (g.trackX + g.trackW * WaterfallPresentation.axisFraction(i, AXIS_TICKS).toFloat()) * u
                drawLine(p.borderSubtle, Offset(x, top0), Offset(x, bottom), strokeWidth = 1f)
            }
            if (g.trackW >= 2) layout.lanes.forEach { lane ->
                val mid = top0 + lane.index * rowH + rowH / 2
                val failed = WaterfallPresentation.isFailure(lane.event)
                val color = if (failed) p.danger else p.kind(lane.kind)
                val startX = g.trackX + g.trackW * lane.startFraction.toFloat()
                val endX = g.trackX + g.trackW * lane.endFraction.toFloat()
                val dot = POINT_SIZE.toFloat().coerceAtMost(g.trackW)
                fun dotX(x: Float) = (x - dot / 2).coerceIn(g.trackX, maxOf(g.trackX, g.trackRight - dot))
                when {
                    // No device timestamp: a hollow ring at the origin, never a confident dot.
                    !lane.timed -> drawCircle(p.textMuted, radius = (dot / 2 - 0.5f) * u, center = Offset((dotX(startX) + dot / 2) * u, mid), style = Stroke(1.2f * u))
                    lane.isPoint -> drawCircle(color, radius = dot / 2 * u, center = Offset((dotX(startX) + dot / 2) * u, mid))
                    else -> {
                        val w = (endX - startX).coerceAtLeast(2f).coerceAtMost(g.trackW)
                        val barX = startX.coerceAtMost(g.trackRight - w).coerceAtLeast(g.trackX)
                        val alpha = when {
                            lane.isOpen -> WaterfallPresentation.pulseAlpha(pulseMs)
                            failed -> WaterfallPresentation.FAILED_ALPHA
                            else -> WaterfallPresentation.SPAN_ALPHA
                        }
                        val tl = Offset(barX * u, mid - BAR_HEIGHT * u / 2)
                        val sz = Size(w * u, BAR_HEIGHT * u)
                        drawRoundRect(color.copy(alpha = alpha / 255f), tl, sz, CornerRadius(4 * u / 2))
                        drawRoundRect(color, tl, sz, CornerRadius(4 * u / 2), style = Stroke(1f * u))
                        if (lane.isOpen) {
                            // The spinner rides the growing edge, held inside the track.
                            val glyph = SPINNER[spinnerFrame % SPINNER.length].toString()
                            val m = measurer.measure(glyph, spinStyle.copy(color = color))
                            if (g.trackW * u >= m.size.width) {
                                val sx = ((barX + w + 4) * u).coerceAtMost(g.trackRight * u - m.size.width).coerceAtLeast(g.trackX * u)
                                drawText(m, topLeft = Offset(sx, mid - m.size.height / 2f))
                            }
                        }
                    }
                }
            }
            // The scale belongs beneath what it measures: labels left-aligned on their gridlines.
            val strip = bottom + AXIS_GAP * u
            WaterfallPresentation.axisLabels(layout.wallSpanMillis, AXIS_TICKS).forEachIndexed { i, label ->
                val x = (g.trackX + g.trackW * WaterfallPresentation.axisFraction(i, AXIS_TICKS).toFloat() + 2) * u
                drawText(measurer.measure(label, axisStyle), topLeft = Offset(x, strip))
            }
        }
        Column(Modifier.padding(top = PAD_TOP.dp)) {
            layout.lanes.forEach { lane ->
                LaneRow(layout, lane, g, onHover = { on -> hovered = if (on) lane.index else if (hovered == lane.index) -1 else hovered }) {
                    actions.onSelectLane(lane.id)
                }
            }
        }
    }
}

/** One lane's interactive row: gutter (glyph + name), the empty track the canvas paints, the duration cell. */
@Composable
private fun LaneRow(layout: WaterfallLayout.Layout, lane: WaterfallLayout.Lane, g: Geom, onHover: (Boolean) -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover(hovered) }
    val label = WaterfallPresentation.laneLabel(lane.event)
    val failed = WaterfallPresentation.isFailure(lane.event)
    val cell = WaterfallPresentation.durationCell(lane, layout.startMillis, layout.slowest?.id)
    Hint(laneTip(layout, lane)) {
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT.dp).hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(PAD_X.dp))
            KindGlyph(lane.kind, Modifier.size(GLYPH_SIZE.dp), tint = if (failed) p.danger else null)
            Spacer(Modifier.width(8.dp))
            val textWidth: Dp = (g.trackX - PAD_X - GLYPH_SIZE - 8 - 8).coerceAtLeast(0f).dp
            Row(Modifier.width(textWidth), verticalAlignment = Alignment.CenterVertically) {
                val style = if (label.mono) Type.monoSmall.copy(fontSize = 10.5.sp) else Type.body.copy(fontSize = 11.5.sp)
                T(label.text, if (lane.timed) p.text else p.textMuted, Modifier.weight(1f, fill = false), style = style)
                label.status?.let { Spacer(Modifier.width(4.dp)); T(it, p.danger, style = style) }
            }
            Spacer(Modifier.weight(1f))
            val bold = cell.tone == WaterfallPresentation.Tone.SLOWEST
            T(
                cell.text,
                when (cell.tone) {
                    WaterfallPresentation.Tone.SLOWEST -> p.text
                    WaterfallPresentation.Tone.RUNNING -> p.accentHover
                    WaterfallPresentation.Tone.FAILED -> p.danger
                    WaterfallPresentation.Tone.MUTED -> p.textDim
                },
                Modifier.width(DURATION_WIDTH.dp),
                style = Type.monoTiny.copy(textAlign = TextAlign.End, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
            )
            Spacer(Modifier.width(PAD_X.dp))
        }
    }
}

private fun laneTip(layout: WaterfallLayout.Layout, lane: WaterfallLayout.Lane): String {
    val offset = if (!lane.timed) "no device timestamp"
    else "+${WaterfallPresentation.humanMillis(lane.startMillis - layout.startMillis)} into the trace"
    val length = when {
        !lane.timed || lane.isPoint -> ""
        lane.isOpen -> "\nstill running after ${WaterfallPresentation.humanMillis(lane.durationMillis)}"
        else -> "\ntook ${WaterfallPresentation.humanMillis(lane.durationMillis)}"
    }
    val worker = (lane.event as? LogEvent.Worker)?.let { w ->
        val queued = RowContent.workerQueueMillis(w.work)
        val ran = RowContent.workerRunMillis(w)
        if (queued != null && ran != null) "\nqueued ${WaterfallPresentation.humanMillis(queued)} · ran ${WaterfallPresentation.humanMillis(ran)}" else null
    }.orEmpty()
    return "${KindPresenter.rowLabel(lane.event)}\n${KindPresenter.kindLabel(lane.event)}  ·  $offset$length$worker\nclick to open this row"
}
