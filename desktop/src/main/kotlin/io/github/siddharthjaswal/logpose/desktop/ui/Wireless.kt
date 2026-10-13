package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.AddTab
import io.github.siddharthjaswal.logpose.desktop.app.CodeForm
import io.github.siddharthjaswal.logpose.desktop.app.Sheet
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.FailReason
import io.github.siddharthjaswal.logpose.desktop.wireless.Onboarding
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingCopy
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingState
import io.github.siddharthjaswal.logpose.desktop.wireless.QrMatrix

/** The wireless verbs the sheets call; defaults are no-ops so renders and tests need none. */
class WirelessActions(
    val onAddDevice: () -> Unit = {},
    val onHowToConnect: () -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onSelectTab: (AddTab) -> Unit = {},
    val onNewQr: () -> Unit = {},
    val onFormChange: (CodeForm) -> Unit = {},
    val onPairWithCode: () -> Unit = {},
    val onSwitchToWireless: (serial: String, name: String) -> Unit = { _, _ -> },
    val onForget: (id: String) -> Unit = {},
    val onDismissLegacy: () -> Unit = {},
)

/** The modal layer over the window: the Add-device sheet or the How-to-connect checklist. */
@Composable
fun WirelessSheets(ui: WirelessUi, actions: WirelessActions) {
    when (val sheet = ui.sheet) {
        Sheet.None -> Unit
        is Sheet.AddDevice -> Modal(actions.onCloseSheet, width = 680.dp) { AddDeviceSheet(sheet.tab, ui, actions) }
        Sheet.HowToConnect -> Modal(actions.onCloseSheet, width = 640.dp) { HowToConnect(actions) }
    }
}

@Composable
internal fun Modal(onDismiss: () -> Unit, width: Dp, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (p.dark) 0.55f else 0.32f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.padding(24.dp).widthIn(max = width).fillMaxWidth()
                .background(p.bg1, RoundedCornerShape(12.dp))
                .border(1.dp, p.borderStrong, RoundedCornerShape(12.dp))
                // Swallow clicks so a click inside the card doesn't dismiss it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) { content() }
    }
}

@Composable
internal fun SheetHeader(title: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, p.text, size = 17.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Button("Close", onClose)
    }
}

