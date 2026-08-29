package io.github.siddharthjaswal.logpose.analysis

/**
 * Derives the operation and table from a SQL statement, so a database row can read
 * `[SELECT] users` instead of dumping the whole statement into the timeline.
 *
 * This lives in the plugin rather than the library on purpose: parsing on one side of the wire
 * keeps a single implementation, and the device only has to send what it actually knows.
 *
 * It is deliberately shallow — a prefix and the identifier after the keyword. Anything it can't
 * read confidently becomes [OTHER] with a null table rather than a wrong guess, because a
 * mislabelled row is worse than an unlabelled one. That bar is why [identifierAt] rejects SQL
 * keywords outright: `UPDATE OR ABORT \`x\` SET …` used to name the table `OR`, which then became
 * the row's *primary* text and the table filter MCP matches on.
 *
 * The vocabulary here stays semantic and lowercase because [Summary.operation] is emitted verbatim
 * over MCP. Display abbreviations (`TXN`) belong in `ui/RowContent.kt`, not here.
 */
object SqlSummary {

    const val SELECT = "select"
    const val INSERT = "insert"
    const val UPDATE = "update"
    const val DELETE = "delete"
    const val TRANSACTION = "transaction"
    const val PRAGMA = "pragma"
    const val SCHEMA = "schema"
    const val OTHER = "other"

    data class Summary(val operation: String, val table: String?)

    /**
     * What a statement does to a transaction, for the row-folding pass in
     * `analysis/RowCollapse.kt`. Ceremony is recognised here because this is the one place that
     * reads SQL — a second parser in the folder would be a second thing to keep correct.
     *
     * [CHANGES] covers Room's `SELECT changes()` probe, which is ceremony in practice: it exists
     * only to report what the transaction just did.
     */
    enum class Role { NONE, OPEN, SUCCESS, CLOSE, ABORT, CHANGES }

    /** Statements that mark transaction boundaries rather than touching data. */
    private val TRANSACTION_PREFIXES = listOf(
        "begin", "commit", "rollback", "end", "savepoint", "release", "transaction successful",
    )
    private val SCHEMA_PREFIXES = listOf("create", "drop", "alter", "vacuum", "reindex", "analyze")
    private val QUOTES = Regex("[`\"\\[\\]']")

    /** SQLite's conflict clause sits between the verb and the table: `UPDATE OR ABORT users …`. */
    private val CONFLICT = Regex("^\\s+or\\s+(rollback|abort|fail|ignore|replace)\\b")

    /** Room's post-statement probe, and its siblings. */
    private val CHANGES = Regex("^select (changes|total_changes|last_insert_rowid) ?\\( ?\\)$")

    /**
     * Tokens that can follow a verb but are never a table name. Without this guard the parser
     * degrades from "no table" to "wrong table", which is the one failure this object exists to
     * avoid.
     */
    private val NOT_A_TABLE = setOf(
        "or", "into", "from", "set", "values", "select", "table", "index", "view", "if", "not",
        "exists", "temp", "temporary", "unique", "virtual", "where", "join", "as", "on",
    )

    fun of(sql: String): Summary {
        val normalized = normalize(sql)
        val lower = normalized.lowercase()

        if (TRANSACTION_PREFIXES.any { lower.startsWithWord(it) }) return Summary(TRANSACTION, null)
        // PRAGMA is its own verb, not schema DDL — §6 gives it its own tag and an agent filtering
        // `query_hotspots` on it should not have to know it once hid inside "schema".
        if (lower.startsWithWord("pragma")) return Summary(PRAGMA, null)
        if (SCHEMA_PREFIXES.any { lower.startsWithWord(it) }) {
            return Summary(SCHEMA, tableAfter(lower, normalized, listOf("table", "index", "view")))
        }

        return when {
            lower.startsWithWord("select") ->
                Summary(SELECT, tableAfter(lower, normalized, listOf("from")))
            lower.startsWithWord("insert") ->
                Summary(INSERT, tableAfter(lower, normalized, listOf("into")))
            lower.startsWithWord("update") ->
                // UPDATE takes the table immediately — but an optional conflict clause can sit in
                // between, and Room's @Update emits ABORT by default, so this is the common path.
                Summary(UPDATE, identifierAt(normalized, skipConflict(lower, "update".length)))
            lower.startsWithWord("delete") ->
                Summary(DELETE, tableAfter(lower, normalized, listOf("from")))
            lower.startsWithWord("replace") ->
                Summary(INSERT, tableAfter(lower, normalized, listOf("into")))
            lower.startsWithWord("with") ->
                // A CTE ends in one of the above; the outer keyword is what matters.
                Summary(SELECT, null)
            else -> Summary(OTHER, null)
        }
    }

