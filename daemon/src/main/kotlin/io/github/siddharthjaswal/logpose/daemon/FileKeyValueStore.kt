package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The daemon's answer to core's [KeyValueStore]: a `java.util.Properties` file at
 * `<project-dir>/.logpose/daemon.properties`, beside the `scenarios/` directory the IDE already
 * writes there.
 *
 * Semantics are copied from the platform's `PropertiesComponent`, not invented — a null value
 * removes the key, and `setInt`/`setBoolean` store nothing when the value equals the default —
 * because core's controllers were written against those rules and read back through them.
 *
 * **This is not the IDE's store.** The plugin persists the same keys in `PropertiesComponent`,
 * which lives in the IDE's own workspace file, so a token or a correlation vocabulary configured
 * in the tool window is not visible here (see the note on [io.github.siddharthjaswal.logpose.settings.CorrelationSettings]
 * in the daemon's README-facing docs). Scenarios, which are plain files under `.logpose/`, *are*
 * shared — that is the seam the PRD actually promised.
 *
 * Thread-safety: every method is synchronized on the instance. Writes are whole-file, which is
 * fine for a store this size (a token, a rule set, a revision counter) and is what makes the
 * temp-file-plus-rename below atomic enough that a kill mid-write can't leave a truncated file.
 */
class FileKeyValueStore(private val file: File) : KeyValueStore {

    private val props = Properties()

    init {
        if (file.isFile) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
    }

    @Synchronized
    override fun get(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun set(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        flush()
    }

    @Synchronized
    override fun getInt(key: String, default: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun setInt(key: String, value: Int, default: Int) {
        set(key, if (value == default) null else value.toString())
    }

    @Synchronized
    override fun getBoolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    @Synchronized
    override fun setBoolean(key: String, value: Boolean, default: Boolean) {
        set(key, if (value == default) null else value.toString())
    }

    /**
     * Writes the whole file: into a sibling temp file first, then renamed over the real one, so a
     * reader (or a crash) never sees a half-written properties file. Falls back to a plain copy
     * when the filesystem refuses an atomic move.
     */
    @Synchronized
    fun flush() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("daemon", ".properties", file.parentFile)
            temp.outputStream().use { props.store(it, "LogPose daemon settings — do not edit while running") }
            runCatching {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { temp.delete() }
        }
    }

    companion object {
        /** `<project-dir>/.logpose/daemon.properties`, the directory created on demand. */
        fun forProject(projectDir: File): FileKeyValueStore =
            FileKeyValueStore(File(File(projectDir, ".logpose"), "daemon.properties"))
    }
}
