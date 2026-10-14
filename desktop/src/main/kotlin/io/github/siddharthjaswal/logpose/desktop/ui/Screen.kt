package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.app.UpdateUi
import io.github.siddharthjaswal.logpose.desktop.adb.PlatformTools
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.device.DeviceStatuses
import io.github.siddharthjaswal.logpose.desktop.device.SidebarEntries
import io.github.siddharthjaswal.logpose.desktop.device.SidebarEntry
import io.github.siddharthjaswal.logpose.desktop.device.Transport
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.desktop.app.Notice
import io.github.siddharthjaswal.logpose.desktop.app.ShareUi
import io.github.siddharthjaswal.logpose.desktop.app.ViewingFile
import io.github.siddharthjaswal.logpose.desktop.timeline.SessionMarks
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.CompositionLocalProvider
import io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle
import io.github.siddharthjaswal.logpose.desktop.detail.DetailModel
import io.github.siddharthjaswal.logpose.desktop.timeline.FilterEdits
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineView
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedUi
import io.github.siddharthjaswal.logpose.desktop.app.FindUi
import io.github.siddharthjaswal.logpose.desktop.app.KeysUi
import io.github.siddharthjaswal.logpose.desktop.mcp.McpUi
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallView
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation

/** Everything the window paints, as plain values — so the off-screen renders use the real screen. */
data class ScreenState(
    val adb: AdbStatus,
    val devices: List<Adb.DeviceInfo>,
    val selectedSerial: String?,
    val session: SessionState,
    val snapshot: TimelineSnapshot,
    val selectedEventId: String?,
    val placeholder: Placeholder,
    val wireless: WirelessUi = WirelessUi(),
    // M3 — record and share
    val marks: SessionMarks = SessionMarks.EMPTY,
    val viewing: ViewingFile? = null,
    val pendingBookmark: Long? = null,
    val notice: Notice? = null,
    val share: ShareUi? = null,
    val selectedIds: Set<String> = emptySet(),
    val screenshotBusy: Boolean = false,
    val canBookmark: Boolean = session is SessionState.Running,
    val canShare: Boolean = viewing != null || snapshot.events.isNotEmpty(),
    // M4a — parity
    val filter: FilterState = FilterState(),
    /** The folded, filtered timeline; null builds it here from [snapshot] (the renders' path). */
    val view: TimelineView? = null,
    val expanded: Set<String> = emptySet(),
    val pinnedOccurrence: String? = null,
    /** Host-clock age of an event — the live count-up's source (`EventStore.elapsedMillis`). */
    val elapsedOf: (String) -> Long? = { null },
    val transitions: (LogEvent.Worker) -> List<WorkerLifecycle.Transition> = { emptyList() },
    /** Render seeds: open the Filters popover / a find-in-body query on first composition. */
    val filterPopover: Boolean = false,
    val detailFind: String? = null,
    // M4b — waterfall, correlation, Advanced, Serve MCP
    val correlation: Grouping? = null,
    val waterfall: WaterfallView? = null,
    val keys: KeysUi? = null,
    val find: FindUi? = null,
    val advanced: AdvancedUi = AdvancedUi(),
    val mcp: McpUi = McpUi(),
    val serveBusy: Boolean = false,
    // M5 — the update line in the sidebar footer
    val updates: UpdateUi = UpdateUi(),
    // Emulators — the lean-emulator sheet, and which emulator serials run a guest-slimmed Android
    val emulators: io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi = io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi(),
)

class ScreenActions(
    val onDownloadAdb: () -> Unit = {},
    val onRecheckAdb: () -> Unit = {},
    val onSelectDevice: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onSelectEvent: (String?) -> Unit = {},
    val wireless: WirelessActions = WirelessActions(),
    val onBookmark: () -> Unit = {},
    /** Enter passes the note; Esc passes null (the marker is kept, without a note). */
    val onCommitBookmark: (String?) -> Unit = {},
    val onScreenshot: () -> Unit = {},
    val onOpenFile: () -> Unit = {},
    val onBackToLive: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val share: ShareActions = ShareActions(),
    val onFilter: (FilterState) -> Unit = {},
    val timeline: TimelineActions = TimelineActions(onSelect = { onSelectEvent(it) }),
    val detail: DetailActions = DetailActions(onClose = { onSelectEvent(null) }),
    val onClearCorrelation: () -> Unit = {},
    val waterfall: WaterfallActions = WaterfallActions(),
    val correlation: CorrelationActions = CorrelationActions(),
    val advanced: AdvancedActions = AdvancedActions(),
    val onUpdatesEnabled: (Boolean) -> Unit = {},
    val onDownloadUpdate: () -> Unit = {},
    val emulators: EmulatorActions = EmulatorActions(),
)

