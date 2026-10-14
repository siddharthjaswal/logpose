package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.siddharthjaswal.logpose.desktop.advanced.MockDraft
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.advanced.PushDraft
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedUi
import io.github.siddharthjaswal.logpose.desktop.app.MockEditorUi
import io.github.siddharthjaswal.logpose.desktop.app.SaveScenarioUi
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette
import io.github.siddharthjaswal.logpose.mock.DeviceCapability
import io.github.siddharthjaswal.logpose.mock.DeviceFeature
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.presentation.MockRuleForm
import io.github.siddharthjaswal.logpose.presentation.RowContent

class AdvancedActions(
    val onAdvanced: (Boolean) -> Unit = {},
    val onWriter: (String, Boolean) -> Unit = { _, _ -> },
    val onEditRule: (MockRule) -> Unit = {},
    val onToggleRule: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteRule: (String) -> Unit = {},
    val onDisableAll: () -> Unit = {},
    val onEditorChange: (MockDraft) -> Unit = {},
    val onEditorSave: () -> Unit = {},
    val onEditorClose: () -> Unit = {},
    val onScenariosMenu: (Boolean) -> Unit = {},
    val onSaveRules: () -> Unit = {},
    val onSnapshot: () -> Unit = {},
    val onLoadScenario: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteScenario: (String) -> Unit = {},
    val onSaveScenarioChange: (SaveScenarioUi) -> Unit = {},
    val onSaveScenarioCommit: () -> Unit = {},
    val onSaveScenarioClose: () -> Unit = {},
    val onCompose: () -> Unit = {},
    val onComposeChange: (PushDraft) -> Unit = {},
    val onComposeSend: () -> Unit = {},
    val onComposeClose: () -> Unit = {},
    val onProjectFolder: () -> Unit = {},
    val onClearProjectFolder: () -> Unit = {},
    val onServe: (Boolean) -> Unit = {},
    val onCopy: (String, String) -> Unit = { _, _ -> },
)

/** The sync line's three-plus states — the plugin MocksBar's `applySync`, word for word. */
private data class SyncLine(val dot: Color, val textColor: Color, val text: String, val tip: String?)

private fun syncLine(p: Palette, device: MocksController.DeviceState?, writer: Boolean): SyncLine {
    if (device == null || !device.helloSeen) {
        return SyncLine(p.warn, p.textMuted, "waiting for device — needs logpose-android ≥ 1.1.0 + a running session", "No device has announced itself yet. Start a session and run the app.")
    }
    val name = device.pkg ?: "device"
    val sync = device.sync
    var line = when {
        !writer -> SyncLine(p.textMuted, p.textDim, "$name  ·  read-only — this app isn't writing its mocks", MockWriter.CONFLICT)
        sync.phase == SyncState.Phase.FAILED -> SyncLine(p.danger, p.danger, "$name  ·  not synced — rules may not be live", sync.message)
        !device.capturing -> SyncLine(p.textMuted, p.textMuted, "$name  ·  session stopped — phone rules cleared", "Rules stay here; they're pushed again when a session starts.")
        sync.phase == SyncState.Phase.PENDING -> SyncLine(p.warn, p.textDim, "$name  ·  syncing rev ${sync.revision}…", "Waiting for the phone to acknowledge revision ${sync.revision}.")
        else -> SyncLine(p.ok, p.textDim, "$name  ·  synced rev ${device.syncedRevision}", "Phone acknowledged ${sync.expectedRules} rule(s) at revision ${sync.syncedRevision}" + (device.libVersion?.let { " · logpose-android $it" } ?: "") + ".")
    }
    if (device.withheldRules > 0) {
        line = line.copy(tip = (line.tip?.plus("\n") ?: "") + "${device.withheldRules} rule(s) withheld — they need logpose-android ≥ ${DeviceFeature.RICH_MATCHERS.since} on the phone.")
    }
    return line
}