// ── Add device ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddDeviceSheet(tab: AddTab, ui: WirelessUi, actions: WirelessActions) {
    val p = LocalPalette.current
    Column {
        SheetHeader("Add a device", actions.onCloseSheet)
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("Scan QR code", tab == AddTab.QR) { actions.onSelectTab(AddTab.QR) }
            TabChip("Pairing code", tab == AddTab.CODE) { actions.onSelectTab(AddTab.CODE) }
            TabChip("USB cable", tab == AddTab.CABLE) { actions.onSelectTab(AddTab.CABLE) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
        Box(Modifier.padding(24.dp)) {
            when (tab) {
                AddTab.QR -> QrTab(ui, actions)
                AddTab.CODE -> CodeTab(ui, actions)
                AddTab.CABLE -> CableTab(actions)
            }
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.height(28.dp)
            .background(if (selected) p.accentTint else p.bg1, shape)
            .border(1.dp, if (selected) p.accent else p.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, if (selected) p.text else p.textDim, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun QrTab(ui: WirelessUi, actions: WirelessActions) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Dark modules on a white plate in both themes: phone scanners expect dark-on-light, and an
        // inverted code fails on many of them.
        Box(Modifier.size(232.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            val qr = ui.qr
            // A timed-out or failed attempt has stopped listening for this code: don't leave a
            // scannable-looking code up that nothing will answer.
            val spent = ui.pairing is PairingState.Failed || ui.pairing is PairingState.TimedOut
            val paired = ui.pairing is PairingState.Connected
            if (qr != null && !spent && !paired) {
                val matrix = remember(qr.payload) { QrMatrix.encode(qr.payload) }
                QrCanvas(matrix, Modifier.size(216.dp))
            } else {
                val noMdns = (ui.pairing as? PairingState.Failed)?.reason == FailReason.MDNS_UNAVAILABLE
                val label = when {
                    paired -> "Paired"
                    noMdns -> "QR pairing unavailable"
                    spent -> "Code expired"
                    else -> "Preparing code…"
                }
                T(label, Color(0xFF6C707E))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("On the phone (Android 11 or newer):", p.text, weight = FontWeight.SemiBold)
            NumberedStep(1, "Open Settings → Developer options → Wireless debugging, and turn it on.")
            NumberedStep(2, "Tap Pair device with QR code.")
            NumberedStep(3, "Point the phone's camera at this code.")
            Spacer(Modifier.height(4.dp))
            PairingStatus(ui.pairing, ui.mdns, preparing = true)
            val state = ui.pairing
            if (state is PairingState.Connected) Row { Button("Done", actions.onCloseSheet, primary = true) }
            if (state is PairingState.TimedOut || state is PairingState.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("Try again", actions.onNewQr, primary = true)
                    Button("Pairing code", { actions.onSelectTab(AddTab.CODE) })
                    Button("Use the cable", { actions.onSelectTab(AddTab.CABLE) })
                }
            }
            T("Phone and Mac must be on the same Wi-Fi.", p.textMuted, style = Type.small)
        }
    }
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(p.bg2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            T(n.toString(), p.textDim, style = Type.small, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        T(text, p.textDim, Modifier.padding(top = 2.dp), maxLines = 3, style = Type.body.copy(lineHeight = 17.sp))
    }
}

/** The one plain-English progress line, or a problem box with the explanation. */
@Composable
private fun PairingStatus(state: PairingState?, mdns: AdbOutput.MdnsCheck?, preparing: Boolean = false) {
    val p = LocalPalette.current
    if (state == null) {
        // Only the QR tab has a preparation step (the mDNS check) before anything is shown.
        if (preparing && mdns == null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            T("Getting ready…", p.textDim)
        }
        return
    }
    val line = PairingCopy.describe(state)
    if (line.problem) {
        Column(
            Modifier.fillMaxWidth().background(p.warnPlate, RoundedCornerShape(8.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T(line.title, p.warn, weight = FontWeight.SemiBold, maxLines = 2)
            T(line.body, p.text, style = Type.small.copy(lineHeight = 16.sp), maxLines = 6)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state is PairingState.Connected) {
            Box(Modifier.size(10.dp).background(p.ok, RoundedCornerShape(5.dp)))
        } else if (state is PairingState.WaitingForScan) {
            // A countdown ring: the discovery window is finite, and the tester should see it run down.
            val left = 1f - (state.elapsedMillis.toFloat() / state.timeoutMillis).coerceIn(0f, 1f)
            CircularProgressIndicator(left, Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            T(line.title, if (state is PairingState.Connected) p.ok else p.text, weight = FontWeight.SemiBold)
            T(line.body, p.textDim, style = Type.small, maxLines = 2)
        }
    }
}

/** Draws [matrix] with a 4-module quiet zone, modules snapped to whole pixels so edges stay crisp. */
@Composable
fun QrCanvas(matrix: QrMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val quiet = 4
        val cells = matrix.size + quiet * 2
        val cell = kotlin.math.floor(minOf(size.width, size.height) / cells)
        val origin = Offset(((size.width - cell * cells) / 2f).toInt().toFloat(), ((size.height - cell * cells) / 2f).toInt().toFloat())
        drawRect(Color.White, origin, Size(cell * cells, cell * cells))
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset(origin.x + (x + quiet) * cell, origin.y + (y + quiet) * cell), Size(cell, cell))
            }
        }
    }
}

@Composable
private fun CodeTab(ui: WirelessUi, actions: WirelessActions) {
    val p = LocalPalette.current
    val form = ui.form
    val busy = ui.pairing != null && !ui.pairing.done
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!form.askConnectAddress) {
            T("When the camera can't scan, use the six-digit code instead:", p.text, weight = FontWeight.SemiBold)
            NumberedStep(1, "On the phone: Developer options → Wireless debugging → Pair device with pairing code.")
            NumberedStep(2, "Type the Wi-Fi pairing code and the IP address & port the phone shows.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("IP address & port", form.pairAddress, "192.168.1.20:37755", Modifier.weight(1.4f)) { actions.onFormChange(form.copy(pairAddress = it)) }
                Field("Pairing code", form.code, "123456", Modifier.weight(1f)) { actions.onFormChange(form.copy(code = it)) }
            }
        } else {
            T("One more step: tell LogPose where to connect", p.text, weight = FontWeight.SemiBold)
            NumberedStep(1, "Go back to the main Wireless debugging screen on the phone.")
            NumberedStep(2, "Under \"IP address & Port\" it shows something like 192.168.1.20:41234 — type that here.")
            Field("IP address & port (from the Wireless debugging screen)", form.connectAddress, "192.168.1.20:41234", Modifier.fillMaxWidth()) {
                actions.onFormChange(form.copy(connectAddress = it))
            }
        }
        if (form.error != null) T(form.error, p.danger, style = Type.small, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(if (form.askConnectAddress) "Connect" else "Pair", actions.onPairWithCode, primary = true, enabled = !busy)
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
        }
        PairingStatus(ui.pairing, ui.mdns)
        if (ui.pairing is PairingState.Connected) Row { Button("Done", actions.onCloseSheet, primary = true) }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, modifier: Modifier, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        T(label, p.textMuted, style = Type.small)
        Box(
            Modifier.fillMaxWidth().height(32.dp).background(p.bg0, RoundedCornerShape(6.dp))
                .border(1.dp, p.borderStrong, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) T(placeholder, p.textMuted, style = Type.monoSmall)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.monoSmall.copy(color = p.text, fontSize = 13.sp),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CableTab(actions: WirelessActions) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("Android 10 or older — or no Wi-Fi pairing at all", p.text, weight = FontWeight.SemiBold)
        NumberedStep(1, "Turn on USB debugging (see How to connect) and plug the phone into this Mac.")
        NumberedStep(2, "Tap Allow on the phone. It appears in the device list on the left.")
        NumberedStep(3, "To go wireless: click Switch to wireless under the phone, then unplug the cable.")
        T(
            "Switch to wireless lasts until the phone restarts. The phone must be on the same Wi-Fi as this Mac.",
            p.textMuted, style = Type.small.copy(lineHeight = 16.sp), maxLines = 3,
        )
        Row { Button("How to turn on USB debugging", actions.onHowToConnect) }
    }
}

// ── How to connect ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun HowToConnect(actions: WirelessActions) {
    val p = LocalPalette.current
    Column {
        SheetHeader("How to connect a phone", actions.onCloseSheet)
        Column(
            Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            T("Android hides these switches. Do this once per phone.", p.textDim)
            Onboarding.steps.forEach { step ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(step.title, p.text, weight = FontWeight.SemiBold, size = 14.sp)
                    T(step.body, p.textDim, maxLines = 5, style = Type.body.copy(lineHeight = 18.sp))
                    if (step.notes.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().background(p.bg2, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            step.notes.forEach { (brand, path) ->
                                Row {
                                    T(brand, p.text, Modifier.width(170.dp), style = Type.small, weight = FontWeight.SemiBold, maxLines = 2)
                                    T(path, p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 3)
                                }
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(p.warnPlate, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                T("Wireless limits", p.warn, weight = FontWeight.SemiBold)
                T(Onboarding.LIMITS, p.text, style = Type.small.copy(lineHeight = 16.sp), maxLines = 4)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Add a device", actions.onAddDevice, primary = true)
                Button("Done", actions.onCloseSheet)
            }
        }
    }
}

// ── Glyphs ──────────────────────────────────────────────────────────────────────────────────────

/** A three-arc Wi-Fi mark, tinted like the status dot it replaces. */
@Composable
fun WifiGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val w = size.width
        val stroke = w * 0.12f
        val center = Offset(w / 2f, size.height * 0.86f)
        for ((i, r) in listOf(0.78f, 0.52f).withIndex()) {
            val radius = w * r * 0.62f + i * 0f
            drawArc(
                color, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(color, radius = stroke * 0.95f, center = Offset(center.x, center.y - stroke * 0.4f))
    }
}