@Composable
fun LogPoseScreen(state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides if (p.dark) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation,
    ) { Screen(state, actions) }
}

@Composable
private fun Screen(state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.bg0)) {
        if (state.adb !is AdbStatus.Ready) {
            AdbSetup(state.adb, actions)
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            Sidebar(state, actions, Modifier.width(248.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(p.borderSubtle))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Toolbar(state, actions)
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                state.viewing?.let { ViewingBanner(it, actions) }
                state.pendingBookmark?.let { BookmarkPrompt(it, state.marks.bookmarks.size + 1, actions) }
                state.notice?.let { NoticeStrip(it, actions) }
                val view = state.view ?: remember(state.snapshot.version, state.filter, state.expanded, state.correlation) {
                    TimelineViews.build(
                        state.snapshot.events, state.filter, state.expanded, version = state.snapshot.version,
                        correlation = state.correlation,
                        inGroup = { e, g -> Correlation.containsValue(Correlation.searchableText(e), g.value) },
                    )
                }
                val showTimeline = state.placeholder == Placeholder.None || state.viewing != null
                val target = DetailModel.resolve(view, state.selectedEventId, state.pinnedOccurrence) { id ->
                    state.snapshot.events.firstOrNull { it.id == id }
                }
                Row(Modifier.fillMaxSize()) {
                    // The bar sits over the list only, as in the plugin (the list half of its
                    // splitter) — so its narrow layout follows the list's width, not the window's.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (showTimeline) {
                        FilterBar(
                            state.filter, view, actions.onFilter, popoverInitiallyOpen = state.filterPopover,
                            correlation = state.correlation, onClearCorrelation = actions.onClearCorrelation,
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                    }
                    MocksStrip(state.advanced, actions.advanced)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val empty = view.empty
                        when {
                            !showTimeline -> EmptyState(state.placeholder)
                            empty != null -> FilteredToNothing(
                                empty,
                                onClearAll = { actions.onFilter(FilterEdits.clearAll(state.filter)); actions.onClearCorrelation() },
                                onLoosen = { id ->
                                    if (id == FilterPresentation.FilterId.CORRELATION) actions.onClearCorrelation()
                                    else actions.onFilter(FilterEdits.loosen(state.filter, id))
                                },
                            )
                            else -> Timeline(
                                view, state.selectedEventId, Modifier.fillMaxSize(),
                                marks = state.marks, selectedIds = state.selectedIds, elapsedOf = state.elapsedOf,
                                actions = actions.timeline,
                            )
                        }
                    }
                    }
                    val flow = state.waterfall
                    if (flow != null) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(p.borderSubtle))
                        WaterfallCard(
                            flow, state.selectedEventId, hostAge = { state.elapsedOf(it) ?: 0L },
                            Modifier.width(540.dp).fillMaxHeight(), actions.waterfall,
                        )
                    } else if (target != null) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(p.borderSubtle))
                        DetailPane(
                            target, { view.duplicateMarks[it.id] }, Modifier.width(470.dp).fillMaxHeight(),
                            elapsedOf = state.elapsedOf, transitions = state.transitions, actions = actions.detail,
                            initialFind = state.detailFind,
                            burstOf = { view.burstMarks[it] },
                        )
                    }
                }
            }
        }
        WirelessSheets(state.wireless, actions.wireless)
        ShareSheet(state.share, actions.share)
        KeysSheet(state.keys, actions.correlation)
        FindSheet(state.find, actions.correlation)
        MockEditorSheet(state.advanced.editor, actions.advanced)
        ComposePushSheet(state.advanced.compose, actions.advanced)
        SaveScenarioSheet(state.advanced.saveScenario, state.advanced.location?.label ?: "this Mac", actions.advanced)
        EmulatorsSheet(state.emulators, actions.emulators)
    }
}

