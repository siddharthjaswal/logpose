package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.BonjourBrowser
import io.github.siddharthjaswal.logpose.desktop.wireless.BonjourSession
import io.github.siddharthjaswal.logpose.desktop.wireless.Check
import io.github.siddharthjaswal.logpose.desktop.wireless.ConnectionDoctor
import io.github.siddharthjaswal.logpose.desktop.wireless.Diagnostics
import io.github.siddharthjaswal.logpose.desktop.wireless.DnsSdSession
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorContext
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorFacts
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorProbe
import io.github.siddharthjaswal.logpose.desktop.wireless.FailReason
import io.github.siddharthjaswal.logpose.desktop.wireless.FixAction
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingLog
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingProgress
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingTrace
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
    /** The connection doctor, on demand: every check, its verdict and fix, and *Copy diagnostics*. */
    data object ConnectionCheck : Sheet
}

/** One connection-doctor run: what it saw, what it concluded, and the shareable write-up. */
data class DoctorResult(val facts: DoctorFacts, val checks: List<Check>, val markdown: String) {
    val top: Check? get() = ConnectionDoctor.top(checks)

    companion object {
        fun of(facts: DoctorFacts, nowMillis: Long = System.currentTimeMillis()): DoctorResult {
            val checks = ConnectionDoctor.diagnose(facts)
            return DoctorResult(facts, checks, Diagnostics.markdown(facts, checks, nowMillis))
        }
    }
}

/** The doctor's state, for the stall panel and the Check-connection sheet alike. */
data class DoctorUi(
    val running: Boolean = false,
    val result: DoctorResult? = null,
    /** The Markdown preview is expanded. */
    val showReport: Boolean = false,
    /** "Copied" feedback after *Copy diagnostics*. */
    val copied: Boolean = false,
)

/** What the tester typed into the pairing-code form; kept in state so it survives a tab switch. */
data class CodeForm(
    val pairAddress: String = "",
    val code: String = "",
    val connectAddress: String = "",
    /** Shown once pairing worked but discovery couldn't find the connect port — or chosen by hand ([manual]). */
    val askConnectAddress: Boolean = false,
    /** *Enter IP & port manually*: the connect-only form for a phone that is already paired. */
    val manual: Boolean = false,
    val error: String? = null,
)