/**
 * The MOCKS strip under the filter bar — the plugin's `MocksBar`: caption, sync dot, the rules styled
 * like timeline rows (weighted method, outcome pill, latency and hit counts), *Scenarios ▾* and
 * *Disable all*. When the app is not this phone's mock writer the strip says why, offers the
 * hand-over, and every write control is absent rather than disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MocksStrip(
    ui: AdvancedUi,
    actions: AdvancedActions,
    /** The most the pane may take — the splitter under it sets this. */
    maxHeight: Dp = 220.dp,
    /** ⌘3: just the header line (caption, sync, rule count). */
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null,
) {
    if (!ui.advanced) return
    val p = LocalPalette.current
    val sync = syncLine(p, ui.device, ui.writer)
    Column(
        Modifier.fillMaxWidth().background(p.bg0).heightIn(max = if (collapsed) 40.dp else maxHeight)
            .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = if (collapsed) 7.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onToggleCollapsed != null) {
                GlyphButton(if (collapsed) "▸" else "▾", if (collapsed) "Show the mocks pane  ⌘3" else "Collapse the mocks pane  ⌘3", onClick = onToggleCollapsed)
                Spacer(Modifier.width(2.dp))
            }
            T("MOCKS", p.textDim, style = Type.small.copy(fontSize = 10.5.sp), weight = FontWeight.Bold)
            if (collapsed && ui.rules.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                T("${ui.rules.count { it.enabled }}/${ui.rules.size} on", p.textMuted, style = Type.small)
            }
            Spacer(Modifier.width(7.dp))
            Hint(sync.tip) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(sync.dot, CircleShape))
                    Spacer(Modifier.width(7.dp))
                    T(sync.text, sync.textColor, style = Type.small)
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                LinkText(if (ui.scenarios.isNotEmpty()) "Scenarios (${ui.scenarios.size}) ▾" else "Scenarios ▾") { actions.onScenariosMenu(!ui.scenariosMenu) }
                if (ui.scenariosMenu) ScenariosMenu(ui, actions)
            }
            if (ui.writer && ui.rules.any { it.enabled }) {
                Spacer(Modifier.width(12.dp))
                LinkText("Disable all", onClick = actions.onDisableAll)
            }
            if (ui.writer) {
                Spacer(Modifier.width(12.dp))
                LinkText("+ Push…", onClick = actions.onCompose)
            }
        }
        if (collapsed) return@Column
        val serial = ui.serial
        if (!ui.writer) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().background(p.bg1, RoundedCornerShape(6.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(MockWriter.CONFLICT, p.textDim, Modifier.weight(1f), style = Type.small.copy(lineHeight = 15.sp), maxLines = 4)
                Spacer(Modifier.width(12.dp))
                if (serial != null) {
                    Hint(MockWriter.TAKE_OVER) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToggleSwitch(false) { actions.onWriter(serial, true) }
                            Spacer(Modifier.width(8.dp))
                            T("This app controls mocks", p.text, style = Type.small, weight = FontWeight.Bold)
                        }
                    }
                } else T("Select a phone to hand its mocks to this app.", p.textMuted, style = Type.small)
            }
        } else if (serial != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToggleSwitch(true) { actions.onWriter(serial, false) }
                Spacer(Modifier.width(8.dp))
                T("This app controls mocks for this phone", p.textDim, style = Type.small)
                Spacer(Modifier.width(6.dp))
                Hint(MockWriter.TAKE_OVER) { T("ⓘ", p.textMuted, style = Type.small) }
            }
        }
        if (ui.rules.isNotEmpty()) Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ui.rules.forEach { RuleRow(it, ui.device?.hits?.get(it.id) ?: 0, ui, actions) }
        }
        if (ui.rules.isEmpty() && ui.writer) {
            T("No rules yet — right-click a request → Mock this endpoint…", p.textMuted, Modifier.padding(top = 4.dp), style = Type.small)
        }
    }
}

