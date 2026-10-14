package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi
import io.github.siddharthjaswal.logpose.desktop.app.Popover
import io.github.siddharthjaswal.logpose.desktop.device.DeviceSwitcher
import io.github.siddharthjaswal.logpose.desktop.device.SwitcherAction
import io.github.siddharthjaswal.logpose.desktop.device.SwitcherItem
import io.github.siddharthjaswal.logpose.desktop.device.SwitcherSection
import io.github.siddharthjaswal.logpose.desktop.device.Transport
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.theme.Palette

/** The switcher's rows for this frame — the same list the chip, the popover and the welcome read. */
fun switcherItems(state: ScreenState): List<SwitcherItem> = DeviceSwitcher.build(
    devices = state.devices,
    remembered = state.wireless.remembered,
    avds = state.emulators.rows,
    selectedSerial = state.selectedSerial,
    capturingSerial = (state.session as? SessionState.Running)?.serial,
    reconnecting = state.wireless.reconnecting,
    paused = state.wireless.paused,
    launch = state.emulators.launch,
    guestBySerial = state.emulators.guestBySerial,
)

/** Runs a row's primary action through the screen's verbs. */
fun runSwitcherAction(a: SwitcherAction, actions: ScreenActions, close: Boolean = true) {
    when (a) {
        is SwitcherAction.Select -> actions.onSelectDevice(a.serial)
        is SwitcherAction.LaunchLean -> actions.emulators.onLaunchLean(a.avd)
        is SwitcherAction.Reconnect -> actions.wireless.onReconnect(a.id)
        SwitcherAction.None -> return
    }
    if (close && a is SwitcherAction.Select) actions.chrome.onClosePopover()
}

private fun transportIco(t: Transport): Ico = when (t) {
    Transport.CABLE -> Ico.USB
    Transport.WIFI -> Ico.WIFI
    Transport.EMULATOR -> Ico.EMULATOR
}

/** The status dot's colour: green only for "capturing right now" (the palette's one green). */
private fun dotColor(p: Palette, item: SwitcherItem.Connected): Color = when {
    item.capturing -> p.ok
    item.entry.status.actionNeeded -> p.warn
    item.ready -> p.textDim
    else -> p.textMuted.copy(alpha = 0.6f)
}

/**
 * The toolbar's leftmost control: the device in focus (kind icon, name, status dot, chevron), or
 * *Connect a device*. A device that needs a step says it right here, in warn. Opens the switcher.
 */
@Composable
fun DeviceChip(state: ScreenState, actions: ScreenActions, items: List<SwitcherItem>) {
    val p = LocalPalette.current
    val toggle = remember { PopoverToggle() }
    val open = state.popover == Popover.SWITCHER
    val chip = DeviceSwitcher.chipItem(items)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(7.dp)
    val tip = buildString {
        append("Devices and emulators  ⌘K")
        chip?.takeIf { it.entry.status.actionNeeded }?.let { append("\n").append(it.entry.status.label) }
        chip?.guest?.let { append("\nAndroid slimmed: ").append(it) }
    }
    Box {
        Hint(if (open) null else tip) {
            Row(
                Modifier.height(30.dp).widthIn(max = 380.dp)
                    .background(if (open) p.bg3 else if (hovered) p.rowHover else p.bg2, shape)
                    .border(1.dp, if (open) p.accent else p.borderStrong, shape)
                    .focusRing(focused, shape, p.accent)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { if (toggle.allow()) actions.chrome.onTogglePopover(Popover.SWITCHER) }
                    .padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chip == null) {
                    Icon(Ico.PHONE, p.textDim, size = 15.dp)
                    Spacer(Modifier.width(8.dp))
                    T(if (items.none { it is SwitcherItem.Connected }) "Connect a device" else "Choose a device", p.text, Modifier.weight(1f, fill = false), weight = FontWeight.Medium)
                } else {
                    Icon(transportIco(chip.entry.transport), if (chip.capturing) p.ok else p.textDim, size = 15.dp)
                    Spacer(Modifier.width(8.dp))
                    T(chip.title, p.text, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold)
                    if (chip.entry.status.actionNeeded) {
                        Spacer(Modifier.width(8.dp))
                        T(chip.entry.status.label, p.warn, Modifier.widthIn(max = 180.dp), style = Type.small)
                    }
                    if (chip.guest != null) {
                        Spacer(Modifier.width(6.dp))
                        Pill("ANDROID SLIMMED", p.warn, stroke = p.warn)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).background(dotColor(p, chip), CircleShape))
                }
                Spacer(Modifier.width(6.dp))
                Icon(Ico.CHEVRON_DOWN, p.textDim, size = 12.dp)
            }
        }
        if (open) {
            SwitcherPopover(state, actions, items, onDismiss = { toggle.dismissed(); actions.chrome.onClosePopover() })
        }
    }
}

