package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import io.github.siddharthjaswal.logpose.desktop.app.DoctorUi
import io.github.siddharthjaswal.logpose.desktop.app.Sheet
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.Check
import io.github.siddharthjaswal.logpose.desktop.wireless.CheckId
import io.github.siddharthjaswal.logpose.desktop.wireless.FailReason
import io.github.siddharthjaswal.logpose.desktop.wireless.FixAction
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingProgress
import io.github.siddharthjaswal.logpose.desktop.wireless.Verdict
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
    /** Opens the Check-connection sheet (and runs the doctor). */
    val onCheckConnection: () -> Unit = {},
    /** Runs the doctor again inside the sheet. */
    val onRunCheck: () -> Unit = {},
    val onCopyDiagnostics: () -> Unit = {},
    val onToggleReport: () -> Unit = {},
    /** A finding's fix button. */
    val onFix: (FixAction) -> Unit = {},
    /** *Enter IP & port manually* — the connect-only form. */
    val onConnectByIp: () -> Unit = {},
    val onPairInstead: () -> Unit = {},
    /** *Disconnect* a Wi-Fi phone (a remembered one stays offline until *Reconnect*). */
    val onDisconnect: (serial: String) -> Unit = {},
    /** *Reconnect* an offline remembered phone now. */
    val onReconnect: (id: String) -> Unit = {},
)

