package io.github.siddharthjaswal.logpose.desktop.detail

/**
 * Find-in-body over the Raw view's lines — the plugin's `JsonTreePanel` find, rule for rule:
 * case-insensitive, every match highlighted, non-overlapping (the next search starts after the
 * previous match, as `indexOf(query, i + query.length)` does there), `n/total` with `0/0` for
 * none, Enter/↓ next and Shift+Enter/↑ previous, both wrapping. Like the plugin, opening find
 * switches the pane to Raw: the matches are positions in the pretty-printed text.
 *
 * Searching per line equals searching the whole text for any query without a newline, and a
 * single-line text field cannot type one.
 */
object JsonFind {

    data class Match(val line: Int, val start: Int, val end: Int)

    fun matches(lines: List<String>, query: String): List<Match> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Match>()
        lines.forEachIndexed { n, text ->
            var i = text.indexOf(query, 0, ignoreCase = true)
            while (i >= 0) {
                out += Match(n, i, i + query.length)
                i = text.indexOf(query, i + query.length, ignoreCase = true)
            }
        }
        return out
    }

    /** `3/12`, `0/0` when nothing matched, blank before anything was typed. */
    fun counter(query: String, total: Int, current: Int): String = when {
        query.isBlank() -> ""
        total == 0 -> "0/0"
        else -> "${current + 1}/$total"
    }

    /** Steps the current match by [dir], wrapping at both ends. */
    fun step(current: Int, total: Int, dir: Int): Int = if (total == 0) -1 else ((current + dir) % total + total) % total
}