@Composable
private fun RuleRow(rule: MockRule, hits: Int, ui: AdvancedUi, actions: AdvancedActions) {
    val p = LocalPalette.current
    val on = rule.enabled
    fun fade(c: Color) = if (on) c else p.fade(c, 0.45f)
    val device = ui.device
    val withheld = device != null && device.helloSeen && !DeviceCapability.canPush(rule, device.libVersion)
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ui.writer) ToggleSwitch(on) { actions.onToggleRule(rule.id, !on) } else Box(Modifier.size(7.dp).background(if (on) p.textDim else p.bg3, CircleShape))
        Spacer(Modifier.width(10.dp))
        val read = RowContent.isReadMethod(rule.method)
        T(rule.method, fade(if (read) p.methodRead else p.methodWrite), Modifier.width(46.dp), style = Type.small, weight = if (read) FontWeight.Normal else FontWeight.Bold)
        T(rule.pathPattern, if (on) p.text else p.textMuted, Modifier.weight(1f), style = Type.body.copy(fontSize = 12.sp))
        if (withheld) {
            Hint("Not sent to this phone — it runs logpose-android ${device?.libVersion ?: "an older version"}, which would ignore this rule's constraints and match more broadly than it reads.") {
                Pill("lib ≥ ${DeviceFeature.RICH_MATCHERS.since}", fade(p.warn), stroke = fade(p.warn))
            }
            Spacer(Modifier.width(6.dp))
        }
        MockRuleForm.matchSummary(rule)?.let { Hint(it) { Pill("only when", fade(p.accent), stroke = fade(p.accent)) }; Spacer(Modifier.width(6.dp)) }
        MockRuleForm.stepsLabel(rule)?.let { Hint(MockRuleForm.stepsSummary(rule)) { Pill(it, fade(p.textDim), stroke = fade(p.borderStrong)) }; Spacer(Modifier.width(6.dp)) }
        val behavior = MockRuleForm.effectiveBehavior(rule)
        val status = MockRuleForm.effectiveStatus(rule)
        when {
            rule.mode == MockRule.MODE_PATCH -> Pill("MERGE", fade(p.textDim), fill = p.bg2)
            behavior == MockRule.BEHAVIOR_TIMEOUT -> Pill("TIMEOUT", fade(p.danger), fill = p.dangerPlate)
            behavior == MockRule.BEHAVIOR_CONNECTION_FAILURE -> Pill("CONN FAIL", fade(p.danger), fill = p.dangerPlate)
            else -> Pill(status.toString(), fade(p.statusColor(status, null)), fill = p.statusFill(status, null), style = Type.pill.copy(fontWeight = FontWeight.Bold))
        }
        val latency = rule.responses.firstOrNull()?.latencyMillis ?: rule.latencyMillis
        if (latency > 0) { Spacer(Modifier.width(6.dp)); Pill("${latency}ms", fade(p.textMuted), stroke = p.borderSubtle) }
        if (rule.serveLimit > 0) { Spacer(Modifier.width(6.dp)); Pill("×${rule.serveLimit} max", fade(p.textMuted), stroke = p.borderSubtle) }
        T(if (hits > 0) "${hits}×" else "", p.textMuted, Modifier.width(40.dp), style = Type.monoSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
        Spacer(Modifier.width(8.dp))
        if (ui.writer) {
            GlyphButton("✎", "Edit") { actions.onEditRule(rule) }
            GlyphButton("✕", "Delete") { actions.onDeleteRule(rule.id) }
        }
    }
}

@Composable
private fun ScenariosMenu(ui: AdvancedUi, actions: AdvancedActions) {
    val p = LocalPalette.current
    Popup(offset = IntOffset(-180, 44), onDismissRequest = { actions.onScenariosMenu(false) }, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.widthIn(min = 300.dp, max = 380.dp).background(p.bg1, RoundedCornerShape(8.dp)).border(1.dp, p.borderStrong, RoundedCornerShape(8.dp)).padding(vertical = 6.dp),
        ) {
            MenuLine("Save current rules as…", actions.onSaveRules)
            MenuLine("Snapshot session into scenario…", actions.onSnapshot)
            if (ui.scenarios.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                T("Saved  ·  ${if (ui.location?.shared == true) ".logpose/scenarios" else "on this Mac"}", p.textMuted, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = Type.small)
            }
            ui.scenarios.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint(s.note) { T("${s.name}   (${s.rules} rules)", p.text, Modifier.widthIn(max = 170.dp), style = Type.small) }
                    Spacer(Modifier.weight(1f))
                    if (ui.writer) {
                        LinkText("merge") { actions.onLoadScenario(s.name, false) }
                        Spacer(Modifier.width(8.dp))
                        LinkText("replace") { actions.onLoadScenario(s.name, true) }
                        Spacer(Modifier.width(8.dp))
                    }
                    LinkText("delete") { actions.onDeleteScenario(s.name) }
                }
            }
        }
    }
}

