package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.AddTab
import io.github.siddharthjaswal.logpose.desktop.device.DeviceSwitcher
import io.github.siddharthjaswal.logpose.desktop.device.SwitcherItem
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette

/**
 * The main area when no device is connected: three ways in — Wi-Fi (QR), a USB cable, an emulator
 * (the first AVDs, each one click from booting lean) — and opening a capture file. Replaces a blank
 * timeline; the toolbar's device menu offers the same verbs.
 */
@Composable
fun Welcome(state: ScreenState, actions: ScreenActions, items: List<SwitcherItem>) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = maxWidth < 780.dp
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            T("Connect a device", p.text, size = 20.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            T(
                "LogPose reads what an Android app does — every request, push and event — from a phone or an emulator.",
                p.textDim, Modifier.widthIn(max = 560.dp), size = 13.sp, maxLines = 2, style = Type.body.copy(textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(24.dp))
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> WifiCard(m, actions) },
                { m -> UsbCard(m, actions) },
                { m -> EmulatorCard(m, state, actions, items) },
            )
            if (stacked) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { cards.forEach { it(Modifier.fillMaxWidth()) } }
            } else {
                Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    cards.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ico.FILE, p.textDim, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                LinkText("Open a .logpose file…", onClick = actions.onOpenFile)
                Spacer(Modifier.width(18.dp))
                Icon(Ico.PULSE, p.textDim, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                LinkText("Check connection", onClick = actions.wireless.onCheckConnection)
            }
        }
    }
}

@Composable
private fun WelcomeCard(modifier: Modifier, ico: Ico, title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.background(p.bg1, shape).border(1.dp, p.borderSubtle, shape).padding(18.dp)) {
        Box(Modifier.size(34.dp).background(p.accentTint, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Icon(ico, p.accent, size = 18.dp) }
        Spacer(Modifier.height(12.dp))
        T(title, p.text, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        T(body, p.textDim, style = Type.body.copy(lineHeight = 18.sp), maxLines = 5)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun WifiCard(modifier: Modifier, actions: ScreenActions) {
    WelcomeCard(modifier, Ico.WIFI, "Connect a phone over Wi-Fi", "On the phone, open Developer options → Wireless debugging → Pair device with QR code, and scan. Android 11 or newer.") {
        Spacer(Modifier.weight(1f))
        ToolButton("Show QR code", null, actions.wireless.onAddDevice, primary = true)
        Spacer(Modifier.height(10.dp))
        LinkText("Use a pairing code instead") { actions.wireless.onSelectTab(AddTab.CODE) }
    }
}

@Composable
private fun UsbCard(modifier: Modifier, actions: ScreenActions) {
    WelcomeCard(modifier, Ico.USB, "Use a USB cable", "Plug the phone in and tap Allow on its USB-debugging prompt — LogPose picks it up by itself. Then switch it to wireless if you like.") {
        Spacer(Modifier.weight(1f))
        ToolButton("How to connect a phone", null, actions.wireless.onHowToConnect)
        Spacer(Modifier.height(10.dp))
        LinkText("First time? Turn on developer options") { actions.wireless.onHowToConnect() }
    }
}

@Composable
private fun EmulatorCard(modifier: Modifier, state: ScreenState, actions: ScreenActions, items: List<SwitcherItem>) {
    val p = LocalPalette.current
    val e = state.emulators
    val avds = items.filterIsInstance<SwitcherItem.Avd>()
    WelcomeCard(modifier, Ico.EMULATOR, "Start an emulator", "Boot one of this Mac's virtual devices lean — with less of your Mac's memory.") {
        when {
            e.sdkMissing -> T("No Android emulator on this Mac — it comes with Android Studio's SDK.", p.textMuted, style = Type.small, maxLines = 3)
            avds.isEmpty() && e.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), color = p.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                T("Looking for virtual devices…", p.textDim, style = Type.small)
            }
            avds.isEmpty() -> T("No virtual devices yet — create one in Android Studio's Device Manager (a Google APIs image).", p.textMuted, style = Type.small, maxLines = 3)
            else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                avds.take(3).forEach { item -> WelcomeAvd(item, state, actions) }
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
        LinkText(if (avds.size > 3) "All emulators (${avds.size})…" else "All emulators…", onClick = actions.emulators.onOpen)
    }
}

@Composable
private fun WelcomeAvd(item: SwitcherItem.Avd, state: ScreenState, actions: ScreenActions) {
    val p = LocalPalette.current
    val e = state.emulators
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(item.title, p.text, weight = FontWeight.Medium)
            val launch = item.launch
            when {
                item.booting && launch != null -> T("${DeviceSwitcher.stageLabel(launch)}  ${DeviceSwitcher.launchSeconds(launch)}s", p.accent, style = Type.small)
                item.failed != null -> T(item.failed!!.message, p.danger, style = Type.small, maxLines = 2)
                else -> T(item.row.avd.apiLevel?.let { "API $it" + (item.row.avd.tagDisplay?.let { t -> " · $t" } ?: "") } ?: item.row.avd.imageLine, p.textMuted, style = Type.small)
            }
            if (item.row.slim.slimmed || item.row.guest != null || item.row.avd.playStore || item.row.avd.pageSize16k) {
                Spacer(Modifier.height(4.dp))
                AvdBadges(item.row)
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            item.booting || item.failed != null -> LinkText("Details…", onClick = actions.emulators.onShowLaunch)
            else -> SmallButton("Launch lean", accentText = true, enabled = e.emulatorPath != null && item.row.avd.name !in e.busy) { actions.emulators.onLaunchLean(item.row.avd.name) }
        }
    }
}
