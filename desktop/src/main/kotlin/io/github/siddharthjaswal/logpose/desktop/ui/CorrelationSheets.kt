package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.analysis.FindByValue
import io.github.siddharthjaswal.logpose.desktop.app.FindUi
import io.github.siddharthjaswal.logpose.desktop.app.KeysUi
import io.github.siddharthjaswal.logpose.desktop.correlation.KeysDraft
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette

class CorrelationActions(
    val onKeysChange: (KeysDraft) -> Unit = {},
    val onKeysSave: () -> Unit = {},
    val onKeysClose: () -> Unit = {},
    val onFindChange: (String) -> Unit = {},
    val onFindCommit: () -> Unit = {},
    val onFindClose: () -> Unit = {},
)

/** A bordered text input in the app's form vocabulary; [lines] > 1 makes it a small text area. */
@Composable
fun InputBox(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    lines: Int = 1,
    mono: Boolean = false,
    focus: FocusRequester? = null,
    onEnter: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val style: TextStyle = (if (mono) Type.monoSmall.copy(fontSize = 12.sp) else Type.body.copy(fontSize = 12.5.sp)).copy(color = p.text)
    Box(
        modifier.heightIn(min = 30.dp).then(if (lines > 1) Modifier.height((lines * 17 + 12).dp) else Modifier)
            .background(p.bg0, RoundedCornerShape(6.dp)).border(1.dp, p.borderStrong, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = if (lines > 1) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (value.isEmpty()) T(placeholder, p.textMuted, style = style.copy(color = p.textMuted), maxLines = lines)
        BasicTextField(
            value = value,
            onValueChange = { onChange(if (lines == 1) it.replace("\n", "") else it) },
            singleLine = lines == 1,
            textStyle = style,
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth()
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .then(if (onEnter != null) Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onEnter(); true } else false
                } else Modifier)
                .then(if (lines > 1) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        )
    }
}

/** A small square checkbox, accent when ticked. */
@Composable
fun CheckBox(checked: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(16.dp).background(if (checked) p.accent else p.bg0, RoundedCornerShape(4.dp))
            .border(1.dp, if (checked) p.accent else p.borderStrong, RoundedCornerShape(4.dp)).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) { if (checked) T("✓", p.onAccent, style = Type.small.copy(fontSize = 10.sp), weight = FontWeight.Bold) }
}

/**
 * The project's correlation vocabulary — the plugin's `CorrelationKeysDialog`: suggestions arrive
 * **unticked and inert**, each with its evidence ("groups 31 events · largest 6 · latest …"), and
 * nothing groups until a human ticks it. There are no built-in keys.
 */
@Composable
fun KeysSheet(ui: KeysUi?, actions: CorrelationActions) {
    ui ?: return
    val p = LocalPalette.current
    val draft = ui.draft
    Modal(actions.onKeysClose, width = 680.dp) {
        Column(Modifier.padding(bottom = 18.dp)) {
            SheetHeader("Correlation keys", actions.onKeysClose)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T(
                    "The key names the value; the value does the matching — an event joins the group when the value appears " +
                        "anywhere in it, even with no key beside it and no trace at all.",
                    p.textDim, style = Type.small.copy(fontSize = 11.5.sp, lineHeight = 16.sp), maxLines = 3,
                )
                T(
                    if (draft.seeded) "Suggested from this capture — nothing groups until you tick it."
                    else "Tick \"Short\" only for a key whose values are under 4 characters; short values over-group easily.",
                    p.textMuted, style = Type.small, maxLines = 2,
                )
                T("Saved to ${ui.where}", p.textMuted, style = Type.small, maxLines = 2)
                // Table: ☐ | Key | Short | In this capture
                Column(Modifier.fillMaxWidth().background(p.bg0, RoundedCornerShape(8.dp)).border(1.dp, p.borderStrong, RoundedCornerShape(8.dp))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(26.dp))
                        T("Key", p.textMuted, Modifier.width(190.dp), style = Type.small, weight = FontWeight.SemiBold)
                        T("Short", p.textMuted, Modifier.width(52.dp), style = Type.small, weight = FontWeight.SemiBold)
                        T("In this capture", p.textMuted, Modifier.weight(1f), style = Type.small, weight = FontWeight.SemiBold)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        if (ui.loading) T("Reading the capture for id-ish keys…", p.textDim, Modifier.padding(12.dp))
                        else if (draft.rows.isEmpty()) T("No keys yet. Add one, or capture a flow and use Suggest from capture.", p.textDim, Modifier.padding(12.dp), maxLines = 2)
                        draft.rows.forEachIndexed { i, row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CheckBox(row.enabled) { actions.onKeysChange(draft.edit(i) { it.copy(enabled = !it.enabled) }) }
                                Spacer(Modifier.width(10.dp))
                                InputBox(row.name, { v -> actions.onKeysChange(draft.edit(i) { it.copy(name = v) }) }, Modifier.width(180.dp), placeholder = "order_id", mono = true)
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.width(52.dp)) { CheckBox(row.allowShort) { actions.onKeysChange(draft.edit(i) { it.copy(allowShort = !it.allowShort) }) } }
                                T(row.note, p.textDim, Modifier.weight(1f), style = Type.monoTiny)
                                GlyphButton("✕", "Remove") { actions.onKeysChange(draft.remove(i)) }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("+ Add key", filled = false) { actions.onKeysChange(draft.add()) }
                    Spacer(Modifier.width(8.dp))
                    if (draft.suggestions.isNotEmpty()) PillButton("Suggest from capture", filled = false) { actions.onKeysChange(draft.appendSuggestions()) }
                    else T("No id-ish keys in what's captured so far — capture a flow first.", p.textMuted, style = Type.small)
                    Spacer(Modifier.weight(1f))
                    draft.problem()?.let { T(it, p.warn, Modifier.weight(2f, fill = false), style = Type.small, maxLines = 2); Spacer(Modifier.width(10.dp)) }
                    Button("Save", actions.onKeysSave, primary = true, enabled = draft.problem() == null && !ui.loading)
                }
            }
        }
    }
}

/**
 * *Find by value…* — the plugin's `FindByValueDialog`: core's [io.github.siddharthjaswal.logpose.analysis.Correlation.parseFindQuery]
 * reads the input, [FindByValue.preview] says the count **before** committing (debounced), and a
 * too-short value says so instead of opening an empty waterfall.
 */
@Composable
fun FindSheet(ui: FindUi?, actions: CorrelationActions) {
    ui ?: return
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    Modal(actions.onFindClose, width = 520.dp) {
        Column(Modifier.padding(bottom = 18.dp)) {
            SheetHeader("Find by value", actions.onFindClose)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Paste an id — an order, a trip, a request id. Any event carrying it joins the group.", p.textDim, style = Type.small.copy(fontSize = 11.5.sp), maxLines = 2)
                InputBox(ui.text, actions.onFindChange, Modifier.fillMaxWidth(), placeholder = "21053953   or   order_id=21053953", mono = true, focus = focus, onEnter = actions.onFindCommit)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(
                        ui.preview.message.ifBlank { " " },
                        when (ui.preview.tone) {
                            FindByValue.Tone.READY -> p.accent
                            FindByValue.Tone.PROBLEM -> p.warn
                            FindByValue.Tone.NEUTRAL -> p.textMuted
                        },
                        Modifier.weight(1f), style = Type.small.copy(fontSize = 11.5.sp), maxLines = 3,
                    )
                    Spacer(Modifier.width(10.dp))
                    Button("Show waterfall", actions.onFindCommit, primary = true, enabled = ui.preview.grouping != null)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
