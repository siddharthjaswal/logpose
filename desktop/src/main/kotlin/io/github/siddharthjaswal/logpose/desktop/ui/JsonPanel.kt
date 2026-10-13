package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.detail.JsonFind
import io.github.siddharthjaswal.logpose.desktop.detail.JsonTreeModel
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import kotlinx.serialization.json.JsonElement

/** A Headers ▾/▸ chip in a pane header: request headers default on, response headers off. */
class HeadersToggle(val on: Boolean, val onToggle: (Boolean) -> Unit)

/**
 * One JSON pane — the plugin's `JsonTreePanel`: a header (title, status caption, optional Headers
 * toggle, Tree/Raw, find, copy), then a collapsible syntax-coloured tree (closing braces under
 * expanded nodes) or the pretty-printed raw text. ⌘F (with focus anywhere in the pane) opens find:
 * every match highlighted, `n/total`, Enter/Shift+Enter or the arrows to step, Esc to close.
 * Opening find switches to Raw, as the plugin does — the matches are positions in that text.
 *
 * Pass [element] null with a [message] for a pane that has nothing to show yet ("Waiting for
 * response…").
 */
@Composable
fun JsonPanel(
    title: String,
    element: JsonElement?,
    key: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    headers: HeadersToggle? = null,
    message: String? = null,
    initialFind: String? = null,
    onCopy: (String, String) -> Unit = { _, _ -> },
) {
    val p = LocalPalette.current
    var raw by remember(key) { mutableStateOf(initialFind != null) }
    var findOpen by remember(key) { mutableStateOf(initialFind != null) }
    var query by remember(key) { mutableStateOf(initialFind.orEmpty()) }
    var current by remember(key) { mutableIntStateOf(0) }
    val paneFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val rawLines = remember(element) { element?.let { JsonTreeModel.prettyPrint(it).lines() } ?: emptyList() }
    val matches = remember(rawLines, query) { JsonFind.matches(rawLines, query) }
    LaunchedEffect(matches) { current = if (matches.isEmpty()) -1 else 0 }

    fun openFind() { raw = true; findOpen = true }
    fun closeFind() { findOpen = false; query = ""; runCatching { paneFocus.requestFocus() } }
    fun step(dir: Int) { current = JsonFind.step(current, matches.size, dir) }

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .background(p.bg1, shape).border(1.dp, p.borderSubtle, shape)
            .focusRequester(paneFocus).focusable()
            .pointerInput(Unit) {
                // Any press inside the pane gives it focus (without consuming the press), so ⌘F
                // means "find in *this* pane", as WHEN_ANCESTOR_OF_FOCUSED_COMPONENT does in Swing.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && !findOpen) runCatching { paneFocus.requestFocus() }
                    }
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.isMetaPressed && e.key == Key.F) { openFind(); true } else false
            },
    ) {
        // ---- header ---------------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(p.bg2, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(title, p.text, style = Type.body.copy(fontSize = 13.sp), weight = FontWeight.Bold)
            status?.let { Spacer(Modifier.width(8.dp)); T(it, p.textDim, Modifier.weight(1f, fill = false), style = Type.monoSmall) }
            Spacer(Modifier.weight(1f))
            headers?.let { h ->
                T(
                    if (h.on) "Headers ▾" else "Headers ▸", if (h.on) p.text else p.textDim,
                    Modifier.clickable { h.onToggle(!h.on) }.padding(horizontal = 6.dp), style = Type.small, weight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
            }
            Segmented(listOf("Tree", "Raw"), if (raw) 1 else 0) { raw = it == 1; if (!raw) findOpen = false }
            Spacer(Modifier.width(4.dp))
            GlyphButton("⌕", "Find (⌘F)", enabled = element != null) { openFind() }
            GlyphButton("⧉", "Copy JSON", enabled = element != null) {
                element?.let { onCopy(JsonTreeModel.prettyPrint(it), "$title JSON copied") }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderStrong))
        // ---- find bar -------------------------------------------------------------------------
        if (findOpen) {
            Row(
                Modifier.fillMaxWidth().height(34.dp).background(p.bg2).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(220.dp).height(24.dp).background(p.bg0, RoundedCornerShape(6.dp))
                        .border(1.dp, p.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) T("Find in $title…", p.textMuted, style = Type.small)
                    BasicTextField(
                        query, { query = it.replace("\n", "") }, singleLine = true,
                        textStyle = Type.small.copy(color = p.text), cursorBrush = SolidColor(p.text),
                        modifier = Modifier.fillMaxWidth().focusRequester(findFocus).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { step(if (e.isShiftPressed) -1 else 1); true }
                                Key.DirectionDown -> { step(1); true }
                                Key.DirectionUp -> { step(-1); true }
                                Key.Escape -> { closeFind(); true }
                                else -> false
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                T(JsonFind.counter(query, matches.size, current), p.textDim, style = Type.monoSmall)
                Spacer(Modifier.weight(1f))
                GlyphButton("‹", "Previous (Shift+Enter)", enabled = matches.isNotEmpty()) { step(-1) }
                GlyphButton("›", "Next (Enter)", enabled = matches.isNotEmpty()) { step(1) }
                GlyphButton("✕", "Close (Esc)") { closeFind() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderStrong))
            LaunchedEffect(Unit) { runCatching { findFocus.requestFocus() } }
        }
        // ---- body -----------------------------------------------------------------------------
        when {
            element == null -> Box(Modifier.fillMaxSize().padding(14.dp)) { T(message ?: "", p.textDim, style = Type.monoSmall, maxLines = 4) }
            raw -> RawJson(rawLines, matches, current, Modifier.fillMaxSize())
            else -> JsonTree(element, key, Modifier.fillMaxSize())
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.height(22.dp).background(p.bg3, shape).border(1.dp, p.borderSubtle, shape).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.fillMaxHeight()
                    .background(if (on) p.bg1 else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, if (on) p.text else p.textDim, style = Type.small, weight = if (on) FontWeight.SemiBold else null) }
        }
    }
}

