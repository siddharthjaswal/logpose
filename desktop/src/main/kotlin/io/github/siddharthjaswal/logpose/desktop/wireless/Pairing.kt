package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.device.DeviceStatuses
import io.github.siddharthjaswal.logpose.logcat.Adb

/** Where a wireless pairing attempt stands. The sheet renders each one as a plain-English line. */
sealed interface PairingState {
    val done: Boolean get() = this is Connected || this is TimedOut || this is Failed

    /** QR: waiting for the phone's `_adb-tls-pairing._tcp` service named after our code. */
    data class WaitingForScan(val elapsedMillis: Long, val timeoutMillis: Long) : PairingState
    /** Running `adb pair` against [address]. */
    data class Pairing(val address: String) : PairingState
    /** Paired; waiting for the phone's connect service / adb's auto-connect. */
    data class Connecting(val guid: String?) : PairingState
    data class Connected(
        val serial: String,
        val name: String,
        /** The `_adb-tls-connect._tcp` instance (adb's guid), the stable identity across port changes. */
        val guid: String?,
        /** The ip:port it connected on, when known — a hint for the next reconnect. */
        val address: String?,
    ) : PairingState
    data class TimedOut(val cause: TimeoutCause) : PairingState
    data class Failed(val reason: FailReason, val detail: String) : PairingState
}

enum class TimeoutCause {
    /** Not a single adb mDNS service on the network in the whole window: client isolation, VPN, or another network. */
    NETWORK_SILENT,
    /** Other phones were visible but ours never advertised: it isn't on the scan screen, or is on another network. */
    PHONE_NOT_SEEN,
    /** Paired, but the phone's connect service never appeared and adb never auto-connected. */
    CONNECT_NOT_FOUND,
}

enum class FailReason { WRONG_CODE, UNREACHABLE, NOT_AUTHORIZED, MDNS_UNAVAILABLE, OTHER }

/** The words for each state — kept beside the states so tests pin them and the UI only lays them out. */
object PairingCopy {
    data class Line(val title: String, val body: String, val problem: Boolean)

    fun describe(state: PairingState): Line = when (state) {
        is PairingState.WaitingForScan -> Line(
            "Scan this code on your phone…",
            "Waiting for your phone to appear on the network (${(state.timeoutMillis - state.elapsedMillis).coerceAtLeast(0) / 1000}s left).",
            false,
        )
        is PairingState.Pairing -> Line("Found your phone — pairing…", "Talking to ${state.address}.", false)
        is PairingState.Connecting -> Line("Paired. Connecting…", "Almost there — the phone is accepting this Mac.", false)
        is PairingState.Connected -> Line("Connected", "${state.name} is connected over Wi-Fi. You can start a session.", false)
        is PairingState.TimedOut -> when (state.cause) {
            TimeoutCause.NETWORK_SILENT -> Line(
                "Couldn't find any phone on this Wi-Fi",
                "Wireless debugging needs the phone and this Mac on the same Wi-Fi network. Office and guest " +
                    "Wi-Fi often block devices from seeing each other (\"client isolation\"), and a VPN on " +
                    "either device can too. If you're on such a network, use the USB cable instead.",
                true,
            )
            TimeoutCause.PHONE_NOT_SEEN -> Line(
                "Your phone didn't show up",
                "Keep the phone on the \"Pair device with QR code\" screen while you scan, and check it is on " +
                    "the same Wi-Fi as this Mac (not mobile data, not a guest network).",
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
                "adb's network discovery isn't working here, so QR pairing can't find the phone. Use a pairing code with the phone's address, or the USB cable.",
                true,
            )
            FailReason.OTHER -> Line("Pairing failed", state.detail, true)
        }
    }
}

/**
 * Drives one pairing attempt against adb. Blocking adb calls — run it on `Dispatchers.IO`. Time is
 * injected ([clock], [sleep]) so tests walk a 90-second discovery window instantly.
 */
