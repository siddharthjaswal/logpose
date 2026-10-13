package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import io.github.siddharthjaswal.logpose.desktop.timeline.MenuItem
import io.github.siddharthjaswal.logpose.desktop.timeline.RowCells
import io.github.siddharthjaswal.logpose.desktop.timeline.RowCommand
import io.github.siddharthjaswal.logpose.desktop.timeline.Rows
import io.github.siddharthjaswal.logpose.desktop.timeline.Selection
import io.github.siddharthjaswal.logpose.desktop.timeline.SessionMarks
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineItem
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineItems
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineView
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import io.github.siddharthjaswal.logpose.presentation.RowContent
import io.github.siddharthjaswal.logpose.presentation.RowGeometry
import io.github.siddharthjaswal.logpose.presentation.WaterfallPresentation
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.cos

/** What a row can ask the screen to do. */
class TimelineActions(
    val onSelect: (String) -> Unit = {},
    val onToggleSelect: (RowCollapse.Row) -> Unit = {},
    val onSelectRange: (RowCollapse.Row) -> Unit = {},
    val onToggleExpand: (String) -> Unit = {},
    val onCommand: (RowCommand) -> Unit = {},
    /** The context menu for a row, built when it opens (it reads the current selection). */
    val menuFor: (RowCells) -> List<MenuItem> = { emptyList() },
)

/** The live tick's period: the plugin's 250ms timer, and only while a live row is on screen. */
private const val LIVE_TICK_MS = 250L

/**
 * The live timeline: a virtualized list of folded rows, newest at the bottom, following the tail
 * until the tester scrolls up — and following again once they scroll back down.
 */
@Composable
fun Timeline(
    view: TimelineView,
    selectedId: String?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    marks: SessionMarks = SessionMarks.EMPTY,
    selectedIds: Set<String> = emptySet(),
    elapsedOf: (String) -> Long? = { null },
    actions: TimelineActions = TimelineActions(),
) {
    val p = LocalPalette.current
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    val items = remember(view, marks) { TimelineItems.merge(view.rows, marks) }
    LaunchedEffect(view.version, marks) {
        if (follow && items.isNotEmpty()) listState.scrollToItem(items.size - 1)
    }
    // One shared clock for every live cell, ticking only while a pending call or a running worker
    // is actually visible — no per-row timers, and an idle timeline costs nothing.
    val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val liveVisible by remember(items) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { (items.getOrNull(it.index) as? TimelineItem.Event)?.cells?.live == true }
        }
    }
    LaunchedEffect(liveVisible) {
        while (liveVisible) {
            delay(LIVE_TICK_MS)
            tick.longValue = System.currentTimeMillis()
        }
    }
    Box(modifier.background(p.bg0)) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 2.dp), state = listState) {
            itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                when (item) {
                    is TimelineItem.Event -> {
                        val row = item.cells.row
                        TimelineRow(
                            item.cells,
                            selected = Selection.isSelected(row, selectedId, selectedIds),
                            elapsedOf = elapsedOf, tick = tick, actions = actions,
                        )
                    }
                    is TimelineItem.Mark -> BookmarkRow(item)
                    is TimelineItem.Shot -> ScreenshotRow(item.shot)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private val hintClock = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineRow(
    cells: RowCells,
    selected: Boolean,
    elapsedOf: (String) -> Long? = { null },
    tick: State<Long> = remember { mutableLongStateOf(0) },
    actions: TimelineActions = TimelineActions(),
) {
    val p = LocalPalette.current
    val row = cells.row
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var lastClick by remember { mutableLongStateOf(0L) }
    val group = row as? RowCollapse.Row.Group
    val hint = when {
        group != null -> hintText(RowContent.groupHint(group) { hintClock.get().format(Date(it)) })
        cells is RowCells.Http && cells.duplicate != null -> hintText(DetailContent.duplicateHint(cells.event.tx, cells.duplicate))
        else -> null
    }
    ContextMenuArea(items = {
        if (!selected) actions.onSelect(row.anchorId)
        actions.menuFor(cells).map { item -> ContextMenuItem(item.label) { actions.onCommand(item.command) } }
    }) {
        Hint(hint) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((if (cells.short) RowGeometry.FOLD_HEIGHT else RowGeometry.HEIGHT).dp)
                    .hoverable(interaction)
                    .onClick(keyboardModifiers = { isMetaPressed }) { actions.onToggleSelect(row) }
                    .onClick(keyboardModifiers = { isShiftPressed && !isMetaPressed }) { actions.onSelectRange(row) }
                    .onClick(keyboardModifiers = { !isMetaPressed && !isShiftPressed }) {
                        // §6 says "click expands", but a single click is selection — so the gesture
                        // is a double-click (and a context-menu item, which the hint names).
                        val now = System.currentTimeMillis()
                        if (group != null && now - lastClick < DOUBLE_CLICK_MS) actions.onToggleExpand(group.key)
                        lastClick = now
                        actions.onSelect(row.anchorId)
                    },
            ) {
                RowPlate(selected, hovered)
                Row(
                    Modifier.fillMaxSize().padding(horizontal = RowGeometry.EDGE.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(RowGeometry.GLYPH.dp), contentAlignment = Alignment.CenterStart) {
                        KindGlyph(row.lead.kind, tint = if (cells is RowCells.Txn && cells.fold.faded) p.fade(p.kind(row.lead.kind), if (hovered) 0.7f else 0.34f) else null)
                    }
                    Spacer(Modifier.width(RowGeometry.GAP.dp))
                    val armed = selected || hovered
                    when (cells) {
                        is RowCells.Http -> HttpCells(cells, hovered, armed, elapsedOf, tick, actions)
                        is RowCells.Fcm -> FcmCells(cells)
                        is RowCells.Db -> DbCells(cells)
                        is RowCells.Txn -> TxnCells(cells, hovered)
                        is RowCells.Worker -> WorkerCells(cells, elapsedOf, tick)
                        is RowCells.Generic -> GenericCells(cells)
                    }
                }
            }
        }
    }
}