@Composable
private fun Sidebar(state: ScreenState, actions: ScreenActions, modifier: Modifier) {
    val p = LocalPalette.current
    val w = state.wireless
    val entries = SidebarEntries.build(state.devices, w.remembered, w.reconnecting)
    Column(modifier.background(p.bg1).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            T("DEVICES", p.textMuted, style = Type.small, weight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button("+ Add device", actions.wireless.onAddDevice)
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            T("No phone connected", p.textDim, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = Type.body)
        }
        val running = state.session as? SessionState.Running
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            entries.forEach { e -> DeviceRow(e, state, running, actions) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Link("Emulators", onClick = actions.emulators.onOpen)
            Link("How to connect a phone", onClick = actions.wireless.onHowToConnect)
            Link("Check connection", onClick = actions.wireless.onCheckConnection)
        }
        DeveloperPanel(state.advanced, state.mcp, state.serveBusy, actions.advanced)
        val adb = state.adb as? AdbStatus.Ready
        if (adb != null) {
            T(
                if (adb.downloaded) "adb: downloaded platform-tools" else "adb: ${adb.path}",
                p.textMuted, Modifier.padding(horizontal = 16.dp), style = Type.monoTiny, maxLines = 2,
            )
        }
        UpdateFooter(state.updates, actions)
    }
}

/** The app's version, the *Update available* link when GitHub has a newer Mac release, and the opt-out. */
@Composable
private fun UpdateFooter(u: UpdateUi, actions: ScreenActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val available = u.available
        if (available != null) {
            Hint("LogPose ${available.version} is out — opens the release page on GitHub.") {
                Link("Update available — Download", onClick = actions.onDownloadUpdate)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("LogPose ${u.version}", p.textMuted, style = Type.monoTiny)
            Spacer(Modifier.weight(1f))
            Hint("Once a day, LogPose asks GitHub whether a newer Mac release exists. Nothing about you, your phones or your captures is sent.") {
                LinkText(if (u.enabled) "Update check: on" else "Update check: off") { actions.onUpdatesEnabled(!u.enabled) }
            }
        }
    }
}

