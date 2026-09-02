package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpRpc
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets

/**
 * MCP over stdio: newline-delimited JSON-RPC in on stdin, newline-delimited JSON-RPC out on stdout.
 *
 * This is the transport `claude mcp add logpose -- java -jar logpose-daemon.jar serve --stdio`
 * uses, and it is deliberately the smaller of the two — [McpRpc] is the protocol, so what is left
 * here is a read loop, a write lock, and the two places stdio differs from HTTP (PRD §5.3):
 *
 *  - **No auth.** Over HTTP anything on the machine can reach the port, so a token gates
 *    `tools/call`. Here the client *is* the parent process: it started this JVM and owns both
 *    pipes, so possession of the pipe is the authentication and a token would only be a second
 *    copy of that fact for a user to misconfigure. The bypass is a [McpRpc.SessionLookup] that
 *    answers every token with the one session ([anySession]) rather than a flag inside [McpRpc] —
 *    that keeps the plugin's dispatch untouched, and keeps "who may call" a property of the
 *    transport, which is where it belongs.
 *  - **Notifications produce nothing at all.** HTTP owes a 202 because a request is open; a stdio
 *    notification carries no id and gets no line, not even an empty one.
 *
 * ### The write lock
 *
 * Replies are written under one lock, and that is the only synchronization in the file. It has to
 * cover more than interleaved bytes: the seven deferred tools complete on foreign threads (a
 * waiter's thread, a push ack, a pooled disk read) minutes after their request was read, and the
 * loop keeps reading meanwhile. So a reply can be written by any thread, in any order relative to
 * the requests — legal, because JSON-RPC matches by `id` — and the lock's job is that each line
 * arrives whole, with its newline, flushed before the next one starts.
 *
 * ### Shutdown
 *
 * EOF on stdin (the parent closed the pipe, or the user hit ctrl-D) ends [run] and is the same
 * shutdown as SIGTERM: the caller stops the capture and the process exits. A malformed line is
 * *not* a shutdown — [McpRpc] answers it with the standard -32700 and the loop reads on, because
 * one bad line from a client is not a reason to drop a live capture.
 */
class StdioTransport(
    private val rpc: McpRpc,
    input: InputStream,
    output: OutputStream,
    private val log: Log,
) {

    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val writer: Writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
    private val writeLock = Any()

    /**
     * Reads and dispatches until stdin reaches EOF, on the calling thread. Returns when the client
     * goes away.
     */
    fun run() {
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                // A broken pipe reads the same as a hang-up: stop, don't spin.
                log.warn("stdin closed: ${e.message ?: e::class.java.simpleName}")
                null
            } ?: break

            if (line.isBlank()) continue
            handle(line)
        }
        log.info("stdin closed — shutting down")
    }

    private fun handle(line: String) {
        try {
            val outcome = rpc.dispatch(line, TOKEN) { payload -> write(payload) }
            when (outcome) {
                is McpRpc.Outcome.Reply -> write(outcome.body)
                // The one shape HTTP has and stdio doesn't: no id, no line, no bytes.
                McpRpc.Outcome.NoReply -> Unit
                // Someone else will write it, through the same lock, whenever the tool answers.
                McpRpc.Outcome.Deferred -> Unit
            }
        } catch (t: Throwable) {
            // Never fatal to the loop: the client's next request may be perfectly good, and the
            // capture behind it is live.
            log.warn("request failed: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /** One whole line per response, flushed, never interleaved. Safe from any thread. */
    private fun write(body: JsonObject) {
        val text = rpc.encode(body)
        synchronized(writeLock) {
            runCatching {
                writer.write(text)
                writer.write("\n")
                writer.flush()
            }.onFailure { e ->
                // The client is gone; the loop's own EOF will notice next.
                log.warn("could not write response: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    companion object {
        /** What [anySession] is handed and ignores — see the auth note above. */
        private const val TOKEN = "stdio"

        /**
         * The auth bypass, as narrow as it can be made: one session, returned for any token, and
         * reachable only from a transport that was constructed with it.
         */
        fun anySession(session: io.github.siddharthjaswal.logpose.mcp.McpSessions.Session): McpRpc.SessionLookup =
            object : McpRpc.SessionLookup {
                override fun byToken(token: String) = session
                override fun hasSessions() = true
            }
    }
}
