package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.appcontrol.AppAction
import io.github.siddharthjaswal.logpose.appcontrol.AppCommands
import io.github.siddharthjaswal.logpose.desktop.app.AppActionsUi
import io.github.siddharthjaswal.logpose.desktop.app.Popover
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette

/** The App menu's verbs. */
class AppActions(
    val onRequest: (AppAction) -> Unit = {},
    val onPick: (String) -> Unit = {},
    val onDraft: (String) -> Unit = {},
    val onUseDraft: () -> Unit = {},
    val onRegrant: (Boolean) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancelConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** The menu bar's key equivalents, shown beside the popover rows so the two can't disagree. */
object AppShortcuts {
    const val RESTART = "⌥⌘R"
    const val CLEAR_CACHE = "⌥⌘C"
    const val CLEAR_DATA = "⌥⇧⌘C"

    fun of(action: AppAction): String = when (action) {
        AppAction.RESTART -> RESTART
        AppAction.CLEAR_CACHE -> CLEAR_CACHE
        AppAction.CLEAR_DATA -> CLEAR_DATA
    }
}

/**
 * The toolbar's "App ▾" button, beside the session controls: restart or clear the app being
 * captured. Shows the target package in its tooltip; greys out while an action runs (the strip below shows progress).
 */
@Composable
fun AppMenuButton(state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    val toggle = remember { PopoverToggle() }
    val open = state.popover == Popover.APP
    val ui = state.app
    val device = state.devices.firstOrNull { it.serial == state.selectedSerial }
    val enabled = device?.ready == true && state.viewing == null
    Box(contentAlignment = Alignment.Center) {
        ToolButton(
            "App", Ico.REFRESH, { if (toggle.allow()) actions.chrome.onTogglePopover(Popover.APP) },
            enabled = enabled && !ui.busy,
            tip = when {
                ui.busy -> ui.progress ?: "Working…"
                !enabled -> if (state.viewing != null) "Back to live capture first" else "Choose a ready device first"
                ui.pkg != null -> "Restart or clear ${ui.pkg}"
                else -> "Restart or clear the app being captured"
            },
        )
        if (open) AppMenuPopover(ui, actions) { toggle.dismissed(); actions.chrome.onClosePopover() }
    }
}

@Composable
internal fun AppMenuPopover(ui: AppActionsUi, actions: ScreenActions, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val a = actions.app
    Popover(onDismiss, width = 320.dp) {
        Caption("App", trailing = when {
            ui.target.ambiguous && ui.picked == null -> "${ui.target.announced.size} apps"
            ui.target.announced.isNotEmpty() -> "announced"
            ui.pkg != null -> "remembered"
            else -> null
        })
        when {
            ui.target.announced.size > 1 -> Column(Modifier.padding(bottom = 4.dp)) {
                ui.target.announced.forEach { pkg ->
                    MenuRow(pkg, ico = if (pkg == ui.pkg) Ico.CHECK else null) { a.onPick(pkg) }
                }
            }
            ui.target.announced.isEmpty() -> Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                T(
                    if (ui.pkg != null) "No app has announced itself yet — using the package you typed for this device."
                    else "No app has announced itself yet. Type its package (exactly as installed, e.g. com.acme.app.debug).",
                    p.textDim, style = Type.small, maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InputBox(ui.draft.ifEmpty { ui.pkg.orEmpty() }, a.onDraft, Modifier.weight(1f), placeholder = "com.acme.app.debug", mono = true)
                    Spacer(Modifier.width(6.dp))
                    Button("Use", a.onUseDraft, enabled = AppCommands.isValidPackage(ui.draft))
                }
            }
            else -> T(ui.pkg.orEmpty(), p.text, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = Type.monoSmall)
        }
        PopoverDivider()
        Column(Modifier.padding(vertical = 6.dp)) {
            val can = ui.pkg != null && !ui.busy
            AppAction.entries.forEach { action ->
                MenuRow(
                    if (action.destructive) "${action.label}…" else action.label,
                    ico = if (action.destructive) Ico.TRASH else Ico.REFRESH,
                    shortcut = AppShortcuts.of(action), enabled = can,
                ) { onDismiss(); a.onRequest(action) }
            }
        }
        PopoverDivider()
        T(
            "Each one stops the app and launches it again, as a new run; the timeline marks the moment. " +
                "Cache clearing needs a debuggable build.",
            p.textMuted, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = Type.small, maxLines = 3,
        )
    }
}

/** The confirm before `pm clear`: what is lost, said plainly, and the re-grant option. */
@Composable
fun AppConfirmSheet(ui: AppActionsUi, actions: ScreenActions) {
    val pkg = ui.confirm ?: return
    val p = LocalPalette.current
    val a = actions.app
    Modal(a.onCancelConfirm, width = 520.dp) {
        Column(Modifier.padding(bottom = 16.dp)) {
            SheetHeader("Clear all data for this app?", a.onCancelConfirm)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T(pkg, p.text, style = Type.monoSmall, weight = FontWeight.Medium)
                T(AppAction.CLEAR_DATA_WARNING, p.text, maxLines = 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(ui.regrant) { a.onRegrant(!ui.regrant) }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        T("Re-grant runtime permissions afterwards", p.text, style = Type.body)
                        T("Reads the ones granted now and grants them back, so the app doesn't open on permission prompts. Special access (overlay, exact alarms) isn't covered.", p.textMuted, style = Type.small, maxLines = 3)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Button("Cancel", a.onCancelConfirm)
                    Spacer(Modifier.width(8.dp))
                    DangerButton("Clear all data & restart", a.onConfirm)
                }
            }
        }
    }
}

/** The one red-filled button: only ever on a confirm that deletes something. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(
        Modifier.height(28.dp).background(p.danger, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, p.onAccent, style = Type.body, weight = FontWeight.Medium) }
}

/**
 * Under the toolbar while an action runs ("Stopping…", "Clearing cache…", "Launching…") and after it,
 * with what happened — and, when a cache clear met a release build, the way forward: the full clear,
 * which still goes through its confirm. Never escalated by itself.
 */
@Composable
fun AppActionStrip(ui: AppActionsUi, actions: ScreenActions) {
    val p = LocalPalette.current
    val a = actions.app
    val result = ui.result
    if (!ui.busy && result == null) return
    Row(
        Modifier.fillMaxWidth().background(if (result?.error == true) p.bg2 else p.bg1).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("APP", p.accent, stroke = p.accent)
        Spacer(Modifier.width(10.dp))
        if (ui.busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            T("${ui.running?.label ?: ""} · ${ui.pkg.orEmpty()} — ${ui.progress ?: "Working…"}", p.text, Modifier.weight(1f), maxLines = 1)
        } else if (result != null) {
            T(result.text, if (result.error) p.warn else p.textDim, Modifier.weight(1f), maxLines = 3, size = 12.sp)
            Spacer(Modifier.width(10.dp))
            if (result.offerClearAll) {
                Button("Clear all data & restart…", { a.onRequest(AppAction.CLEAR_DATA) })
                Spacer(Modifier.width(10.dp))
            }
            LinkText("Dismiss", onClick = a.onDismiss)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}
