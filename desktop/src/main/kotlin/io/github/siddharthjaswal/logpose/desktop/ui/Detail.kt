package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle
import io.github.siddharthjaswal.logpose.desktop.detail.DetailTarget
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.presentation.RowContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the detail pane can ask of the screen. */
class DetailActions(
    val onClose: () -> Unit = {},
    /** The stepper moved to this occurrence — pin it, so a new poll doesn't yank the card forward. */
    val onStep: (String) -> Unit = {},
    val onCopy: (text: String, toast: String) -> Unit = { _, _ -> },
)

private val pretty = Json { prettyPrint = true; encodeDefaults = true }

/**
 * The right-hand pane: one per-kind card, like the plugin's `TransactionDetailView` (HTTP),
 * `FcmDetailView` (push) and `GenericDetailView` (db / worker / config / app-defined). Every word
 * on them — status labels, the duplicate banner, the chips, the composed request/response trees —
 * comes from core's [DetailContent] and [KindPresenter]; only the layout is this file's.
 *
 * One deliberate layout difference: the plugin puts Request and Response side by side under the
 * hero; at this pane's width they stack, each taking half the remaining height.
 */
@Composable
fun DetailPane(
    target: DetailTarget,
    duplicateOf: (Transaction) -> DuplicateDetector.Mark?,
    modifier: Modifier = Modifier,
    elapsedOf: (String) -> Long? = { null },
    transitions: (LogEvent.Worker) -> List<WorkerLifecycle.Transition> = { emptyList() },
    actions: DetailActions = DetailActions(),
    initialFind: String? = null,
) {
    val p = LocalPalette.current
    // Request headers are usually useful (auth, api keys) → on; response headers mostly noise →
    // off. Kept across selections, as the plugin's card keeps them.
    var reqHeaders by remember { mutableStateOf(true) }
    var respHeaders by remember { mutableStateOf(false) }
    Column(modifier.background(p.bg0).padding(8.dp)) {
        when (val event = target.event) {
            is LogEvent.Http -> {
                val tx = event.tx
                val pending = RowContent.isPending(tx)
                // One clock for the card, and only while the call it shows is in flight.
                var tick by remember(event.id) { mutableLongStateOf(0L) }
                if (pending) LaunchedEffect(event.id) { while (true) { delay(250); tick = System.currentTimeMillis() } }
                val elapsed = if (pending) { tick; elapsedOf(event.id) } else null
                HttpHero(event, target, duplicateOf(tx), elapsed, tick, actions)
                Spacer(Modifier.height(8.dp))
                JsonPanel(
                    "Request", DetailContent.requestJson(tx, reqHeaders), "req:${event.id}", Modifier.weight(1f).fillMaxWidth(),
                    status = tx.request.method, headers = HeadersToggle(reqHeaders) { reqHeaders = it }, onCopy = actions.onCopy,
                )
                Spacer(Modifier.height(8.dp))
                JsonPanel(
                    "Response", if (pending) null else DetailContent.responseJson(tx, respHeaders), "resp:${event.id}",
                    Modifier.weight(1f).fillMaxWidth(),
                    status = DetailContent.responseStatus(tx), headers = HeadersToggle(respHeaders) { respHeaders = it },
                    message = "${spinner(tick)}  Waiting for response…   ${elapsed ?: 0}ms",
                    initialFind = initialFind, onCopy = actions.onCopy,
                )
            }
            is LogEvent.Fcm -> {
                FcmHero(event, actions)
                Spacer(Modifier.height(8.dp))
                JsonPanel(
                    "Payload", DetailContent.fcmPayloadJson(event.msg), "fcm:${event.id}", Modifier.weight(1f).fillMaxWidth(),
                    status = RowContent.fcmLabel(event.msg).lowercase(), initialFind = initialFind, onCopy = actions.onCopy,
                )
            }
            else -> {
                GenericHero(event, transitions, actions, Modifier.heightIn(max = 440.dp))
                Spacer(Modifier.height(8.dp))
                JsonPanel(
                    "Payload", event.envelope.payload, "gen:${event.id}", Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                    status = event.kind, initialFind = initialFind, onCopy = actions.onCopy,
                )
            }
        }
    }
}

