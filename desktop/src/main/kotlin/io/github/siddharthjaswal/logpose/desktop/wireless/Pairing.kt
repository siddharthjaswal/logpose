package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.device.DeviceStatuses
import io.github.siddharthjaswal.logpose.logcat.Adb


/** Which discovery produced an address — recorded for the progress line and the diagnostics report. */
enum class Via(val label: String) {
    /** `adb mdns services` (adb's own discovery). */
    ADB("adb's discovery"),
    /** macOS's mDNSResponder, through `dns-sd` ([BonjourBrowser]). */
    BONJOUR("macOS Bonjour"),
    /** An address the tester typed. */
    TYPED("the address you typed"),
    /** adb connected the paired phone by itself (`ADB_MDNS_AUTO_CONNECT`); nobody dialled. */
    ADB_AUTO("adb's auto-connect"),
}

/** Where a wireless pairing attempt stands. The sheet renders each one as a plain-English line. */
sealed interface PairingState {
    val done: Boolean get() = this is Connected || this is TimedOut || this is Failed

    /** QR: waiting for the phone's `_adb-tls-pairing._tcp` service named after our code. */
    data class WaitingForScan(val elapsedMillis: Long, val timeoutMillis: Long) : PairingState
    /** Running `adb pair` against [address], found through [via] (null for a typed address). */
    data class Pairing(val address: String, val via: Via? = null) : PairingState
    /** Paired; waiting for the phone's connect service / adb's auto-connect. */
    data class Connecting(val guid: String?) : PairingState
    data class Connected(
        val serial: String,
        val name: String,
        /** The `_adb-tls-connect._tcp` instance (adb's guid), the stable identity across port changes. */
        val guid: String?,
        /** The ip:port it connected on, when known — a hint for the next reconnect. */
        val address: String?,
        /** How the connect address was found (or that adb auto-connected). */
        val connectVia: Via? = null,
    ) : PairingState
    data class TimedOut(val cause: TimeoutCause) : PairingState
    data class Failed(val reason: FailReason, val detail: String) : PairingState
}

enum class TimeoutCause {
    /** Neither adb nor macOS Bonjour saw a single adb service in the whole window: client isolation, VPN, or another network. */
    NETWORK_SILENT,
    /** Other phones were visible but ours never advertised: it isn't on the scan screen, or is on another network. */
    PHONE_NOT_SEEN,
    /** Paired, but the phone's connect service never appeared and adb never auto-connected. */
    CONNECT_NOT_FOUND,
}

enum class FailReason { WRONG_CODE, UNREACHABLE, NOT_AUTHORIZED, MDNS_UNAVAILABLE, OTHER }

/** The stages the sheet lists, in order. QR runs all five; the pairing-code flow starts at [PAIRING]. */
enum class Stage { WAITING, FOUND, PAIRING, CONNECTING, CONNECTED }

/**
 * A pairing attempt's record: when each stage began, which discovery saw the phone first, and what
 * each source saw at all. It feeds the staged progress, stall detection (no new stage for 15 s) and
 * the diagnostics report. It never holds the pairing password.
 */
data class PairingTrace(
    /** QR only: our `_adb-tls-pairing._tcp` instance name (not a secret — the password is). */
    val serviceName: String? = null,
    val startedAt: Long = 0,
    val stageAt: Map<Stage, Long> = emptyMap(),
    /** First time each source saw our pairing service. */
    val pairingSeenAt: Map<Via, Long> = emptyMap(),
    /** First time each source saw the paired phone's connect service. */
    val connectSeenAt: Map<Via, Long> = emptyMap(),
    val foundVia: Via? = null,
    val connectVia: Via? = null,
    val pairAddress: String? = null,
    val connectAddress: String? = null,
    val guid: String? = null,
    /** Whether each source saw any adb service at all (any phone, any type) during the attempt. */
    val adbSawAny: Boolean = false,
    val bonjourSawAny: Boolean = false,
    val bonjourAvailable: Boolean = false,
    val endedAt: Long? = null,
    /** A short machine-ish word for the report: `connected`, `timed out: PHONE_NOT_SEEN`, `failed: WRONG_CODE`. */
    val outcome: String? = null,
) {
    /** When the attempt last moved forward — the stall clock. */
    val lastProgressAt: Long get() = stageAt.values.maxOrNull() ?: startedAt
    val currentStage: Stage? get() = stageAt.maxByOrNull { it.value }?.key
}

/** Thread-safe builder for a [PairingTrace]; the flow writes, the controller snapshots. */
class PairingLog(private val clock: () -> Long = System::currentTimeMillis) {
    private var t = PairingTrace(startedAt = clock())

