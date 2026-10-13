package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.timeline.RowGeometry
import io.github.siddharthjaswal.logpose.desktop.timeline.RowModel
import io.github.siddharthjaswal.logpose.desktop.timeline.RowModels
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.presentation.RowContent

/**
 * The live timeline: a virtualized list, newest at the bottom, following the tail until the tester
 * scrolls up — and following again once they scroll back down.
 */
@Composable
fun Timeline(
    snapshot: TimelineSnapshot,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val p = LocalPalette.current
    var follow by remember { mutableStateOf(true) }
    // Only a scroll the tester made can change `follow`; a programmatic jump to the tail ends at
    // the bottom, so it re-arms rather than disarms it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    val events = snapshot.events
    LaunchedEffect(snapshot.version) {
        if (follow && events.isNotEmpty()) listState.scrollToItem(events.size - 1)
    }
    // Row models are cheap but not free (KindPresenter builds a GenericEvent); compute them once
    // per snapshot, not once per recomposition of a visible row.
    val rows = remember(snapshot.version) { events.map(RowModels::of) }
    Box(modifier.background(p.bg0)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(rows, key = { _, row -> row.event.id }) { _, row ->
                TimelineRow(row, selected = row.event.id == selectedId, onClick = { onSelect(row.event.id) })
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
fun TimelineRow(row: RowModel, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = when {
        selected -> p.accentTint
        hovered -> p.rowHover
        else -> p.bg0
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowGeometry.HEIGHT.dp)
            .background(bg)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = RowGeometry.EDGE.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RowGeometry.GLYPH.dp), contentAlignment = Alignment.CenterStart) {
            KindGlyph(row.event.kind)
        }
        Spacer(Modifier.width(RowGeometry.GAP.dp))
        when (row) {
            is RowModel.Http -> HttpCells(row)
            is RowModel.Fcm -> FcmCells(row)
            is RowModel.Db -> DbCells(row)
            is RowModel.Worker -> WorkerCells(row)
            is RowModel.Generic -> GenericCells(row)
        }
    }
}

@Composable
private fun RowScope.HttpCells(row: RowModel.Http) {
    val p = LocalPalette.current
    // Method carries no hue: read vs write is weight.
    T(
        row.method, if (row.read) p.methodRead else p.methodWrite,
        Modifier.width(RowGeometry.METHOD.dp), style = Type.small,
        weight = if (row.read) FontWeight.Normal else FontWeight.Bold,
    )
    Box(Modifier.width(RowGeometry.STATUS.dp), contentAlignment = Alignment.CenterStart) {
        if (row.pending) {
            // No plate while in flight: the accent dots are the pill.
            T("···", p.accent, style = Type.pill)
        } else {
            Pill(row.status, p.statusColor(row.statusCode, row.error), fill = p.statusFill(row.statusCode, row.error))
        }
    }
    Spacer(Modifier.width(RowGeometry.TEXT_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(row.path, p.text, Modifier.weight(1f, fill = false))
        if (row.mocked) {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill("MOCK", p.onAccent, fill = p.intervention)
        }
    }
    Meta(row.size, RowGeometry.SIZE, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(row.duration, RowGeometry.TIME, if (row.pending) p.accent else p.textMuted)
}

@Composable
private fun RowScope.FcmCells(row: RowModel.Fcm) {
    val p = LocalPalette.current
    // TOKEN / NOTIF / DATA is a label, not a severity — neutral.
    Pill(row.label, p.textDim, fill = p.bg2, style = Type.monoTiny)
    Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(row.summary, p.text, Modifier.weight(1f, fill = false))
        if (row.injected) {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill("INJ", p.onAccent, fill = p.intervention)
        }
    }
    Meta(row.keys, RowGeometry.SIZE, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(row.time, RowGeometry.TIME, p.textMuted)
}

@Composable
private fun RowScope.DbCells(row: RowModel.Db) {
    val p = LocalPalette.current
    val c = row.content
    val read = c.weight == RowContent.Weight.REGULAR
    Pill(
        c.verb, if (read) p.textDim else p.text, fill = p.bg2,
        style = Type.monoTiny.copy(fontWeight = if (read) FontWeight.Normal else FontWeight.Bold),
    )
    Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (c.table != null) {
            T(c.table!!, p.text)
            Spacer(Modifier.width(8.dp))
            T(c.sql, p.textMuted, style = Type.monoSmall)
        } else {
            T(c.sql, p.text, style = Type.monoSmall)
        }
    }
    Meta(c.fact, RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(row.time, RowGeometry.TIME, p.textMuted)
}

@Composable
private fun RowScope.WorkerCells(row: RowModel.Worker) {
    val p = LocalPalette.current
    val c = row.content
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        // The worker's name is what the row is about; its tag ellipsizes first (weights 3 : 1).
        T(c.title, p.text, Modifier.weight(3f, fill = false))
        T("  ·  ", p.textMuted)
        val state = c.state
        T(
            state.text,
            tokenColor(state.token),
            weight = if (state.weight == RowContent.Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
            style = Type.body.copy(
                textDecoration = if (state.decoration == RowContent.Decoration.STRIKETHROUGH) TextDecoration.LineThrough else null,
            ),
        )
        c.retry?.let {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            Pill(it, p.text, stroke = p.warn)
        }
        c.tag?.let {
            Spacer(Modifier.width(RowGeometry.PILL_GAP.dp))
            T(it, p.textMuted, Modifier.weight(1f, fill = false), style = Type.monoSmall)
        }
    }
    Meta(c.fact, RowGeometry.FACT, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(row.time, RowGeometry.TIME, if (c.time is RowContent.TimeCell.LiveCountUp) p.accent else p.textMuted)
}

@Composable
private fun RowScope.GenericCells(row: RowModel.Generic) {
    val p = LocalPalette.current
    val c = row.content
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        T(c.title, p.text, Modifier.weight(1f, fill = false))
        c.subtitle?.let {
            T("  ·  ", p.textMuted)
            T(it, p.textDim, Modifier.weight(1f, fill = false))
        }
    }
    val factWidth = if (row.event.kind == Envelope.KIND_ANALYTICS) RowGeometry.FACT_ANALYTICS else RowGeometry.FACT
    Meta(c.fact, factWidth, p.textMuted)
    Spacer(Modifier.width(RowGeometry.META_GAP.dp))
    Meta(row.time, RowGeometry.TIME, p.textMuted)
}

/** A right-aligned meta cell of fixed width, so every row's size/time columns line up. */
@Composable
private fun Meta(text: String, width: Int, color: androidx.compose.ui.graphics.Color) {
    T(
        text, color, Modifier.width(width.dp),
        style = Type.monoSmall.copy(textAlign = TextAlign.End),
    )
}

@Composable
fun tokenColor(token: RowContent.Token): androidx.compose.ui.graphics.Color {
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