@Composable
private fun MenuLine(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    T(text, p.text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), style = Type.body.copy(fontSize = 12.sp))
}

/** A label + field row, the plugin dialogs' 96px label column. */
@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        T(label, p.textDim, Modifier.width(104.dp).padding(top = 7.dp), style = Type.small)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Section(title: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, p.text, style = Type.body.copy(fontSize = 12.5.sp), weight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(p.borderSubtle))
    }
}

/** A single-choice segmented row over (key → label). */
@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.background(p.bg3, RoundedCornerShape(8.dp)).padding(2.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.height(24.dp).background(if (on) p.bg0 else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onPick(key) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, if (on) p.text else p.textDim, style = Type.small, weight = if (on) FontWeight.Bold else null) }
        }
    }
}

/**
 * The mock editor: **when** a request matches → **then** do this → **send** that — the plugin
 * `MockRuleDialog`'s model (replace / merge, behaviour, latency, serve limit, matchers, steps) as a
 * functional form over core's [MockRuleForm]. Fields that don't apply are hidden, not disabled.
 * The field-tree patch editor and the diff view are not ported: merge mode takes a JSON object.
 */
@Composable
fun MockEditorSheet(ui: MockEditorUi?, actions: AdvancedActions) {
    ui ?: return
    val p = LocalPalette.current
    val d = ui.draft
    fun set(next: MockDraft) = actions.onEditorChange(next)
    Modal(actions.onEditorClose, width = 720.dp) {
        Column(Modifier.heightIn(max = 760.dp)) {
            SheetHeader(ui.title, actions.onEditorClose)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Section("When a request matches")
                FormRow("Method") { Choice(listOf("*", "GET", "POST", "PUT", "PATCH", "DELETE").map { it to it }, d.method) { set(d.copy(method = it)) } }
                FormRow("Path") { InputBox(d.path, { set(d.copy(path = it)) }, Modifier.fillMaxWidth(), placeholder = "/v1/orders/*", mono = true) }
                FormRow("Only when…") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InputBox(d.matchQuery, { set(d.copy(matchQuery = it)) }, Modifier.fillMaxWidth(), placeholder = "query  debug=1", lines = 2, mono = true)
                        InputBox(d.matchHeaders, { set(d.copy(matchHeaders = it)) }, Modifier.fillMaxWidth(), placeholder = "header  X-Env: staging", lines = 2, mono = true)
                        InputBox(d.matchBody, { set(d.copy(matchBody = it)) }, Modifier.fillMaxWidth(), placeholder = "body contains…", mono = true)
                        T("Every line must hold. A key alone means \"present, any value\". Needs logpose-android ≥ ${DeviceFeature.RICH_MATCHERS.since}.", p.textMuted, style = Type.small, maxLines = 2)
                    }
                }
                Section("Then")
                FormRow("Mode") { Choice(listOf(MockRule.MODE_REPLACE to "Replace the response", MockRule.MODE_PATCH to "Merge into the real response"), d.mode) { set(d.copy(mode = it)) } }
                if (!d.patch) {
                    FormRow("Behavior") {
                        Choice(
                            listOf(MockRule.BEHAVIOR_NORMAL to "Serve a response", MockRule.BEHAVIOR_TIMEOUT to "Time out", MockRule.BEHAVIOR_CONNECTION_FAILURE to "Fail to connect"),
                            d.behavior,
                        ) { set(d.copy(behavior = it)) }
                    }
                }
                FormRow("Latency ms") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InputBox(d.latency, { set(d.copy(latency = it)) }, Modifier.width(90.dp), mono = true)
                        Spacer(Modifier.width(16.dp))
                        T("Serve limit", p.textDim, style = Type.small)
                        Spacer(Modifier.width(8.dp))
                        InputBox(d.serveLimit, { set(d.copy(serveLimit = it)) }, Modifier.width(70.dp), mono = true)
                        Spacer(Modifier.width(8.dp))
                        T("0 = forever", p.textMuted, style = Type.small)
                    }
                }
                if (!d.patch) {
                    FormRow("Steps") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToggleSwitch(d.stepsEnabled) { set(d.withSteps(!d.stepsEnabled)) }
                            Spacer(Modifier.width(8.dp))
                            T("Respond in sequence (e.g. fail once, then succeed) — the last step repeats", p.textDim, style = Type.small, maxLines = 2)
                        }
                    }
                }
                Section("Send")
                if (d.stepsEnabled && !d.patch) {
                    d.steps.forEachIndexed { i, step ->
                        fun setStep(s: MockRuleForm.StepDraft) = set(d.copy(steps = d.steps.mapIndexed { j, o -> if (j == i) s else o }))
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(p.bg2, RoundedCornerShape(6.dp)).padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("Step ${i + 1}", p.text, style = Type.small, weight = FontWeight.Bold)
                                Spacer(Modifier.width(10.dp))
                                Choice(listOf(MockRule.BEHAVIOR_NORMAL to "respond", MockRule.BEHAVIOR_TIMEOUT to "timeout", MockRule.BEHAVIOR_CONNECTION_FAILURE to "conn fail"), step.behavior) { setStep(step.copy(behavior = it)) }
                                Spacer(Modifier.width(10.dp))
                                InputBox(step.status, { setStep(step.copy(status = it)) }, Modifier.width(60.dp), mono = true)
                                Spacer(Modifier.width(6.dp))
                                InputBox(step.latency, { setStep(step.copy(latency = it)) }, Modifier.width(70.dp), placeholder = "ms", mono = true)
                                Spacer(Modifier.weight(1f))
                                if (d.steps.size > 1) GlyphButton("✕", "Remove step") { set(d.copy(steps = d.steps.filterIndexed { j, _ -> j != i })) }
                            }
                            if (step.behavior == MockRule.BEHAVIOR_NORMAL) {
                                Spacer(Modifier.height(4.dp))
                                InputBox(step.body, { setStep(step.copy(body = it)) }, Modifier.fillMaxWidth(), placeholder = "body", lines = 3, mono = true)
                            }
                        }
                    }
                    PillButton("+ Add step", filled = false) { set(d.copy(steps = d.steps + MockRuleForm.StepDraft())) }
                } else if (d.patch || d.behavior == MockRule.BEHAVIOR_NORMAL) {
                    if (!d.patch) {
                        FormRow("Status") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InputBox(d.status, { set(d.copy(status = it)) }, Modifier.width(70.dp), mono = true)
                                Spacer(Modifier.width(16.dp))
                                T("Content-Type", p.textDim, style = Type.small)
                                Spacer(Modifier.width(8.dp))
                                InputBox(d.contentType, { set(d.copy(contentType = it)) }, Modifier.weight(1f), mono = true)
                            }
                        }
                        FormRow("Headers") { InputBox(d.headers, { set(d.copy(headers = it)) }, Modifier.fillMaxWidth(), placeholder = "Retry-After: 5", lines = 2, mono = true) }
                    }
                    FormRow(if (d.patch) "Override" else "Body") {
                        Column {
                            InputBox(d.body, { set(d.copy(body = it)) }, Modifier.fillMaxWidth(), placeholder = if (d.patch) "{ \"status\": \"ACCEPTED\" }" else "{ … }", lines = 9, mono = true)
                            if (d.patch) T("Deep-merged into the real backend response on the phone: override keys, add keys, keep the rest.", p.textMuted, style = Type.small, maxLines = 2)
                        }
                    }
                } else {
                    T("Nothing is sent — the call ${if (d.behavior == MockRule.BEHAVIOR_TIMEOUT) "times out (SocketTimeoutException)" else "fails to connect (ConnectException)"} after the latency above.", p.textDim, style = Type.small, maxLines = 2)
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                (ui.problem ?: d.problem())?.let { T(it, if (ui.problem != null) p.danger else p.warn, Modifier.weight(1f), style = Type.small, maxLines = 2) } ?: Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Button("Cancel", actions.onEditorClose)
                Spacer(Modifier.width(8.dp))
                Button("Save rule", actions.onEditorSave, primary = true, enabled = d.problem() == null)
            }
        }
    }
}

