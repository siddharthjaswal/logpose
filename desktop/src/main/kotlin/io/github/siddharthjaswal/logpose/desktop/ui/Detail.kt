package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.detail.JsonTreeModel
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import io.github.siddharthjaswal.logpose.desktop.timeline.RowModels
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.presentation.RowContent
import kotlinx.serialization.json.JsonElement

/**
 * The right-hand pane: a summary header and the event's payload as a Compose JSON tree, with a
 * Raw toggle (pretty-printed, selectable, copyable). Per-kind detail cards are M4.
 */
@Composable
fun DetailPane(event: LogEvent, onClose: () -> Unit, modifier: Modifier = Modifier, initialRaw: Boolean = false) {
    val p = LocalPalette.current
    var raw by remember(event.id) { mutableStateOf(initialRaw) }
    val payload = event.envelope.payload
    Column(modifier.background(p.bg1)) {
        DetailHeader(event, onClose)
        Divider(p)
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Payload", p.textDim, style = Type.small, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Segmented(listOf("Tree", "Raw"), if (raw) 1 else 0) { raw = it == 1 }
            Spacer(Modifier.weight(1f))
            val clipboard = LocalClipboardManager.current
            Button("Copy", onClick = { clipboard.setText(AnnotatedString(JsonTreeModel.prettyPrint(payload))) })
        }
        Divider(p)
        if (raw) RawJson(payload, Modifier.fillMaxSize()) else JsonTree(payload, event.id, Modifier.fillMaxSize())
    }
}

@Composable
private fun DetailHeader(event: LogEvent, onClose: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindGlyph(event.kind)
            Spacer(Modifier.width(8.dp))
            // Kind pill: one of the four sites a kind hue may be painted.
            Pill(KindPresenter.kindLabel(event), p.kind(event.kind), stroke = p.kind(event.kind))
            Spacer(Modifier.weight(1f))
            Box(Modifier.clickable(onClick = onClose).padding(4.dp)) { T("✕", p.textMuted) }
        }
        Spacer(Modifier.height(8.dp))
        when (event) {
            is LogEvent.Http -> {
                val row = RowModels.http(event)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(row.method, if (row.read) p.methodRead else p.methodWrite, weight = if (row.read) FontWeight.Normal else FontWeight.Bold, size = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    if (row.pending) T("in flight", p.accent)
                    else Pill(row.status, p.statusColor(row.statusCode, row.error), fill = p.statusFill(row.statusCode, row.error))
                    if (row.mocked) { Spacer(Modifier.width(8.dp)); Pill("MOCK", p.onAccent, fill = p.intervention) }
                    Spacer(Modifier.weight(1f))
                    event.tx.durationMillis?.let { T("${it}ms", p.textDim, style = Type.monoSmall) }
                    if (row.size.isNotEmpty()) { Spacer(Modifier.width(10.dp)); T(row.size, p.textDim, style = Type.monoSmall) }
                }
                Spacer(Modifier.height(6.dp))
                SelectionContainer { T(event.tx.request.url, p.text, style = Type.monoSmall, maxLines = 3) }
                event.tx.error?.let { Spacer(Modifier.height(4.dp)); T(it, p.danger, style = Type.small, maxLines = 3) }
            }
            is LogEvent.Fcm -> {
                val row = RowModels.fcm(event)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(row.summary, p.text, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, size = 14.sp)
                    if (row.injected) { Spacer(Modifier.width(8.dp)); Pill("INJ", p.onAccent, fill = p.intervention) }
                }
                Spacer(Modifier.height(4.dp))
                T(listOf(row.label, row.keys, row.time).filter { it.isNotEmpty() }.joinToString("  ·  "), p.textDim, style = Type.small)
            }
            else -> {
                val presentation = KindPresenter.present(event)
                val title = presentation?.title ?: event.kind
                T(title, p.text, weight = FontWeight.SemiBold, size = 14.sp, maxLines = 2)
                val subtitle = presentation?.subtitle?.takeIf { it.isNotBlank() }
                val timing = event.durationMillis?.let(RowContent::shortDuration)
                val line = listOfNotNull(subtitle, timing).joinToString("  ·  ")
                if (line.isNotEmpty()) { Spacer(Modifier.height(4.dp)); T(line, p.textDim, style = Type.small, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun Divider(p: Palette) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.height(24.dp).background(p.bg3, shape).border(1.dp, p.borderSubtle, shape).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.fillMaxHeight()
                    .background(if (on) p.bg1 else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, if (on) p.text else p.textDim, style = Type.small, weight = if (on) FontWeight.SemiBold else null) }
        }
    }
}

