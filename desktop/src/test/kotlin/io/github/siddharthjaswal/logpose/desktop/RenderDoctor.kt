package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.app.AddTab
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.DoctorResult
import io.github.siddharthjaswal.logpose.desktop.app.DoctorUi
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.Sheet
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbBinary
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorFacts
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingState
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingTrace
import io.github.siddharthjaswal.logpose.desktop.wireless.QrPairingCodes
import io.github.siddharthjaswal.logpose.desktop.wireless.SeenService
import io.github.siddharthjaswal.logpose.desktop.wireless.Stage
import io.github.siddharthjaswal.logpose.desktop.wireless.SystemOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.TimeoutCause
import io.github.siddharthjaswal.logpose.desktop.wireless.Via
import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.File
import kotlin.random.Random

/**
 * Pairing reliability + connection doctor renders: staged progress, the stall panel with each kind
 * of top finding, the Check-connection sheet, and the diagnostics preview. Facts mirror the live
 * diagnosis on the dev Mac (healthy home Wi-Fi, phone in Wi-Fi power-save, adb's discovery blind).
 *
 *   ./gradlew :desktop:renderScreens -PshotsOnly=doctor -PshotsDir=…
 */
fun renderDoctor(out: File) {
    val ready = AdbStatus.Ready("/Users/qa/Library/Android/sdk/platform-tools/adb", downloaded = false)
    fun idle(w: WirelessUi) = ScreenState(
        ready, emptyList(), null, SessionState.Idle, TimelineSnapshot.EMPTY, null,
        Placeholder.resolve(true, emptyList(), null, SessionState.Idle, null, 0),
    ).copy(wireless = w)

    val code = QrPairingCodes.generate(Random(11))
    val t0 = 1_792_000_000_000L
    val guid = "adb-00173359D000017-zABNgD"
    val mdnsOk = AdbOutput.MdnsCheck.Available("adb discovery 0.0.0")
    val qr = WirelessUi(sheet = Sheet.AddDevice(AddTab.QR), qr = code, mdns = mdnsOk)

    // Staged progress: waiting, then found via Bonjour + pairing, then connecting.
    val waitingTrace = PairingTrace(serviceName = code.serviceName, startedAt = t0, stageAt = mapOf(Stage.WAITING to t0), bonjourAvailable = true)
    val foundTrace = waitingTrace.copy(
        stageAt = mapOf(Stage.WAITING to t0, Stage.FOUND to t0 + 9_400, Stage.PAIRING to t0 + 9_400),
        pairingSeenAt = mapOf(Via.BONJOUR to t0 + 9_100), foundVia = Via.BONJOUR, pairAddress = "192.168.1.10:42667", bonjourSawAny = true,
    )
    val connectingTrace = foundTrace.copy(stageAt = foundTrace.stageAt + (Stage.CONNECTING to t0 + 10_600), guid = guid)
    shot(out, "doctor-progress-waiting-dark", true) {
        LogPoseScreen(idle(qr.copy(pairing = PairingState.WaitingForScan(8_000, 90_000), trace = waitingTrace, nowMillis = t0 + 8_000)), ScreenActions())
    }
    shot(out, "doctor-progress-found-bonjour-light", false) {
        LogPoseScreen(idle(qr.copy(pairing = PairingState.Pairing("192.168.1.10:42667", Via.BONJOUR), trace = foundTrace, nowMillis = t0 + 10_200)), ScreenActions())
    }
    shot(out, "doctor-progress-connecting-dark", true) {
        LogPoseScreen(idle(qr.copy(pairing = PairingState.Connecting(guid), trace = connectingTrace, nowMillis = t0 + 12_100)), ScreenActions())
    }

    // Facts: this Mac as diagnosed live.
    val base = DoctorFacts(
        macOs = "27.0.1", appVersion = "1.0.0", bonjourAvailable = true,
        bonjour = listOf(SeenService(AdbOutput.ServiceType.CONNECT, guid, "192.168.1.10:41983"), SeenService(AdbOutput.ServiceType.CONNECT, "adb-47041VDJH00186-VKmD1s", "192.168.1.5:45835")),
        adbDiscovery = mdnsOk, adb = emptyList(), targetPairing = code.serviceName, targetGuids = setOf(guid),
        interfaces = listOf(SystemOutput.MacInterface("en0", "192.168.1.13", 24)), defaultInterface = "en0", phoneIp = "192.168.1.10",
        ping = SystemOutput.PingStats(5, 5, 6.347, 53.217, 130.115, 52.660), firewall = SystemOutput.Firewall(false, false, false),
        adbBinaries = listOf(AdbBinary("/Users/qa/Library/Android/sdk/platform-tools/adb", "37.0.1-15733141", true)), adbServers = 1,
    )
    val healthyPing = SystemOutput.PingStats(5, 5, 3.1, 4.2, 6.0, 1.1)
    val stuckWaiting = qr.copy(pairing = PairingState.WaitingForScan(21_000, 90_000), trace = waitingTrace.copy(bonjourSawAny = true), nowMillis = t0 + 21_000)
    fun stall(f: DoctorFacts) = DoctorUi(result = DoctorResult.of(f, t0))

    // 1. Power save: the phone's pairing service isn't showing yet and it answers slowly.
    shot(out, "doctor-stall-power-save-dark", true) {
        LogPoseScreen(idle(stuckWaiting.copy(stall = stall(base.copy(bonjour = base.bonjour + SeenService(AdbOutput.ServiceType.PAIRING, code.serviceName))))), ScreenActions())
    }
    // 2. Isolation: nothing advertised anywhere.
    shot(out, "doctor-stall-isolation-light", false) {
        LogPoseScreen(idle(stuckWaiting.copy(trace = waitingTrace, stall = stall(base.copy(bonjour = emptyList(), phoneIp = null, ping = null)))), ScreenActions())
    }
    // 3. VPN carrying the default route (phone otherwise fine).
    shot(out, "doctor-stall-vpn-dark", true) {
        LogPoseScreen(
            idle(
                stuckWaiting.copy(
                    stall = stall(
                        base.copy(
                            bonjour = base.bonjour + SeenService(AdbOutput.ServiceType.PAIRING, code.serviceName), ping = healthyPing,
                            tunnelRoutes = listOf(SystemOutput.Route("0/1", "10.8.0.1", "utun4"), SystemOutput.Route("128.0/1", "10.8.0.1", "utun4")),
                            vpnClients = listOf("GlobalProtect"),
                        ),
                    ),
                ),
            ),
            ScreenActions(),
        )
    }
    // 4. adb missed it (informational): stalled in Connecting while adb's discovery sees nothing.
    val stuckConnecting = qr.copy(pairing = PairingState.Connecting(guid), trace = connectingTrace, nowMillis = t0 + 27_000)
    shot(out, "doctor-stall-adb-missed-light", false) {
        LogPoseScreen(idle(stuckConnecting.copy(stall = stall(base.copy(ping = healthyPing, targetPairing = null, pairing = connectingTrace)))), ScreenActions())
    }
    // The stall check still running.
    shot(out, "doctor-stall-checking-dark", true) {
        LogPoseScreen(idle(stuckWaiting.copy(stall = DoctorUi(running = true))), ScreenActions())
    }
    // A failed attempt with the doctor's finding.
    shot(out, "doctor-timed-out-isolation-dark", true) {
        LogPoseScreen(
            idle(qr.copy(pairing = PairingState.TimedOut(TimeoutCause.NETWORK_SILENT), trace = waitingTrace.copy(endedAt = t0 + 90_000), nowMillis = t0 + 90_000, stall = stall(base.copy(bonjour = emptyList(), phoneIp = null, ping = null)))),
            ScreenActions(),
        )
    }

    // The Check-connection sheet: full panel, then the diagnostics preview.
    val lastAttempt = foundTrace.copy(
        stageAt = foundTrace.stageAt + (Stage.CONNECTING to t0 + 10_600) + (Stage.CONNECTED to t0 + 11_900), guid = guid,
        connectVia = Via.ADB_AUTO, endedAt = t0 + 11_900, outcome = "connected",
    )
    val checkFacts = base.copy(targetPairing = null, pairing = lastAttempt, studioRunning = true)
    val checkSheet = WirelessUi(sheet = Sheet.ConnectionCheck, check = DoctorUi(result = DoctorResult.of(checkFacts, t0)))
    for (dark in listOf(true, false)) {
        shot(out, "doctor-check-panel-${if (dark) "dark" else "light"}", dark, height = 900) { LogPoseScreen(idle(checkSheet), ScreenActions()) }
    }
    shot(out, "doctor-check-running-light", false) {
        LogPoseScreen(idle(WirelessUi(sheet = Sheet.ConnectionCheck, check = DoctorUi(running = true))), ScreenActions())
    }
    shot(out, "doctor-copy-diagnostics-preview-dark", true, height = 1000) {
        LogPoseScreen(idle(checkSheet.copy(check = checkSheet.check.copy(showReport = true, copied = true))), ScreenActions())
    }
    // Sidebar entry point + the manual IP form.
    shot(out, "doctor-sidebar-entry-light", false) {
        LogPoseScreen(idle(WirelessUi()).copy(devices = listOf(Adb.DeviceInfo("$guid._adb-tls-connect._tcp", "device", "A059P"))), ScreenActions())
    }
    shot(out, "doctor-connect-by-ip-dark", true) {
        LogPoseScreen(
            idle(WirelessUi(sheet = Sheet.AddDevice(AddTab.CODE), form = io.github.siddharthjaswal.logpose.desktop.app.CodeForm(askConnectAddress = true, manual = true, connectAddress = "192.168.1.10:41983"))),
            ScreenActions(),
        )
    }
    println(DoctorResult.of(checkFacts, t0).markdown)
}
