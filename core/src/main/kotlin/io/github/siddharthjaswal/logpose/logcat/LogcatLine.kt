package io.github.siddharthjaswal.logpose.logcat

/**
 * One line off `adb logcat -v brief`, split into the process that wrote it and the message.
 *
 * The reader used to run `-v raw`, which strips the prefix and with it the **pid** — the one fact
 * that ties a line to the app run that emitted it without any help from the library. Every
 * LogPose line from a process that has not (yet) announced itself landed in "session 0", and a
 * response-less request from a run that had since died stayed "in flight" forever, because nothing
 * said which process it came from. `-v brief` keeps the pid at the cost of a fixed prefix:
 *
 * ```
 * I/LogPose (27651): {"v":1,"kind":"http",…}
 * ```
 *
 * [parse] is tolerant on purpose: a line without the prefix (a capture file, a test, a host still
 * feeding raw payloads) comes back whole with a null pid, so every consumer can call it
 * unconditionally and nothing that worked on raw lines changes.
 */
data class LogcatLine(val pid: Int?, val message: String) {

    companion object {
        // `<priority>/<tag padded with spaces>(<pid padded>): <message>`. The tag is printed with
        // `%-8s`, so `LogPose` arrives as `LogPose ` — hence the lazy tag and the optional spaces.
        private val BRIEF = Regex("""^[VDIWEFAS]/[^(]*\(\s*(\d+)\): ?(.*)$""")

        fun parse(line: String): LogcatLine {
            // A JSON payload can never start with a priority letter, so the cheap check rules the
            // common raw case out before the regex runs.
            if (line.isEmpty() || line[0] == '{' || line.length < 2 || line[1] != '/') return LogcatLine(null, line)
            val m = BRIEF.matchEntire(line) ?: return LogcatLine(null, line)
            return LogcatLine(m.groupValues[1].toIntOrNull(), m.groupValues[2])
        }
    }
}
