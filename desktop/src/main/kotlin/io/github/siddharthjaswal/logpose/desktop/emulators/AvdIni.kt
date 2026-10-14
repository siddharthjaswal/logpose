package io.github.siddharthjaswal.logpose.desktop.emulators

/**
 * An AVD's `config.ini`, edited **in place**: every line the file had stays where it was, byte for
 * byte, and only the value of a key we set changes. Comments, blank lines, key order, unknown keys,
 * spacing around `=`, the line separator and a missing final newline all survive a parse → render.
 *
 * That is the opposite of avdslim, which rebuilds the file from a map (sorted, comments gone) — fine
 * for the emulator, but it makes the edit hard to read in a diff and impossible to undo by hand.
 * Android Studio reads and rewrites the same file, so the smaller our footprint in it the better.
 */
class AvdIni private constructor(private val lines: MutableList<String>, private val eol: String, private val finalNewline: Boolean) {

    /** The value of [key] (trimmed), or null when the file doesn't set it. The last assignment wins, as in the emulator. */
    operator fun get(key: String): String? = lines.lastOrNull { keyOf(it) == key }?.let(::valueOf)

    /** Every `key=value` pair, in file order (comments and blanks skipped). */
    fun entries(): List<Pair<String, String>> = lines.mapNotNull { l -> keyOf(l)?.let { it to valueOf(l) } }

    /**
     * Sets [key] to [value]: the existing line keeps its key text and the spacing around `=` and only
     * its value is replaced; a key the file lacks is appended at the end. Returns whether the
     * rendered text changed.
     */
    fun set(key: String, value: String): Boolean {
        val i = lines.indexOfLast { keyOf(it) == key }
        if (i < 0) {
            lines += "$key=$value"
            return true
        }
        val line = lines[i]
        val eq = line.indexOf('=')
        // Keep "key = " exactly as written (Studio writes `key=value`, some tools `key = value`).
        val head = line.substring(0, eq + 1)
        val afterEq = line.substring(eq + 1)
        val lead = afterEq.takeWhile { it == ' ' || it == '\t' }
        val next = head + lead + value
        if (next == line) return false
        lines[i] = next
        return true
    }

    fun render(): String = lines.joinToString(eol) + if (finalNewline && lines.isNotEmpty()) eol else ""

    fun copy(): AvdIni = AvdIni(lines.toMutableList(), eol, finalNewline)

    companion object {
        fun parse(text: String): AvdIni {
            // CRLF only when every newline is one; a mixed file splits on \n and keeps its \r's in the lines.
            val crlf = text.contains("\r\n") && !Regex("(?<!\r)\n").containsMatchIn(text)
            val eol = if (crlf) "\r\n" else "\n"
            val finalNewline = text.endsWith(eol)
            val body = if (finalNewline) text.dropLast(eol.length) else text
            val lines = if (text.isEmpty()) mutableListOf() else body.split(eol).toMutableList()
            return AvdIni(lines, eol, finalNewline)
        }

        /** The key of a `key=value` line, or null for a comment / blank / malformed line. */
        internal fun keyOf(line: String): String? {
            val t = line.trimStart()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";")) return null
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            return line.substring(0, eq).trim().takeIf { it.isNotEmpty() }
        }

        private fun valueOf(line: String): String = line.substring(line.indexOf('=') + 1).trim()
    }
}
