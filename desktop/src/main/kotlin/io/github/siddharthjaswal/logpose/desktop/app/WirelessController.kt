package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.FailReason
import io.github.siddharthjaswal.logpose.desktop.wireless.LegacySwitch
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingFlow
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingState
import io.github.siddharthjaswal.logpose.desktop.wireless.QrPairingCode
import io.github.siddharthjaswal.logpose.desktop.wireless.QrPairingCodes
import io.github.siddharthjaswal.logpose.desktop.wireless.Reconnector
import io.github.siddharthjaswal.logpose.desktop.wireless.ReconnectScheduler
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevices
import io.github.siddharthjaswal.logpose.desktop.wireless.TimeoutCause
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddTab { QR, CODE, CABLE }

sealed interface Sheet {
    data object None : Sheet
    data class AddDevice(val tab: AddTab) : Sheet
    data object HowToConnect : Sheet
}

/** What the tester typed into the pairing-code form; kept in state so it survives a tab switch. */
data class CodeForm(
    val pairAddress: String = "",
    val code: String = "",
    val connectAddress: String = "",
    /** Shown once pairing worked but discovery couldn't find the connect port. */
    val askConnectAddress: Boolean = false,
    val error: String? = null,
)

/** A *Switch to wireless* in progress or just finished, shown under that phone in the sidebar. */
data class LegacyStatus(val serial: String, val running: Boolean, val message: String, val ok: Boolean)

/** Everything wireless the window paints, as plain values (the off-screen renders build these directly). */
data class WirelessUi(
    val sheet: Sheet = Sheet.None,
    val qr: QrPairingCode? = null,
    val pairing: PairingState? = null,
    val mdns: AdbOutput.MdnsCheck? = null,
    val form: CodeForm = CodeForm(),
    val remembered: List<RememberedDevice> = emptyList(),
    val reconnecting: Set<String> = emptySet(),
    val legacy: LegacyStatus? = null,
)

/**
 * Wireless setup and upkeep: QR / pairing-code pairing, *Switch to wireless*, remembered phones and
 * their auto-reconnect. Every adb call runs on [Dispatchers.IO]; state goes out through [ui].
 */