/** A hero card: bg1, radius 10, borderSubtle — the plugin's `CardPanel`. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().background(p.bg1, shape).border(1.dp, p.borderSubtle, shape)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<DetailContent.Chip>, live: Pair<String, String>? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            // Trace chips stay visibly links (accent); the waterfall they open arrives with M4b.
            StatChipView(chip, value = if (live != null && chip.caption == live.first) live.second else chip.value)
        }
    }
}

/** The plugin's `OverviewPanel`: header, duplicate strip, pills + stepper, URL, error, chips, actions. */
@Composable
private fun HttpHero(
    event: LogEvent.Http,
    target: DetailTarget,
    dup: DuplicateDetector.Mark?,
    elapsed: Long?,
    tick: Long,
    actions: DetailActions,
) {
    val p = LocalPalette.current
    val tx = event.tx
    val pending = RowContent.isPending(tx)
    val txJson = { pretty.encodeToString(Transaction.serializer(), tx) }
    Card {
        Row(
            Modifier.fillMaxWidth().background(p.bg2, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindGlyph(Envelope.KIND_HTTP)
            Spacer(Modifier.width(8.dp))
            T("Overview", p.text, style = Type.body.copy(fontSize = 13.sp), weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            GlyphButton("⧉", "Copy transaction JSON") { actions.onCopy(txJson(), "Transaction JSON copied") }
            GlyphButton("✕", "Close") { actions.onClose() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderStrong))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (dup != null) {
                val banner = DetailContent.duplicateBanner(tx, dup)
                val (fg, fill) = when (dup.severity) {
                    DuplicateDetector.Severity.STRONG -> p.danger to p.dangerTint
                    DuplicateDetector.Severity.MEDIUM -> p.warn to p.warnTint
                    DuplicateDetector.Severity.INFO -> p.textDim to p.tint(p.textDim, 22)
                }
                Box(Modifier.fillMaxWidth().background(fill, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    T(banner.text, fg, style = Type.small.copy(fontSize = 11.5.sp, lineHeight = 15.sp), weight = FontWeight.Bold, maxLines = 3)
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // MOCK is its own solid-intervention pill in front of the status: LogPose's act and
                // the server's answer are different facts.
                if (tx.mocked) {
                    Pill("MOCK", p.onAccent, fill = p.intervention, style = Type.pill.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                }
                if (pending) {
                    Pill("${spinner(tick)}  pending", p.accent, fill = p.tint(p.accent, 30), style = Type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                } else {
                    Pill(
                        DetailContent.statusLabel(tx), p.statusColor(tx.response?.code, tx.error), fill = p.statusFill(tx.response?.code, tx.error),
                        style = Type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Method carries no hue — read vs write is weight.
                val read = RowContent.isReadMethod(tx.request.method)
                Pill(tx.request.method, if (read) p.methodRead else p.methodWrite, fill = p.bg2, style = Type.body.copy(fontSize = 12.sp, fontWeight = if (read) FontWeight.Normal else FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                if (target.stepper) OccurrenceStepper(target, actions)
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer { T(tx.request.url, p.jsonString, style = Type.monoSmall.copy(fontSize = 12.sp), maxLines = 3) }
            tx.error?.takeIf { tx.response == null }?.let {
                Spacer(Modifier.height(4.dp))
                SelectionContainer { T(it, p.danger, style = Type.monoSmall.copy(fontSize = 12.sp), maxLines = 4) }
            }
            Spacer(Modifier.height(8.dp))
            Chips(
                DetailContent.httpChips(tx, event.envelope),
                live = if (pending) DetailContent.CAPTION_DURATION to DetailContent.liveDuration(elapsed ?: 0) else null,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                PillButton("</>  Copy as cURL", filled = true) { actions.onCopy(CurlBuilder.build(tx), "cURL copied") }
                Spacer(Modifier.width(10.dp))
                PillButton("Copy JSON", filled = false) { actions.onCopy(txJson(), "Transaction JSON copied") }
            }
        }
    }
}

/**
 * `occurrence ‹ n / N ›` — which call of a collapsed run the card is describing. The arrows
 * disable rather than wrap at the ends: a run is a stretch of time.
 */
@Composable
private fun OccurrenceStepper(target: DetailTarget, actions: DetailActions) {
    val p = LocalPalette.current
    val occ = target.occurrences ?: return
    Hint("Call ${target.index + 1} of ${occ.size} folded into this row (matching the current filter)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("occurrence", p.textMuted, style = Type.small)
            Spacer(Modifier.width(6.dp))
            GlyphButton("‹", "Previous occurrence", enabled = target.index > 0) { actions.onStep(occ[target.index - 1].id) }
            T("${target.index + 1} / ${occ.size}", p.textDim, Modifier.padding(horizontal = 2.dp), style = Type.monoSmall)
            GlyphButton("›", "Next occurrence", enabled = target.index < occ.lastIndex) { actions.onStep(occ[target.index + 1].id) }
        }
    }
}

/** The plugin's `FcmDetailView` hero: glyph + neutral kind pill + label, injected banner, summary, chips. */
@Composable
private fun FcmHero(event: LogEvent.Fcm, actions: DetailActions) {
    val p = LocalPalette.current
    val msg = event.msg
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindGlyph(Envelope.KIND_FCM)
                Spacer(Modifier.width(6.dp))
                // TOKEN / NOTIF / DATA: a label, not a severity — neutral; the hue is the glyph's.
                Pill(RowContent.fcmLabel(msg), p.textDim, fill = p.statusNeutralBg, style = Type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                T(DetailContent.fcmEventLabel(msg), p.text, style = Type.body.copy(fontSize = 13.sp), weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                GlyphButton("✕", "Close") { actions.onClose() }
            }
            if (msg.injected) {
                Spacer(Modifier.height(6.dp))
                // Intervention, not kind: the detail half of the row's INJ pill.
                T(DetailContent.INJECTED_BANNER, p.intervention, style = Type.small.copy(fontSize = 11.5.sp), weight = FontWeight.Bold, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            SelectionContainer { T(DetailContent.fcmSummary(msg), p.text, style = Type.monoSmall.copy(fontSize = 13.sp, lineHeight = 18.sp), maxLines = 4) }
            Spacer(Modifier.height(8.dp))
            Chips(DetailContent.fcmChips(msg, event.envelope))
            Spacer(Modifier.height(12.dp))
            PillButton("Copy JSON", filled = false) { actions.onCopy(pretty.encodeToString(FcmMessage.serializer(), msg), "FCM JSON copied") }
        }
    }
}

/**
 * The plugin's `GenericDetailView` hero, driven by [KindPresenter]: kind pill in the kind's hue,
 * title, subtitle, tone badges, chips, then typed section cards. Each hideable part goes with the
 * gap before it, so an event with nothing to say (a bare analytics event) collapses to
 * header → chips instead of reserving blank space. A worker adds its observed state transitions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenericHero(
    event: LogEvent,
    transitions: (LogEvent.Worker) -> List<WorkerLifecycle.Transition>,
    actions: DetailActions,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val presentation = remember(event) { KindPresenter.present(event) }
    val badges = remember(event) { KindPresenter.rowBadges(event, presentation) }
    val kindColor = p.kind(event.kind)
    Card(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindGlyph(event.kind)
                Spacer(Modifier.width(6.dp))
                Pill(KindPresenter.kindLabel(event), kindColor, fill = p.tint(kindColor, 30), style = Type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                T(presentation?.title ?: event.kind, p.text, Modifier.weight(1f, fill = false), style = Type.body.copy(fontSize = 13.sp), weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                GlyphButton("✕", "Close") { actions.onClose() }
            }
            presentation?.subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                SelectionContainer { T(it, p.text, style = Type.monoSmall.copy(fontSize = 13.sp, lineHeight = 18.sp), maxLines = 4) }
            }
            if (badges.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { b ->
                        val c = toneColor(p, b.tone)
                        Pill(b.text, c, fill = p.tint(c, 30), style = Type.small.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Chips(DetailContent.genericChips(event))
            val sections = presentation?.sections.orEmpty()
            val observed = (event as? LogEvent.Worker)?.let { DetailContent.workerTransitions(transitions(it)) }.orEmpty()
            if (sections.isNotEmpty() || observed.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Observed transitions lead: a worker is one mutating row, so this is the only
                    // place its life is visible at all.
                    if (observed.isNotEmpty()) TransitionsCard(observed)
                    sections.forEach { SectionCard(it) }
                }
            }
        }
    }
}

/** Badge tones arrive semantic, never as colours — what keeps the wire theme-independent. */
private fun toneColor(p: Palette, tone: String): Color = when (tone) {
    Badge.TONE_INFO -> p.accent
    Badge.TONE_WARN -> p.warn
    Badge.TONE_ERROR -> p.danger
    else -> p.textDim
}

@Composable
private fun SectionBox(label: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().background(p.bg1, shape).border(1.dp, p.borderSubtle, shape)) {
        T(label, p.textDim, Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp), style = Type.small, weight = FontWeight.Bold)
        content()
    }
}

/**
 * A typed section: `kv` as a two-column key/value table (analytics params, bound args, config
 * values read as a table, not a JSON blob); `text` wrapping prose; `code` and `json` monospaced,
 * json pretty-printed. Scrolls past 160dp like the plugin's.
 */
@Composable
private fun SectionCard(section: Section) {
    val p = LocalPalette.current
    val kv = (section.body as? JsonObject)?.takeIf { section.type == Section.TYPE_KV }
    SectionBox(section.label) {
        if (kv != null) {
            // One key column for the whole table (a grid, as the plugin's GridBag lays it out):
            // the longest key's monospaced width, capped so a long key can't starve the values.
            val keyWidth = ((kv.keys.maxOfOrNull { it.length } ?: 0) * 7.8f + 6f).coerceIn(48f, 170f).dp
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                kv.entries.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        T(k, p.textDim, Modifier.width(keyWidth), style = Type.monoSmall.copy(fontSize = 12.sp))
                        Spacer(Modifier.width(14.dp))
                        SelectionContainer(Modifier.weight(1f)) {
                            T((v as? JsonPrimitive)?.content ?: v.toString(), p.text, style = Type.monoSmall.copy(fontSize = 12.sp), maxLines = 8)
                        }
                    }
                }
            }
        } else {
            val body = when (section.type) {
                Section.TYPE_TEXT, Section.TYPE_CODE -> (section.body as? JsonPrimitive)?.content ?: section.body.toString()
                else -> runCatching { pretty.encodeToString(JsonElement.serializer(), section.body) }.getOrElse { section.body.toString() }
            }
            val mono = section.type != Section.TYPE_TEXT
            Box(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                SelectionContainer {
                    T(body, p.text, style = if (mono) Type.monoSmall.copy(fontSize = 12.sp) else Type.body.copy(fontSize = 12.sp), maxLines = Int.MAX_VALUE)
                }
            }
        }
    }
}

/** A worker's observed state sequence, with the caveat that says what it is (and isn't). */
@Composable
private fun TransitionsCard(rows: List<Pair<String, String>>) {
    val p = LocalPalette.current
    SectionBox("Observed state transitions") {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            rows.forEach { (at, what) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    T(at, p.textDim, style = Type.monoSmall.copy(fontSize = 12.sp))
                    Spacer(Modifier.width(14.dp))
                    T(what, p.text, style = Type.monoSmall.copy(fontSize = 12.sp))
                }
            }
            Spacer(Modifier.height(6.dp))
            T(DetailContent.TRANSITIONS_NOTE, p.textMuted, style = Type.small.copy(lineHeight = 15.sp), maxLines = 4)
        }
    }
}