/** The modal layer over the window: the Add-device sheet or the How-to-connect checklist. */
@Composable
fun WirelessSheets(ui: WirelessUi, actions: WirelessActions) {
    when (val sheet = ui.sheet) {
        Sheet.None -> Unit
        is Sheet.AddDevice -> Modal(actions.onCloseSheet, width = 720.dp) { AddDeviceSheet(sheet.tab, ui, actions) }
        Sheet.HowToConnect -> Modal(actions.onCloseSheet, width = 640.dp) { HowToConnect(actions) }
        Sheet.ConnectionCheck -> Modal(actions.onCloseSheet, width = 720.dp) { ConnectionCheckSheet(ui.check, actions) }
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Dark modules on a white plate in both themes: phone scanners expect dark-on-light, and an
            // inverted code fails on many of them.
            Box(Modifier.size(232.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, p.borderSubtle, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                val qr = ui.qr
                // A timed-out or failed attempt has stopped listening for this code: don't leave a
                // scannable-looking code up that nothing will answer.
                val spent = ui.pairing is PairingState.Failed || ui.pairing is PairingState.TimedOut
                val paired = ui.pairing is PairingState.Connected
                // Once the phone has answered, the code has done its job.
                val scanned = ui.pairing is PairingState.Pairing || ui.pairing is PairingState.Connecting
                if (qr != null && !spent && !paired && !scanned) {
                    val matrix = remember(qr.payload) { QrMatrix.encode(qr.payload) }
                    QrCanvas(matrix, Modifier.size(216.dp))
                } else {
                    val noMdns = (ui.pairing as? PairingState.Failed)?.reason == FailReason.MDNS_UNAVAILABLE
                    val label = when {
                        paired -> "Paired"
                        scanned -> "Scanned"
                        noMdns -> "QR pairing unavailable"
                        spent -> "Code expired"
                        else -> "Preparing code…"
                    }
                    T(label, Color(0xFF6C707E))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val state = ui.pairing
                val found = state != null && state !is PairingState.WaitingForScan
                if (!found) {
                    T("On the phone (Android 11 or newer):", p.text, weight = FontWeight.SemiBold)
                    NumberedStep(1, "Open Settings → Developer options → Wireless debugging, and turn it on.")
                    NumberedStep(2, "Tap Pair device with QR code.")
                    NumberedStep(3, "Point the phone's camera at this code.")
                    Spacer(Modifier.height(2.dp))
                }
                PairingProgressView(ui, preparing = true)
                if (state is PairingState.Connected) Row { Button("Done", actions.onCloseSheet, primary = true) }
                T("Phone and Mac must be on the same Wi-Fi.", p.textMuted, style = Type.small)
            }
        }
        val state = ui.pairing
        if (ui.stall != null || state is PairingState.TimedOut || state is PairingState.Failed) {
            StallPanel(ui, actions, onQr = true)
        }
    }
}

/**
 * The staged progress (each stage with its time), or the problem box once the attempt failed.
 * Falls back to the single line when there's no trace (renders of older states).
 */
@Composable
private fun PairingProgressView(ui: WirelessUi, preparing: Boolean) {
    val state = ui.pairing
    val rows = PairingProgress.rows(state, ui.trace, ui.nowMillis)
    if (rows.isEmpty() || state == null || PairingCopy.describe(state).problem) {
        PairingStatus(state, ui.mdns, preparing)
        return
    }
    PairingStages(rows, state)
}

@Composable
private fun PairingStages(rows: List<PairingProgress.Row>, state: PairingState) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    when (r.status) {
                        PairingProgress.Status.DONE ->
                            if (r.stage == io.github.siddharthjaswal.logpose.desktop.wireless.Stage.CONNECTED) Box(Modifier.size(10.dp).background(p.ok, RoundedCornerShape(5.dp)))
                            else CheckMark(p.textDim)
                        PairingProgress.Status.ACTIVE ->
                            if (state is PairingState.WaitingForScan) {
                                val left = 1f - (state.elapsedMillis.toFloat() / state.timeoutMillis).coerceIn(0f, 1f)
                                CircularProgressIndicator(left, Modifier.size(13.dp), color = p.accent, strokeWidth = 2.dp)
                            } else CircularProgressIndicator(Modifier.size(13.dp), color = p.accent, strokeWidth = 2.dp)
                        PairingProgress.Status.FAILED -> Box(Modifier.size(9.dp).background(p.warn, RoundedCornerShape(5.dp)))
                        PairingProgress.Status.PENDING -> Box(Modifier.size(7.dp).border(1.dp, p.textMuted, RoundedCornerShape(4.dp)))
                    }
                }
                Spacer(Modifier.width(8.dp))
                val color = when (r.status) {
                    PairingProgress.Status.ACTIVE -> p.text
                    PairingProgress.Status.DONE -> if (r.stage == io.github.siddharthjaswal.logpose.desktop.wireless.Stage.CONNECTED) p.ok else p.textDim
                    PairingProgress.Status.FAILED -> p.warn
                    PairingProgress.Status.PENDING -> p.textMuted
                }
                T(r.label, color, Modifier.weight(1f), weight = if (r.status == PairingProgress.Status.ACTIVE || r.stage == io.github.siddharthjaswal.logpose.desktop.wireless.Stage.CONNECTED && r.status == PairingProgress.Status.DONE) FontWeight.SemiBold else null)
                r.elapsedMillis?.let { T(PairingProgress.formatElapsed(it), p.textMuted, style = Type.monoTiny) }
            }
        }
        if (state is PairingState.WaitingForScan) {
            T("${(state.timeoutMillis - state.elapsedMillis).coerceAtLeast(0) / 1000}s left on this code", p.textMuted, Modifier.padding(start = 24.dp), style = Type.small)
        }
    }
}

