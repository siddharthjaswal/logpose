package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.app.UpdateUi
import io.github.siddharthjaswal.logpose.desktop.update.UpdateCheck

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.theme.LogPoseTheme
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import org.jetbrains.skia.EncodedImageFormat
import io.github.siddharthjaswal.logpose.desktop.app.AddTab
import io.github.siddharthjaswal.logpose.desktop.app.CodeForm
import io.github.siddharthjaswal.logpose.desktop.app.LegacyStatus
import io.github.siddharthjaswal.logpose.desktop.app.Sheet
import io.github.siddharthjaswal.logpose.desktop.app.WirelessUi
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.FailReason
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingState
import io.github.siddharthjaswal.logpose.desktop.wireless.QrPairingCodes
import io.github.siddharthjaswal.logpose.desktop.wireless.QrPairingTest
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.desktop.wireless.TimeoutCause
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Renders the real screen composable off-screen — no window, no display, no device — to PNGs a
 * person (or an agent) can look at. `./gradlew :desktop:renderScreens -PshotsDir=…`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    // `-PshotsOnly=m4b` renders one milestone's screens instead of all of them.
    if (args.getOrNull(1) == "m4b") { renderM4b(out); println("Rendered to ${out.absolutePath}"); return }
    if (args.getOrNull(1) == "doctor") { renderDoctor(out); println("Rendered to ${out.absolutePath}"); return }
    if (args.getOrNull(1) == "emulators") { renderEmulators(out); println("Rendered to ${out.absolutePath}"); return }
    val pixel = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val events = Fixtures.store().snapshot()
    val running = SessionState.Running(pixel.serial, System.currentTimeMillis())
    val full = TimelineSnapshot(1, events)
    val ready = AdbStatus.Ready("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", downloaded = true)

    fun state(
        adb: AdbStatus = ready,
        devices: List<Adb.DeviceInfo> = listOf(pixel),
        session: SessionState = running,
        snapshot: TimelineSnapshot = full,
        selected: String? = null,
        verdict: QuietDetector.Verdict? = QuietDetector.Verdict.RECEIVING,
    ) = ScreenState(
        adb, devices, devices.firstOrNull { it.ready }?.serial, session, snapshot, selected,
        Placeholder.resolve(adb is AdbStatus.Ready, devices, devices.firstOrNull()?.model?.replace('_', ' '), session, verdict, snapshot.events.size),
    )

    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        shot(out, "timeline-$theme", dark) { LogPoseScreen(state(), ScreenActions()) }
        shot(out, "detail-http-$theme", dark) { LogPoseScreen(state(selected = "h1"), ScreenActions()) }
    }
    shot(out, "detail-worker-dark", true) { LogPoseScreen(state(selected = "w1"), ScreenActions()) }
    shot(out, "detail-config-light", false) { LogPoseScreen(state(selected = "c1"), ScreenActions()) }
    shot(out, "empty-no-adb-dark", true) { LogPoseScreen(state(adb = AdbStatus.Missing), ScreenActions()) }
    shot(out, "empty-no-adb-light", false) { LogPoseScreen(state(adb = AdbStatus.Missing), ScreenActions()) }
    shot(out, "empty-no-device-dark", true) {
        LogPoseScreen(state(devices = emptyList(), session = SessionState.Idle, snapshot = TimelineSnapshot.EMPTY), ScreenActions())
    }
    shot(out, "empty-no-logpose-dark", true) {
        LogPoseScreen(state(snapshot = TimelineSnapshot.EMPTY, verdict = QuietDetector.Verdict.NO_LOGPOSE), ScreenActions())
    }
    shot(out, "empty-no-logpose-light", false) {
        LogPoseScreen(state(snapshot = TimelineSnapshot.EMPTY, verdict = QuietDetector.Verdict.NO_LOGPOSE), ScreenActions())
    }
    shot(out, "unauthorized-light", false) {
        LogPoseScreen(
            state(devices = listOf(pixel.copy(state = "unauthorized")), session = SessionState.Idle, snapshot = TimelineSnapshot.EMPTY),
            ScreenActions(),
        )
    }
    // M5: the sidebar footer with a newer Mac release known (and the update-check switch).
    val newer = UpdateCheck.Release("desktop-v1.1.0", UpdateCheck.Version(1, 1, 0), UpdateCheck.RELEASE_PAGE_PREFIX + "tag/desktop-v1.1.0")
    for (dark in listOf(true, false)) {
        shot(out, "m5-update-available-${if (dark) "dark" else "light"}", dark) {
            LogPoseScreen(state().copy(updates = UpdateUi("1.0.0", enabled = true, available = newer)), ScreenActions())
        }
    }
    renderWireless(out, ::state)
    renderM3(out)
    renderM4a(out)
    renderM4b(out)
    renderDoctor(out)
    renderEmulators(out)
    println("Rendered to ${out.absolutePath}")
}