/** Compose push — the plugin's `ComposePushDialog`: envelope, optional notification, raw JSON data. */
@Composable
fun ComposePushSheet(draft: PushDraft?, actions: AdvancedActions) {
    draft ?: return
    val p = LocalPalette.current
    fun set(next: PushDraft) = actions.onComposeChange(next)
    Modal(actions.onComposeClose, width = 640.dp) {
        Column(Modifier.padding(bottom = 14.dp)) {
            SheetHeader("Compose push", actions.onComposeClose)
            Column(Modifier.padding(horizontal = 24.dp)) {
                Section("Envelope")
                FormRow("From") { InputBox(draft.from, { set(draft.copy(from = it)) }, Modifier.fillMaxWidth(), mono = true) }
                FormRow("Collapse key") { InputBox(draft.collapseKey, { set(draft.copy(collapseKey = it)) }, Modifier.fillMaxWidth(), mono = true) }
                T("Both are optional — a data message needs neither to reach your handler.", p.textMuted, Modifier.padding(start = 104.dp), style = Type.small)
                Section("Notification (optional)")
                FormRow("Title") { InputBox(draft.title, { set(draft.copy(title = it)) }, Modifier.fillMaxWidth()) }
                FormRow("Body") { InputBox(draft.body, { set(draft.copy(body = it)) }, Modifier.fillMaxWidth()) }
                Section("Data payload")
                T("A JSON object of string values — exactly what your app reads from data[…].", p.textMuted, style = Type.small)
                Spacer(Modifier.height(4.dp))
                InputBox(draft.data, { set(draft.copy(data = it)) }, Modifier.fillMaxWidth(), lines = 9, mono = true)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    draft.problem()?.let { T(it, p.warn, Modifier.weight(1f), style = Type.small, maxLines = 2) } ?: Spacer(Modifier.weight(1f))
                    Button("Cancel", actions.onComposeClose)
                    Spacer(Modifier.width(8.dp))
                    Button("Send", actions.onComposeSend, primary = true, enabled = draft.problem() == null)
                }
            }
        }
    }
}