    @Synchronized fun snapshot(): PairingTrace = t
    @Synchronized fun start(serviceName: String?, bonjourAvailable: Boolean) {
        t = PairingTrace(serviceName = serviceName, startedAt = clock(), bonjourAvailable = bonjourAvailable)
    }
    @Synchronized fun stage(s: Stage) { if (s !in t.stageAt) t = t.copy(stageAt = t.stageAt + (s to clock())) }
    @Synchronized fun sawPairing(via: Via) { if (via !in t.pairingSeenAt) t = t.copy(pairingSeenAt = t.pairingSeenAt + (via to clock())) }
    @Synchronized fun sawConnect(via: Via) { if (via !in t.connectSeenAt) t = t.copy(connectSeenAt = t.connectSeenAt + (via to clock())) }
    @Synchronized fun sawAny(adb: Boolean, bonjour: Boolean) {
        if ((adb && !t.adbSawAny) || (bonjour && !t.bonjourSawAny)) t = t.copy(adbSawAny = t.adbSawAny || adb, bonjourSawAny = t.bonjourSawAny || bonjour)
    }
    @Synchronized fun found(via: Via, address: String) { t = t.copy(foundVia = via, pairAddress = address) }
    @Synchronized fun pairing(address: String) { t = t.copy(pairAddress = address) }
    @Synchronized fun paired(guid: String?) { t = t.copy(guid = guid) }
    @Synchronized fun connectTarget(via: Via, address: String?) { t = t.copy(connectVia = via, connectAddress = address ?: t.connectAddress) }
    @Synchronized fun end(state: PairingState) {
        val outcome = when (state) {
            is PairingState.Connected -> "connected"
            is PairingState.TimedOut -> "timed out: ${state.cause}"
            is PairingState.Failed -> "failed: ${state.reason}"
            else -> null
        }
        t = t.copy(endedAt = clock(), outcome = outcome)
    }
}

/** The words for each state — kept beside the states so tests pin them and the UI only lays them out. */
object PairingCopy {
    data class Line(val title: String, val body: String, val problem: Boolean)

    fun describe(state: PairingState): Line = when (state) {
        is PairingState.WaitingForScan -> Line(
            "Waiting for your phone to scan…",
            "Scan the code from the phone's \"Pair device with QR code\" screen " +
                "(${(state.timeoutMillis - state.elapsedMillis).coerceAtLeast(0) / 1000}s left).",
            false,
        )
        is PairingState.Pairing -> Line(
            if (state.via != null) "Phone found (via ${state.via.label}) — pairing…" else "Pairing…",
            "Pairing with ${state.address}.",
            false,
        )
        is PairingState.Connecting -> Line("Paired. Connecting…", "Almost there — the phone is accepting this Mac.", false)
        is PairingState.Connected -> Line("Connected", "${state.name} is connected over Wi-Fi. You can start a session.", false)
        is PairingState.TimedOut -> when (state.cause) {
            TimeoutCause.NETWORK_SILENT -> Line(
                "Couldn't find any phone on this Wi-Fi",
                "Neither macOS nor adb saw any phone with Wireless debugging on. Phone and Mac need the same " +
                    "Wi-Fi network — office and guest Wi-Fi often keep devices apart (\"client isolation\"), and " +
                    "a VPN on either device can too. On such a network, use the USB cable or the phone's hotspot.",
                true,
            )
            TimeoutCause.PHONE_NOT_SEEN -> Line(
                "Your phone didn't show up",
                "Keep the phone on the \"Pair device with QR code\" screen with the screen on while you scan, and " +
                    "check it is on the same Wi-Fi as this Mac (not mobile data, not a guest network).",
                true,
            )
            TimeoutCause.CONNECT_NOT_FOUND -> Line(
                "Paired, but couldn't connect",
                "The phone is paired, but LogPose couldn't find where to connect. The main Wireless debugging screen shows \"IP address & Port\" — type that into LogPose.",
                true,
            )
        }
        is PairingState.Failed -> when (state.reason) {
            FailReason.WRONG_CODE -> Line("That code didn't work", "Check the six digits on the phone and try again — the code changes each time the dialog opens.", true)
            FailReason.UNREACHABLE -> Line(
                "Couldn't reach the phone",
                "The pairing dialog may have closed, or the address is wrong. Open \"Pair device with pairing code\" again and use the new code and address.",
                true,
            )
            FailReason.NOT_AUTHORIZED -> Line("The phone refused this Mac", "Pair again: the phone no longer trusts this computer.", true)
            FailReason.MDNS_UNAVAILABLE -> Line(
                "This Mac can't search the network for phones",
                "Neither macOS Bonjour nor adb's discovery is working here, so QR pairing can't find the phone. Use a pairing code with the phone's address, or the USB cable.",
                true,
            )
            FailReason.OTHER -> Line("Pairing failed", state.detail, true)
        }
    }
}