class WirelessController(
    private val scope: CoroutineScope,
    private val settings: KeyValueStore,
    private val adb: AdbCommands = AdbCommands.Real,
    private val onConnected: (serial: String) -> Unit = {},
) {
    private val remembered = RememberedDevices(settings)
    private val scheduler = ReconnectScheduler()
    private val reconnector = Reconnector(adb)

    private val _ui = MutableStateFlow(WirelessUi(remembered = remembered.all()))
    val ui: StateFlow<WirelessUi> = _ui.asStateFlow()

    private var pairingJob: Job? = null
    private var legacyJob: Job? = null

    /** First launch: open the checklist once. */
    fun showOnboardingIfFirstRun() {
        if (!settings.getBoolean(ONBOARDING_SEEN, false)) _ui.update { it.copy(sheet = Sheet.HowToConnect) }
    }

    fun openHowToConnect() = _ui.update { it.copy(sheet = Sheet.HowToConnect) }

    fun openAddDevice(tab: AddTab = AddTab.QR) = selectTab(tab)

    fun selectTab(tab: AddTab) {
        pairingJob?.cancel()
        _ui.update { it.copy(sheet = Sheet.AddDevice(tab), pairing = null, form = it.form.copy(error = null)) }
        if (tab == AddTab.QR) startQr()
    }

    fun closeSheet() {
        pairingJob?.cancel()
        if (_ui.value.sheet == Sheet.HowToConnect) {
            scope.launch(Dispatchers.IO) { settings.setBoolean(ONBOARDING_SEEN, true, false) }
        }
        _ui.update { it.copy(sheet = Sheet.None, pairing = null, qr = null) }
    }

    /** A fresh code and a fresh 90 s discovery window. */
    fun startQr() {
        pairingJob?.cancel()
        val code = QrPairingCodes.generate()
        _ui.update { it.copy(qr = code, pairing = null) }
        pairingJob = scope.launch {
            val mdns = withContext(Dispatchers.IO) { adb.mdnsCheck() }
            _ui.update { it.copy(mdns = mdns) }
            if (mdns is AdbOutput.MdnsCheck.Unavailable) {
                _ui.update { it.copy(pairing = PairingState.Failed(FailReason.MDNS_UNAVAILABLE, mdns.detail)) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) { PairingFlow(adb).runQr(code) { s -> _ui.update { it.copy(pairing = s) } } }
            handleResult(result)
        }
    }

    fun updateForm(form: CodeForm) = _ui.update { it.copy(form = form) }

    fun pairWithCode() {
        val form = _ui.value.form
        val connect = form.connectAddress.takeIf { it.isNotBlank() }?.let { AdbOutput.normalizeAddress(it) }
        if (form.askConnectAddress && form.pairAddress.isBlank()) {
            // Second step: already paired, only the connect address is needed.
            if (connect == null) return setFormError("Enter the IP address & port shown on the Wireless debugging screen, like 192.168.1.20:41234.")
            return launchPairing { flow, report -> flow.runConnect(connect, report) }
        }
        val address = AdbOutput.normalizeAddress(form.pairAddress)
            ?: return setFormError("Enter the IP address & port shown under the pairing code, like 192.168.1.20:37755.")
        val code = AdbOutput.normalizePairingCode(form.code)
            ?: return setFormError("The pairing code is the six digits shown on the phone.")
        if (form.connectAddress.isNotBlank() && connect == null) return setFormError("The connect address should look like 192.168.1.20:41234.")
        launchPairing { flow, report -> flow.runCode(address, code, connect, report) }
    }

    private fun setFormError(message: String) = _ui.update { it.copy(form = it.form.copy(error = message)) }

    private fun launchPairing(block: suspend (PairingFlow, (PairingState) -> Unit) -> PairingState) {
        pairingJob?.cancel()
        _ui.update { it.copy(form = it.form.copy(error = null), pairing = null) }
        pairingJob = scope.launch {
            val result = withContext(Dispatchers.IO) { block(PairingFlow(adb)) { s -> _ui.update { it.copy(pairing = s) } } }
            handleResult(result)
        }
    }

    private suspend fun handleResult(result: PairingState) {
        when (result) {
            is PairingState.Connected -> {
                val id = result.guid ?: result.serial
                val device = RememberedDevice(
                    id = id, name = result.name, kind = RememberedDevice.Kind.TLS,
                    address = result.address, guid = result.guid, lastConnectedAtMillis = System.currentTimeMillis(),
                )
                remember(device)
                onConnected(result.serial)
            }
            is PairingState.TimedOut -> if (result.cause == TimeoutCause.CONNECT_NOT_FOUND) {
                // Paired: the code is spent, so the form's next step only needs the connect address.
                _ui.update { it.copy(form = it.form.copy(askConnectAddress = true, pairAddress = "", code = "")) }
            }
            else -> Unit
        }
    }

    /** *Switch to wireless* for a ready cable phone. */
    fun switchToWireless(serial: String, name: String) {
        if (legacyJob?.isActive == true) return
        _ui.update { it.copy(legacy = LegacyStatus(serial, running = true, message = "Switching to wireless…", ok = false)) }
        legacyJob = scope.launch {
            val result = withContext(Dispatchers.IO) { LegacySwitch(adb).switch(serial) }
            val ok = result is LegacySwitch.Result.Connected
            if (result is LegacySwitch.Result.Connected) {
                remember(
                    RememberedDevice(
                        id = "usb:$serial", name = name, kind = RememberedDevice.Kind.LEGACY,
                        address = result.address, lastConnectedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            _ui.update { it.copy(legacy = LegacyStatus(serial, running = false, message = LegacySwitch.describe(result), ok = ok)) }
        }
    }

    fun dismissLegacyStatus() = _ui.update { it.copy(legacy = null) }

    /** *Forget device*: drop it from the list and disconnect its Wi-Fi transport. */
    fun forget(device: RememberedDevice, currentDevices: List<Adb.DeviceInfo>) {
        val serials = currentDevices.filter { device.matches(it.serial) }.map { it.serial } + listOfNotNull(device.address)
        scope.launch {
            withContext(Dispatchers.IO) {
                remembered.forget(device.id)
                serials.distinct().forEach { adb.disconnect(it) }
            }
            _ui.update { it.copy(remembered = remembered.all()) }
        }
    }

    private suspend fun remember(device: RememberedDevice) {
        withContext(Dispatchers.IO) { remembered.remember(device) }
        _ui.update { it.copy(remembered = remembered.all()) }
    }

    /**
     * Retries remembered phones that aren't connected, for as long as the calling coroutine lives —
     * the window runs it only while visible, like the device poll. [devices] reads the latest poll.
     */
    suspend fun reconnectLoop(devices: () -> List<Adb.DeviceInfo>, periodMillis: Long = 4_000) {
        var mdnsWorks: Boolean? = null
        while (kotlin.coroutines.coroutineContext.isActive) {
            val list = remembered.all()
            if (list.isNotEmpty()) {
                val current = devices()
                val present = list.filter { r -> current.any { it.ready && r.matches(it.serial) } }.map { it.id }.toSet()
                val due = scheduler.due(System.currentTimeMillis(), list, present)
                if (due.isNotEmpty()) {
                    _ui.update { it.copy(reconnecting = due.map { d -> d.id }.toSet()) }
                    withContext(Dispatchers.IO) {
                        if (mdnsWorks == null) mdnsWorks = adb.mdnsCheck() is AdbOutput.MdnsCheck.Available
                        val services = if (due.any { it.kind == RememberedDevice.Kind.TLS }) adb.mdnsServices() else emptyList()
                        for (d in due) {
                            val outcome = reconnector.attempt(d, services, mdnsWorks == true, current)
                            scheduler.record(d.id, System.currentTimeMillis(), outcome.connected)
                            if (outcome.connected && outcome.address != null && outcome.address != d.address) {
                                remembered.updateAddress(d.id, outcome.address)
                            }
                        }
                    }
                    _ui.update { it.copy(reconnecting = emptySet(), remembered = remembered.all()) }
                }
            }
            delay(periodMillis)
        }
    }

    fun shutdown() {
        pairingJob?.cancel()
        legacyJob?.cancel()
    }

    companion object {
        const val ONBOARDING_SEEN = "logpose.desktop.onboarding.seen"
    }
}