@Composable
private fun DeviceRow(e: SidebarEntry, state: ScreenState, running: SessionState.Running?, actions: ScreenActions) {
    val p = LocalPalette.current
    val selected = e.serial != null && e.serial == state.selectedSerial
    val capturing = e.serial != null && running?.serial == e.serial
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(if (selected) p.accentTint else p.bg1, RoundedCornerShape(6.dp))
            .clickable(enabled = e.serial != null && e.status.ready && running == null) { e.serial?.let(actions.onSelectDevice) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The one green in the palette: only for "capturing right now".
        val tint = when {
            capturing -> p.ok
            e.status.actionNeeded -> p.warn
            e.serial == null -> p.textMuted.copy(alpha = 0.7f)
            e.status.ready -> p.textMuted
            else -> p.textDim
        }
        Box(Modifier.width(14.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            if (e.transport == Transport.WIFI) WifiGlyph(tint) else Box(Modifier.padding(top = 3.dp).size(8.dp).background(tint, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            T(e.name, if (e.serial == null) p.textDim else p.text, weight = FontWeight.SemiBold)
            T(e.detail, p.textMuted, style = Type.monoTiny)
            // A guest-slimmed emulator says so wherever it's chosen: its Android isn't stock.
            state.emulators.guestBySerial[e.serial]?.let { summary ->
                Spacer(Modifier.height(3.dp))
                Hint(summary) { Pill("ANDROID SLIMMED", p.warn, stroke = p.warn) }
            }
            Spacer(Modifier.height(2.dp))
            val label = if (capturing) "Capturing" else e.status.label
            T(label, if (e.status.actionNeeded) p.warn else p.textDim, style = Type.small, maxLines = 3)
            val legacy = state.wireless.legacy?.takeIf { it.serial == e.serial }
            if (legacy != null) {
                Spacer(Modifier.height(4.dp))
                T(legacy.message, if (legacy.running || legacy.ok) p.textDim else p.warn, style = Type.small.copy(lineHeight = 15.sp), maxLines = 5)
                if (!legacy.running) Link("Dismiss", Modifier.padding(top = 2.dp), actions.wireless.onDismissLegacy)
            } else if (e.canSwitchToWireless && running == null) {
                Link("Switch to wireless", Modifier.padding(top = 4.dp)) { actions.wireless.onSwitchToWireless(e.serial!!, e.name) }
            }
            if (e.remembered != null && !capturing) {
                Link("Forget device", Modifier.padding(top = 4.dp)) { actions.wireless.onForget(e.remembered.id) }
            }
        }
    }
}

/** A quiet text action, for the sidebar's per-row verbs. */
@Composable
private fun Link(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    T(text, p.accent, modifier.clickable(onClick = onClick), style = Type.small, weight = FontWeight.Medium)
}

@Composable
private fun Toolbar(state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    val device = state.devices.firstOrNull { it.serial == state.selectedSerial }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(p.bg1).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val s = state.session) {
            is SessionState.Running -> Button("Stop session", actions.onStop)
            else -> Button(
                "Start session", actions.onStart, primary = true,
                enabled = device != null && device.ready && state.viewing == null,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button("Bookmark ⌘B", actions.onBookmark, enabled = state.canBookmark && state.pendingBookmark == null)
        Spacer(Modifier.width(8.dp))
        Button(if (state.screenshotBusy) "Screenshot…" else "Screenshot", actions.onScreenshot, enabled = state.canBookmark && !state.screenshotBusy)
        Spacer(Modifier.width(14.dp))
        val count = state.snapshot.events.size
        val label = if (state.viewing != null) "Capture file  ·  $count events" else when (val s = state.session) {
            is SessionState.Running -> "Capturing ${device?.let(DeviceStatuses::displayName) ?: s.serial}  ·  $count events"
            is SessionState.Ended -> "Session ended  ·  $count events"
            SessionState.Idle -> when {
                count > 0 -> "Stopped  ·  $count events"
                device != null -> "Ready: ${DeviceStatuses.displayName(device)}"
                state.devices.isNotEmpty() -> "${DeviceStatuses.displayName(state.devices.first())} isn't ready yet"
                else -> "No phone connected"
            }
        }
        T(label, p.textDim, Modifier.weight(1f, fill = false))
        // A session on an emulator whose Android LogPose slimmed: its Android isn't stock, so say so here too.
        val guest = (state.session as? SessionState.Running)?.let { state.emulators.guestBySerial[it.serial] }
        if (guest != null && state.viewing == null) {
            Spacer(Modifier.width(8.dp))
            Hint(guest) { Pill("ANDROID SLIMMED", p.warn, stroke = p.warn) }
        }
        Spacer(Modifier.weight(1f))
        Button("Open…", actions.onOpenFile)
        Spacer(Modifier.width(8.dp))
        Button("Share", actions.onShare, primary = state.canShare && state.session !is SessionState.Running, enabled = state.canShare)
        Spacer(Modifier.width(8.dp))
        Button("Clear", actions.onClear, enabled = state.snapshot.events.isNotEmpty() && state.viewing == null)
    }
}

/** The read-only state: which file is open, and the way back to capturing. */
@Composable
private fun ViewingBanner(v: ViewingFile, actions: ScreenActions) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(p.accentTint).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("READ-ONLY", p.accent, stroke = p.accent)
        Spacer(Modifier.width(10.dp))
        val from = v.manifest?.let { m ->
            listOfNotNull(m.app?.pkg, m.device?.model, m.device?.note?.let { "Android slimmed" }, m.scope).joinToString(" · ").takeIf { it.isNotBlank() }
        }
        T("Viewing ${v.name}" + (from?.let { "  ·  $it" } ?: ""), p.text, Modifier.weight(1f, fill = false), weight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Link("Back to live capture", Modifier, actions.onBackToLive)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

/** ⌘B's note prompt: the marker is already timed; Enter saves the note, Esc saves without one. */
@Composable
private fun BookmarkPrompt(at: Long, number: Int, actions: ScreenActions) {
    val p = LocalPalette.current
    var note by remember(at) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(p.bg1).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(24.dp).background(p.accent))
        Spacer(Modifier.width(9.dp))
        Pill("BOOKMARK $number", p.accent, stroke = p.accent)
        Spacer(Modifier.width(8.dp))
        T(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at)), p.textDim, style = Type.monoSmall)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(28.dp).background(p.bg0, RoundedCornerShape(6.dp))
                .border(1.dp, p.accent, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (note.isEmpty()) T("What happened here? (optional)", p.textMuted)
            BasicTextField(
                value = note,
                onValueChange = { note = it.replace("\n", " ") },
                singleLine = true,
                textStyle = Type.body.copy(color = p.text),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { actions.onCommitBookmark(note); true }
                        Key.Escape -> { actions.onCommitBookmark(null); true }
                        else -> false
                    }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        T("Enter to save · Esc to skip the note", p.textMuted, style = Type.small)
    }
    LaunchedEffect(at) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

@Composable
private fun NoticeStrip(n: Notice, actions: ScreenActions) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.bg1).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(n.text, if (n.error) p.warn else p.textDim, Modifier.weight(1f), maxLines = 3)
        Spacer(Modifier.width(10.dp))
        Link("Dismiss", Modifier, actions.onDismissNotice)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

@Composable
fun EmptyState(placeholder: Placeholder) {
    val p = LocalPalette.current
    val (title, body, hint) = when (placeholder) {
        Placeholder.NoAdb -> Triple("adb isn't available", "LogPose needs Android's adb tool to talk to a phone.", null)
        Placeholder.NoDevice -> Triple(
            "Connect a phone",
            "Connect an Android phone with a USB cable — it appears on the left as soon as the computer sees it. " +
                "No cable? Use + Add device to pair over Wi-Fi with a QR code.",
            "First time? On the phone: Settings → About phone → tap Build number seven times. " +
                "Then Settings → System → Developer options → turn on USB debugging. " +
                "Different on Samsung or Xiaomi — see How to connect a phone.",
        )
        Placeholder.DeviceNotReady -> Triple(
            "Your phone needs one more step",
            "It's connected, but not ready yet — follow the instruction under its name on the left.",
            "Didn't see a prompt? Unplug the cable and plug it back in with the phone unlocked.",
        )
        is Placeholder.NotStarted -> Triple(
            "Ready to capture",
            "Press Start session, then use the app on ${placeholder.deviceName}. Every request, push and event appears here as it happens.",
            null,
        )
        is Placeholder.Listening -> Triple(
            "Listening to ${placeholder.deviceName}…",
            "Use the app — requests appear here the moment it makes them.",
            null,
        )
        is Placeholder.NoLogPose -> Triple(
            "No LogPose events yet",
            "This device is connected, but no app on it is sending LogPose events. Ask for a staging build that includes LogPose.",
            "Already have one? Open the app and do something that loads data — LogPose only reports what the app does.",
        )
        is Placeholder.Ended -> Triple("Session ended", placeholder.reason, null)
        Placeholder.None -> return
    }
    val warn = placeholder is Placeholder.NoLogPose || placeholder is Placeholder.DeviceNotReady
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T(title, if (warn) p.warn else p.text, size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2,
                style = Type.body.copy(textAlign = TextAlign.Center))
            Spacer(Modifier.height(10.dp))
            T(body, p.textDim, size = 13.sp, maxLines = 6, style = Type.body.copy(textAlign = TextAlign.Center, lineHeight = 19.sp))
            if (hint != null) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.background(p.bg1, RoundedCornerShape(8.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { T(hint, p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 6) }
            }
        }
    }
}

