package io.github.siddharthjaswal.logpose.desktop.emulators

/**
 * Which AVD is running where, and how much of the Mac it holds — parsers over the output of
 * `adb devices`, `adb -s emulator-N emu avd name`, `ps` and `footprint`, so the mapping is tested
 * against captured text and the runner stays a thin shell.
 */
object RunningEmulators {

    /** A running AVD: its adb serial (when adb sees it), its qemu process, and that process's memory. */
    data class Running(val avd: String, val serial: String?, val pid: Int?, val memory: HostMemory? = null)

    /** One `qemu-system-*` process from `ps -axo pid=,rss=,command=`. */
    data class QemuProcess(val pid: Int, val rssKb: Long, val avd: String?, val port: Int?)

    /** The `emulator-NNNN` serials in `adb devices` output that are online (state `device`). */
    fun emulatorSerials(adbDevices: String): List<String> = adbDevices.lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[0].startsWith("emulator-") && it[1] == "device" }
        .map { it[0] }.toList()

    /** Every `emulator-NNNN` serial, whatever its state (an offline one still holds its port). */
    fun allEmulatorSerials(adbDevices: String): List<String> = adbDevices.lineSequence()
        .map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
        .filter { it.startsWith("emulator-") }.toList()

    /** `emu avd name` answers `Pixel_9a\r\nOK`; anything else (`KO`, an error, blank) is null. */
    fun parseAvdName(out: String?): String? {
        val lines = out?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList() ?: return null
        val first = lines.firstOrNull() ?: return null
        if (first == "OK" || first.startsWith("KO") || first.contains("error", ignoreCase = true) || first.contains(' ')) return null
        return first
    }

    /** qemu processes from `ps -axo pid=,rss=,command=`; crashpad and netsim helpers are skipped. */
    fun parsePs(ps: String): List<QemuProcess> = ps.lineSequence().mapNotNull { line ->
        val t = line.trim()
        val m = Regex("^(\\d+)\\s+(\\d+)\\s+(.*)$").find(t) ?: return@mapNotNull null
        val command = m.groupValues[3]
        val exe = command.substringBefore(' ')
        if (!exe.substringAfterLast('/').startsWith("qemu-system-")) return@mapNotNull null
        val args = command.split(Regex("\\s+"))
        fun arg(flag: String) = args.indexOf(flag).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
        QemuProcess(m.groupValues[1].toInt(), m.groupValues[2].toLong(), arg("-avd"), arg("-port")?.toIntOrNull())
    }.toList()

    /** The console port in a serial: `emulator-5554` → 5554. */
    fun portOf(serial: String): Int? = serial.removePrefix("emulator-").toIntOrNull()

    /**
     * Joins the three views. [names] maps each online emulator serial to the AVD it says it runs.
     * A qemu process is matched to its AVD by `-avd`, and to a serial by `-port` — or, for a launch
     * without `-port` (Android Studio's), by being the only process for that AVD. An AVD with a qemu
     * process but no serial yet is still booting; it counts as running.
     */
    fun join(names: Map<String, String>, processes: List<QemuProcess>): List<Running> {
        val avds = (names.values + processes.mapNotNull { it.avd }).distinct()
        return avds.map { avd ->
            val serial = names.entries.firstOrNull { it.value == avd }?.key
            val mine = processes.filter { it.avd == avd }
            val proc = mine.firstOrNull { p -> serial != null && p.port == portOf(serial) }
                ?: mine.singleOrNull()
                ?: mine.firstOrNull { it.port == null && serial != null && portOf(serial) == DEFAULT_PORT }
            Running(avd, serial, proc?.pid)
        }
    }

    /** The first free even console port in 5554..5682 — so LogPose knows its launch's serial up front. */
    fun freePort(taken: Collection<Int>): Int? = (DEFAULT_PORT..5682 step 2).firstOrNull { it !in taken && it + 1 !in taken }

    const val DEFAULT_PORT = 5554
}

/** A process's memory as LogPose could measure it, and how. */
data class HostMemory(val mb: Int, val source: Source) {
    enum class Source(val label: String) {
        /** `footprint -p`: phys_footprint, the number Activity Monitor's Memory column shows. */
        FOOTPRINT("footprint · as Activity Monitor"),
        /** `ps -o rss`: resident pages only — lower than Activity Monitor (it misses compressed memory). */
        RSS("RSS via ps · lower than Activity Monitor"),
    }

    companion object {
        /**
         * The `phys_footprint:` line of `footprint -p <pid>` (`5403 MB`, `1.2 GB`, `812 KB`,
         * or bare bytes from `--format bytes`). Null when the line isn't there.
         */
        fun parseFootprint(out: String?): HostMemory? {
            val line = out?.lineSequence()?.firstOrNull { it.trim().startsWith("phys_footprint:") } ?: return null
            val m = Regex("phys_footprint:\\s*([0-9.]+)\\s*([A-Za-z]*)").find(line) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val mb = when (m.groupValues[2].uppercase()) {
                "KB", "K", "KIB" -> v / 1024
                "MB", "M", "MIB" -> v
                "GB", "G", "GIB" -> v * 1024
                "B", "", "BYTES" -> v / (1024 * 1024)
                else -> return null
            }
            return HostMemory(Math.round(mb).toInt(), Source.FOOTPRINT)
        }

        fun fromRssKb(kb: Long): HostMemory = HostMemory(Math.round(kb / 1024.0).toInt(), Source.RSS)
    }
}
