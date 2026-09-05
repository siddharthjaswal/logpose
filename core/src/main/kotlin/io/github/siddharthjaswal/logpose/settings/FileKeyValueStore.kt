package io.github.siddharthjaswal.logpose.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * A file-backed [KeyValueStore]: a `java.util.Properties` file under `<project-dir>/.logpose/`,
 * beside the `scenarios/` directory the IDE already writes there. This is the headless host's
 * answer to the store [KeyValueStore] describes — "A headless host backs it with a properties
 * file instead; nothing in core knows the difference."
 *
 * Semantics are copied from the platform's `PropertiesComponent`, not invented — a null value
 * removes the key, and `setInt`/`setBoolean` store nothing when the value equals the default —
 * because core's controllers were written against those rules and read back through them. That
 * exact match is what lets a single file be a **shared** seam between the IDE and the daemon:
 * [sharedCorrelation] backs a project's correlation vocabulary with `.logpose/correlation.properties`,
 * which both halves read and write, exactly as `ScenarioStore` shares `.logpose/scenarios`. The
 * daemon's own private settings (token, mock rules, revision) still live in [forProject]'s
 * `daemon.properties`, which the plugin does not read.
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
            temp.outputStream().use { props.store(it, "LogPose settings — do not edit while a session is running") }
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
        /** `<project-dir>/.logpose/daemon.properties` — the daemon's own private settings. */
        fun forProject(projectDir: File): FileKeyValueStore =
            FileKeyValueStore(File(File(projectDir, ".logpose"), "daemon.properties"))

        /**
         * `<project-dir>/.logpose/correlation.properties` — the project's correlation vocabulary,
         * shared between the IDE plugin and the daemon. Both construct this over the same file, so a
         * key configured in the tool window is visible to `list_correlation_keys`/`get_related` in a
         * daemon run against the same project dir, and vice versa. Committable, like scenarios.
         */
        fun sharedCorrelation(projectDir: File): FileKeyValueStore =
            FileKeyValueStore(File(File(projectDir, ".logpose"), "correlation.properties"))
    }
}
