package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The transport end-to-end over a real socket: an ephemeral port, a fake capture, and the whole
 * request path an MCP client takes — including the two shapes only this transport has to get
 * right, a 202 with no body and a deferred answer written after the handler blocked on it.
 */
class HttpTransportTest {

    private val token = "test-token"
    private val store = EventStore()
    private lateinit var transport: HttpTransport
    private var port = 0

    /** Sits in for `await_event`'s waiter registry so a test can decide when (or whether) it ends. */
    private val pending = CompletableFuture<LogEvent?>()
    private var waitsOffered = true

    private fun session() = McpSessions.Session(
        projectName = "fake",
        store = store,
        hostAgeMillis = { 0 },
        exposeBodies = { true },
        captureRunning = { true },
        waits = McpTools.Waits { _, _ -> if (waitsOffered) pending else null },
    )

    @BeforeEach
    fun start() {
        val fixed = session()
        val rpc = McpRpc(
            sessions = object : McpRpc.SessionLookup {
                override fun byToken(t: String) = fixed.takeIf { t == token }
                override fun hasSessions() = true
            },
            hint = DaemonSession.AuthHint,
        )
        transport = HttpTransport(
            port = 0,
            rpc = rpc,
            log = Log(verboseStateChanges = false),
            health = { HttpTransport.Health(store.snapshot().size, "attached") },
            // Short, so the "nobody ever called back" branch is testable in a second rather than
            // in two and a half minutes.
            deferTimeoutSeconds = 1,
        )
        port = transport.start()
    }

    @AfterEach
    fun stop() {
        pending.complete(null)
        transport.stop()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private data class Res(val status: Int, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    private fun post(body: String, withToken: String? = token, path: String = HttpTransport.PATH): Res {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        withToken?.let { connection.setRequestProperty(McpRpc.TOKEN_HEADER, it) }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val text = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.readText().orEmpty()
        return Res(status, text)
    }

    private fun get(path: String): Res {
        val connection = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val status = connection.responseCode
        val text = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.readText().orEmpty()
        return Res(status, text)
    }

    private fun call(tool: String, args: String = "{}", id: Int = 1, withToken: String? = token) =
        post("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""", withToken)

    // ---- the protocol -------------------------------------------------------------------------

    @Test
    fun `initialize answers with the server info a client expects`() {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals(200, response.status)
        val result = response.json["result"]!!.jsonObject
        assertEquals("logpose", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"])
    }

    @Test
    fun `tools_list is unauthenticated and lists the whole catalogue`() {
        val response = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", withToken = null)
        assertEquals(200, response.status)
        val tools = response.json["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(21, tools.size, "the daemon must expose the same 21 tools as the plugin")
    }

    @Test
    fun `a sync tool call answers 200 with the content wrapper`() {
        store.add(httpEvent("evt-1"))
        val response = call("session_summary")
        assertEquals(200, response.status)
        val content = response.json["result"]!!.jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val payload = Json.parseToJsonElement(content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNotNull(payload.jsonObject)
    }

    @Test
    fun `a bad token is a JSON-RPC 401-shaped error naming the daemon`() {
        val response = call("session_summary", withToken = "wrong")
        // The plugin answers 200 with a JSON-RPC error body rather than an HTTP 401, and parity
        // is the product — a client must not be able to tell the two hosts apart.
        assertEquals(200, response.status)
        val error = response.json["error"]!!.jsonObject
        assertEquals(-32001, error["code"]!!.jsonPrimitive.content.toInt())
        val message = error["message"]!!.jsonPrimitive.content
        assertTrue(message.contains(McpRpc.TOKEN_HEADER), message)
        assertTrue(message.contains("daemon"), message)
    }

    @Test
    fun `a missing token is refused the same way`() {
        assertNotNull(call("session_summary", withToken = null).json["error"])
    }

    @Test
    fun `a notification is 202 with an empty body`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `a deferred tool blocks the handler and writes when the wait completes`() {
        val done = CountDownLatch(1)
        var response: Res? = null
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            response = call("await_event", """{"kind":"http","timeout_seconds":5}""")
            done.countDown()
        }
        // Nothing has answered yet, so the handler thread is parked inside the exchange.
        assertTrue(!done.await(300, TimeUnit.MILLISECONDS), "the handler must wait, not answer early")

        pending.complete(httpEvent("evt-async"))
        assertTrue(done.await(10, TimeUnit.SECONDS), "the completed wait must be written back")
        worker.shutdownNow()

        assertEquals(200, response!!.status)
        val content = response!!.json["result"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("evt-async"))
    }

    @Test
    fun `a deferred tool that never answers is a JSON-RPC error rather than a hung connection`() {
        // The transport's own backstop, above every tool's deadline: injected as 1s here.
        val response = call("await_event", """{"kind":"http","timeout_seconds":600}""", id = 9)
        assertEquals(200, response.status)
        val error = response.json["error"]!!.jsonObject
        assertEquals(-32000, error["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(9, response.json["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a malformed body is the standard parse error`() {
        val response = post("not json at all")
        assertEquals(200, response.status)
        assertEquals(-32700, response.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `GET on the MCP path is 405`() {
        assertEquals(405, get(HttpTransport.PATH).status)
    }

    // ---- health -------------------------------------------------------------------------------

    @Test
    fun `health is unauthenticated and reports the count and the capture state`() {
        store.add(httpEvent("evt-h1"))
        store.add(httpEvent("evt-h2"))
        val response = get(HttpTransport.HEALTH_PATH)
        assertEquals(200, response.status)
        assertEquals("ok", response.json["status"]!!.jsonPrimitive.content)
        assertEquals(2, response.json["events"]!!.jsonPrimitive.content.toInt())
        assertEquals("attached", response.json["capture"]!!.jsonPrimitive.content)
    }

    @Test
    fun `health JSON shape is exactly what a CI check greps for`() {
        assertEquals(
            """{"status":"ok","events":0,"capture":"waiting"}""",
            HttpTransport.healthJson(HttpTransport.Health(0, "waiting")),
        )
    }

    @Test
    fun `POST on health is 405`() {
        assertEquals(405, post("{}", path = HttpTransport.HEALTH_PATH).status)
    }

    // ---- pool sizing --------------------------------------------------------------------------

    @Test
    fun `the server pool is sized above the store's waiter cap`() {
        // The whole safety argument for blocking the handler thread: no burst of await_event can
        // consume every thread, because the store refuses more than MAX_WAITERS of them.
        assertTrue(
            HttpTransport.MAX_THREADS > EventStore.MAX_WAITERS,
            "${HttpTransport.MAX_THREADS} must leave room above ${EventStore.MAX_WAITERS} waiters",
        )
    }

    @Test
    fun `too many waits is answered, not queued`() {
        waitsOffered = false
        val response = call("await_event", """{"kind":"http","timeout_seconds":5}""")
        assertEquals(200, response.status)
        assertNull(response.json["error"])
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun httpEvent(id: String): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = 1_000,
            request = Request(method = "GET", url = "https://api.example.com/thing", path = "/thing"),
            response = Response(code = 200),
            durationMillis = 30,
        )
        return LogEvent.Http(
            tx,
            Envelope(
                kind = Envelope.KIND_HTTP,
                id = id,
                at = 1_000,
                endedAt = 1_030,
                payload = Json.encodeToJsonElement(Transaction.serializer(), tx),
            ),
        )
    }
}
