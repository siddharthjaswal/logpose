package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.emulators.AvdInfo
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdScanner
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlim
import io.github.siddharthjaswal.logpose.desktop.emulators.AvdSlimmer
import io.github.siddharthjaswal.logpose.desktop.emulators.EmulatorSdk
import io.github.siddharthjaswal.logpose.desktop.emulators.EmulatorSystem
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaker
import io.github.siddharthjaswal.logpose.desktop.emulators.GuestTweaks
import io.github.siddharthjaswal.logpose.desktop.emulators.HostMemory
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchPlan
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.emulators.RunningEmulators
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One AVD as the panel shows it. */
data class AvdRow(
    val avd: AvdInfo,
    val slim: AvdSlim.State = AvdSlim.State(null, false, null),
    val running: RunningEmulators.Running? = null,
    val guest: GuestTweaks.Record? = null,
    /** The RAM the emulator raised this AVD to last time (its floor), learned from a LogPose launch's log. */
    val ramFloorMb: Int? = null,
) {
    val isRunning: Boolean get() = running != null
}

/** What the sheet's body shows. */
sealed interface EmulatorPanel {
    data object Overview : EmulatorPanel
    /** [changes] are computed off the UI thread from the file as it is now; [reuseBackup] when an earlier slim kept the original. */
    data class SlimConfirm(
        val avd: String,
        val ramMb: Int,
        val changes: List<AvdSlim.Change> = emptyList(),
        val reuseBackup: String? = null,
        val loading: Boolean = true,
    ) : EmulatorPanel
    data class LaunchOptions(
        val avd: String,
        val ramMb: Int,
        val coldBoot: Boolean,
        val lowRam: Boolean,
        val guest: Boolean = false,
        val showPackages: Boolean = false,
    ) : EmulatorPanel
    data class Launching(val avd: String) : EmulatorPanel
}

/** A lean launch in progress (or just finished). */
data class LaunchUi(
    val avd: String,
    val serial: String,
    val command: String,
    val stage: LaunchStage,
    val startedAtMillis: Long,
    val nowMillis: Long,
    val stageAt: Map<String, Long> = emptyMap(),
    val guest: Boolean = false,
    val skippedFlags: List<String> = emptyList(),
    /** After boot: whether Android reports itself low-RAM (`ro.config.low_ram`). */
    val lowRamDevice: Boolean? = null,
    val ramFloorMb: Int? = null,
    val tweakMessage: String? = null,
)

data class EmulatorsUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    /** The emulator binary in use; null with [sdkMissing] when there is no SDK emulator. */
    val emulatorPath: String? = null,
    val sdkMissing: Boolean = false,
    val avdHome: String? = null,
    val rows: List<AvdRow> = emptyList(),
    val panel: EmulatorPanel = EmulatorPanel.Overview,
    /** A launch in flight (or just finished/failed) — shown in the sheet and inline in the device switcher. */
    val launch: LaunchUi? = null,
    /** Flags `emulator -help` lists — what Launch lean may use. */
    val supported: Set<String> = emptySet(),
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** AVDs with a slim / revert / shutdown / undo in flight. */
    val busy: Set<String> = emptySet(),
    /** Online emulator serial → the guest-tweak summary, for the device chip, the header and the capture metadata. */
    val guestBySerial: Map<String, String> = emptyMap(),
) {
    fun row(avd: String): AvdRow? = rows.firstOrNull { it.avd.name == avd }
}

/**
 * The Emulators panel (avdslim's ideas, our own implementation): list AVDs, slim / revert their
 * config.ini, launch lean, and the opt-in guest tweaks. Files and processes only on [Dispatchers.IO].
 * Writes only inside `~/.android/avd/<name>.avd/` and LogPose's settings; never the SDK.
 */