/**
 * Drives one pairing attempt against adb, with macOS Bonjour racing adb's own discovery. Blocking adb
 * calls — run it on `Dispatchers.IO`. Time is injected ([clock], [sleep]) so tests walk a 90-second
 * discovery window instantly.
 *
 * **The race.** Every tick the flow asks both sources for the phone: `adb mdns services` (a snapshot)
 * and the [bonjour] session (a running `dns-sd -B`, resolved with `-L`/`-G` in the background). The
 * first to produce an `ip:port` wins and goes straight to `adb pair <ip:port> <password>` — adb pair
 * takes an explicit address and needs none of its own discovery. The connect step does the same for
 * the phone's `_adb-tls-connect._tcp` service (adb's guid from the pair output), alongside adb's own
 * auto-connect. [log] records which source saw what, when, and which one won.
 */
class PairingFlow(
    private val adb: AdbCommands,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val discoveryTimeoutMillis: Long = 90_000,
    private val connectTimeoutMillis: Long = 20_000,
    private val pollMillis: Long = 1_000,
    private val bonjour: BonjourSession = BonjourSession.None,
    val log: PairingLog = PairingLog(clock),
) {

    /** QR flow: wait for the phone to advertise [code]'s service (either source), pair, connect. */
    suspend fun runQr(code: QrPairingCode, report: (PairingState) -> Unit): PairingState {
        log.start(code.serviceName, bonjour.available)
        log.stage(Stage.WAITING)
        val start = clock()
        while (true) {
            val elapsed = clock() - start
            report(PairingState.WaitingForScan(elapsed, discoveryTimeoutMillis))
            val services = adb.mdnsServices()
            val bonjourAny = AdbOutput.ServiceType.entries.any { it != AdbOutput.ServiceType.OTHER && bonjour.seen(it).isNotEmpty() }
            log.sawAny(adb = services.isNotEmpty(), bonjour = bonjourAny)

            val viaAdb = services.firstOrNull { it.type == AdbOutput.ServiceType.PAIRING && it.instance == code.serviceName }
            if (viaAdb != null) log.sawPairing(Via.ADB)
            val bonjourSees = code.serviceName in bonjour.seen(AdbOutput.ServiceType.PAIRING)
            if (bonjourSees) log.sawPairing(Via.BONJOUR)
            // adb's snapshot is free to read; Bonjour's endpoint resolves in the background once seen.
            val winner: Pair<Via, String>? = viaAdb?.let { Via.ADB to it.address }
                ?: if (bonjourSees) bonjour.endpoint(code.serviceName, AdbOutput.ServiceType.PAIRING)?.let { Via.BONJOUR to it.address } else null
            if (winner != null) {
                log.found(winner.first, winner.second)
                log.stage(Stage.FOUND)
                return finish(pairAndConnect(winner.second, code.password, null, winner.first, report), report)
            }
            if (elapsed >= discoveryTimeoutMillis) {
                val t = log.snapshot()
                return finish(PairingState.TimedOut(if (t.adbSawAny || t.bonjourSawAny) TimeoutCause.PHONE_NOT_SEEN else TimeoutCause.NETWORK_SILENT), report)
            }
            sleep(pollMillis)
        }
    }

    /** Pairing-code flow: the tester typed the pairing address + six digits (and maybe the connect address). */
    suspend fun runCode(pairAddress: String, pairingCode: String, connectAddress: String?, report: (PairingState) -> Unit): PairingState {
        log.start(null, bonjour.available)
        return finish(pairAndConnect(pairAddress, pairingCode, connectAddress, null, report), report)
    }

    /** Already paired, the phone just needs connecting at a known address (the code form's second step, or *Connect by IP*). */
    suspend fun runConnect(connectAddress: String, report: (PairingState) -> Unit): PairingState {
        log.start(null, bonjour.available)
        log.stage(Stage.CONNECTING)
        report(PairingState.Connecting(null))
        return finish(connectPhase(null, connectAddress), report)
    }

    private suspend fun pairAndConnect(address: String, secret: String, connectAddress: String?, via: Via?, report: (PairingState) -> Unit): PairingState {
        log.pairing(address)
        log.stage(Stage.PAIRING)
        report(PairingState.Pairing(address, via))
        return when (val r = adb.pair(address, secret)) {
            is AdbOutput.PairResult.Success -> {
                log.paired(r.guid)
                log.stage(Stage.CONNECTING)
                report(PairingState.Connecting(r.guid))
                connectPhase(r.guid, connectAddress)
            }
            AdbOutput.PairResult.WrongCode -> PairingState.Failed(FailReason.WRONG_CODE, "Wrong password or connection was dropped.")
            is AdbOutput.PairResult.Unreachable -> PairingState.Failed(FailReason.UNREACHABLE, r.detail)
            is AdbOutput.PairResult.Failed -> PairingState.Failed(FailReason.OTHER, r.detail)
        }
    }

    /**
     * After a successful `adb pair`, adb itself calls `adb_secure_connect_by_service_name(guid)` and,
     * by default (`ADB_MDNS_AUTO_CONNECT=adb-tls-connect`), auto-connects paired phones it sees. So
     * the device often just appears as `adb-<serial>-<id>._adb-tls-connect._tcp`. We don't rely on it:
     * every few seconds we also look the guid up in `adb mdns services` *and* macOS Bonjour (or use the
     * typed address) and run `adb connect ip:port` ourselves.
     */
    private suspend fun connectPhase(guid: String?, connectAddress: String?): PairingState {
        val start = clock()
        var lastTry = Long.MIN_VALUE / 2
        var connectedSerial: String? = null
        var usedAddress: String? = connectAddress
        var usedVia: Via? = if (connectAddress != null) Via.TYPED else null
        while (true) {
            val devices = adb.devices()
            val match = devices.firstOrNull { d ->
                d.ready && ((guid != null && d.serial.startsWith(guid)) || d.serial == connectedSerial || (usedAddress != null && d.serial == usedAddress))
            }
            if (match != null) {
                // Ready before our own connect landed: adb auto-connected it.
                val via = if (connectedSerial == null && match.serial != usedAddress) Via.ADB_AUTO else usedVia
                if (via != null) log.connectTarget(via, usedAddress)
                log.stage(Stage.CONNECTED)
                return PairingState.Connected(match.serial, DeviceStatuses.displayName(match), guid ?: guidOf(match.serial), usedAddress, via)
            }
            val now = clock()
            if (now - lastTry >= 3_000) {
                lastTry = now
                val target: Pair<Via, String>? = connectAddress?.let { Via.TYPED to it } ?: guid?.let { g -> findConnect(g) }
                if (target != null) {
                    usedAddress = target.second
                    usedVia = target.first
                    when (val r = adb.connect(target.second)) {
                        is AdbOutput.ConnectResult.Connected -> connectedSerial = r.serial
                        is AdbOutput.ConnectResult.AlreadyConnected -> connectedSerial = r.serial
                        is AdbOutput.ConnectResult.NotAuthorized -> return PairingState.Failed(FailReason.NOT_AUTHORIZED, r.detail)
                        is AdbOutput.ConnectResult.Unreachable, is AdbOutput.ConnectResult.Failed ->
                            if (target.first == Via.BONJOUR && guid != null) bonjour.invalidate(guid) // its port may have moved; re-resolve
                    }
                }
            }
            if (clock() - start >= connectTimeoutMillis) return PairingState.TimedOut(TimeoutCause.CONNECT_NOT_FOUND)
            sleep(pollMillis)
        }
    }

    /**
     * The paired phone's connect address, from whichever source has it — Bonjour first when its
     * endpoint is already resolved (mDNSResponder's answer is current; adb's list can hold a stale
     * port), else adb's snapshot, else null while Bonjour is still resolving.
     */
    private fun findConnect(guid: String): Pair<Via, String>? {
        val viaAdb = adb.mdnsServices().firstOrNull { it.type == AdbOutput.ServiceType.CONNECT && it.instance == guid }
        if (viaAdb != null) log.sawConnect(Via.ADB)
        if (guid in bonjour.seen(AdbOutput.ServiceType.CONNECT)) log.sawConnect(Via.BONJOUR)
        return bonjour.endpoint(guid, AdbOutput.ServiceType.CONNECT)?.let { Via.BONJOUR to it.address }
            ?: viaAdb?.let { Via.ADB to it.address }
    }

    private fun finish(state: PairingState, report: (PairingState) -> Unit): PairingState {
        log.end(state)
        report(state)
        return state
    }

    companion object {
        /** `adb-14141FDF600081-QoXtBe` from `adb-14141FDF600081-QoXtBe._adb-tls-connect._tcp`; null for ip:port serials. */
        fun guidOf(serial: String): String? =
            serial.substringBefore("._adb-tls-connect", "").ifEmpty { null }
    }
}

/** Whether an adb serial is a network transport (Wi-Fi) rather than USB or an emulator. */
fun isWirelessSerial(serial: String): Boolean =
    serial.contains("._adb-tls-connect._tcp") || serial.contains("._adb._tcp") ||
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d+$").matches(serial)

/** True for a cable-attached real phone: not wireless, not an emulator. */
fun isCableSerial(device: Adb.DeviceInfo): Boolean = !isWirelessSerial(device.serial) && !device.serial.startsWith("emulator-")
