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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.AvdRow
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorPanel
import io.github.siddharthjaswal.logpose.desktop.app.EmulatorsUi
import io.github.siddharthjaswal.logpose.desktop.app.LaunchUi
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaks
import io.github.siddharthjaswal.logpose.desktop.emulators.ImageKind
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchPlan
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette

/** The Emulators sheet's verbs; defaults are no-ops so renders need none. */
class EmulatorActions(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onSlim: (avd: String) -> Unit = {},
    val onSlimRam: (Int) -> Unit = {},
    val onConfirmSlim: () -> Unit = {},
    val onRevert: (avd: String) -> Unit = {},
    val onLaunch: (avd: String) -> Unit = {},
    val onLaunchOptions: (EmulatorPanel.LaunchOptions) -> Unit = {},
    val onConfirmLaunch: () -> Unit = {},
    val onDismissLaunch: () -> Unit = {},
    val onShutDown: (avd: String) -> Unit = {},
    val onUndoGuest: (avd: String) -> Unit = {},
)

private const val STUDIO_LINE = "Android Studio's ▶ boots from this same config.ini, so its launches get slimmer too (down to the emulator's RAM floor)."

@Composable
fun EmulatorsSheet(ui: EmulatorsUi, actions: EmulatorActions) {
    if (!ui.open) return
    Modal(actions.onClose, width = 780.dp) {
        Column {
            SheetHeader("Emulators", actions.onClose)
            Column(
                Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ui.message?.let { MessageLine(it, ui.messageIsError, actions) }
                when (val panel = ui.panel) {
                    EmulatorPanel.Overview -> Overview(ui, actions)
                    is EmulatorPanel.SlimConfirm -> ui.row(panel.avd)?.let { SlimConfirm(it, panel, actions) }
                    is EmulatorPanel.LaunchOptions -> ui.row(panel.avd)?.let { LaunchOptionsView(it, panel, ui, actions) }
                    is EmulatorPanel.Launching -> ui.launch?.let { LaunchProgress(it, actions) }
                }
            }
        }
    }
}

@Composable
private fun MessageLine(text: String, error: Boolean, actions: EmulatorActions) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(if (error) p.warnTint else p.accentTint, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, p.text, Modifier.weight(1f), style = Type.small.copy(lineHeight = 16.sp), maxLines = 5)
        Spacer(Modifier.width(10.dp))
        LinkText("Dismiss", onClick = actions.onDismissMessage)
    }
}

// ── The list ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Overview(ui: EmulatorsUi, actions: EmulatorActions) {
    val p = LocalPalette.current
    T(
        "Run Android emulators with less of your Mac's memory. LogPose edits only the AVD's own config.ini " +
            "(keeping a backup beside it) and never touches the SDK.",
        p.textDim, maxLines = 2, style = Type.body.copy(lineHeight = 18.sp),
    )
    when {
        ui.loading && ui.rows.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(13.dp), color = p.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            T("Looking for virtual devices…", p.textDim)
        }
        ui.sdkMissing && ui.rows.isEmpty() -> EmptyEmulators(
            "No Android emulator on this Mac",
            "LogPose runs the emulator that comes with Android Studio's SDK (sdk/emulator/emulator). " +
                "Install Android Studio, then create a virtual device in its Device Manager — pick a Google APIs image.",
        )
        ui.rows.isEmpty() -> EmptyEmulators(
            "No virtual devices yet",
            "Create one in Android Studio's Device Manager; pick a Google APIs image. " +
                "It has Firebase, FCM and Maps, and runs lighter than a Google Play or 16 KB page-size image.",
            ui.avdHome?.let { "Looked in $it" },
        )
        else -> {
            ui.rows.forEach { AvdCard(it, ui, actions) }
            T(STUDIO_LINE, p.textMuted, style = Type.small, maxLines = 2)
        }
    }
    if (!ui.sdkMissing && ui.emulatorPath != null) T("emulator: ${ui.emulatorPath}", p.textMuted, style = Type.monoTiny, maxLines = 2)
}

