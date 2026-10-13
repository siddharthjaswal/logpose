package io.github.siddharthjaswal.logpose.desktop.correlation

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.CorrelationKeys
import io.github.siddharthjaswal.logpose.analysis.Suggestion
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import io.github.siddharthjaswal.logpose.settings.FileKeyValueStore
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import java.io.File

/**
 * Where the Mac app keeps the two pieces of per-app vocabulary the IDE keeps per *project*: the
 * correlation keys and the saved mock scenarios.
 *
 * ### The decision
 *
 * The IDE and the daemon both anchor them in a project directory — `.logpose/correlation.properties`
 * ([FileKeyValueStore.sharedCorrelation]) and `.logpose/scenarios/` ([ScenarioStore.REL_DIR]) — so a
 * key configured in one is the key the other groups on. A QA tester has no project directory, and
 * inventing one (a hidden folder they never chose, or "the folder the last capture came from")
 * would make the vocabulary move around under them. So there are exactly two places, chosen by one
 * setting, never by guessing:
 *
 *  - **Default — this Mac.** Keys live in the app's own settings file (the same
 *    `logpose.correlation.keys` property name the shared file uses, so a value can be copied across
 *    by hand), scenarios in `~/Library/Application Support/LogPose/scenarios`. One vocabulary for
 *    every capture the tester records or opens — `order_id` is the app's language, not a session's.
 *  - **A developer's project folder**, set explicitly in *Advanced → Project folder*. Then the app
 *    reads and writes the very files the IDE plugin and `logpose serve --project-dir` use, so all
 *    three hosts agree. Nothing is migrated into the project on the way in: the project's file is
 *    committable, and the app must not write a tester's experiments into someone's repo unasked.
 *
 * Opening a `.logpose` capture changes neither: a shared capture carries events, not vocabulary.
 */
data class VocabularyLocation(
    /** Non-null exactly when a developer pointed the app at a project folder that exists. */
    val projectDir: File?,
    val scenariosDir: File,
) {
    /** True when keys and scenarios are the project's shared files rather than this Mac's. */
    val shared: Boolean get() = projectDir != null

    /** `.logpose/correlation.properties` in the project, or null for the app settings file. */
    val correlationFile: File? get() = projectDir?.let { File(File(it, ".logpose"), "correlation.properties") }

    /** How the keys dialog and the Advanced panel name where things are kept. */
    val label: String get() = projectDir?.let { "project ${it.name} — .logpose/, shared with the IDE and logpose serve" }
        ?: "this Mac — LogPose's own settings"

    /** How a saved scenario's path reads back (an MCP report, a notice). */
    fun scenarioPath(name: String): String =
        if (shared) "${ScenarioStore.REL_DIR}/$name.json" else File(scenariosDir, "$name.json").path
}

object Vocabulary {

    /** The app setting that names a developer's project folder; unset for a QA tester. */
    const val PROJECT_DIR_KEY = "logpose.desktop.projectDir"

    /**
     * Resolves the location from the stored setting. A folder that no longer exists (a deleted
     * checkout, an unmounted drive) falls back to this Mac rather than to a path nobody can write.
     */
    fun resolve(
        projectDirSetting: String?,
        appSupport: File,
        isDirectory: (File) -> Boolean = File::isDirectory,
    ): VocabularyLocation {
        val project = projectDirSetting?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(isDirectory)
        return VocabularyLocation(
            projectDir = project,
            scenariosDir = project?.let { File(it, ScenarioStore.REL_DIR) } ?: File(appSupport, "scenarios"),
        )
    }

    /**
     * The store the correlation vocabulary reads and writes for [location]: the project's shared
     * file — opened fresh, so a key the IDE saved a minute ago is the one read now — or the app's
     * own settings.
     */
    fun correlationStore(location: VocabularyLocation, appSettings: KeyValueStore): KeyValueStore =
        location.correlationFile?.let(::FileKeyValueStore) ?: appSettings
}