/** The first-run screen when this Mac has no adb (PRD §4): names the source, asks before downloading. */
@Composable
private fun AdbSetup(status: AdbStatus, actions: ScreenActions) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).background(p.bg1, RoundedCornerShape(12.dp))
                .border(1.dp, p.borderSubtle, RoundedCornerShape(12.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                AdbStatus.Checking -> T("Looking for adb…", p.textDim)
                is AdbStatus.Downloading -> {
                    T("Setting up adb", p.text, size = 18.sp, weight = FontWeight.SemiBold)
                    T(status.stage, p.textDim)
                    if (status.progress != null) {
                        LinearProgressIndicator(status.progress, Modifier.fillMaxWidth(), color = p.accent, backgroundColor = p.bg3)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, backgroundColor = p.bg3)
                    }
                }
                else -> {
                    T("LogPose needs one free tool from Google", p.text, size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2)
                    T(
                        "To read what an Android phone's apps are doing, LogPose uses adb, part of Google's official " +
                            "Android platform-tools. This Mac doesn't have it yet.",
                        p.textDim, size = 13.sp, maxLines = 5, style = Type.body.copy(lineHeight = 19.sp),
                    )
                    Column(
                        Modifier.fillMaxWidth().background(p.bg2, RoundedCornerShape(8.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        T("Source", p.textMuted, style = Type.small)
                        T(PlatformTools.LATEST_ALIAS_URL, p.text, style = Type.monoSmall, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        T("Installed to", p.textMuted, style = Type.small)
                        T(AdbLocator.appSupportDir().path + "/platform-tools", p.text, style = Type.monoSmall, maxLines = 2)
                    }
                    T(
                        "About 16 MB. The download is checked against the checksum in Google's SDK manifest before " +
                            "it is used. It is covered by the Android SDK license.",
                        p.textMuted, style = Type.small.copy(lineHeight = 16.sp), maxLines = 4,
                    )
                    if (status is AdbStatus.Failed) T("Setup failed: ${status.message}", p.danger, style = Type.small, maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(if (status is AdbStatus.Failed) "Try again" else "Download platform-tools", actions.onDownloadAdb, primary = true)
                        Button("I installed Android Studio — look again", actions.onRecheckAdb)
                    }
                }
            }
        }
    }
}
