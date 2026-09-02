package io.github.siddharthjaswal.logpose.analysis

/**
 * The configured correlation vocabulary as a value: parse, serialize, validate, dedupe.
 *
 * A project's keys (`order_id`, `trip_id`) are a per-app vocabulary, so they are stored per
 * project — but *storing* them is a platform concern and *deciding what a valid key set is* is
 * not. Everything that can be decided without `PropertiesComponent` is decided here, which is
 * what makes the rules testable: a name that would never match anything, two spellings of one
 * key, a truncated line left behind by an older build.
 *
 * The wire format is deliberately dumb — one key per line, pipe-separated fields:
 *
 * ```
 * order_id|1|4|0
 * trip_id|0|4|1
 * ```
 *
 * A name can't contain `|` or a newline (see [sanitizeName]), so no escaping is possible or
 * needed, and [parse] treats anything it doesn't understand as absent rather than failing —
 * a corrupt settings string must cost the user their key list, never their tool window.
 *
 * LogPose ships **no** built-in keys: an empty list is the correct starting state, and
 * [Correlation.suggest] is how a human discovers what to put in it.
 */
object CorrelationKeys {

    /** How many keys one project may configure. Past this the extract scan stops being cheap. */
    const val MAX_KEYS = 24

    /** Matches [Correlation]'s own idea of a key name, so a configured key is always typeable
     *  as `key=value` in "Find by value…". */
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_.\\-]{0,63}")

    private const val RECORD = '\n'
    private const val FIELD = '|'

    /**
     * The name as it will be stored, or null when it could never be a key.
     *
     * Trims, and rejects anything the payload scan couldn't match anyway — a name has to start
     * like an identifier and hold only identifier characters. Rejecting here beats accepting a
     * key that silently matches nothing forever.
     */
    fun sanitizeName(raw: String): String? {
        val trimmed = raw.trim()
        return if (NAME.matches(trimmed)) trimmed else null
    }

    /**
     * `order_id`, `orderId`, `ORDER_ID` and `order-id` are one key — the same rule
     * [Correlation.extract] matches payload names by, so two spellings can never both be
     * configured and then disagree about which one grouped a row.
     */
    fun canonical(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    /**
     * The key set as it should be stored: valid names only, one entry per canonical name
     * (first wins, so an edit in the dialog beats a stale duplicate below it), capped.
     */
    fun normalize(keys: List<CorrelationKey>): List<CorrelationKey> {
        val seen = HashSet<String>()
        val out = ArrayList<CorrelationKey>(minOf(keys.size, MAX_KEYS))
        for (key in keys) {
            val name = sanitizeName(key.name) ?: continue
            if (!seen.add(canonical(name))) continue
            out.add(
                key.copy(
                    name = name,
                    minLength = key.minLength.coerceIn(1, 32),
                )
            )
            if (out.size == MAX_KEYS) break
        }
        return out
    }

    /** True when [name] is already in [keys], under any spelling of it. */
    fun contains(keys: List<CorrelationKey>, name: String): Boolean {
        val canonical = canonical(sanitizeName(name) ?: return false)
        return keys.any { canonical(it.name) == canonical }
    }

    /** [keys] plus [name], or [keys] unchanged when the name is invalid, duplicate, or over cap. */
    fun withAdded(
        keys: List<CorrelationKey>,
        name: String,
        enabled: Boolean = false,
    ): List<CorrelationKey> {
        val clean = sanitizeName(name) ?: return keys
        if (contains(keys, clean) || keys.size >= MAX_KEYS) return keys
        return keys + CorrelationKey(clean, enabled = enabled)
    }

    fun serialize(keys: List<CorrelationKey>): String =
        normalize(keys).joinToString(RECORD.toString()) {
            listOf(it.name, flag(it.enabled), it.minLength.toString(), flag(it.allowShortValues))
                .joinToString(FIELD.toString())
        }

    /**
     * Reads a stored key set back. Every field is optional and every failure is silent: a line
     * that isn't a key is dropped, a field that isn't a number takes its default.
     */
    fun parse(stored: String?): List<CorrelationKey> {
        if (stored.isNullOrBlank()) return emptyList()
        val keys = stored.split(RECORD).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val fields = line.split(FIELD)
            val name = sanitizeName(fields[0]) ?: return@mapNotNull null
            CorrelationKey(
                name = name,
                enabled = fields.getOrNull(1)?.let { it == "1" } ?: true,
                minLength = fields.getOrNull(2)?.toIntOrNull() ?: Correlation.DEFAULT_MIN_LENGTH,
                allowShortValues = fields.getOrNull(3) == "1",
            )
        }
        return normalize(keys)
    }

    private fun flag(on: Boolean) = if (on) "1" else "0"
}
