package io.github.siddharthjaswal.logpose.export

/**
 * Process-wide bounded ring of emitted event lines — the raw envelope JSON, one per line, i.e.
 * NDJSON. Kept only when [io.github.siddharthjaswal.logpose.LogPoseConfig.exportEnabled], so a CI
 * gate can DUMP the whole capture to a file (see `mock/LogPoseExportReceiver`) and assert on it at
 * the wire level — no IDE or MCP session in the loop.
 *
 * In-memory only: a process death empties it, exactly like the mock registry. Oldest lines fall
 * off once [capacity] is reached, so a long run can't grow without bound.
 */
internal object ExportBuffer {

    @Volatile
    var capacity: Int = 2000

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun record(line: String) {
        lines.addLast(line)
        while (lines.size > capacity && lines.isNotEmpty()) lines.removeFirst()
    }

    /** The buffer as NDJSON lines, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun size(): Int = lines.size
}
