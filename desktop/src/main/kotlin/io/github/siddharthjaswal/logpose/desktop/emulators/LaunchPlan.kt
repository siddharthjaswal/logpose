package io.github.siddharthjaswal.logpose.desktop.emulators

/**
 * *Launch lean*: the emulator command line, built only from flags the installed emulator says it
 * accepts (`emulator -help`), so a newer or older emulator never gets a flag it would reject.
 *
 * Verified on emulator 37.2.12 (Apple M4, macOS 27): `-memory`, `-gpu host`, `-no-snapshot-save`,
 * `-no-snapshot-load`, `-no-boot-anim` and `-lowram` all exist. Measured there on a Pixel 9a AVD
 * (API 36.1 Google APIs): without `-lowram` the emulator logs *Increasing RAM size to 2560MB* and
 * ignores both `-memory 1536` and `hw.ramSize=1536` — a RAM floor it derives from the image and the
 * screen. `-lowram` lifts the floor, but it also makes Android report itself as a low-RAM device
 * (`ro.config.low_ram=true`, `ActivityManager.isLowRamDevice()`), which changes how apps behave.
 * So it is its own, explained, opt-in — never folded silently into "lean".
 */
object LaunchPlan {

    data class Options(
        val avd: String,
        val port: Int,
        val ramMb: Int?,
        /** Skip the Quick Boot snapshot: needed once after a slim, since a snapshot carries the old RAM. */
        val coldBoot: Boolean,
        /** `-lowram`: below the emulator's RAM floor, at the price of a low-RAM-device Android. */
        val lowRam: Boolean,
    )

    data class Plan(val args: List<String>, val skipped: List<String>) {
        fun commandLine(emulator: String): String = (listOf(emulator) + args).joinToString(" ")
    }

    /**
     * The option names in `emulator -help` output: lines like
     * `    -memory <size>      physical RAM size in MBs`.
     */
    fun supportedFlags(help: String): Set<String> = help.lineSequence()
        .mapNotNull { Regex("^\\s{2,}(-[A-Za-z0-9][A-Za-z0-9_-]*)(\\s|$)").find(it)?.groupValues?.get(1) }
        .toSet()

    /**
     * The command line for [o]: `-avd` and `-port` always (the port fixes the adb serial up front);
     * then each lean flag the emulator supports. [skipped] names what this emulator didn't offer.
     *
     * - `-memory <MB>` — the guest RAM for this run, matching the slimmed config.ini.
     * - `-gpu host` — the Mac's GPU (Metal), never a CPU renderer.
     * - `-no-snapshot-save` — don't write a multi-GB RAM snapshot on exit (and don't overwrite the
     *   Quick Boot snapshot Android Studio uses with a lean one).
     * - `-no-snapshot-load` — when [Options.coldBoot]: boot fresh rather than resume old RAM.
     * - `-no-boot-anim` — a faster boot; nothing the app can see.
     * - `-lowram` — only when asked (see the class comment).
     */
    fun build(o: Options, supported: Set<String>): Plan {
        val args = mutableListOf("-avd", o.avd, "-port", o.port.toString())
        val skipped = mutableListOf<String>()
        fun add(flag: String, vararg values: String) {
            if (flag in supported) { args += flag; args += values } else skipped += flag
        }
        o.ramMb?.let { add("-memory", it.toString()) }
        add("-gpu", "host")
        add("-no-snapshot-save")
        if (o.coldBoot) add("-no-snapshot-load")
        add("-no-boot-anim")
        if (o.lowRam) add("-lowram")
        return Plan(args, skipped)
    }

    /** `INFO | Increasing RAM size to 2560MB` in the emulator's output → 2560: this AVD's floor. */
    fun ramFloorFromLog(log: String): Int? =
        Regex("Increasing RAM size to (\\d+)\\s*MB", RegexOption.IGNORE_CASE).findAll(log).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
}

/** Where a lean launch is, for the progress panel. */
sealed interface LaunchStage {
    data object Starting : LaunchStage
    data object WaitingForAdb : LaunchStage
    data object Booting : LaunchStage
    data object Tweaking : LaunchStage
    data object Ready : LaunchStage
    data class Failed(val message: String, val logTail: String? = null) : LaunchStage
}