    /**
     * Where [sql] sits in a transaction's ceremony, if anywhere.
     *
     * Vocabulary is integration-dependent: Room emits `BEGIN EXCLUSIVE` / `TRANSACTION SUCCESSFUL`
     * / `END TRANSACTION`, raw SQLite emits `BEGIN` / `COMMIT`. Both are recognised; anything else
     * is [Role.NONE], which means it stays a normal row rather than being folded away.
     */
    fun role(sql: String): Role {
        val lower = normalize(sql).lowercase().removeSuffix(";").trim()
        return when {
            lower.startsWithWord("transaction successful") -> Role.SUCCESS
            lower.startsWithWord("begin") -> Role.OPEN
            lower.startsWithWord("rollback") -> Role.ABORT
            lower.startsWithWord("commit") || lower.startsWithWord("end") -> Role.CLOSE
            CHANGES.matches(lower) -> Role.CHANGES
            else -> Role.NONE
        }
    }

    /** Single-line, single-spaced — the form every rule below is written against. */
    fun normalize(sql: String): String = sql.trim().replace(Regex("\\s+"), " ")

    /** Prefix match on a word boundary, so `endorsements` is not an `END`. */
    private fun String.startsWithWord(word: String): Boolean =
        this == word || (startsWith(word) && length > word.length && !this[word.length].isIdentifierChar())

    private fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'

    /** Advances past `OR ABORT` / `OR REPLACE` / … when one is present. */
    private fun skipConflict(lower: String, from: Int): Int {
        if (from >= lower.length) return from
        val match = CONFLICT.find(lower.substring(from)) ?: return from
        return from + match.range.last + 1
    }

    /** The identifier following the first of [keywords] — e.g. `FROM users` → `users`. */
    private fun tableAfter(lower: String, original: String, keywords: List<String>): String? {
        for (keyword in keywords) {
            val index = lower.indexOf(" $keyword ")
            if (index >= 0) return identifierAt(original, index + keyword.length + 2)
        }
        return null
    }

    /** Reads one identifier at [from], stripping quoting and any `schema.` qualifier. */
    private fun identifierAt(sql: String, from: Int): String? {
        if (from >= sql.length) return null
        var rest = sql.substring(from).trimStart()
        // `CREATE TABLE IF NOT EXISTS users` — the guard clause is not the table.
        if (rest.lowercase().startsWith("if not exists ")) rest = rest.substring(14).trimStart()
        // A subquery (`FROM (SELECT …)`) has no single table to name.
        if (rest.startsWith("(")) return null
        val raw = rest.takeWhile { !it.isWhitespace() && it != ';' && it != '(' && it != ',' }
        // Strip quoting before splitting the qualifier: `main`.`orders` quotes each part, so
        // trimming only the ends would leave a stray backtick on the table name.
        val cleaned = raw.replace(QUOTES, "").substringAfterLast('.')
        if (cleaned.isBlank()) return null
        if (cleaned.lowercase() in NOT_A_TABLE) return null
        return cleaned.takeIf { it.first().let { c -> c.isLetter() || c == '_' } }
    }
}
