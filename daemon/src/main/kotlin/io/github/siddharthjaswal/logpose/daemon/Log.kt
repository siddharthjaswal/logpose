package io.github.siddharthjaswal.logpose.daemon

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Everything the daemon says, on **stderr**.
 *
 * The rule is not stylistic. `logpose serve --stdio` (M4) will carry newline-delimited JSON-RPC on
 * stdout, and one stray log line there corrupts the stream for the client. So stdout is reserved
 * from the start for output that *is* the product — the `claude mcp add` line at startup, a
 * `version` string — and lifecycle chatter goes to stderr where a supervisor or a terminal can see
 * it without a client parsing it.
 */
class Log(private val verboseStateChanges: Boolean = true) {

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun info(message: String) = write("info", message)

    fun warn(message: String) = write("warn", message)

    /** A capture-lifecycle transition — attached, dropped, waiting for a device. */
    fun stateChange(message: String) {
        if (verboseStateChanges) write("capture", message)
    }

    /** Output that is the point of the command, not commentary about it. Stdout, no prefix. */
    fun out(message: String) {
        println(message)
        System.out.flush()
    }

    private fun write(level: String, message: String) {
        System.err.println("${LocalTime.now().format(clock)} [$level] $message")
        System.err.flush()
    }
}