/** A small tick for finished stages and passing checks. */
@Composable
private fun CheckMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(11.dp)) {
        val w = size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, w * 0.55f); lineTo(w * 0.40f, w * 0.82f); lineTo(w * 0.90f, w * 0.22f)
        }
        drawPath(path, color, style = Stroke(w * 0.16f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/**
 * Under the QR code once pairing stalls (no new stage for 15 s) or ends badly: what the connection
 * doctor found — the top finding in plain words — and every way forward.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StallPanel(ui: WirelessUi, actions: WirelessActions, onQr: Boolean) {
    val p = LocalPalette.current
    val stall = ui.stall
    val state = ui.pairing
    val failed = state is PairingState.TimedOut || state is PairingState.Failed
    val top = stall?.result?.top
    val serious = top != null && top.verdict.rank <= Verdict.WARNING.rank
    Column(
        Modifier.fillMaxWidth()
            .background(if (serious) p.warnPlate else p.bg2, RoundedCornerShape(8.dp))
            .border(1.dp, p.borderSubtle, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val stuckFor = ui.trace?.let { (ui.nowMillis - it.lastProgressAt).coerceAtLeast(0) }
        val heading = when {
            failed -> "What LogPose found"
            stuckFor != null && stuckFor >= 1_000 -> "No progress for ${PairingProgress.formatElapsed(stuckFor)} — LogPose checked your connection"
            else -> "LogPose checked your connection"
        }
        T(heading, p.textMuted, style = Type.small, weight = FontWeight.SemiBold)
        when {
            stall == null || (stall.running && stall.result == null) -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), color = p.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                T(if (stall == null) "Checking the network…" else "Checking your Wi-Fi, VPN and adb…", p.textDim)
            }
            top == null -> T(
                "Nothing on this Mac looks wrong. Keep the phone on \"Pair device with QR code\" with the screen on — or use one of the options below.",
                p.text, style = Type.body.copy(lineHeight = 17.sp), maxLines = 4,
            )
            else -> FindingText(top)
        }
        val own = top?.actions.orEmpty()
        val standard = listOf(FixAction.PAIRING_CODE, FixAction.USE_CABLE, FixAction.CONNECT_BY_IP)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failed && onQr) Button("Try again", actions.onNewQr, primary = true)
            // Isolation blocks a pairing code just the same — don't offer what the finding says won't work.
            val noCode = !onQr || top?.id == CheckId.ISOLATION
            (own + standard).distinct().filter { !(noCode && it == FixAction.PAIRING_CODE) }.forEach { a -> Button(a.label, { actions.onFix(a) }) }
            Button("Run connection check", actions.onCheckConnection)
            Button(if (stall?.copied == true) "Copied" else "Copy diagnostics", actions.onCopyDiagnostics)
        }
    }
}

@Composable
private fun FindingText(c: Check) {
    val p = LocalPalette.current
    val color = when (c.verdict) {
        Verdict.PROBLEM -> p.danger
        Verdict.WARNING -> p.warn
        else -> p.text
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        T(c.title, color, weight = FontWeight.SemiBold, maxLines = 3, style = Type.body.copy(lineHeight = 17.sp))
        T(c.detail, p.text, style = Type.small.copy(lineHeight = 16.sp), maxLines = 6)
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
            T("No network discovery needed: LogPose dials the address you type.", p.textMuted, style = Type.small)
            InlineLink("Already paired this phone? Enter its IP address & port to connect", actions.onConnectByIp)
        } else if (form.manual) {
            T("Connect to a phone that's already paired", p.text, weight = FontWeight.SemiBold)
            NumberedStep(1, "On the phone: Developer options → Wireless debugging (the main screen).")
            NumberedStep(2, "Under \"IP address & Port\" it shows something like 192.168.1.20:41234 — type that here.")
            Field("IP address & port (from the Wireless debugging screen)", form.connectAddress, "192.168.1.20:41234", Modifier.fillMaxWidth()) {
                actions.onFormChange(form.copy(connectAddress = it))
            }
            InlineLink("Not paired yet? Pair with a code instead", actions.onPairInstead)
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
        PairingProgressView(ui, preparing = false)
        if (ui.pairing is PairingState.Connected) Row { Button("Done", actions.onCloseSheet, primary = true) }
        if (ui.stall != null) StallPanel(ui, actions, onQr = false)
    }
}

@Composable
private fun InlineLink(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    T(text, p.accent, Modifier.clickable(onClick = onClick), style = Type.small, weight = FontWeight.Medium)
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
        NumberedStep(2, "Tap Allow on the phone. It appears in the device menu at the top left.")
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
                Button("Check connection", actions.onCheckConnection)
                Button("Done", actions.onCloseSheet)
            }
        }
    }
}

// ── Check connection ────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCheckSheet(check: DoctorUi, actions: WirelessActions) {
    val p = LocalPalette.current
    Column {
        SheetHeader("Check connection", actions.onCloseSheet)
        Column(
            Modifier.heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            T("LogPose looked at this Mac, your Wi-Fi and adb. It only reads — nothing here changes a setting.", p.textDim, maxLines = 2)
            val result = check.result
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(if (check.copied) "Copied" else "Copy diagnostics", actions.onCopyDiagnostics, primary = true, enabled = result != null)
                Button(if (check.showReport) "Hide report" else "Preview report", actions.onToggleReport, enabled = result != null)
                Button("Check again", actions.onRunCheck, enabled = !check.running)
                Button("Add a device", actions.onAddDevice)
            }
            if (check.showReport && result != null) {
                T("This is exactly what Copy diagnostics puts on the clipboard. No pairing codes or passwords; your home folder shows as ~.", p.textMuted, style = Type.small, maxLines = 2)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).background(p.bg0, RoundedCornerShape(6.dp))
                        .border(1.dp, p.borderSubtle, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        T(result.markdown, p.textDim, style = Type.monoSmall.copy(lineHeight = 15.sp), maxLines = 400)
                    }
                }
            }
            if (check.running) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), color = p.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                T(if (result == null) "Checking… this takes about five seconds." else "Checking again…", p.textDim)
            }
            if (result != null) {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, p.borderSubtle, RoundedCornerShape(8.dp)),
                ) {
                    result.checks.forEachIndexed { i, c ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
                        CheckRow(c, actions)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(p.bg2, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                T("Local Network permission (macOS 15 and later)", p.text, weight = FontWeight.SemiBold)
                T(
                    "macOS asks once whether LogPose may reach devices on your Wi-Fi. If you clicked Don't Allow, " +
                        "wireless pairing can't work until you turn LogPose on there.",
                    p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 3,
                )
                Row { Button(FixAction.OPEN_LOCAL_NETWORK.label, { actions.onFix(FixAction.OPEN_LOCAL_NETWORK) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckRow(c: Check, actions: WirelessActions) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(18.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) { VerdictMark(c.verdict) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val color = when (c.verdict) {
                Verdict.PROBLEM -> p.danger
                Verdict.WARNING -> p.warn
                else -> p.text
            }
            T(c.title, color, weight = FontWeight.SemiBold, maxLines = 3, style = Type.body.copy(lineHeight = 17.sp))
            T(c.detail, p.textDim, style = Type.small.copy(lineHeight = 16.sp), maxLines = 6)
            // Local Network has its own block below; its button isn't repeated here.
            val shown = c.actions.filter { !(c.id == CheckId.LOCAL_NETWORK && c.verdict == Verdict.OK) }
            if (shown.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { a -> Button(a.label, { actions.onFix(a) }) }
            }
        }
        Spacer(Modifier.width(8.dp))
        T(c.verdict.word, p.textMuted, style = Type.small)
    }
}

/** A round mark per verdict: tick (OK), "!" (warning / problem), "i" (note), "?" (can't check). */
@Composable
private fun VerdictMark(v: Verdict) {
    val p = LocalPalette.current
    when (v) {
        Verdict.OK -> CheckMark(p.textDim, Modifier.padding(top = 2.dp))
        else -> {
            val (fg, glyph) = when (v) {
                Verdict.PROBLEM -> p.danger to "!"
                Verdict.WARNING -> p.warn to "!"
                Verdict.INFO -> p.accent to "i"
                else -> p.textMuted to "?"
            }
            Box(Modifier.size(15.dp).border(1.5.dp, fg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                T(glyph, fg, style = Type.small.copy(fontSize = 10.sp, lineHeight = 10.sp), weight = FontWeight.Bold)
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