/**
 * The keys dialog's rows — the plugin `CorrelationKeysDialog`'s logic without the Swing: seeding,
 * appending suggestions, validation and the final normalized key list. What a key set *is* stays
 * [CorrelationKeys]'.
 *
 * **Nothing here is on until a human turns it on**: a suggestion arrives unticked and inert.
 */
data class KeysDraft(
    val rows: List<Row>,
    /** True when this open seeded the list from suggestions — drives the hint line. */
    val seeded: Boolean,
    val suggestions: List<Suggestion>,
) {
    data class Row(val name: String, val enabled: Boolean, val allowShort: Boolean, val note: String)

    fun appendSuggestions(): KeysDraft = copy(rows = withSuggestions(rows, suggestions))

    fun add(): KeysDraft = copy(rows = rows + Row("", true, false, ""))

    fun remove(index: Int): KeysDraft = copy(rows = rows.filterIndexed { i, _ -> i != index })

    fun edit(index: Int, change: (Row) -> Row): KeysDraft = copy(
        rows = rows.mapIndexed { i, r ->
            if (i != index) r else change(r).let { next -> if (next.name != r.name) next.copy(note = noteFor(next.name, suggestions)) else next }
        },
    )

    /** The plugin dialog's validation, word for word; null when the rows can be saved. */
    fun problem(): String? {
        val seen = HashSet<String>()
        for (row in rows) {
            val raw = row.name.trim()
            if (raw.isBlank()) return "Give every key a name, or remove the blank row."
            val name = CorrelationKeys.sanitizeName(raw)
                ?: return "\"$raw\" isn't a key name — use letters, digits, _ . or -, starting with a letter."
            if (!seen.add(CorrelationKeys.canonical(name))) return "\"$name\" is already in the list — order_id and orderId are the same key."
        }
        return null
    }

    fun result(): List<CorrelationKey> =
        CorrelationKeys.normalize(rows.map { CorrelationKey(it.name.trim(), enabled = it.enabled, allowShortValues = it.allowShort) })

    companion object {
        /**
         * A first open of a project that has never configured keys seeds the list with the
         * capture's own id-ish keys, unticked. A project that has been here before does not, so a
         * key the user removed stays removed — *Suggest from capture* brings them back on demand.
         */
        fun open(configured: List<CorrelationKey>, suggestions: List<Suggestion>, firstTime: Boolean): KeysDraft {
            val rows = configured.map { Row(it.name, it.enabled, it.allowShortValues, noteFor(it.name, suggestions)) }
            val seeded = firstTime && rows.isEmpty()
            return KeysDraft(if (seeded) withSuggestions(rows, suggestions) else rows, seeded && suggestions.isNotEmpty(), suggestions)
        }

        private fun withSuggestions(rows: List<Row>, suggestions: List<Suggestion>): List<Row> {
            val out = rows.toMutableList()
            for (s in suggestions) {
                if (out.size >= CorrelationKeys.MAX_KEYS) break
                if (out.any { CorrelationKeys.canonical(it.name) == CorrelationKeys.canonical(s.key) }) continue
                if (CorrelationKeys.sanitizeName(s.key) == null) continue
                out += Row(s.key, enabled = false, allowShort = false, note = noteOf(s))
            }
            return out
        }

        fun noteFor(name: String, suggestions: List<Suggestion>): String =
            suggestions.firstOrNull { CorrelationKeys.canonical(it.key) == CorrelationKeys.canonical(name) }?.let(::noteOf) ?: ""

        /** A suggestion's evidence: what it would group, and the value it last held. */
        fun noteOf(s: Suggestion): String {
            val grouped = "groups ${s.eventsGrouped} event${if (s.eventsGrouped == 1) "" else "s"}"
            val latest = s.latestValue?.let { "  ·  latest $it" } ?: ""
            return "$grouped  ·  largest ${s.largestGroup}$latest"
        }
    }
}