/** Collapsible, syntax-coloured tree; virtualized, so a 2 MB body costs only its visible lines. */
@Composable
fun JsonTree(root: JsonElement, key: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var expanded by remember(key) { mutableStateOf(JsonTreeModel.defaultExpanded(root)) }
    val lines = remember(root, expanded) { JsonTreeModel.flatten(root, expanded) }
    val state = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp), state = state) {
            items(lines, key = { it.path }) { line ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (line.expandable) Modifier.clickable {
                            expanded = if (line.path in expanded) expanded - line.path else expanded + line.path
                        } else Modifier)
                        .padding(start = (10 + line.depth * 14).dp, end = 10.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(14.dp)) {
                        if (line.expandable) T(if (line.expanded) "▾" else "▸", p.jsonPunct, style = Type.monoSmall)
                    }
                    Text(jsonLineText(line, p), style = Type.monoSmall, maxLines = 1, softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun jsonLineText(line: JsonTreeModel.Line, p: Palette): AnnotatedString = buildAnnotatedString {
    line.key?.let { k ->
        if (k.startsWith("[")) withStyle(SpanStyle(color = p.jsonCount)) { append(k) }
        else withStyle(SpanStyle(color = p.jsonKey)) { append("\"$k\"") }
        withStyle(SpanStyle(color = p.jsonPunct)) { append(": ") }
    }
    when (line.kind) {
        JsonTreeModel.Kind.OBJECT, JsonTreeModel.Kind.ARRAY -> {
            val (open, close) = if (line.kind == JsonTreeModel.Kind.OBJECT) "{" to "}" else "[" to "]"
            if (line.expanded) {
                withStyle(SpanStyle(color = p.jsonPunct)) { append(open) }
                withStyle(SpanStyle(color = p.jsonCount)) { append("  ${line.count}") }
            } else {
                withStyle(SpanStyle(color = p.jsonCount)) { append("$open${line.count}$close") }
            }
            if (line.embedded) withStyle(SpanStyle(color = p.textMuted, fontSize = 10.sp)) { append("   decoded from string") }
        }
        JsonTreeModel.Kind.STRING -> withStyle(SpanStyle(color = p.jsonString)) { append(line.text.take(MAX_SCALAR)) }
        JsonTreeModel.Kind.NUMBER -> withStyle(SpanStyle(color = p.jsonNumber)) { append(line.text) }
        JsonTreeModel.Kind.BOOL -> withStyle(SpanStyle(color = p.jsonBool)) { append(line.text) }
        JsonTreeModel.Kind.NULL -> withStyle(SpanStyle(color = p.jsonNull)) { append(line.text) }
    }
}

private const val MAX_SCALAR = 2_000

/** Pretty-printed payload, selectable, one lazy item per line so huge bodies stay smooth. */
@Composable
fun RawJson(root: JsonElement, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val lines = remember(root) { JsonTreeModel.prettyPrint(root).lines() }
    val state = rememberLazyListState()
    Box(modifier) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = state) {
                // Long lines wrap rather than scroll sideways: a body is read, not laid out.
                items(lines) { T(it, p.text, style = Type.monoSmall, maxLines = Int.MAX_VALUE) }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