/** Save rules / snapshot the session into a named scenario — the plugin's `SaveScenarioDialog`. */
@Composable
fun SaveScenarioSheet(ui: SaveScenarioUi?, where: String, actions: AdvancedActions) {
    ui ?: return
    val p = LocalPalette.current
    Modal(actions.onSaveScenarioClose, width = 560.dp) {
        Column(Modifier.padding(bottom = 14.dp)) {
            SheetHeader(if (ui.fromSession) "Snapshot session into scenario" else "Save mock rules as scenario", actions.onSaveScenarioClose)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormRow("Name") { InputBox(ui.name, { actions.onSaveScenarioChange(ui.copy(name = it)) }, Modifier.fillMaxWidth(), mono = true) }
                FormRow("Note") { InputBox(ui.note, { actions.onSaveScenarioChange(ui.copy(note = it)) }, Modifier.fillMaxWidth(), placeholder = "optional") }
                if (ui.fromSession) {
                    Row(Modifier.padding(start = 104.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckBox(ui.successOnly) { actions.onSaveScenarioChange(ui.copy(successOnly = !ui.successOnly)) }
                        Spacer(Modifier.width(8.dp))
                        T("Only 2xx responses", p.text, style = Type.small)
                    }
                    ui.preview?.let { T(it, p.textDim, Modifier.padding(start = 104.dp), style = Type.small, maxLines = 3) }
                }
                T("Saved to $where. Scenarios contain captured response bodies — review before sharing.", p.textMuted, style = Type.small, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ui.problem?.let { T(it, p.danger, Modifier.weight(1f), style = Type.small, maxLines = 2) } ?: Spacer(Modifier.weight(1f))
                    Button("Cancel", actions.onSaveScenarioClose)
                    Spacer(Modifier.width(8.dp))
                    Button("Save", actions.onSaveScenarioCommit, primary = true)
                }
            }
        }
    }
}
