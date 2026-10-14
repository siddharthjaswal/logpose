package io.github.siddharthjaswal.logpose.desktop.emulators

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * *Slim this AVD* / *Revert*: the config.ini levers, as a pure plan plus the file work around it.
 *
 * The levers (all three are what avdslim's `tune-avd` sets for memory; its other keys are left alone,
 * see [REJECTED]):
 *  - `hw.ramSize` — the guest's RAM, which qemu maps as host memory. Only ever **lowered**.
 *  - `hw.gpu.enabled=yes`, `hw.gpu.mode=host` — render with the Mac's GPU (Metal) instead of a CPU
 *    renderer (SwiftShader / lavapipe), whose frame buffers live in host RAM.
 */
object AvdSlim {
    val RAM_CHOICES = listOf(1024, 1536, 2048)
    const val DEFAULT_RAM = 1536

    const val BACKUP_PREFIX = "config.ini.logpose-backup-"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** avdslim's other config.ini keys, and why LogPose leaves each alone. Shown nowhere; kept for review. */
    val REJECTED = mapOf(
        "vm.heapSize" to "the per-app Java heap limit, not host memory; lowering it makes the app under test hit OutOfMemoryError sooner than on a phone",
        "hw.cpu.ncore" to "CPU, not memory; 2 cores slow boot and the app",
        "hw.audioInput/hw.audioOutput, hw.camera.*" to "turns off hardware the app under test may use (camera capture, mic, media)",
        "fastboot.forceColdBoot / forceFastBoot" to "turns off Quick Boot for every Android Studio launch; LogPose cold-boots only its own launches",
        "hw.keyboard, hw.dPad" to "input, not memory",
        "deleting hardware-qemu.ini and snapshots/" to "destroys saved Quick Boot / named snapshots; hardware-qemu.ini is regenerated from config.ini on every launch anyway",
    )

    data class Change(val key: String, val before: String?, val after: String)

    /** What slimming to [ramMb] would change, in file order of the levers; empty when already slim. */
    fun plan(ini: AvdIni, ramMb: Int): List<Change> {
        val out = ArrayList<Change>()
        val ram = AvdInfo.parseMb(ini["hw.ramSize"])
        if (ram == null || ram > ramMb) out += Change("hw.ramSize", ini["hw.ramSize"], ramMb.toString())
        if (ini["hw.gpu.enabled"]?.lowercase() !in setOf("yes", "true")) out += Change("hw.gpu.enabled", ini["hw.gpu.enabled"], "yes")
        if (ini["hw.gpu.mode"] != "host") out += Change("hw.gpu.mode", ini["hw.gpu.mode"], "host")
        return out
    }

    /** [text] with [changes] applied in place (see [AvdIni]). */
    fun apply(text: String, changes: List<Change>): String {
        val ini = AvdIni.parse(text)
        changes.forEach { ini.set(it.key, it.after) }
        return ini.render()
    }

    fun backupName(now: LocalDateTime): String = BACKUP_PREFIX + STAMP.format(now)

    /** LogPose's backups of [dir]'s config.ini, oldest first (the first one is the original). */
    fun backups(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(BACKUP_PREFIX) }.orEmpty().sortedBy { it.name }

    /**
     * Where an AVD stands: [backup] is the original config.ini when LogPose slimmed it. [editedSince]
     * means config.ini no longer looks like what LogPose wrote (Studio's Device Manager edited it), so
     * Revert would also drop those edits.
     */
    data class State(val backup: File?, val editedSince: Boolean, val slimmedToMb: Int?, val originalRamMb: Int? = null) {
        val slimmed: Boolean get() = backup != null
    }

    fun state(dir: File): State {
        val backup = backups(dir).firstOrNull() ?: return State(null, false, null)
        val original = runCatching { backup.readText() }.getOrNull() ?: return State(backup, false, null)
        val current = runCatching { File(dir, "config.ini").readText() }.getOrNull() ?: return State(backup, true, null)
        val now = AvdIni.parse(current)
        val ram = AvdInfo.parseMb(now["hw.ramSize"])
        // Untouched since the slim iff re-slimming the original to the RAM now in the file reproduces it.
        val expected = ram?.let { apply(original, plan(AvdIni.parse(original), it)) }
        return State(backup, editedSince = expected != current, slimmedToMb = ram, originalRamMb = AvdInfo.parseMb(AvdIni.parse(original)["hw.ramSize"]))
    }
}

/** The file side of [AvdSlim]: backup before the first change, refuse while running, exact revert. */
class AvdSlimmer(private val now: () -> LocalDateTime = LocalDateTime::now) {

    sealed interface Result {
        data class Done(val message: String, val changes: List<AvdSlim.Change> = emptyList(), val backup: File? = null) : Result
        data class Refused(val reason: String) : Result
    }

    fun slim(avd: AvdInfo, ramMb: Int, running: Boolean): Result {
        if (running) return Result.Refused(RUNNING)
        val config = avd.configFile
        val bytes = runCatching { config.readBytes() }.getOrElse { return Result.Refused("Couldn't read ${config.path}: ${it.message}") }
        val text = String(bytes, Charsets.UTF_8)
        val changes = AvdSlim.plan(AvdIni.parse(text), ramMb)
        if (changes.isEmpty()) return Result.Done("${avd.name} is already slim — nothing to change.")
        // The first change keeps the original; a second slim must not back up an already-slimmed file.
        val existing = AvdSlim.backups(avd.dir).firstOrNull()
        val backup = existing ?: File(avd.dir, AvdSlim.backupName(now())).also { b ->
            runCatching {
                Files.copy(config.toPath(), b.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                check(b.readBytes().contentEquals(bytes)) { "the backup doesn't match config.ini" }
            }.onFailure { e ->
                b.delete()
                return Result.Refused("Didn't change anything: couldn't write the backup (${e.message}).")
            }
        }
        val next = AvdSlim.apply(text, changes)
        runCatching { writeReplacing(config, next.toByteArray(Charsets.UTF_8)) }.onFailure { e ->
            return Result.Refused("Couldn't write config.ini (${e.message}). The original is in ${backup.name}.")
        }
        return Result.Done("Slimmed ${avd.name}. The original is kept as ${backup.name}.", changes, backup)
    }

    /** Puts the original back byte for byte, proves it, then removes LogPose's backups. */
    fun revert(avd: AvdInfo, running: Boolean): Result {
        if (running) return Result.Refused(RUNNING)
        val backups = AvdSlim.backups(avd.dir)
        val original = backups.firstOrNull() ?: return Result.Refused("LogPose has no backup for ${avd.name} — it was never slimmed here.")
        val bytes = runCatching { original.readBytes() }.getOrElse { return Result.Refused("Couldn't read ${original.name}: ${it.message}") }
        runCatching { writeReplacing(avd.configFile, bytes) }.onFailure { e -> return Result.Refused("Couldn't restore config.ini: ${e.message}") }
        if (!avd.configFile.readBytes().contentEquals(bytes)) return Result.Refused("config.ini doesn't match ${original.name} after restoring — the backup is kept.")
        backups.forEach { it.delete() }
        return Result.Done("Reverted ${avd.name}: config.ini is back to the original, byte for byte.")
    }

    /** Write beside, then rename over: a crash mid-write never leaves a half config.ini. */
    private fun writeReplacing(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.logpose-tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val RUNNING = "It's running. Shut the emulator down first — it reads config.ini only when it starts, and Android Studio rewrites the file while an AVD is open."
    }
}