private const val DOUBLE_CLICK_MS = 400L

/**
 * Selection is a 15% accent plate inset 6dp with a 2dp accent rail (4dp in from the plate's ends,
 * so two adjacent selected rows read as two); hover is a rowHover plate. The plugin's `RowPanel`.
 */
@Composable
private fun RowPlate(selected: Boolean, hovered: Boolean) {
    val p = LocalPalette.current
    if (!selected && !hovered) return
    Box(
        Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 1.dp)
            .background(if (selected) p.accentTint else p.rowHover, RoundedCornerShape(4.dp)),
    ) {
        if (selected) Box(Modifier.padding(vertical = 4.dp).width(2.dp).fillMaxHeight().background(p.accent, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun RowScope.HttpCells(
    c: RowCells.Http,
    hovered: Boolean,
    armed: Boolean,
    elapsedOf: (String) -> Long?,
    tick: State<Long>,
    actions: TimelineActions,
) {
    val p = LocalPalette.current
    val h = c.content
    // A muted endpoint is drawn faded (34% — 70% hovered), the same fade a folded transaction gets.
    fun shade(color: Color) = if (!c.muted) color else p.fade(color, if (hovered) 0.7f else 0.34f)
    val read = h.methodWeight == RowContent.Weight.REGULAR
    T(
        h.method, shade(if (read) p.methodRead else p.methodWrite), Modifier.width(RowGeometry.METHOD.dp),
        style = Type.small, weight = if (read) FontWeight.Normal else FontWeight.Bold,
    )
    Spacer(Modifier.width(RowGeometry.GAP.dp))
    Box(Modifier.width(RowGeometry.STATUS.dp), contentAlignment = Alignment.CenterStart) {
        if (h.pending) {
            // No plate while in flight: the accent glyph is the pill.
            T(spinner(tick.value), p.accent, style = Type.pill)
        } else {
            val fg = p.statusColor(h.statusCode, h.error)
            Pill(h.status, shade(fg), fill = if (c.muted) p.tint(fg, 14) else p.statusFill(h.statusCode, h.error), style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
    }
    Spacer(Modifier.width(RowGeometry.TEXT_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(h.path, shade(p.text), Modifier.weight(1f, fill = false))
        // Pill order is the plugin's: ×N (structure, outlined neutral) sits closest to the path,
        // then MOCK (intervention, solid accent), then DUP (advice, outlined in severity).
        c.poll?.let {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill(it.count, shade(p.textDim), stroke = shade(p.borderStrong), style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
        if (h.mocked) {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill("MOCK", shade(p.onAccent), fill = shade(p.intervention), style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
        c.duplicate?.let { dup ->
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill("DUP ×${dup.ordinal}", shade(p.text), stroke = shade(severityColor(p, dup.severity)), style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
    }
    when {
        c.poll != null -> {
            Meta(c.poll.size, RowGeometry.SIZE, shade(p.textMuted))
            Spacer(Modifier.width(RowGeometry.META_GAP.dp))
            Meta(c.poll.median, RowGeometry.TIME, shade(p.textMuted))
        }
        h.pending -> {
            Meta("", RowGeometry.SIZE, p.textMuted)
            Spacer(Modifier.width(RowGeometry.META_GAP.dp))
            LiveMeta(c, elapsedOf, tick)
        }
        else -> {
            // The ⧉ cURL button paints on the hovered and the selected row, over the size cell —
            // never on a pending, muted or collapsed row (one cURL for thirty calls names none).
            if (armed && !c.muted) {
                ActionCell("⧉ cURL", RowGeometry.SIZE) { actions.onCommand(RowCommand.Copy(CurlBuilder.build(c.event.tx), "cURL copied")) }
            } else {
                Meta(h.size, RowGeometry.SIZE, shade(p.textMuted))
            }
            Spacer(Modifier.width(RowGeometry.META_GAP.dp))
            Meta(h.duration, RowGeometry.TIME, shade(p.textMuted))
        }
    }
}

fun severityColor(p: Palette, s: DuplicateDetector.Severity): Color = when (s) {
    DuplicateDetector.Severity.STRONG -> p.danger
    DuplicateDetector.Severity.MEDIUM -> p.warn
    DuplicateDetector.Severity.INFO -> p.textDim
}

private const val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

/** The plugin's braille spinner, stepped by the shared tick. */
fun spinner(tick: Long): String = SPINNER[((tick / LIVE_TICK_MS) % SPINNER.length).toInt()].toString()

@Composable
private fun RowScope.FcmCells(c: RowCells.Fcm) {
    val p = LocalPalette.current
    // TOKEN / NOTIF / DATA is a label, not a severity — neutral.
    Pill(c.content.label, p.textDim, fill = p.bg2, style = Type.pill.copy(fontWeight = FontWeight.Bold))
    Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(c.content.summary, p.text, Modifier.weight(1f, fill = false))
        if (c.content.injected) {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill("INJ", p.onAccent, fill = p.intervention, style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
    }
    Meta(c.content.keys, RowGeometry.SIZE, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(c.time, RowGeometry.TIME, p.textMuted)
}

@Composable
private fun RowScope.DbCells(c: RowCells.Db) {
    val p = LocalPalette.current
    val d = c.content
    val read = d.weight == RowContent.Weight.REGULAR
    Pill(d.verb, if (read) p.textDim else p.text, fill = p.bg2, style = Type.monoTiny.copy(fontWeight = if (read) FontWeight.Normal else FontWeight.Bold))
    Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        val table = d.table
        if (table != null) {
            T(table, p.text)
            Spacer(Modifier.width(8.dp))
            T(d.sql, p.textMuted, style = Type.monoSmall)
        } else {
            T(d.sql, p.text, style = Type.monoSmall)
        }
    }
    Meta(d.fact, RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(c.time, RowGeometry.TIME, p.textMuted)
}

/** `transaction ✓ · 4 statements · 128ms`, faded unless it failed (then full strength, ✕ in danger). */
@Composable
private fun RowScope.TxnCells(c: RowCells.Txn, hovered: Boolean) {
    val p = LocalPalette.current
    val f = c.fold
    fun shade(color: Color) = if (!f.faded) color else p.fade(color, if (hovered) 0.7f else 0.34f)
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(f.word, shade(p.textDim))
        when (f.mark) {
            RowContent.TxnMark.FAILED -> T(" ✕", p.danger, weight = FontWeight.Bold)
            RowContent.TxnMark.SUCCEEDED -> T(" ✓", shade(p.textDim))
            null -> Unit
        }
        T(f.tail, shade(p.textMuted), style = Type.monoSmall)
    }
    Meta("", RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta("", RowGeometry.TIME, p.textMuted)
}

@Composable
private fun RowScope.WorkerCells(c: RowCells.Worker, elapsedOf: (String) -> Long?, tick: State<Long>) {
    val p = LocalPalette.current
    val w = c.content
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        // The worker's name is what the row is about; its tag ellipsizes first.
        T(w.title, p.text, Modifier.weight(3f, fill = false))
        T("  ·  ", p.textMuted)
        StateToken(w.state, tick)
        w.retry?.let {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill(it, p.text, stroke = p.warn, style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
        w.tag?.let {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            T(it, p.textMuted, Modifier.weight(1f, fill = false), style = Type.monoTiny)
        }
    }
    Meta(w.fact, RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    if (c.live) LiveMeta(c, elapsedOf, tick) else Meta(c.time, RowGeometry.TIME, p.textMuted)
}

/**
 * `✓ succeeded`, `● running`, `cancelled`… Shape does the work colour may not: the running dot is
 * drawn (not a glyph in the string) and breathes on the plugin's 1.6s cosine, sampled on the wall
 * clock at each shared tick; strikethrough is a text decoration.
 */
@Composable
private fun StateToken(token: RowContent.StateToken, tick: State<Long>) {
    val color = tokenColor(token.token)
    val pulsing = token.decoration == RowContent.Decoration.PULSING_DOT
    if (pulsing) {
        val period = WaterfallPresentation.PULSE_PERIOD_MILLIS
        val phase = 0.5 - 0.5 * cos(2 * Math.PI * (tick.value % period) / period)
        val alpha = (0.45 + 0.55 * phase).toFloat()
        Box(Modifier.size(6.dp).background(color.copy(alpha = alpha), CircleShape))
        Spacer(Modifier.width(4.dp))
    }
    T(
        if (pulsing) token.text.removePrefix("● ") else token.text,
        color,
        weight = if (token.weight == RowContent.Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
        style = Type.monoSmall.copy(
            textDecoration = if (token.decoration == RowContent.Decoration.STRIKETHROUGH) TextDecoration.LineThrough else null,
        ),
    )
}

@Composable
private fun RowScope.GenericCells(c: RowCells.Generic) {
    val p = LocalPalette.current
    val g = c.content
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(g.title, p.text, Modifier.weight(1f, fill = false))
        g.subtitle?.let {
            T("  ·  ", p.textMuted)
            T(it, p.textDim, Modifier.weight(1f, fill = false))
        }
    }
    Meta(g.fact, if (c.analytics) RowGeometry.FACT_ANALYTICS else RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(c.time, RowGeometry.TIME, p.textMuted)
}

/** The live time cell: reads the shared tick (so only live cells recompose) and the host clock. */
@Composable
private fun LiveMeta(c: RowCells, elapsedOf: (String) -> Long?, tick: State<Long>) {
    val p = LocalPalette.current
    tick.value // read, so this cell — and only this cell — recomposes on each tick
    Meta(Rows.liveText(c, elapsedOf(c.row.lead.id)), RowGeometry.TIME, p.accent)
}

/** A right-aligned meta cell of fixed width, so every row's size/time columns line up. */
@Composable
private fun Meta(text: String, width: Int, color: Color) {
    T(text, color, Modifier.width(width.dp), style = Type.monoSmall.copy(textAlign = TextAlign.End))
}

/**
 * A meta cell drawn as the button it becomes on an armed row — same width, so the columns never
 * shift as the pointer crosses a row. Label `text`, accent on the stroke (contrast, as the plugin).
 */
@Composable
private fun ActionCell(label: String, width: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.width(width.dp).height(20.dp).background(p.bg1, shape).border(1.dp, p.accent, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, p.text, style = Type.monoSmall.copy(fontWeight = FontWeight.Bold)) }
}

@Composable
fun tokenColor(token: RowContent.Token): Color {
    val p = LocalPalette.current
    return when (token) {
        RowContent.Token.TEXT -> p.text
        RowContent.Token.TEXT_DIM -> p.textDim
        RowContent.Token.TEXT_MUTED -> p.textMuted
        RowContent.Token.ACCENT -> p.accent
        RowContent.Token.DANGER -> p.danger
        RowContent.Token.WARN -> p.warn
    }
}
