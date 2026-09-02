package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The stdio transport driven the way a client drives it: lines in, lines out, EOF at the end.
 *
 * In-memory rather than by spawning the jar — the process-level version of this is the release
 * gate's scripted session, while everything that can actually break in code (the write lock, the
 * silent notification, a deferred answer arriving out of order, surviving a bad line) is decided
 * here where a test can hold the completing thread.
 */
class StdioTransportTest {

    private val store = EventStore()

    /** One future per `await_event`, handed out in call order so a test can finish them backwards. */
    private val pending = ConcurrentLinkedQueue<CompletableFuture<LogEvent?>>()

    private fun session() = McpSessions.Session(
        projectName = "fake",
        store = store,
        hostAgeMillis = { 0 },
        exposeBodies = { true },
        captureRunning = { true },
        waits = McpTools.Waits { _, _ ->
            CompletableFuture<LogEvent?>().also { pending.add(it) }
        },
    )

    private fun rpc() = McpRpc(
        sessions = StdioTransport.anySession(session()),
        hint = DaemonSession.AuthHint,
        unavailable = DaemonSession.Unavailable,
    )

    /** A running transport plus the two ends a client would hold. */
    private class Harness(rpc: McpRpc) {
        private val toDaemon = PipedOutputStream()
        val out = ByteArrayOutputStream()
        private val stdin = PipedInputStream(toDaemon, 1 shl 16)
        private val writer: Writer = toDaemon.writer(StandardCharsets.UTF_8)
        val finished = CountDownLatch(1)
        val transport = StdioTransport(rpc, stdin, out, Log(verboseStateChanges = false))

        init {
            Thread({ transport.run(); finished.countDown() }, "stdio-test").apply {
                isDaemon = true
            }.start()
        }

        fun send(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        /** Closes stdin — the client going away. */
        fun eof() = writer.close()

        /** Blocks until [count] whole lines have been written, then returns them. */
        fun lines(count: Int): List<String> {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val text = out.toString(StandardCharsets.UTF_8)
                val complete = text.lines().filter { it.isNotBlank() }
                if (complete.size >= count && text.endsWith("\n")) return complete
                Thread.sleep(10)
            }
            return out.toString(StandardCharsets.UTF_8).lines().filter { it.isNotBlank() }
        }

        fun bytesSoFar(): String = out.toString(StandardCharsets.UTF_8)
    }

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    @Test
    fun `a request gets exactly one response line, and it carries the id`() {
        val h = Harness(rpc())
        h.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")

        val lines = h.lines(1)
        assertEquals(1, lines.size)
        val body = parse(lines[0])
        assertEquals("2.0", body["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, body["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("logpose", body["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `tools_list answers without a token — over stdio the pipe is the authentication`() {
        val h = Harness(rpc())
        h.send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        h.send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}""")

        val lines = h.lines(2)
        assertEquals(21, parse(lines[0])["result"]!!.jsonObject["tools"]!!.jsonArray.size)
        // The call would be a 401 over HTTP without a header; here it just works.
        val call = parse(lines[1])
        assertNotNull(call["result"], "tools/call must not be gated on stdio: $call")

        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a notification produces no output at all — not even an empty line`() {
        val h = Harness(rpc())
        h.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        // Followed by a real request, so "nothing yet" can't pass by being merely slow: when the
        // ping's answer is out, the notification has demonstrably been handled.
        h.send("""{"jsonrpc":"2.0","id":9,"method":"ping"}""")

        val lines = h.lines(1)
        assertEquals(1, lines.size, "the notification must not have written a line: $lines")
        assertEquals(9, parse(lines[0])["id"]!!.jsonPrimitive.content.toInt())

        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `deferred answers may complete out of order, one whole line each`() {
        val h = Harness(rpc())
        h.send("""{"jsonrpc":"2.0","id":"first","method":"tools/call","params":{"name":"await_event","arguments":{}}}""")
        h.send("""{"jsonrpc":"2.0","id":"second","method":"tools/call","params":{"name":"await_event","arguments":{}}}""")

        // Both requests are parked before either answers — that is what makes the order below a
        // choice rather than an accident.
        val deadline = System.currentTimeMillis() + 5_000
        while (pending.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, pending.size)
        assertEquals("", h.bytesSoFar(), "a deferred tool must not write before it answers")

        val futures = pending.toList()
        futures[1].complete(null)
        futures[0].complete(null)

        val lines = h.lines(2)
        assertEquals(2, lines.size)
        // Responses carry ids, so the reversed order is legal — and it is the order that arrived.
        assertEquals("second", parse(lines[0])["id"]!!.jsonPrimitive.content)
        assertEquals("first", parse(lines[1])["id"]!!.jsonPrimitive.content)

        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a malformed line is answered with -32700 and the loop reads on`() {
        val h = Harness(rpc())
        h.send("this is not json")
        h.send("""{"jsonrpc":"2.0","id":7,"method":"ping"}""")

        val lines = h.lines(2)
        assertEquals(2, lines.size)
        assertEquals(-32700, parse(lines[0])["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, parse(lines[1])["id"]!!.jsonPrimitive.content.toInt())

        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `EOF on stdin ends the loop`() {
        val h = Harness(rpc())
        h.send("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        h.lines(1)
        h.eof()
        assertTrue(h.finished.await(5, TimeUnit.SECONDS), "EOF must return from run(), the way SIGTERM stops the daemon")
    }
}
