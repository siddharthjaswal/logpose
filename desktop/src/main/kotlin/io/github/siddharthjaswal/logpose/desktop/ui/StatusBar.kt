package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Popover
import io.github.siddharthjaswal.logpose.desktop.device.SwitcherItem
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.desktop.device.DeviceStatuses
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette

/** What the status bar's left end says about the capture, and its dot. */
internal fun captureLine(state: ScreenState, items: List<SwitcherItem> = emptyList()): Pair<String, Boolean> {
    // An emulator reads as its AVD ("Pixel 9a"), not adb's model ("sdk_gphone64_arm64").
    fun nameOf(d: Adb.DeviceInfo) = items.filterIsInstance<SwitcherItem.Connected>().firstOrNull { it.entry.serial == d.serial }?.title ?: DeviceStatuses.displayName(d)
    val count = state.snapshot.events.size
    val device = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val events = if (count == 1) "1 event" else "$count events"
    if (state.viewing != null) return "Viewing ${state.viewing.name}  ·  $events" to false
    return when (val s = state.session) {
        is SessionState.Running -> {
            val name = state.devices.firstOrNull { it.serial == s.serial }?.let(::nameOf) ?: s.serial
            "Capturing $name  ·  $events" to true
        }
        is SessionState.Ended -> "Session ended  ·  $events" to false
        SessionState.Idle -> when {
            count > 0 -> "Stopped  ·  $events" to false
            device != null && device.ready -> "Ready: ${nameOf(device)}" to false
            device != null -> "${nameOf(device)} isn't ready yet" to false
            state.devices.isNotEmpty() -> "Choose a device" to false
            else -> "No device connected" to false
        }
    }
}

/**
 * The bottom status bar (24px): capture state and adb on the left; the MCP server, *Update
 * available* and the app's version on the right. The MCP item opens its popover.
 */
@Composable
fun StatusBar(state: ScreenState, actions: ScreenActions, items: List<SwitcherItem> = emptyList()) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(p.bg1).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (line, live) = captureLine(state, items)
        Box(Modifier.size(7.dp).background(if (live) p.ok else p.textMuted.copy(alpha = 0.6f), CircleShape))
        Spacer(Modifier.width(7.dp))
        T(line, if (live) p.text else p.textDim, Modifier.widthIn(max = 420.dp), style = Type.small)
        val guest = (state.session as? SessionState.Running)?.let { state.emulators.guestBySerial[it.serial] }
        if (guest != null && state.viewing == null) {
            Spacer(Modifier.width(8.dp))
            Hint(guest) { T("Android slimmed", p.warn, style = Type.small, weight = FontWeight.Medium) }
        }
        val adb = state.adb as? AdbStatus.Ready
        if (adb != null) {
            StatusSep()
            Hint((if (adb.downloaded) "adb (downloaded platform-tools)\n" else "adb\n") + adb.path) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("adb", p.textMuted, style = Type.small)
                    Spacer(Modifier.width(5.dp))
                    T(shortPath(adb.path), p.textDim, Modifier.widthIn(max = 320.dp), style = Type.monoTiny)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val available = state.updates.available
        if (available != null) {
            Hint("LogPose ${available.version} is out — opens the release page on GitHub.") {
                StatusItem(onClick = actions.onDownloadUpdate) {
                    Icon(Ico.BOLT, p.accent, size = 11.dp)
                    Spacer(Modifier.width(4.dp))
                    T("Update available", p.accent, style = Type.small, weight = FontWeight.Medium)
                }
            }
            StatusSep()
        }
        McpStatus(state, actions)
        StatusSep()
        T("LogPose ${state.updates.version}", p.textMuted, style = Type.monoTiny)
    }
}

@Composable
private fun StatusSep() {
    val p = LocalPalette.current
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(12.dp).background(p.borderSubtle))
}