/** Collapsible, syntax-coloured tree; virtualized, so a 2 MB body costs only its visible lines. */
@Composable
fun JsonTree(root: JsonElement, key: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var expanded by remember(key, root) { mutableStateOf(JsonTreeModel.defaultExpanded(root)) }
    val lines = remember(root, expanded) { JsonTreeModel.flatten(root, expanded) }
    val state = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp), state = state) {
            itemsIndexed(lines, key = { _, it -> it.path }) { _, line ->
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
                    Text(jsonLineText(line, p), style = Type.monoSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
        JsonTreeModel.Kind.CLOSE -> withStyle(SpanStyle(color = p.jsonPunct)) { append(line.text) }
        JsonTreeModel.Kind.STRING -> withStyle(SpanStyle(color = p.jsonString)) { append(line.text.take(MAX_SCALAR)) }
        JsonTreeModel.Kind.NUMBER -> withStyle(SpanStyle(color = p.jsonNumber)) { append(line.text) }
        JsonTreeModel.Kind.BOOL -> withStyle(SpanStyle(color = p.jsonBool)) { append(line.text) }
        JsonTreeModel.Kind.NULL -> withStyle(SpanStyle(color = p.jsonNull)) { append(line.text) }
    }
}

private const val MAX_SCALAR = 2_000

/**
 * Pretty-printed payload, selectable, one lazy item per line so huge bodies stay smooth. Find
 * matches paint in `findAll`; the current one a stronger accent plate, scrolled into view.
 */
@Composable
fun RawJson(lines: List<String>, matches: List<JsonFind.Match>, current: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val state = rememberLazyListState()
    val byLine = remember(matches) { matches.withIndex().groupBy { it.value.line } }
    LaunchedEffect(current, matches) {
        matches.getOrNull(current)?.let { state.scrollToItem((it.line - 4).coerceAtLeast(0)) }
    }
    Box(modifier) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = state) {
                itemsIndexed(lines) { n, text ->
                    val hits = byLine[n]
                    val annotated = if (hits == null) AnnotatedString(text) else buildAnnotatedString {
                        append(text)
                        hits.forEach { (i, m) ->
                            addStyle(SpanStyle(background = if (i == current) p.accent.copy(alpha = 0.45f) else p.findAll), m.start, m.end)
                        }
                    }
                    // Long lines wrap rather than scroll sideways: a body is read, not laid out.
                    Text(annotated, color = p.text, style = Type.monoSmall)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