/** A *Switch to wireless* in progress or just finished, shown under that phone in the device switcher. */
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
    /** Remembered phones the tester disconnected: the auto-reconnect leaves them alone until *Reconnect*. */
    val paused: Set<String> = emptySet(),
    val legacy: LegacyStatus? = null,
    /** The running (or last) pairing attempt's record — stages, timings, which discovery won. */
    val trace: PairingTrace? = null,
    /** Wall clock for the elapsed times, ticked once a second while pairing. */
    val nowMillis: Long = 0,
    /** The doctor run when pairing stalled (or ended badly), shown inline on the Add-device sheet. */
    val stall: DoctorUi? = null,
    /** The Check-connection sheet's doctor run. */
    val check: DoctorUi = DoctorUi(),
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
    private val bonjour: BonjourBrowser = BonjourBrowser.shared,
    private val probe: DoctorProbe = DoctorProbe(adb, { Adb.resolve() }, bonjour),
    private val appVersion: String? = null,
    /** *Use Android Studio's adb*: the app model re-points core's adb at [String]. */
    private val onUseAdb: (String) -> Unit = {},
    private val copyText: (String) -> Unit = { text ->
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    },
    private val openUrl: (String) -> Unit = { url -> runCatching { ProcessBuilder("/usr/bin/open", url).start() } },
) {
    private val remembered = RememberedDevices(settings)
    private val scheduler = ReconnectScheduler()
    private val reconnector = Reconnector(adb)

    /** The running pairing attempt's Bonjour session and log, so a stall check can reuse what it saw. */
    @Volatile private var liveSession: BonjourSession? = null
    @Volatile private var liveLog: PairingLog? = null
    private var tickerJob: Job? = null
    private var stallJob: Job? = null
    private var checkJob: Job? = null

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
        stopPairing()
        _ui.update { it.copy(sheet = Sheet.AddDevice(tab), pairing = null, trace = null, stall = null, form = it.form.copy(error = null)) }
        if (tab == AddTab.QR) startQr()
    }

    /** *Enter IP & port manually*: the pairing-code tab in its connect-only mode — no mDNS at all. */
    fun connectByIp() {
        selectTab(AddTab.CODE)
        _ui.update { it.copy(form = it.form.copy(askConnectAddress = true, manual = true, pairAddress = "", code = "", error = null)) }
    }

    /** Back from the connect-only form to pairing with a code. */
    fun pairInstead() = _ui.update { it.copy(form = it.form.copy(askConnectAddress = false, manual = false, error = null)) }

    fun closeSheet() {
        stopPairing()
        checkJob?.cancel()
        if (_ui.value.sheet == Sheet.HowToConnect) {
            scope.launch(Dispatchers.IO) { settings.setBoolean(ONBOARDING_SEEN, true, false) }
        }
        _ui.update { it.copy(sheet = Sheet.None, pairing = null, qr = null, trace = null, stall = null, check = DoctorUi()) }
    }

    /** Cancels the attempt; its coroutine's `finally` kills the dns-sd browses. */
    private fun stopPairing() {
        pairingJob?.cancel()
        tickerJob?.cancel()
        stallJob?.cancel()
    }

    /** A fresh code and a fresh 90 s discovery window, with adb's discovery and macOS Bonjour racing. */
    fun startQr() {
        stopPairing()
        val code = QrPairingCodes.generate()
        _ui.update { it.copy(qr = code, pairing = null, trace = null, stall = null) }
        pairingJob = scope.launch {
            val mdns = withContext(Dispatchers.IO) { adb.mdnsCheck() }
            _ui.update { it.copy(mdns = mdns) }
            val bonjourOk = withContext(Dispatchers.IO) { bonjour.available }
            if (mdns is AdbOutput.MdnsCheck.Unavailable && !bonjourOk) {
                _ui.update { it.copy(pairing = PairingState.Failed(FailReason.MDNS_UNAVAILABLE, mdns.detail)) }
                return@launch
            }
            runFlow(setOf(AdbOutput.ServiceType.PAIRING, AdbOutput.ServiceType.CONNECT)) { flow, report -> flow.runQr(code, report) }
        }
    }

    /**
     * Runs one attempt with its own Bonjour session (killed when the attempt ends or is cancelled),
     * a 1 s ticker for elapsed times, and the stall watch: no new stage for 15 s — or a failure —
     * runs the connection doctor once for that stage and shows its top finding inline.
     */
    private suspend fun runFlow(types: Set<AdbOutput.ServiceType>, block: suspend (PairingFlow, (PairingState) -> Unit) -> PairingState) {
        val session = withContext(Dispatchers.IO) { DnsSdSession(bonjour, types) }
        val log = PairingLog()
        liveSession = session
        liveLog = log
        tickerJob?.cancel()
        tickerJob = scope.launch { tick(log) }
        try {
            val result = withContext(Dispatchers.IO) {
                PairingFlow(adb, bonjour = session, log = log).let { flow -> block(flow) { s -> _ui.update { it.copy(pairing = s, trace = log.snapshot(), nowMillis = System.currentTimeMillis()) } } }
            }
            _ui.update { it.copy(trace = log.snapshot()) }
            handleResult(result)
            if (result is PairingState.TimedOut || result is PairingState.Failed) runStallDoctor(log.snapshot(), session)
        } finally {
            tickerJob?.cancel()
            liveSession = null
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { session.close() }
        }
    }

    private suspend fun tick(log: PairingLog) {
        var checkedStage: Any? = null
        while (kotlin.coroutines.coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val trace = log.snapshot()
            _ui.update { it.copy(nowMillis = now, trace = trace) }
            val state = _ui.value.pairing
            if (PairingProgress.stalled(state, trace, now) && checkedStage != trace.currentStage) {
                checkedStage = trace.currentStage
                liveSession?.let { runStallDoctor(trace, it) }
            }
            delay(1_000)
        }
    }

    private fun runStallDoctor(trace: PairingTrace, session: BonjourSession?) {
        stallJob?.cancel()
        _ui.update { it.copy(stall = (it.stall ?: DoctorUi()).copy(running = true, copied = false)) }
        stallJob = scope.launch {
            val ctx = doctorContext(trace)
            val result = withContext(Dispatchers.IO) { runCatching { DoctorResult.of(probe.run(ctx, session)) }.getOrNull() }
            _ui.update { it.copy(stall = DoctorUi(running = false, result = result ?: it.stall?.result)) }
        }
    }

    private fun doctorContext(trace: PairingTrace?): DoctorContext {
        val list = _ui.value.remembered
        return DoctorContext(
            targetPairing = trace?.serviceName,
            targetGuids = setOfNotNull(trace?.guid) + list.mapNotNull { it.guid },
            knownAddresses = list.mapNotNull { it.address },
            pairing = trace,
            appVersion = appVersion,
        )
    }

    /** *Check connection*: opens the sheet and runs the doctor (again). */
    fun openConnectionCheck() {
        stopPairing()
        _ui.update { it.copy(sheet = Sheet.ConnectionCheck, pairing = null, stall = null) }
        runCheck()
    }

    /** Runs the doctor for the Check-connection sheet, including the last pairing attempt's trace. */
    fun runCheck() {
        checkJob?.cancel()
        val trace = _ui.value.trace
        _ui.update { it.copy(check = it.check.copy(running = true, copied = false)) }
        checkJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DoctorResult.of(probe.run(doctorContext(trace))) }.getOrNull() }
            _ui.update { it.copy(check = it.check.copy(running = false, result = result ?: it.check.result)) }
        }
    }

    fun toggleReport() = _ui.update { it.copy(check = it.check.copy(showReport = !it.check.showReport)) }

    /**
     * *Copy diagnostics*: the latest doctor run as Markdown, refreshed with the attempt's current
     * trace; when the doctor hasn't run yet, it runs first. Never includes the pairing password.
     */
    fun copyDiagnostics() {
        val ui = _ui.value
        val fromCheck = ui.sheet == Sheet.ConnectionCheck
        val existing = if (fromCheck) ui.check.result else ui.stall?.result ?: ui.check.result
        scope.launch {
            val base = existing ?: withContext(Dispatchers.IO) { runCatching { DoctorResult.of(probe.run(doctorContext(ui.trace), liveSession)) }.getOrNull() } ?: return@launch
            val facts = base.facts.copy(pairing = ui.trace ?: base.facts.pairing)
            val text = Diagnostics.markdown(facts, ConnectionDoctor.diagnose(facts))
            copyText(text)
            _ui.update {
                if (fromCheck) it.copy(check = it.check.copy(copied = true, result = it.check.result ?: base))
                else it.copy(stall = (it.stall ?: DoctorUi()).copy(copied = true, result = it.stall?.result ?: base))
            }
        }
    }

    /** A finding's button. */
    fun fix(action: FixAction) {
        when (action) {
            FixAction.USE_CABLE -> selectTab(AddTab.CABLE)
            FixAction.PAIRING_CODE -> selectTab(AddTab.CODE)
            FixAction.CONNECT_BY_IP -> connectByIp()
            FixAction.RETRY_QR -> selectTab(AddTab.QR)
            FixAction.OPEN_LOCAL_NETWORK -> scope.launch(Dispatchers.IO) { openUrl(LOCAL_NETWORK_SETTINGS) }
            FixAction.OPEN_FIREWALL -> scope.launch(Dispatchers.IO) { openUrl(FIREWALL_SETTINGS) }
            FixAction.USE_STUDIO_ADB -> {
                val path = (_ui.value.check.result ?: _ui.value.stall?.result)?.facts?.studioAdb ?: return
                onUseAdb(path)
                if (_ui.value.sheet == Sheet.ConnectionCheck) runCheck()
            }
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
        stopPairing()
        _ui.update { it.copy(form = it.form.copy(error = null), pairing = null, trace = null, stall = null) }
        // The connect step resolves the paired phone's connect service through Bonjour as well as adb.
        pairingJob = scope.launch { runFlow(setOf(AdbOutput.ServiceType.CONNECT), block) }
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
                _ui.update { it.copy(form = it.form.copy(askConnectAddress = true, manual = false, pairAddress = "", code = "")) }
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
            _ui.update { it.copy(remembered = remembered.all(), paused = it.paused - device.id) }
        }
    }

    /**
     * *Disconnect* on a Wi-Fi phone: `adb disconnect`, and — for a remembered phone — no auto-redial
     * until *Reconnect*, or it would be back within seconds. In memory only: the next launch dials it again.
     */
    fun disconnect(serial: String) {
        val memory = _ui.value.remembered.firstOrNull { it.matches(serial) }
        if (memory != null) _ui.update { it.copy(paused = it.paused + memory.id) }
        scope.launch(Dispatchers.IO) { adb.disconnect(serial) }
    }

    /** *Reconnect* on an offline remembered phone: lifts a disconnect's pause and tries on the next tick. */
    fun reconnectNow(id: String) {
        scheduler.reset(id)
        _ui.update { it.copy(paused = it.paused - id) }
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
        // One `dns-sd -B _adb-tls-connect._tcp` for as long as the loop runs and TLS phones are
        // remembered: a returning phone shows up (with its new port) the moment it advertises.
        var session: BonjourSession? = null
        try {
            reconnectTicks(devices, periodMillis, { mdnsWorks }, { mdnsWorks = it }) { want ->
                if (want && session == null) session = DnsSdSession(bonjour, setOf(AdbOutput.ServiceType.CONNECT))
                if (!want) { session?.close(); session = null }
                session ?: BonjourSession.None
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { session?.close() }
        }
    }

    private suspend fun reconnectTicks(
        devices: () -> List<Adb.DeviceInfo>,
        periodMillis: Long,
        mdnsWorksNow: () -> Boolean?,
        setMdnsWorks: (Boolean) -> Unit,
        bonjourFor: (Boolean) -> BonjourSession,
    ) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val wantBonjour = remembered.all().any { it.kind == RememberedDevice.Kind.TLS }
            val session = withContext(Dispatchers.IO) { bonjourFor(wantBonjour) }
            val paused = _ui.value.paused
            val list = remembered.all().filter { it.id !in paused }
            if (list.isNotEmpty()) {
                val current = devices()
                val present = list.filter { r -> current.any { it.ready && r.matches(it.serial) } }.map { it.id }.toSet()
                val due = scheduler.due(System.currentTimeMillis(), list, present)
                if (due.isNotEmpty()) {
                    _ui.update { it.copy(reconnecting = due.map { d -> d.id }.toSet()) }
                    withContext(Dispatchers.IO) {
                        if (mdnsWorksNow() == null) setMdnsWorks(adb.mdnsCheck() is AdbOutput.MdnsCheck.Available)
                        val services = if (due.any { it.kind == RememberedDevice.Kind.TLS }) adb.mdnsServices() else emptyList()
                        for (d in due) {
                            val outcome = reconnector.attempt(d, services, mdnsWorksNow() == true, current, session)
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
        stopPairing()
        checkJob?.cancel()
        legacyJob?.cancel()
        liveSession?.close()
        bonjour.closeAll() // no dns-sd outlives the window
    }

    companion object {
        const val ONBOARDING_SEEN = "logpose.desktop.onboarding.seen"
        const val LOCAL_NETWORK_SETTINGS = "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork"
        const val FIREWALL_SETTINGS = "x-apple.systempreferences:com.apple.preference.security?Firewall"
    }
}
