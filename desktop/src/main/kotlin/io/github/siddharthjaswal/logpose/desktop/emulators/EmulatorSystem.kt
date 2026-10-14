package io.github.siddharthjaswal.logpose.desktop.emulators

import java.io.File
import java.util.concurrent.TimeUnit

/** The SDK's emulator on this Mac, found the way the app finds adb (env vars, adb's own SDK, the default). */
data class EmulatorSdk(val root: File) {
    val emulator: File get() = File(root, "emulator/emulator")

    companion object {
        fun locate(
            adbPath: String?,
            env: (String) -> String? = System::getenv,
            home: String = System.getProperty("user.home"),
            canExecute: (File) -> Boolean = { it.isFile && it.canExecute() },
        ): EmulatorSdk? {
            val roots = listOfNotNull(
                env("ANDROID_HOME"),
                env("ANDROID_SDK_ROOT"),
                // `<sdk>/platform-tools/adb` → `<sdk>`; a downloaded platform-tools has no emulator beside it.
                adbPath?.let(::File)?.takeIf { it.parentFile?.name == "platform-tools" }?.parentFile?.parentFile?.path,
                "$home/Library/Android/sdk",
            ).filter { it.isNotBlank() }.distinct()
            return roots.map { EmulatorSdk(File(it)) }.firstOrNull { canExecute(it.emulator) }
        }
    }
}

/**
 * The processes the Emulators panel runs, behind an interface so the controller is tested with
 * canned output. Every call is **blocking**: callers are on Dispatchers.IO, never the UI thread.
 */
interface EmulatorSystem {
    /** `emulator -help` (the flag list LaunchPlan checks against). */
    fun help(emulator: File): String?
    /** `ps -axo pid=,rss=,command=` */
    fun ps(): String?
    /** `footprint -p <pid>` — phys_footprint, as Activity Monitor. Null if footprint isn't there. */
    fun footprint(pid: Int): String?
    /**
     * Starts [command] detached from LogPose — `nohup`, stdin from /dev/null, output to [log] — so the
     * emulator outlives the app. Returns a liveness probe, or null if it couldn't start.
     */
    fun spawn(command: List<String>, log: File): (() -> Boolean)?

    object Real : EmulatorSystem {
        override fun help(emulator: File): String? = run(listOf(emulator.path, "-help"), 15)
        override fun ps(): String? = run(listOf("/bin/ps", "-axo", "pid=,rss=,command="), 5)
        override fun footprint(pid: Int): String? =
            File("/usr/bin/footprint").takeIf { it.canExecute() }?.let { run(listOf(it.path, "-p", pid.toString()), 10) }

        override fun spawn(command: List<String>, log: File): (() -> Boolean)? = runCatching {
            log.parentFile?.mkdirs()
            val proc = ProcessBuilder(listOf("/usr/bin/nohup") + command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val probe: () -> Boolean = { proc.isAlive }
            probe
        }.getOrNull()

        private fun run(cmd: List<String>, timeoutSeconds: Long): String? = runCatching {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc.outputStream.close()
            val out = StringBuilder()
            val reader = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
                .apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { proc.destroyForcibly(); return@runCatching null }
            reader.join(1_000)
            synchronized(out) { out.toString() }
        }.getOrNull()
    }
}
