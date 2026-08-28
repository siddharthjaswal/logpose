package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.MockRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A named, committable set of mock rules: `.logpose/scenarios/<name>.json` in the project.
 *
 * Files rather than `PropertiesComponent` on purpose — sharing a repro with a teammate should be
 * committing a file, and an offline demo of a whole app should survive a machine. The stored
 * shape is the wire [MockRule], so a scenario stays honest about what it will actually make the
 * device serve.
 *
 * ⚠ Scenarios contain **captured response bodies**. Review one before committing it.
 *
 * Deliberately plain `java.io` and free of IntelliJ types so it can be unit-tested against a
 * temp directory. Callers do the I/O off the EDT.
 */
class ScenarioStore(val dir: File) {

    /** A saved scenario file. Unknown JSON keys are ignored, so a newer file still loads. */
    @Serializable
    data class Scenario(
        val name: String,
        val createdAt: Long = 0,
        val note: String? = null,
        val rules: List<MockRule> = emptyList(),
    )

    /** Scenario names on disk, alphabetical. Never throws — a missing directory is "none". */
    fun listNames(): List<String> =
        dir.listFiles { f: File -> f.isFile && f.name.endsWith(EXT) }
            ?.map { it.name.removeSuffix(EXT) }
            ?.filter { isValidName(it) }
            ?.sorted()
            .orEmpty()

    /** Every scenario that parses. A corrupt file is skipped, not fatal. */
    fun list(): List<Scenario> = listNames().mapNotNull { load(it) }

    fun load(name: String): Scenario? {
        val file = fileFor(name) ?: return null
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(Scenario.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Writes [scenario] under its own name, creating `.logpose/scenarios/` if needed. Returns the
     * file written, or null when the name isn't one this store will accept (see [isValidName]) —
     * which is also what makes traversal impossible: a name that could escape the directory never
     * reaches the filesystem.
     */
    fun save(scenario: Scenario): File? {
        val file = fileFor(scenario.name) ?: return null
        dir.mkdirs()
        file.writeText(json.encodeToString(Scenario.serializer(), scenario))
        return file
    }

    fun delete(name: String): Boolean = fileFor(name)?.takeIf { it.isFile }?.delete() ?: false

    fun exists(name: String): Boolean = fileFor(name)?.isFile == true

    private fun fileFor(name: String): File? =
        if (!isValidName(name)) null else File(dir, name + EXT)

    companion object {
        private const val EXT = ".json"
        const val MAX_NAME = 64

        /** The scenarios directory, relative to the project root. */
        const val REL_DIR = ".logpose/scenarios"

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        // Lowercase letters, digits, '-' and '_' only. No dots, no separators, no "..": a valid
        // name cannot address anything outside the scenarios directory, by construction rather
        // than by sanitising a path afterwards.
        private val VALID = Regex("[a-z0-9_-]{1,$MAX_NAME}")

        fun isValidName(name: String): Boolean = VALID.matches(name)

        /**
         * Best-effort conversion of something a human typed ("Order assigned — offline!") into a
         * valid name ("order-assigned-offline"). Returns null if nothing usable survives.
         */
        fun sanitize(raw: String): String? {
            val slug = raw.trim().lowercase()
                .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(MAX_NAME)
            return slug.takeIf { isValidName(it) }
        }

        /** The store for a project, or null when the project has no directory on disk. */
        fun forProject(basePath: String?): ScenarioStore? =
            basePath?.takeIf { it.isNotBlank() }?.let { ScenarioStore(File(it, REL_DIR)) }
    }
}