class EmulatorController(
    private val scope: CoroutineScope,
    private val settings: KeyValueStore,
    private val adb: AdbCommands = AdbCommands.Real,
    private val system: EmulatorSystem = EmulatorSystem.Real,
    private val locateSdk: () -> EmulatorSdk? = { EmulatorSdk.locate(io.github.siddharthjaswal.logpose.logcat.Adb.resolve()) },
    private val avdHome: () -> File = { AvdScanner.avdHome() },
    private val logDir: File = File(io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator.appSupportDir(), "emulators"),
    private val slimmer: AvdSlimmer = AvdSlimmer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val bootTimeoutMillis: Long = 300_000,
) {
    private val _ui = MutableStateFlow(EmulatorsUi())
    val ui: StateFlow<EmulatorsUi> = _ui.asStateFlow()

    private val tweaker = GuestTweaker(adb, settings, clock)
    private var pollJob: Job? = null
    private var launchJob: Job? = null
    /** emulator serial → AVD name, learned once per serial (`emu avd name`). */
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun open() {
        _ui.update { it.copy(open = true, panel = EmulatorPanel.Overview, message = null) }
        pollJob?.cancel()
        // Live host RAM while the sheet is up; a closed sheet polls nothing.
        pollJob = scope.launch {
            while (isActive) {
                refreshNow()
                delay(5_000)
            }
        }
    }

    fun close() {
        pollJob?.cancel()
        _ui.update { it.copy(open = false, panel = EmulatorPanel.Overview, message = null) }
    }

    fun refresh() { scope.launch { refreshNow() } }

    /**
     * Keeps the AVD list fresh for as long as the caller lives — the window runs it while the device
     * switcher or the no-device welcome is on screen, so stopped AVDs show without opening the sheet.
     */
    suspend fun watch(periodMillis: Long = 5_000) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            refreshNow()
            delay(periodMillis)
        }
    }

    fun back() = _ui.update { it.copy(panel = EmulatorPanel.Overview) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private suspend fun refreshNow() {
        if (_ui.value.rows.isEmpty()) _ui.update { it.copy(loading = true) }
        val snapshot = withContext(Dispatchers.IO) { scan() }
        _ui.update { snapshot(it) }
    }

    /** Reads everything off the UI thread and returns how to fold it into the state. */
    private fun scan(): (EmulatorsUi) -> EmulatorsUi {
        val sdk = locateSdk()
        val home = avdHome()
        val supported = _ui.value.supported.ifEmpty { sdk?.let { system.help(it.emulator) }?.let(LaunchPlan::supportedFlags).orEmpty() }
        val avds = AvdScanner.scan(home)
        val running = runningEmulators()
        val rows = avds.map { avd ->
            AvdRow(
                avd = avd,
                slim = AvdSlim.state(avd.dir),
                running = running.firstOrNull { it.avd == avd.name },
                guest = GuestTweaks.load(settings, avd.name),
                ramFloorMb = settings.get(FLOOR_PREFIX + avd.name)?.toIntOrNull(),
            )
        }
        val guest = guestMap(running)
        return { ui ->
            ui.copy(
                loading = false, emulatorPath = sdk?.emulator?.path, sdkMissing = sdk == null, avdHome = home.path,
                rows = rows, supported = supported, guestBySerial = guest,
            )
        }
    }

    /** adb + ps + footprint → which AVD runs where and what it costs. Blocking. */
    private fun runningEmulators(): List<RunningEmulators.Running> {
        val serials = RunningEmulators.emulatorSerials(adb.run(listOf("devices"), 5).orEmpty())
        val map = serials.mapNotNull { s -> avdNameOf(s)?.let { s to it } }.toMap()
        val procs = RunningEmulators.parsePs(system.ps().orEmpty())
        return RunningEmulators.join(map, procs).map { r ->
            val pid = r.pid ?: return@map r
            val mem = HostMemory.parseFootprint(system.footprint(pid))
                ?: procs.firstOrNull { it.pid == pid }?.let { HostMemory.fromRssKb(it.rssKb) }
            r.copy(memory = mem)
        }
    }

    private fun avdNameOf(serial: String): String? =
        names[serial] ?: RunningEmulators.parseAvdName(adb.run(listOf("-s", serial, "emu", "avd", "name"), 5))?.also { names[serial] = it }

    private fun guestMap(running: List<RunningEmulators.Running>): Map<String, String> =
        running.mapNotNull { r -> r.serial?.let { s -> GuestTweaks.load(settings, r.avd)?.let { s to it.summary() } } }.toMap()

    /**
     * Called with each device poll: learns the AVD behind any new emulator serial and refreshes
     * [EmulatorsUi.guestBySerial], so the device switcher, the toolbar chip and the status bar know without the sheet open.
     */
    fun noteDevices(serials: List<String>) {
        val emus = serials.filter { it.startsWith("emulator-") }
        names.keys.retainAll(emus.toSet())
        if (emus.isEmpty()) { if (_ui.value.guestBySerial.isNotEmpty()) _ui.update { it.copy(guestBySerial = emptyMap()) }; return }
        scope.launch(Dispatchers.IO) {
            val map = emus.mapNotNull { s -> avdNameOf(s)?.let { avd -> GuestTweaks.load(settings, avd)?.let { s to it.summary() } } }.toMap()
            if (map != _ui.value.guestBySerial) _ui.update { it.copy(guestBySerial = map) }
        }
    }

    /** The guest-tweak summary for a serial (blocking: may ask adb) — what a new session records. */
    fun guestSummaryFor(serial: String): String? {
        if (!serial.startsWith("emulator-")) return null
        val avd = avdNameOf(serial) ?: return null
        return GuestTweaks.load(settings, avd)?.summary()
    }

    // ---- Slim / Revert ----------------------------------------------------------------------------

    fun openSlim(avd: String) {
        val row = _ui.value.row(avd) ?: return
        if (row.isRunning) return say("${row.avd.name}: ${AvdSlimmer.RUNNING}", error = true)
        _ui.update { it.copy(panel = EmulatorPanel.SlimConfirm(avd, AvdSlim.DEFAULT_RAM), message = null) }
        planSlim(row.avd, AvdSlim.DEFAULT_RAM)
    }

    fun setSlimRam(ramMb: Int) {
        val p = _ui.value.panel as? EmulatorPanel.SlimConfirm ?: return
        val row = _ui.value.row(p.avd) ?: return
        _ui.update { it.copy(panel = p.copy(ramMb = ramMb)) }
        planSlim(row.avd, ramMb)
    }

    /** Reads config.ini (off the UI thread) and shows exactly what Slim would change. */
    private fun planSlim(avd: AvdInfo, ramMb: Int) {
        scope.launch {
            val (changes, backup) = withContext(Dispatchers.IO) {
                val ini = runCatching { io.github.siddharthjaswal.logpose.desktop.emulators.AvdIni.parse(avd.configFile.readText()) }.getOrNull()
                (ini?.let { AvdSlim.plan(it, ramMb) }.orEmpty()) to AvdSlim.backups(avd.dir).firstOrNull()?.name
            }
            _ui.update { ui ->
                val p = ui.panel as? EmulatorPanel.SlimConfirm ?: return@update ui
                if (p.avd != avd.name || p.ramMb != ramMb) ui else ui.copy(panel = p.copy(changes = changes, reuseBackup = backup, loading = false))
            }
        }
    }

    fun confirmSlim() {
        val p = _ui.value.panel as? EmulatorPanel.SlimConfirm ?: return
        val row = _ui.value.row(p.avd) ?: return
        busy(p.avd, true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { slimmer.slim(row.avd, p.ramMb, running = isRunningNow(row.avd.name)) }
            busy(p.avd, false)
            when (result) {
                is AvdSlimmer.Result.Done -> {
                    val snapshotNote = if (row.avd.hasQuickBootSnapshot && result.changes.isNotEmpty())
                        " It has a Quick Boot snapshot with the old RAM: boot cold once (Launch lean does; in Android Studio use Cold Boot Now)." else ""
                    _ui.update { it.copy(panel = EmulatorPanel.Overview) }
                    say(result.message + snapshotNote)
                }
                is AvdSlimmer.Result.Refused -> say(result.reason, error = true)
            }
            refreshNow()
        }
    }

    fun revert(avd: String) {
        val row = _ui.value.row(avd) ?: return
        busy(avd, true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { slimmer.revert(row.avd, running = isRunningNow(avd)) }
            busy(avd, false)
            when (result) {
                is AvdSlimmer.Result.Done -> say(result.message)
                is AvdSlimmer.Result.Refused -> say(result.reason, error = true)
            }
            refreshNow()
        }
    }

    /** Re-checks right before a write: the list may be up to five seconds old. Blocking. */
    private fun isRunningNow(avd: String): Boolean = runningEmulators().any { it.avd == avd }

    /** `adb -s emulator-N emu kill` — a clean shutdown (no snapshot is saved by a lean launch). */
    fun shutDown(avd: String) {
        val serial = _ui.value.row(avd)?.running?.serial ?: return say("$avd isn't visible to adb yet — close its window to stop it.", error = true)
        busy(avd, true)
        scope.launch {
            withContext(Dispatchers.IO) {
                adb.run(listOf("-s", serial, "emu", "kill"), 10)
                repeat(30) { if (runningEmulators().none { it.avd == avd }) return@withContext; Thread.sleep(1_000) }
            }
            busy(avd, false)
            refreshNow()
        }
    }

    // ---- Launch lean ------------------------------------------------------------------------------

    fun openLaunch(avd: String) {
        val ui = _ui.value
        val row = ui.row(avd) ?: return
        if (row.isRunning) return say("$avd is already running.", error = true)
        _ui.update { it.copy(panel = defaultLaunch(row, ui.supported), message = null) }
    }

    /** The launch sheet's starting options for [row] — also exactly what the switcher's *Launch lean* runs. */
    private fun defaultLaunch(row: AvdRow, supported: Set<String>): EmulatorPanel.LaunchOptions {
        val ram = row.slim.slimmedToMb ?: row.avd.ramMb?.coerceAtMost(AvdSlim.DEFAULT_RAM) ?: AvdSlim.DEFAULT_RAM
        return EmulatorPanel.LaunchOptions(
            row.avd.name, ram,
            // A Quick Boot snapshot carries the RAM it was taken with: skip it after a slim.
            coldBoot = row.avd.hasQuickBootSnapshot && row.slim.slimmed,
            lowRam = "-lowram" in supported,
        )
    }

    /** The launch-options sheet for one AVD, opened from outside it (the switcher's *Options…*). */
    fun openLaunchOptions(avd: String) {
        open()
        openLaunch(avd)
    }

    /** The full progress view of the launch in flight (the switcher's *Details…*). */
    fun showLaunch() {
        val l = _ui.value.launch ?: return open()
        open()
        _ui.update { it.copy(panel = EmulatorPanel.Launching(l.avd)) }
    }

    /**
     * The switcher's primary action: launch [avd] lean with the sheet's defaults, no sheet. Progress
     * is [EmulatorsUi.launch], which the switcher shows inline.
     */
    fun launchLean(avd: String) {
        val ui = _ui.value
        val row = ui.row(avd) ?: return
        if (row.isRunning) return say("$avd is already running.", error = true)
        start(defaultLaunch(row, ui.supported), stock = false)
    }

    /** *Launch*: as Android Studio's ▶ would — the AVD's own RAM and its Quick Boot snapshot, no lean flags. */
    fun launchStock(avd: String) {
        val ui = _ui.value
        val row = ui.row(avd) ?: return
        if (row.isRunning) return say("$avd is already running.", error = true)
        start(EmulatorPanel.LaunchOptions(avd, row.avd.ramMb ?: AvdSlim.DEFAULT_RAM, coldBoot = false, lowRam = false), stock = true)
    }

    fun editLaunch(options: EmulatorPanel.LaunchOptions) = _ui.update { it.copy(panel = options) }

    fun launch() {
        val o = _ui.value.panel as? EmulatorPanel.LaunchOptions ?: return
        start(o, stock = false)
    }

    private fun start(o: EmulatorPanel.LaunchOptions, stock: Boolean) {
        val ui = _ui.value
        if (ui.launch?.let { it.stage !is LaunchStage.Ready && it.stage !is LaunchStage.Failed } == true) {
            return say("${ui.launch.avd} is still starting — one launch at a time.", error = true)
        }
        val emulator = ui.emulatorPath ?: return say("No Android emulator on this Mac.", error = true)
        launchJob?.cancel()
        launchJob = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                if (isRunningNow(o.avd)) return@withContext null
                val taken = RunningEmulators.allEmulatorSerials(adb.run(listOf("devices"), 5).orEmpty()).mapNotNull(RunningEmulators::portOf) +
                    RunningEmulators.parsePs(system.ps().orEmpty()).mapNotNull { it.port }
                val port = RunningEmulators.freePort(taken) ?: return@withContext null
                port to LaunchPlan.build(LaunchPlan.Options(o.avd, port, o.ramMb, o.coldBoot, o.lowRam, stock = stock), ui.supported)
            } ?: return@launch say("${o.avd} is already running, or no emulator port is free.", error = true)
            val (port, plan) = prepared
            val serial = "emulator-$port"
            val start = clock()
            fun stage(s: LaunchStage, f: (LaunchUi) -> LaunchUi = { it }) = _ui.update { u ->
                val l = u.launch ?: return@update u
                u.copy(launch = f(l.copy(stage = s, nowMillis = clock(), stageAt = l.stageAt + (s::class.simpleName.orEmpty() to clock()))))
            }
            _ui.update {
                it.copy(
                    // The sheet follows the launch only when it's the sheet that started it.
                    panel = if (it.open) EmulatorPanel.Launching(o.avd) else it.panel,
                    launch = LaunchUi(o.avd, serial, plan.commandLine(emulator), LaunchStage.Starting, start, start, mapOf("Starting" to start), o.guest, plan.skipped),
                )
            }
            val log = File(logDir, "${o.avd}-${STAMP.format(LocalDateTime.now())}.log")
            val alive = withContext(Dispatchers.IO) { system.spawn(listOf(emulator) + plan.args, log) }
                ?: return@launch stage(LaunchStage.Failed("Couldn't start $emulator."))
            val outcome = withContext(Dispatchers.IO) { awaitBoot(o.avd, serial, alive, log) { s -> stage(s) } }
            if (outcome != null) return@launch stage(outcome)
            val (lowRam, floor) = withContext(Dispatchers.IO) {
                val low = adb.run(listOf("-s", serial, "shell", "getprop", "ro.config.low_ram"), 5)?.trim()?.lowercase() == "true"
                val f = runCatching { LaunchPlan.ramFloorFromLog(log.readText()) }.getOrNull()
                f?.let { settings.set(FLOOR_PREFIX + o.avd, it.toString()) }
                low to f
            }
            var tweakMessage: String? = null
            if (o.guest) {
                stage(LaunchStage.Tweaking)
                val r = withContext(Dispatchers.IO) { tweaker.apply(serial, o.avd) }
                tweakMessage = when (r) { is GuestTweaker.Result.Done -> r.message; is GuestTweaker.Result.Failed -> r.message }
            }
            stage(LaunchStage.Ready) { it.copy(lowRamDevice = lowRam, ramFloorMb = floor, tweakMessage = tweakMessage) }
            refreshNow()
            // Launched from the switcher (no sheet to say Done in): once the emulator is a device, the
            // progress has nothing left to say.
            if (!_ui.value.open) _ui.update { u -> if (u.launch?.stage is LaunchStage.Ready) u.copy(launch = null) else u }
        }
    }

    /**
     * Waits for adb to see [serial] and Android to finish booting, watching for an emulator that died.
     * Returns null when booted, else the failure. The emulator launcher may hand over to qemu and
     * exit, so "dead" means: the launcher is gone **and** no qemu process for this AVD exists.
     */
    private fun awaitBoot(avd: String, serial: String, alive: () -> Boolean, log: File, report: (LaunchStage) -> Unit): LaunchStage? {
        val deadline = clock() + bootTimeoutMillis
        fun dead() = !alive() && RunningEmulators.parsePs(system.ps().orEmpty()).none { it.avd == avd }
        fun failed(msg: String) = LaunchStage.Failed(msg, runCatching { log.readLines().takeLast(12).joinToString("\n") }.getOrNull())
        Thread.sleep(1_500)
        report(LaunchStage.WaitingForAdb)
        while (true) {
            if (clock() > deadline) return failed("adb never saw $serial. The emulator may still be starting — it keeps running.")
            if (dead()) return failed("The emulator exited during startup.")
            if (RunningEmulators.allEmulatorSerials(adb.run(listOf("devices"), 5).orEmpty()).contains(serial)) break
            Thread.sleep(1_000)
        }
        report(LaunchStage.Booting)
        while (true) {
            if (clock() > deadline) return failed("Android didn't finish booting in time. The emulator keeps running.")
            if (dead()) return failed("The emulator exited while Android was booting.")
            if (adb.run(listOf("-s", serial, "shell", "getprop", "sys.boot_completed"), 5)?.trim() == "1") return null
            Thread.sleep(2_000)
        }
    }

    /** Leaves the progress view; a launch already under way keeps running (and so does the emulator). */
    fun dismissLaunch() {
        if (_ui.value.launch?.stage.let { it is LaunchStage.Ready || it is LaunchStage.Failed }) _ui.update { it.copy(launch = null) }
        _ui.update { it.copy(panel = EmulatorPanel.Overview) }
        refresh()
    }

    // ---- Guest tweaks -----------------------------------------------------------------------------

    fun undoGuest(avd: String) {
        val serial = _ui.value.row(avd)?.running?.serial ?: return say("Start $avd first — the tweaks are undone over adb on the running emulator.", error = true)
        busy(avd, true)
        scope.launch {
            val r = withContext(Dispatchers.IO) { tweaker.undo(serial, avd) }
            busy(avd, false)
            when (r) {
                is GuestTweaker.Result.Done -> say(r.message)
                is GuestTweaker.Result.Failed -> say(r.message, error = true)
            }
            refreshNow()
        }
    }

    private fun busy(avd: String, on: Boolean) = _ui.update { it.copy(busy = if (on) it.busy + avd else it.busy - avd) }

    private fun say(text: String, error: Boolean = false) = _ui.update { it.copy(message = text, messageIsError = error) }

    fun shutdown() {
        pollJob?.cancel()
        // A launch in flight only stops being watched: the emulator is detached and keeps running.
        launchJob?.cancel()
    }

    companion object {
        const val FLOOR_PREFIX = "logpose.desktop.emulators.ramFloor."
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
