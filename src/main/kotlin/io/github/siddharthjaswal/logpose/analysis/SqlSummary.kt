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
 * mislabelled row is worse than an unlabelled one.
 */
object SqlSummary {

    const val SELECT = "select"
    const val INSERT = "insert"
    const val UPDATE = "update"
    const val DELETE = "delete"
    const val TRANSACTION = "transaction"
    const val SCHEMA = "schema"
    const val OTHER = "other"

    data class Summary(val operation: String, val table: String?)

    /** Statements that mark transaction boundaries rather than touching data. */
    private val TRANSACTION_PREFIXES = listOf("begin", "commit", "rollback", "end transaction", "savepoint")
    private val SCHEMA_PREFIXES = listOf("create ", "drop ", "alter ", "pragma", "vacuum", "reindex", "analyze")
    private val QUOTES = Regex("[`\"\\[\\]']")

    fun of(sql: String): Summary {
        val normalized = sql.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase()

        TRANSACTION_PREFIXES.firstOrNull { lower.startsWith(it) }?.let {
            return Summary(TRANSACTION, null)
        }
        SCHEMA_PREFIXES.firstOrNull { lower.startsWith(it) }?.let {
            return Summary(SCHEMA, tableAfter(lower, normalized, listOf("table", "index", "view")))
        }

        return when {
            lower.startsWith("select") ->
                Summary(SELECT, tableAfter(lower, normalized, listOf("from")))
            lower.startsWith("insert") ->
                Summary(INSERT, tableAfter(lower, normalized, listOf("into")))
            lower.startsWith("update") ->
                // UPDATE takes the table immediately, with no intervening keyword.
                Summary(UPDATE, identifierAt(normalized, "update".length))
            lower.startsWith("delete") ->
                Summary(DELETE, tableAfter(lower, normalized, listOf("from")))
            lower.startsWith("replace") ->
                Summary(INSERT, tableAfter(lower, normalized, listOf("into")))
            lower.startsWith("with") ->
                // A CTE ends in one of the above; the outer keyword is what matters.
                Summary(SELECT, null)
            else -> Summary(OTHER, null)
        }
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
        val rest = sql.substring(from).trimStart()
        // A subquery (`FROM (SELECT …)`) has no single table to name.
        if (rest.startsWith("(")) return null
        val raw = rest.takeWhile { !it.isWhitespace() && it != ';' && it != '(' && it != ',' }
        // Strip quoting before splitting the qualifier: `main`.`orders` quotes each part, so
        // trimming only the ends would leave a stray backtick on the table name.
        val cleaned = raw.replace(QUOTES, "").substringAfterLast('.')
        return cleaned.takeIf { it.isNotBlank() && it.first().let { c -> c.isLetter() || c == '_' } }
    }
}