@Composable
private fun EmptyEmulators(title: String, body: String, foot: String? = null) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().background(p.bg2, RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(title, p.text, size = 15.sp, weight = FontWeight.SemiBold)
        T(body, p.textDim, style = Type.body.copy(lineHeight = 18.sp), maxLines = 4)
        foot?.let { T(it, p.textMuted, style = Type.monoTiny, maxLines = 2) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvdCard(row: AvdRow, ui: EmulatorsUi, actions: EmulatorActions) {
    val p = LocalPalette.current
    val avd = row.avd
    val busy = avd.name in ui.busy
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().background(p.bg0, shape).border(1.dp, if (row.isRunning) p.borderStrong else p.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (row.isRunning) p.accent else p.textMuted.copy(alpha = 0.6f), CircleShape))
            Spacer(Modifier.width(8.dp))
            T(avd.displayName, p.text, size = 13.5.sp, weight = FontWeight.SemiBold)
            if (avd.displayName.replace(' ', '_') != avd.name) {
                Spacer(Modifier.width(8.dp))
                T(avd.name, p.textMuted, style = Type.monoTiny)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.slim.slimmed) Pill("SLIMMED", p.accent, stroke = p.accent)
                if (row.guest != null) Hint(row.guest.summary()) { Pill("ANDROID SLIMMED", p.warn, stroke = p.warn) }
                if (row.isRunning) Pill("RUNNING" + (row.running?.serial?.let { " · $it" } ?: ""), p.text, fill = p.accentTint)
                else Pill("STOPPED", p.textMuted, stroke = p.borderStrong)
            }
        }
        T(avd.imageLine, p.textDim, style = Type.monoSmall)
        val was = row.slim.originalRamMb?.takeIf { row.slim.slimmed && it != avd.ramMb }
        T(avd.configLine + (was?.let { "  (RAM was $it MB)" } ?: ""), p.text, style = Type.monoSmall)
        val mem = row.running?.memory
        if (row.isRunning) Row(verticalAlignment = Alignment.CenterVertically) {
            T("Host memory", p.textDim, style = Type.small)
            Spacer(Modifier.width(8.dp))
            T(mem?.let { "%,d MB".format(it.mb) } ?: "measuring…", p.text, style = Type.monoSmall.copy(fontSize = 13.sp), weight = FontWeight.Bold)
            mem?.let { Spacer(Modifier.width(8.dp)); T(it.source.label, p.textMuted, style = Type.small) }
        }
        val floor = row.ramFloorMb
        val target = row.slim.slimmedToMb ?: avd.ramMb
        if (floor != null && target != null && floor > target) {
            Note(
                "The emulator raised RAM to $floor MB at its last LogPose launch — its floor for this image and screen. " +
                    "Launch lean goes below it with -lowram; Android Studio's ▶ doesn't, so there it stays at $floor MB.",
            )
        }
        ImageKind.warnings(avd).forEach { w -> Warn(w.title, w.detail) }
        if (row.slim.editedSince) Note("config.ini changed after LogPose slimmed it (Android Studio's Device Manager?). Revert brings back the original and drops those changes too.")
        if (row.guest != null) Note(
            "Android slimmed: ${row.guest.packages.size} background packages disabled, cached processes capped at 4. " +
                "It persists across reboots (Android Studio's launches too) until you undo it.",
        )
        Spacer(Modifier.height(2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button("Launch lean", { actions.onLaunch(avd.name) }, primary = !row.isRunning, enabled = !row.isRunning && !busy && ui.emulatorPath != null)
            if (row.slim.slimmed) Button("Revert", { actions.onRevert(avd.name) }, enabled = !row.isRunning && !busy)
            else Button("Slim this AVD…", { actions.onSlim(avd.name) }, enabled = !row.isRunning && !busy)
            if (row.isRunning) Button("Shut down", { actions.onShutDown(avd.name) }, enabled = !busy && row.running?.serial != null)
            if (row.guest != null) Button("Undo Android tweaks", { actions.onUndoGuest(avd.name) }, enabled = row.isRunning && !busy)
            if (busy) CircularProgressIndicator(Modifier.padding(top = 6.dp).size(14.dp), color = p.accent, strokeWidth = 2.dp)
        }
        if (row.isRunning) T("Shut it down to slim or revert — the emulator reads config.ini only when it starts.", p.textMuted, style = Type.small, maxLines = 2)
        else if (row.guest != null) T("Undo works over adb, so start the emulator first.", p.textMuted, style = Type.small)
    }
}

