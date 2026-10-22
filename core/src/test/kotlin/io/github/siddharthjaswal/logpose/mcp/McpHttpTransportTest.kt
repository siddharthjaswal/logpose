package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.HttpURLConnection
import java.net.URI

/**
 * The shared transport over a real loopback socket. The daemon's `HttpTransportTest` drives it
 * end to end through the daemon's adapter; these pin the parts that are now `:core`'s own — the
 * auth gate, the health shape, and the port-fallback a second host on the machine relies on.
 */
class McpHttpTransportTest {

    private val token = "core-token"
    private val store = EventStore()
    private lateinit var transport: McpHttpTransport
    private var port = 0
    private val warnings = mutableListOf<String>()

    @BeforeEach
    fun start() {
        val session = McpSessions.Session("fake", store, { 0 }, { true })
        val rpc = McpRpc(sessions = object : McpRpc.SessionLookup {
            override fun byToken(token: String) = session.takeIf { token == this@McpHttpTransportTest.token }
            override fun hasSessions() = true
        })
        transport = McpHttpTransport(0, rpc, { warnings += it }, { McpHttpTransport.Health(store.snapshot().size, "attached") })
        port = transport.start()
    }

    @AfterEach
    fun stop() = transport.stop()

    private fun call(body: String, withToken: String?): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port${McpHttpTransport.PATH}").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        withToken?.let { c.setRequestProperty(McpRpc.TOKEN_HEADER, it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return c.responseCode to (stream?.readBytes()?.decodeToString() ?: "")
    }

    @Test
    fun `tools list answers with the token and the full tool set`() {
        val (status, body) = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token)
        assertEquals(200, status)
        val tools = Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(23, tools.size)
    }

    @Test
    fun `a wrong token is refused on tools call`() {
        val (status, body) = call(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}""",
            "wrong",
        )
        assertTrue(status == 401 || body.contains("error"), "refused: $status $body")
    }

    @Test
    fun `health is unauthenticated and states the count`() {
        val c = URI("http://127.0.0.1:$port${McpHttpTransport.HEALTH_PATH}").toURL().openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("""{"status":"ok","events":0,"capture":"attached"}""", c.inputStream.readBytes().decodeToString())
    }

    @Test
    fun `firstFree skips taken ports and reports the one that bound`() {
        val taken = setOf(63343, 63344)
        val won = McpHttpTransport.firstFree(McpHttpTransport.candidatePorts()) { p ->
            if (p in taken) throw BindException("in use") else "server@$p"
        }
        assertEquals(63345 to "server@63345", won)
        assertNull(McpHttpTransport.firstFree(listOf(1, 2)) { throw BindException("x") })
    }

    @Test
    fun `firstFree binds for real past a port another server holds`() {
        // `port` is held by the transport under test: asking for it first must fall through.
        val second = McpHttpTransport.firstFree(listOf(port, 0)) { p ->
            McpHttpTransport(p, McpRpc(), {}, { McpHttpTransport.Health(0, "x") }).also { it.start() }
        }
        try {
            assertEquals(0, second?.first)
        } finally {
            second?.second?.stop()
        }
    }

    @Test
    fun `candidate ports start at the preferred one and stay in range`() {
        assertEquals(listOf(63343, 63344, 63345), McpHttpTransport.candidatePorts(63343, 3))
        assertEquals(listOf(65535), McpHttpTransport.candidatePorts(65535, 3))
        assertTrue(McpHttpTransport.connectCommand(63344, "t").contains("http://localhost:63344/api/logpose/mcp"))
    }
}