/** A hoverable status-bar item. */
@Composable
private fun StatusItem(active: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.height(20.dp).background(if (active) p.bg3 else if (hovered) p.rowHover else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** `MCP ● :63343` when serving, `MCP off` when not; opens the MCP popover. */
@Composable
private fun McpStatus(state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    val mcp = state.mcp
    val toggle = remember { PopoverToggle() }
    val open = state.popover == Popover.MCP
    Box {
        Hint(if (open) null else if (mcp.serving) "Serving this capture to coding agents over MCP — click for the connect command." else "Serve this capture to a coding agent over MCP") {
            StatusItem(active = open, onClick = { if (toggle.allow()) actions.chrome.onTogglePopover(Popover.MCP) }) {
                T("MCP", if (mcp.serving) p.text else p.textMuted, style = Type.small, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(5.dp))
                val dot = when {
                    mcp.error != null -> p.danger
                    state.serveBusy -> p.warn
                    mcp.serving -> p.ok
                    else -> p.textMuted.copy(alpha = 0.5f)
                }
                Box(Modifier.size(6.dp).background(dot, CircleShape))
                Spacer(Modifier.width(5.dp))
                T(if (mcp.serving && mcp.port != null) ":${mcp.port}" else if (state.serveBusy) "…" else "off", p.textDim, style = Type.monoTiny)
            }
        }
        if (open) McpPopover(state, actions) { toggle.dismissed(); actions.chrome.onClosePopover() }
    }
}

/** *Serve MCP*'s one home: the switch, the ready-made `claude mcp add …` line with Copy, and the token. */
@Composable
private fun McpPopover(state: ScreenState, actions: ScreenActions, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val mcp = state.mcp
    val a = actions.advanced
    Popover(onDismiss, width = 400.dp, above = true, alignEnd = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(if (state.serveBusy) "Serve MCP…" else "Serve MCP", p.text, size = 13.sp, weight = FontWeight.SemiBold)
                    T(if (mcp.serving && mcp.port != null) "Listening on 127.0.0.1:${mcp.port}" else "Off", p.textDim, style = Type.small)
                }
                ToggleSwitch(mcp.serving) { if (!state.serveBusy) a.onServe(!mcp.serving) }
            }
            T(
                "Expose this window's live capture to a coding agent over MCP on 127.0.0.1 — so a developer can point Claude Code at this session while you test.",
                p.textDim, style = Type.small.copy(lineHeight = 15.sp), maxLines = 4,
            )
            mcp.error?.let { T(it, p.danger, style = Type.small, maxLines = 3) }
            val command = mcp.command
            if (mcp.serving && command != null) {
                mcp.portNote?.let { T(it, p.warn, style = Type.small, maxLines = 2) }
                Box(Modifier.fillMaxWidth().background(p.bg0, RoundedCornerShape(6.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(6.dp)).padding(8.dp)) {
                    T(command, p.text, style = Type.monoTiny.copy(lineHeight = 13.sp), maxLines = 6)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Copy command", filled = true) { a.onCopy(command, "MCP connect command copied — paste it in your terminal") }
                    Spacer(Modifier.width(10.dp))
                    Hint("The token both authenticates the agent and is kept between launches, so the line keeps working.") {
                        T("token ${mcp.token.take(8)}…", p.textMuted, style = Type.monoTiny)
                    }
                }
                T(
                    if (mcp.readOnly) "Agents can read everything; mocks and push stay read-only until this app controls the phone's mocks."
                    else "Agents can create mocks and inject pushes on this phone.",
                    p.textMuted, style = Type.small.copy(lineHeight = 14.sp), maxLines = 3,
                )
            }
        }
    }
}

/**
 * The toolbar's gear: the *Advanced* switch (and where keys and scenarios live), the daily update
 * check, adb's path, and a pointer to the MCP server's own popover in the status bar.
 */
@Composable
fun DeveloperButton(state: ScreenState, actions: ScreenActions) {
    val toggle = remember { PopoverToggle() }
    val open = state.popover == Popover.DEVELOPER
    Box {
        IconButton(Ico.GEAR, "Developer settings — Advanced mode, updates, adb", { if (toggle.allow()) actions.chrome.onTogglePopover(Popover.DEVELOPER) }, active = open)
        if (open) DeveloperPopover(state, actions) { toggle.dismissed(); actions.chrome.onClosePopover() }
    }
}

@Composable
private fun DeveloperPopover(state: ScreenState, actions: ScreenActions, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val ui = state.advanced
    val a = actions.advanced
    Popover(onDismiss, width = 360.dp, alignEnd = true) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T("Developer", p.text, size = 13.sp, weight = FontWeight.SemiBold)
            SettingRow("Advanced", "Mocks, scenarios and push injection — for reproducing a state. Off keeps the app a plain recorder.", ui.advanced) { a.onAdvanced(!ui.advanced) }
            if (ui.advanced) {
                val loc = ui.location
                Column(Modifier.padding(start = 0.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Hint(loc?.label) {
                        T(if (loc?.shared == true) "Keys & scenarios: ${loc.projectDir?.name}/.logpose" else "Keys & scenarios: on this Mac", p.textDim, style = Type.small, maxLines = 2)
                    }
                    Row {
                        LinkText(if (loc?.shared == true) "Change project folder…" else "Use a project folder…", onClick = a.onProjectFolder)
                        if (loc?.shared == true) { Spacer(Modifier.width(10.dp)); LinkText("Use this Mac", onClick = a.onClearProjectFolder) }
                    }
                }
            }
        }
        PopoverDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("MCP server", p.text, weight = FontWeight.Medium)
                T(if (state.mcp.serving && state.mcp.port != null) "Serving on :${state.mcp.port}" else "Off — lives in the status bar", p.textDim, style = Type.small)
            }
            LinkText("Open…") { actions.chrome.onTogglePopover(Popover.MCP) }
        }
        PopoverDivider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingRow(
                "Check for updates daily",
                "Once a day, LogPose asks GitHub whether a newer Mac release exists. Nothing about you, your phones or your captures is sent.",
                state.updates.enabled,
            ) { actions.onUpdatesEnabled(!state.updates.enabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("LogPose ${state.updates.version}", p.textMuted, style = Type.monoTiny)
                state.updates.available?.let {
                    Spacer(Modifier.width(10.dp))
                    LinkText("${it.version} is out — Download", onClick = actions.onDownloadUpdate)
                }
            }
        }
        val adb = state.adb as? AdbStatus.Ready
        if (adb != null) {
            PopoverDivider()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(if (adb.downloaded) "adb · downloaded platform-tools" else "adb", p.text, Modifier.weight(1f), weight = FontWeight.Medium)
                    LinkText("Copy path") { a.onCopy(adb.path, "adb path copied") }
                }
                T(adb.path, p.textDim, style = Type.monoTiny.copy(lineHeight = 13.sp), maxLines = 3)
            }
        }
    }
}

/** A label + explanation with a switch on the right. */
@Composable
private fun SettingRow(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            T(title, p.text, weight = FontWeight.Medium)
            T(detail, p.textDim, style = Type.small.copy(lineHeight = 15.sp), maxLines = 4)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.padding(top = 1.dp)) { ToggleSwitch(on, onToggle) }
    }
}