@Composable
private fun Note(text: String) {
    val p = LocalPalette.current
    T(text, p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 4)
}

@Composable
private fun Warn(title: String, detail: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.warnTint, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp)) {
        T("!", p.warn, Modifier.width(14.dp), weight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, p.warn, style = Type.small, weight = FontWeight.SemiBold, maxLines = 2)
            T(detail, p.textDim, style = Type.small.copy(lineHeight = 15.sp), maxLines = 4)
        }
    }
}

// ── Slim confirm ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RamChoice(selected: Int, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AvdSlim.RAM_CHOICES.forEach { mb ->
            ToggleChip("$mb MB" + if (mb == AvdSlim.DEFAULT_RAM) " (default)" else "", p.accent, selected == mb, { onPick(mb) })
        }
    }
}

@Composable
private fun SlimConfirm(row: AvdRow, panel: EmulatorPanel.SlimConfirm, actions: EmulatorActions) {
    val p = LocalPalette.current
    val avd = row.avd
    T("Slim ${avd.displayName}", p.text, size = 15.sp, weight = FontWeight.SemiBold)
    T("RAM for the emulator", p.textDim, style = Type.small)
    RamChoice(panel.ramMb, actions.onSlimRam)
    Column(Modifier.fillMaxWidth().border(1.dp, p.borderSubtle, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().background(p.bg2).padding(horizontal = 12.dp, vertical = 6.dp)) {
            T("config.ini key", p.textMuted, Modifier.weight(1.3f), style = Type.small)
            T("now", p.textMuted, Modifier.weight(1f), style = Type.small)
            T("after", p.textMuted, Modifier.weight(1f), style = Type.small)
        }
        when {
            panel.loading -> T("Reading config.ini…", p.textDim, Modifier.padding(12.dp))
            panel.changes.isEmpty() -> T("Nothing to change — it's already this slim.", p.textDim, Modifier.padding(12.dp))
            else -> panel.changes.forEach { c ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
                    T(c.key, p.text, Modifier.weight(1.3f), style = Type.monoSmall)
                    T(c.before ?: "(not set)", p.textDim, Modifier.weight(1f), style = Type.monoSmall)
                    T(c.after, p.text, Modifier.weight(1f), style = Type.monoSmall, weight = FontWeight.Bold)
                }
            }
        }
    }
    val bullets = buildList {
        add(
            panel.reuseBackup?.let { "The original from the first slim stays in $it; Revert restores it byte for byte." }
                ?: "Before the first change LogPose copies config.ini to config.ini.logpose-backup-<date-time> beside it; Revert restores it byte for byte.",
        )
        add(STUDIO_LINE + " Every other line of the file stays exactly as it is.")
        row.ramFloorMb?.takeIf { it > panel.ramMb }?.let {
            add("This AVD's RAM floor is $it MB: below it, only Launch lean (-lowram) gets the lower RAM; Android Studio's ▶ still boots $it MB" +
                (if (panel.changes.any { c -> c.key.startsWith("hw.gpu") }) ", but keeps the GPU change." else "."))
        }
        if (avd.hasQuickBootSnapshot) add("It has a Quick Boot snapshot taken with the old RAM. Boot cold once after slimming — Launch lean does it for you; in Android Studio use Device Manager → Cold Boot Now.")
        else add("No Quick Boot snapshot, so the next launch boots with the new settings. (hardware-qemu.ini is regenerated from config.ini at every launch, so it never overrides it.)")
        add("Left alone on purpose: vm.heapSize (the app's own heap limit — lowering it brings OutOfMemoryErrors a phone wouldn't), CPU cores, camera and audio, and Quick Boot settings.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { bullets.forEach { Bullet(it) } }
    ImageKind.warnings(avd).forEach { w -> Warn(w.title, w.detail) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button("Slim", actions.onConfirmSlim, primary = true, enabled = !panel.loading && panel.changes.isNotEmpty())
        Button("Cancel", actions.onBack)
    }
}

@Composable
private fun Bullet(text: String) {
    val p = LocalPalette.current
    Row {
        T("•", p.textMuted, Modifier.width(14.dp), style = Type.small)
        T(text, p.textDim, Modifier.weight(1f), style = Type.small.copy(lineHeight = 16.sp), maxLines = 4)
    }
}

// ── Launch options ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun OptionRow(on: Boolean, title: String, detail: String?, onToggle: () -> Unit, enabled: Boolean = true) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp)) { if (enabled) ToggleSwitch(on, onToggle) else ToggleSwitch(false) {} }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, if (enabled) p.text else p.textMuted, weight = FontWeight.Medium, maxLines = 3, style = Type.body.copy(lineHeight = 17.sp))
            detail?.let { T(it, p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 5) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchOptionsView(row: AvdRow, o: EmulatorPanel.LaunchOptions, ui: EmulatorsUi, actions: EmulatorActions) {
    val p = LocalPalette.current
    T("Launch ${row.avd.displayName} lean", p.text, size = 15.sp, weight = FontWeight.SemiBold)
    T("RAM for this run (-memory)", p.textDim, style = Type.small)
    RamChoice(o.ramMb) { actions.onLaunchOptions(o.copy(ramMb = it)) }
    OptionRow(
        o.coldBoot, "Cold boot — skip the Quick Boot snapshot",
        if (row.avd.hasQuickBootSnapshot) "Its snapshot was taken with the old settings; resuming it would bring them back."
        else "This AVD has no Quick Boot snapshot, so every boot is a cold boot anyway.",
        { actions.onLaunchOptions(o.copy(coldBoot = !o.coldBoot)) },
    )
    val hasLowRam = "-lowram" in ui.supported
    OptionRow(
        o.lowRam && hasLowRam, "Go below the emulator's RAM floor (-lowram)",
        if (!hasLowRam) "This emulator doesn't offer -lowram." else
            "Without it the emulator raises RAM to its floor for the image (2560 MB for a Pixel 9a on API 36) and ignores a lower setting. " +
                "On some images it also makes Android report a low-RAM device; LogPose checks after boot and tells you.",
        { actions.onLaunchOptions(o.copy(lowRam = !o.lowRam)) }, enabled = hasLowRam,
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
    OptionRow(
        o.guest,
        "Also slim Android itself (disables background Google services; changes how your app behaves under memory pressure)",
        "Off by default. Disables ${GuestTweaks.ALL_PACKAGES.size} background packages where installed and caps cached processes at 4. " +
            "Recorded per AVD; Undo Android tweaks puts back exactly what changed. It persists across reboots until undone, " +
            "and captures from this emulator say so.",
        { actions.onLaunchOptions(o.copy(guest = !o.guest)) },
    )
    Row(Modifier.padding(start = 46.dp)) {
        LinkText(if (o.showPackages) "Hide the package list" else "Show the ${GuestTweaks.ALL_PACKAGES.size} packages") {
            actions.onLaunchOptions(o.copy(showPackages = !o.showPackages))
        }
    }
    if (o.showPackages) {
        Column(
            Modifier.padding(start = 46.dp).fillMaxWidth().background(p.bg0, RoundedCornerShape(6.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(6.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GuestTweaks.PACKAGES.forEach { (group, pkgs) ->
                T(group, p.text, style = Type.small, weight = FontWeight.SemiBold)
                T(pkgs.joinToString("  "), p.textDim, style = Type.monoTiny.copy(lineHeight = 14.sp), maxLines = 6)
            }
            T(
                "Never touched: Google Play services (FCM, Auth, Maps SDK), WebView, Chrome, Play Store, Bluetooth, and the apps your app hands off to — " +
                    "dialer, contacts, SMS, Maps, camera, photos, mail, calendar, Drive, text-to-speech. Animations stay at 1×.",
                p.textMuted, style = Type.small.copy(lineHeight = 15.sp), maxLines = 4,
            )
        }
    }
    val preview = LaunchPlan.build(LaunchPlan.Options(o.avd, 5554, o.ramMb, o.coldBoot, o.lowRam && hasLowRam), ui.supported)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T("Runs (detached — it keeps running after LogPose quits; the port is the first free one):", p.textMuted, style = Type.small)
        T(preview.commandLine("emulator"), p.text, Modifier.fillMaxWidth().background(p.bg0, RoundedCornerShape(6.dp)).padding(8.dp), style = Type.monoSmall, maxLines = 3)
        if (preview.skipped.isNotEmpty()) T("This emulator doesn't offer ${preview.skipped.joinToString(", ")}, so it's left out.", p.textMuted, style = Type.small)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button("Launch", actions.onConfirmLaunch, primary = true)
        Button("Cancel", actions.onBack)
    }
}

// ── Launch progress ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LaunchProgress(l: LaunchUi, actions: EmulatorActions) {
    val p = LocalPalette.current
    T("Launching ${l.avd}", p.text, size = 15.sp, weight = FontWeight.SemiBold)
    val order = buildList {
        add("Starting" to "Starting the emulator")
        add("WaitingForAdb" to "Waiting for adb to see ${l.serial}")
        add("Booting" to "Booting Android")
        if (l.guest) add("Tweaking" to "Slimming Android (background packages, cached processes)")
        add("Ready" to "Ready — ${l.serial} is in the device list")
    }
    val failed = l.stage as? LaunchStage.Failed
    val current = l.stage::class.simpleName
    val currentIndex = order.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: order.indexOfLast { it.first in l.stageAt }
    Column(Modifier.fillMaxWidth().border(1.dp, p.borderSubtle, RoundedCornerShape(8.dp)).padding(vertical = 4.dp)) {
        order.forEachIndexed { i, (key, label) ->
            val done = i < currentIndex || (key == "Ready" && l.stage is LaunchStage.Ready)
            val active = i == currentIndex && failed == null && l.stage !is LaunchStage.Ready
            val at = l.stageAt[key]
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    when {
                        done -> T("✓", p.textDim, weight = FontWeight.Bold)
                        active -> CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 2.dp)
                        failed != null && i == currentIndex -> T("!", p.danger, weight = FontWeight.Bold)
                        else -> Box(Modifier.size(6.dp).background(p.borderStrong, CircleShape))
                    }
                }
                Spacer(Modifier.width(8.dp))
                T(label, if (done || active) p.text else p.textMuted, Modifier.weight(1f))
                if (at != null) {
                    val end = if (active) l.nowMillis else order.getOrNull(i + 1)?.first?.let { l.stageAt[it] } ?: at
                    val secs = ((end - at).coerceAtLeast(0)) / 1000
                    if (secs > 0 || active) T("${secs}s", p.textMuted, style = Type.monoSmall)
                }
            }
        }
    }
    T(l.command, p.textDim, Modifier.fillMaxWidth().background(p.bg0, RoundedCornerShape(6.dp)).padding(8.dp), style = Type.monoSmall, maxLines = 3)
    if (failed != null) {
        T(failed.message, p.danger, maxLines = 3)
        failed.logTail?.takeIf { it.isNotBlank() }?.let {
            T(it, p.textDim, Modifier.fillMaxWidth().background(p.bg0, RoundedCornerShape(6.dp)).padding(8.dp), style = Type.monoTiny.copy(lineHeight = 13.sp), maxLines = 14)
        }
    }
    if (l.stage is LaunchStage.Ready) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Bullet("Select ${l.serial} on the left and press Start session — capture works as with any device.")
            l.lowRamDevice?.let { low ->
                Bullet(if (low) "Android reports a low-RAM device here (ro.config.low_ram=true): apps that check isLowRamDevice() behave as on Android Go phones." else "Android does not report a low-RAM device (ro.config.low_ram unset), so apps see a normal phone.")
            }
            l.ramFloorMb?.let { Bullet("The emulator raised RAM to $it MB — this image's floor. Turn on -lowram to go below it.") }
            l.tweakMessage?.let { Bullet(it) }
            Bullet("The emulator is detached: quitting LogPose leaves it running. Shut it down from this panel or close its window.")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val finished = l.stage is LaunchStage.Ready || failed != null
        Button(if (finished) "Done" else "Back to emulators", actions.onDismissLaunch, primary = finished)
    }
    if (l.stage !is LaunchStage.Ready && failed == null) T("Leaving this view doesn't stop the launch.", p.textMuted, style = Type.small)
}
