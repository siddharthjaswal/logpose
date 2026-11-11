package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI

/**
 * The shared transport over a real loopback socket — the daemon's and the Mac app's bytes. The
 * daemon's `HttpTransportTest` drives it through the daemon's adapter; these pin what is `:core`'s
 * own: both token forms, the 401 shapes, 202/405, health, the Host/Origin guard, batches, version
 * negotiation and the session headers.
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
            override fun byToken(token: String) = session.takeIf { McpAuth.constantTimeEquals(token, this@McpHttpTransportTest.token) }
            override fun hasSessions() = true
        })
        transport = McpHttpTransport(
            0, rpc, { warnings += it }, { McpHttpTransport.Health(store.snapshot().size, "attached") },
            hostName = "mac", version = "9.9.9",
        )
        port = transport.start()
    }

    @AfterEach
    fun stop() = transport.stop()

    private data class Res(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
        fun header(name: String) = headers.entries.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()
    }

    private fun request(
        method: String,
        body: String? = null,
        headers: Map<String, String> = mapOf("Authorization" to "Bearer $token"),
        path: String = McpHttpTransport.PATH,
    ): Res {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return Res(c.responseCode, stream?.readBytes()?.decodeToString() ?: "", c.headerFields)
    }

    private fun post(body: String, headers: Map<String, String> = mapOf("Authorization" to "Bearer $token")) =
        request("POST", body, headers)

    private val summary = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}"""

    // ---- auth ---------------------------------------------------------------------------------

    @Test
    fun `a Bearer token is accepted`() {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(200, res.status)
        assertEquals(25, res.json["result"]!!.jsonObject["tools"]!!.jsonArray.size)
    }

    @Test
    fun `the legacy X-LogPose-Token header still works, with the same bytes as Bearer`() {
        val viaHeader = post(summary, mapOf(McpRpc.TOKEN_HEADER to token))
        val viaBearer = post(summary)
        assertEquals(200, viaHeader.status)
        assertEquals(viaBearer.body, viaHeader.body)
        assertTrue(viaHeader.body.contains("total_events"), viaHeader.body)
    }

    @Test
    fun `the Bearer scheme is case-insensitive`() {
        assertEquals(200, post(summary, mapOf("Authorization" to "bearer $token")).status)
    }

    @Test
    fun `a missing token is 401 with a Bearer challenge and a body that says how to send it`() {
        val res = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", emptyMap())
        assertEquals(401, res.status)
        assertEquals("Bearer realm=\"logpose\"", res.header("WWW-Authenticate"))
        val message = res.json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("Authorization: Bearer"), message)
        assertTrue(message.contains("X-LogPose-Token"), message)
        assertTrue(res.header("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `a wrong token is 401 invalid_token, by either header`() {
        for (headers in listOf(mapOf("Authorization" to "Bearer nope"), mapOf(McpRpc.TOKEN_HEADER to "nope"))) {
            val res = post(summary, headers)
            assertEquals(401, res.status, headers.toString())
            assertTrue(res.header("WWW-Authenticate")!!.contains("error=\"invalid_token\""))
            assertTrue(res.json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("regenerated"))
        }
    }

    @Test
    fun `a non-Bearer Authorization scheme is not a token`() {
        assertEquals(401, post(summary, mapOf("Authorization" to "Basic $token")).status)
    }

    // ---- protocol -----------------------------------------------------------------------------

    @Test
    fun `a notification is 202 with no body`() {
        val res = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, res.status)
        assertEquals("", res.body)
    }

    @Test
    fun `GET and DELETE on the MCP path are 405 — no SSE stream, no session`() {
        for (m in listOf("GET", "DELETE")) {
            val res = request(m)
            assertEquals(405, res.status, m)
            assertEquals("POST", res.header("Allow"))
        }
    }

    @Test
    fun `Accept for both JSON and SSE is answered with JSON`() {
        val res = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream"),
        )
        assertEquals(200, res.status)
        assertTrue(res.header("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", res.body)
    }

    @Test
    fun `session and protocol-version headers are accepted, and no session is issued`() {
        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertNull(init.header("Mcp-Session-Id"), "stateless: the token already selects the capture")
        val next = post(
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            mapOf("Authorization" to "Bearer $token", "Mcp-Session-Id" to "client-made-up", "MCP-Protocol-Version" to "2025-06-18"),
        )
        assertEquals(200, next.status)
        // Even a version this server doesn't speak — Codex's 2026-07-28 discovery sends one.
        val modern = post(
            """{"jsonrpc":"2.0","id":3,"method":"server/discover","params":{}}""",
            mapOf("Authorization" to "Bearer $token", "MCP-Protocol-Version" to "2026-07-28"),
        )
        assertEquals(200, modern.status)
        assertEquals(-32601, modern.json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `initialize negotiates the protocol version`() {
        fun negotiated(requested: String?): String {
            val params = requested?.let { """{"protocolVersion":"$it"}""" } ?: "{}"
            return post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":$params}""")
                .json["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content
        }
        assertEquals("2025-06-18", negotiated("2025-06-18"))
        assertEquals("2025-03-26", negotiated("2025-03-26"))
        assertEquals("2024-11-05", negotiated("2024-11-05"))
        assertEquals("2025-11-25", negotiated("2025-11-25"))
        assertEquals("2025-06-18", negotiated("1999-01-01"))
        assertEquals("2025-06-18", negotiated(null))
    }

    @Test
    fun `a batch answers in order and leaves out notifications`() {
        val res = post(
            """[{"jsonrpc":"2.0","id":"a","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},$summary]""",
        )
        assertEquals(200, res.status)
        val replies = Json.parseToJsonElement(res.body).jsonArray
        assertEquals(2, replies.size)
        assertEquals("a", replies[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(2, replies[1].jsonObject["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a batch of only notifications is 202, an empty batch is invalid`() {
        assertEquals(202, post("""[{"jsonrpc":"2.0","method":"notifications/initialized"}]""").status)
        assertEquals(-32600, post("[]").json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    // ---- health -------------------------------------------------------------------------------

    @Test
    fun `health is unauthenticated and states identity, port and the count`() {
        val res = request("GET", headers = emptyMap(), path = McpHttpTransport.HEALTH_PATH)
        assertEquals(200, res.status)
        assertEquals(
            """{"ok":true,"app":"logpose","host":"mac","version":"9.9.9","port":$port,"status":"ok","events":0,"capture":"attached"}""",
            res.body,
        )
        assertFalse(res.body.contains(token))
    }

    @Test
    fun `routing is exact — a lookalike path is 404, POST on health is 405`() {
        assertEquals(404, request("GET", headers = emptyMap(), path = "/healthz").status)
        assertEquals(404, request("POST", "{}", path = "/api/logpose/mcpx").status)
        assertEquals(405, request("POST", "{}", path = McpHttpTransport.HEALTH_PATH).status)
    }

    // ---- DNS rebinding ------------------------------------------------------------------------

    /** Raw bytes: HttpURLConnection won't let a caller set Host. */
    private fun raw(host: String, origin: String? = null, path: String = McpHttpTransport.PATH): Int {
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        Socket("127.0.0.1", port).use { s ->
            val req = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                origin?.let { append("Origin: $it\r\n") }
                append("Authorization: Bearer $token\r\n")
                append("Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n")
                append(body)
            }
            s.getOutputStream().write(req.toByteArray())
            val status = s.getInputStream().bufferedReader().readLine()
            return status.split(' ')[1].toInt()
        }
    }

    @Test
    fun `a rebinding Host or a foreign Origin is 403, loopback names pass`() {
        assertEquals(403, raw("evil.example:$port"))
        assertEquals(403, raw("127.0.0.1:$port", origin = "http://evil.example"))
        assertEquals(403, raw("127.0.0.1:$port", origin = "null"))
        assertEquals(200, raw("127.0.0.1:$port"))
        assertEquals(200, raw("localhost:$port", origin = "http://localhost:3000"))
        assertEquals(200, raw("[::1]:$port"))
    }

    // ---- ports --------------------------------------------------------------------------------

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
        assertEquals(
            "claude mcp add --transport http logpose http://127.0.0.1:63344/api/logpose/mcp --header \"Authorization: Bearer t\"",
            McpHttpTransport.connectCommand(63344, "t"),
        )
    }
}