class PairingFlow(
    private val adb: AdbCommands,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val discoveryTimeoutMillis: Long = 90_000,
    private val connectTimeoutMillis: Long = 20_000,
    private val pollMillis: Long = 1_000,
) {

    /** QR flow: wait for the phone to advertise [code]'s service, pair, connect. */
    suspend fun runQr(code: QrPairingCode, report: (PairingState) -> Unit): PairingState {
        val start = clock()
        var sawAnyService = false
        while (true) {
            val elapsed = clock() - start
            report(PairingState.WaitingForScan(elapsed, discoveryTimeoutMillis))
            val services = adb.mdnsServices()
            if (services.isNotEmpty()) sawAnyService = true
            val ours = services.firstOrNull { it.type == AdbOutput.ServiceType.PAIRING && it.instance == code.serviceName }
            if (ours != null) {
                return finish(pairAndConnect(ours.address, code.password, null, report), report)
            }
            if (elapsed >= discoveryTimeoutMillis) {
                return finish(PairingState.TimedOut(if (sawAnyService) TimeoutCause.PHONE_NOT_SEEN else TimeoutCause.NETWORK_SILENT), report)
            }
            sleep(pollMillis)
        }
    }

    /** Pairing-code flow: the tester typed the pairing address + six digits (and maybe the connect address). */
    suspend fun runCode(pairAddress: String, pairingCode: String, connectAddress: String?, report: (PairingState) -> Unit): PairingState =
        finish(pairAndConnect(pairAddress, pairingCode, connectAddress, report), report)

    /** Already paired, the phone just needs connecting at a known address (the code form's second step). */
    suspend fun runConnect(connectAddress: String, report: (PairingState) -> Unit): PairingState {
        report(PairingState.Connecting(null))
        return finish(connectPhase(null, connectAddress), report)
    }

    private suspend fun pairAndConnect(address: String, secret: String, connectAddress: String?, report: (PairingState) -> Unit): PairingState {
        report(PairingState.Pairing(address))
        return when (val r = adb.pair(address, secret)) {
            is AdbOutput.PairResult.Success -> {
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
     * every few seconds we also look the guid up in `adb mdns services` (or use the typed address)
     * and run `adb connect ip:port` ourselves.
     */
    private suspend fun connectPhase(guid: String?, connectAddress: String?): PairingState {
        val start = clock()
        var lastTry = Long.MIN_VALUE / 2
        var connectedSerial: String? = null
        var usedAddress: String? = connectAddress
        while (true) {
            val devices = adb.devices()
            val match = devices.firstOrNull { d ->
                d.ready && ((guid != null && d.serial.startsWith(guid)) || d.serial == connectedSerial || (usedAddress != null && d.serial == usedAddress))
            }
            if (match != null) {
                return PairingState.Connected(match.serial, DeviceStatuses.displayName(match), guid ?: guidOf(match.serial), usedAddress)
            }
            val now = clock()
            if (now - lastTry >= 3_000) {
                lastTry = now
                val target = connectAddress
                    ?: guid?.let { g -> adb.mdnsServices().firstOrNull { it.type == AdbOutput.ServiceType.CONNECT && it.instance == g }?.address }
                if (target != null) {
                    usedAddress = target
                    when (val r = adb.connect(target)) {
                        is AdbOutput.ConnectResult.Connected -> connectedSerial = r.serial
                        is AdbOutput.ConnectResult.AlreadyConnected -> connectedSerial = r.serial
                        is AdbOutput.ConnectResult.NotAuthorized -> return PairingState.Failed(FailReason.NOT_AUTHORIZED, r.detail)
                        is AdbOutput.ConnectResult.Unreachable, is AdbOutput.ConnectResult.Failed -> Unit // retry until the deadline
                    }
                }
            }
            if (clock() - start >= connectTimeoutMillis) return PairingState.TimedOut(TimeoutCause.CONNECT_NOT_FOUND)
            sleep(pollMillis)
        }
    }

    private fun finish(state: PairingState, report: (PairingState) -> Unit): PairingState {
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