/** M2: the Add-device sheet, pairing-code form, checklist, mixed sidebar and the no-mDNS message. */
private fun renderWireless(
    out: File,
    state: (AdbStatus, List<Adb.DeviceInfo>, SessionState, TimelineSnapshot, String?, QuietDetector.Verdict?) -> ScreenState,
) {
    val ready = AdbStatus.Ready("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", downloaded = true)
    fun idle(devices: List<Adb.DeviceInfo>, wireless: WirelessUi) =
        state(ready, devices, SessionState.Idle, TimelineSnapshot.EMPTY, null, null).copy(wireless = wireless)
    val code = QrPairingCodes.generate(Random(7))
    val mdnsOk = AdbOutput.MdnsCheck.Available("adb discovery 0.0.0")
    val qrUi = WirelessUi(sheet = Sheet.AddDevice(AddTab.QR), qr = code, pairing = PairingState.WaitingForScan(12_000, 90_000), mdns = mdnsOk)

    for (dark in listOf(true, false)) {
        val theme = if (dark) "dark" else "light"
        val png = shot(out, "m2-add-device-qr-$theme", dark) { LogPoseScreen(idle(emptyList(), qrUi), ScreenActions()) }
        // A QR that renders but doesn't scan is the classic failure: decode the real pixels.
        val decoded = decodeQr(png)
        check(decoded == code.payload) { "QR in $png decoded to '$decoded', expected '${code.payload}'" }
        println("QR decodes ($theme): $decoded")
    }
    shot(out, "m2-add-device-qr-pairing-light", false) {
        LogPoseScreen(idle(emptyList(), qrUi.copy(pairing = PairingState.Pairing("192.168.1.20:37755"))), ScreenActions())
    }
    val form = CodeForm(pairAddress = "192.168.1.20:37755", code = "48213", error = "The pairing code is the six digits shown on the phone.")
    shot(out, "m2-pairing-code-dark", true) {
        LogPoseScreen(idle(emptyList(), WirelessUi(sheet = Sheet.AddDevice(AddTab.CODE), form = form)), ScreenActions())
    }
    shot(out, "m2-pairing-code-connect-step-light", false) {
        LogPoseScreen(
            idle(
                emptyList(),
                WirelessUi(
                    sheet = Sheet.AddDevice(AddTab.CODE),
                    form = CodeForm(askConnectAddress = true, connectAddress = "192.168.1.20:41"),
                    pairing = PairingState.TimedOut(TimeoutCause.CONNECT_NOT_FOUND),
                ),
            ),
            ScreenActions(),
        )
    }
    shot(out, "m2-cable-tab-dark", true) {
        LogPoseScreen(idle(emptyList(), WirelessUi(sheet = Sheet.AddDevice(AddTab.CABLE))), ScreenActions())
    }
    for (dark in listOf(true, false)) {
        shot(out, "m2-how-to-connect-${if (dark) "dark" else "light"}", dark) {
            LogPoseScreen(idle(emptyList(), WirelessUi(sheet = Sheet.HowToConnect)), ScreenActions())
        }
    }

    // Sidebar: a cable phone mid-"Switch to wireless", a TLS phone, an offline legacy phone, and a
    // remembered phone that isn't on this Wi-Fi (being retried).
    val cable = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val tls = Adb.DeviceInfo("adb-R5CT20ABCDE-QoXtBe._adb-tls-connect._tcp", "device", "SM_S918B")
    val legacyOffline = Adb.DeviceInfo("192.168.1.31:5555", "offline")
    val remembered = listOf(
        RememberedDevice("adb-R5CT20ABCDE-QoXtBe", "Galaxy S23", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-R5CT20ABCDE-QoXtBe"),
        RememberedDevice("usb:XYZ", "Redmi Note 8", RememberedDevice.Kind.LEGACY, "192.168.1.31:5555"),
        RememberedDevice("adb-OLD-1", "Pixel 6", RememberedDevice.Kind.TLS, "192.168.1.50:40000", "adb-OLD-1"),
        RememberedDevice("usb:OLD2", "Galaxy A51", RememberedDevice.Kind.LEGACY, "192.168.1.60:5555"),
    )
    val mix = WirelessUi(
        remembered = remembered, reconnecting = setOf("adb-OLD-1"),
        legacy = LegacyStatus(cable.serial, running = true, message = "Switching to wireless…", ok = false),
    )
    for (dark in listOf(true, false)) {
        shot(out, "m2-sidebar-mix-${if (dark) "dark" else "light"}", dark) {
            LogPoseScreen(idle(listOf(cable, tls, legacyOffline), mix), ScreenActions())
        }
    }

    // The honest limit: nothing on the network answered (client isolation), and adb's mDNS broken.
    shot(out, "m2-no-mdns-isolation-dark", true) {
        LogPoseScreen(idle(emptyList(), qrUi.copy(pairing = PairingState.TimedOut(TimeoutCause.NETWORK_SILENT))), ScreenActions())
    }
    shot(out, "m2-mdns-unavailable-light", false) {
        LogPoseScreen(
            idle(
                emptyList(),
                qrUi.copy(
                    mdns = AdbOutput.MdnsCheck.Unavailable("ERROR: mdns daemon unavailable"),
                    pairing = PairingState.Failed(FailReason.MDNS_UNAVAILABLE, "ERROR: mdns daemon unavailable"),
                ),
            ),
            ScreenActions(),
        )
    }
    shot(out, "m2-connected-dark", true) {
        LogPoseScreen(
            idle(listOf(tls), qrUi.copy(pairing = PairingState.Connected(tls.serial, "SM S918B", "adb-R5CT20ABCDE-QoXtBe", null), remembered = remembered.take(1))),
            ScreenActions(),
        )
    }
}

/** Decodes the QR code anywhere in a rendered screenshot with ZXing. */
fun decodeQr(png: File): String {
    val img = ImageIO.read(png)
    val pixels = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    return QrPairingTest.decodePixels(pixels, img.width, img.height)
}

/** Renders [content] at 1280×800 logical px, 2× density (a Retina frame), after a few frames settle. */
fun shot(out: File, name: String, dark: Boolean, width: Int = 1280, height: Int = 800, content: @Composable () -> Unit): File {
    val scale = 2f
    ImageComposeScene((width * scale).toInt(), (height * scale).toInt(), Density(scale)) {
        LogPoseTheme(dark) { content() }
    }.use { scene ->
        // Several frames: LaunchedEffects (tail-follow scroll) run between them.
        var image = scene.render(0)
        for (i in 1..4) image = scene.render(i * 16_000_000L)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        return File(out, "$name.png").apply { writeBytes(png.bytes) }
    }
}