/**
 * The device switcher: search, then Connected / Emulators / Remembered Wi-Fi phones, then the
 * connection verbs. ↑/↓ move, ⏎ selects or launches, Esc closes; hover also moves the highlight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherPopover(state: ScreenState, actions: ScreenActions, items: List<SwitcherItem>, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf(state.switcherQuery) }
    val shown = DeviceSwitcher.filter(items, query)
    var picked by remember { mutableStateOf(state.switcherHighlight ?: DeviceSwitcher.initialHighlight(items)) }
    val highlight = DeviceSwitcher.keep(shown, picked)
    var fromKeys by remember { mutableStateOf(false) }
    val running = state.session is SessionState.Running
    val emulatorOk = state.emulators.emulatorPath != null
    val focus = remember { FocusRequester() }
    Popover(
        onDismiss = onDismiss, width = 440.dp,
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Popover false
            when (e.key) {
                Key.DirectionDown -> { picked = DeviceSwitcher.move(shown, highlight, 1); fromKeys = true; true }
                Key.DirectionUp -> { picked = DeviceSwitcher.move(shown, highlight, -1); fromKeys = true; true }
                Key.Enter, Key.NumPadEnter -> {
                    shown.firstOrNull { it.key == highlight }?.let { runSwitcherAction(DeviceSwitcher.primary(it, running, emulatorOk), actions) }
                    true
                }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
    ) {
        // Search
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ico.SEARCH, p.textMuted, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) T("Search devices and emulators", p.textMuted)
                BasicTextField(
                    value = query, onValueChange = { query = it.replace("\n", "") }, singleLine = true,
                    textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            T("⌘K", p.textMuted, style = Type.small)
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        PopoverDivider()
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
            val sections = DeviceSwitcher.sections(shown)
            if (sections.isEmpty()) {
                T(
                    if (query.isBlank()) "No devices yet — add one below." else "Nothing matches “${query.trim()}”",
                    p.textDim, Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
            sections.forEach { (section, rows) ->
                Caption(section.title, trailing = rows.size.toString())
                rows.forEach { item ->
                    val hl = item.key == highlight
                    SwitcherRowFrame(hl, fromKeys, onHover = { picked = item.key; fromKeys = false }, onClick = {
                        runSwitcherAction(DeviceSwitcher.primary(item, running, emulatorOk), actions)
                    }) {
                        when (item) {
                            is SwitcherItem.Connected -> ConnectedRow(item, hl, state, actions)
                            is SwitcherItem.Avd -> AvdRowView(item, hl, state.emulators, actions)
                            is SwitcherItem.Remembered -> RememberedRow(item, hl, actions)
                        }
                    }
                }
            }
            // Say why there are no emulators rather than leaving the section out silently.
            if (query.isBlank() && sections.none { it.first == SwitcherSection.EMULATORS }) {
                val e = state.emulators
                val note = when {
                    e.sdkMissing -> "No Android emulator on this Mac — it comes with Android Studio's SDK."
                    e.rows.isEmpty() && !e.loading -> "No virtual devices yet — create one in Android Studio's Device Manager."
                    e.rows.isEmpty() -> "Looking for virtual devices…"
                    else -> null
                }
                if (note != null) {
                    Caption(SwitcherSection.EMULATORS.title)
                    T(note, p.textDim, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = Type.small, maxLines = 2)
                }
            }
        }
        PopoverDivider()
        Column(Modifier.padding(vertical = 6.dp)) {
            MenuRow("Add device…", Ico.PLUS, "⌘N") { actions.chrome.onClosePopover(); actions.wireless.onAddDevice() }
            MenuRow("Manage emulators…", Ico.EMULATOR) { actions.chrome.onClosePopover(); actions.emulators.onOpen() }
            MenuRow("Check connection", Ico.PULSE) { actions.chrome.onClosePopover(); actions.wireless.onCheckConnection() }
            MenuRow("How to connect a phone", Ico.HELP) { actions.chrome.onClosePopover(); actions.wireless.onHowToConnect() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherRowFrame(highlighted: Boolean, fromKeys: Boolean, onHover: () -> Unit, onClick: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    if (hovered) LaunchedEffect(Unit) { onHover() }
    val bring = remember { BringIntoViewRequester() }
    if (highlighted && fromKeys) LaunchedEffect(Unit) { runCatching { bring.bringIntoView() } }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).bringIntoViewRequester(bring)
            .background(if (highlighted) p.rowHover else Color.Transparent, RoundedCornerShape(7.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) { content() }
}

@Composable
private fun RowIcon(ico: Ico, tint: Color) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(ico, tint, size = 16.dp) }
}

@Composable
private fun ConnectedRow(item: SwitcherItem.Connected, highlighted: Boolean, state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    val e = item.entry
    val running = state.session is SessionState.Running
    Row(verticalAlignment = Alignment.Top) {
        Box {
            RowIcon(transportIco(e.transport), if (item.capturing) p.ok else if (e.status.actionNeeded) p.warn else p.textDim)
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp).size(7.dp).background(p.bg1, CircleShape).padding(1.dp).background(dotColor(p, item), CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(item.title, p.text, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold)
                if (item.avd?.slim?.slimmed == true) Pill("SLIMMED", p.accent, stroke = p.accent)
                item.guest?.let { g -> Hint(g) { Pill("ANDROID SLIMMED", p.warn, stroke = p.warn) } }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(item.subtitle, if (item.capturing) p.ok else if (e.status.actionNeeded) p.warn else p.textDim, Modifier.weight(1f, fill = false), style = Type.small, maxLines = 2)
                Spacer(Modifier.width(8.dp))
                T(e.detail, p.textMuted, Modifier.widthIn(max = 170.dp), style = Type.monoTiny)
            }
            if (running && !item.capturing && item.ready) {
                T("Stop the session to switch to this device.", p.textMuted, Modifier.padding(top = 2.dp), style = Type.small)
            }
            val legacy = state.wireless.legacy?.takeIf { it.serial == e.serial }
            if (legacy != null) {
                Spacer(Modifier.height(4.dp))
                T(legacy.message, if (legacy.running || legacy.ok) p.textDim else p.warn, style = Type.small.copy(lineHeight = 15.sp), maxLines = 5)
                if (!legacy.running) LinkText("Dismiss", Modifier.padding(top = 2.dp), actions.wireless.onDismissLegacy)
            } else if (e.canSwitchToWireless && !running && highlighted) {
                LinkText("Switch to wireless", Modifier.padding(top = 4.dp)) { actions.wireless.onSwitchToWireless(e.serial!!, e.name) }
            }
        }
        Spacer(Modifier.width(6.dp))
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (highlighted) {
                if (e.transport == Transport.WIFI && !item.capturing) IconButton(Ico.UNLINK, "Disconnect", { actions.wireless.onDisconnect(e.serial!!) }, size = 24.dp)
                val avdName = item.avd?.avd?.name
                if (e.transport == Transport.EMULATOR && avdName != null && !item.capturing) {
                    IconButton(Ico.POWER, "Shut down this emulator", { actions.emulators.onShutDown(avdName) }, enabled = avdName !in state.emulators.busy, size = 24.dp)
                }
                if (e.remembered != null && !item.capturing) IconButton(Ico.TRASH, "Forget this phone", { actions.wireless.onForget(e.remembered.id) }, size = 24.dp)
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (item.selected) Icon(Ico.CHECK, p.accent, size = 14.dp)
            }
        }
    }
}

@Composable
fun AvdBadges(row: io.github.siddharthjaswal.logpose.desktop.app.AvdRow) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.slim.slimmed) Hint("config.ini slimmed by LogPose — Revert in Manage emulators.") { Pill("SLIMMED", p.accent, stroke = p.accent) }
        if (row.guest != null) Hint(row.guest.summary()) { Pill("ANDROID SLIMMED", p.warn, stroke = p.warn) }
        if (row.avd.playStore) Hint("A Google Play image: heavier than Google APIs.") { Pill("PLAY", p.textDim, stroke = p.borderStrong) }
        if (row.avd.pageSize16k) Hint("A 16 KB page-size image: slower and heavier.") { Pill("16 KB", p.textDim, stroke = p.borderStrong) }
    }
}

@Composable
private fun AvdRowView(item: SwitcherItem.Avd, highlighted: Boolean, ui: EmulatorsUi, actions: ScreenActions) {
    val p = LocalPalette.current
    val avd = item.row.avd
    val busy = avd.name in ui.busy
    Row(verticalAlignment = Alignment.Top) {
        RowIcon(Ico.EMULATOR, if (item.booting) p.accent else p.textMuted)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T(item.title, p.text, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold)
                AvdBadges(item.row)
            }
            Spacer(Modifier.height(2.dp))
            val launch = item.launch
            when {
                item.booting && launch != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(DeviceSwitcher.stageLabel(launch), p.text, Modifier.weight(1f, fill = false), style = Type.small)
                        Spacer(Modifier.width(6.dp))
                        T("${DeviceSwitcher.launchSeconds(launch)}s", p.textMuted, style = Type.monoTiny)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(DeviceSwitcher.launchProgress(launch), Modifier.fillMaxWidth().height(3.dp), color = p.accent, backgroundColor = p.bg3)
                }
                item.failed != null -> T(item.failed!!.message, p.danger, style = Type.small, maxLines = 2)
                else -> T(avd.imageLine, p.textMuted, style = Type.monoTiny)
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                item.booting -> LinkText("Details…") { actions.chrome.onClosePopover(); actions.emulators.onShowLaunch() }
                item.failed != null -> {
                    LinkText("Details…") { actions.chrome.onClosePopover(); actions.emulators.onShowLaunch() }
                    Spacer(Modifier.width(6.dp))
                    LinkText("Dismiss", onClick = actions.emulators.onDismissLaunch)
                }
                else -> {
                    if (busy) CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 2.dp)
                    if (highlighted) {
                        IconButton(Ico.MORE, "Launch options…", { actions.chrome.onClosePopover(); actions.emulators.onOpenLaunchOptions(avd.name) }, size = 24.dp)
                        SmallButton("Launch", tip = "Boot as Android Studio's ▶ does: the AVD's own RAM and Quick Boot snapshot.", enabled = !busy && ui.emulatorPath != null) { actions.emulators.onLaunchStock(avd.name) }
                    }
                    SmallButton("Launch lean", primary = highlighted, accentText = true, tip = "Less of your Mac's memory: -memory, host GPU, no snapshot save, no boot animation.", enabled = !busy && ui.emulatorPath != null) {
                        actions.emulators.onLaunchLean(avd.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun RememberedRow(item: SwitcherItem.Remembered, highlighted: Boolean, actions: ScreenActions) {
    val p = LocalPalette.current
    val trying = item.subtitle == "Reconnecting…"
    Row(verticalAlignment = Alignment.Top) {
        RowIcon(Ico.WIFI, p.textMuted)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            T(item.title, p.textDim, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            T(item.subtitle, p.textMuted, style = Type.small, maxLines = 2)
            T(item.entry.detail, p.textMuted, style = Type.monoTiny)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (highlighted) IconButton(Ico.TRASH, "Forget this phone", { actions.wireless.onForget(item.device.id) }, size = 24.dp)
            if (trying) CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 2.dp)
            else SmallButton("Reconnect") { actions.wireless.onReconnect(item.device.id) }
        }
    }
}

/** A compact 24px button for row actions. */
@Composable
fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, tip: String? = null, accentText: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)
    val bg = when {
        !enabled -> p.bg2
        primary -> if (hovered) p.accentHover else p.accent
        hovered -> p.bg3
        else -> p.bg2
    }
    Hint(tip) {
        Box(
            Modifier.height(24.dp).background(bg, shape)
                .border(1.dp, if (primary && enabled) Color.Transparent else p.borderStrong, shape)
                .focusRing(focused, shape, p.accent)
                .hoverable(interaction)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) { T(text, if (!enabled) p.textMuted else if (primary) p.onAccent else if (accentText) p.accent else p.text, style = Type.small, weight = FontWeight.SemiBold) }
    }
}
