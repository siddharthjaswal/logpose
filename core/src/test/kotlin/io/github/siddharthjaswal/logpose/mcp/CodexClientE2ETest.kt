package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The whole conversation Codex's MCP client has with a LogPose server, over a real socket, against
 * the transport the daemon and the Mac app run — header for header what
 * `codex-rs/rmcp-client/src/http_client_adapter.rs` sends (openai/codex, October 2026):
 *
 *  - every POST: `Accept: text/event-stream, application/json`, `Content-Type: application/json`,
 *    `Authorization: Bearer $LOGPOSE_TOKEN` (from `bearer_token_env_var`);
 *  - after `initialize`: `MCP-Protocol-Version` on every request, and `Mcp-Session-Id` only if the
 *    server issued one (LogPose doesn't);
 *  - a `GET` for the server-push stream, where 405 means "no SSE" and is not an error.
 *
 * Then `tools/list` and a read-only `tools/call` (session_summary) that must see a captured event.
 */
class CodexClientE2ETest {

    private val token = McpSessions.newToken()
    private val store = EventStore()
    private lateinit var transport: McpHttpTransport
    private lateinit var url: String
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun start() {
        val session = McpSessions.Session("codex-e2e", store, { 0 }, { true })
        val rpc = McpRpc(sessions = object : McpRpc.SessionLookup {
            override fun byToken(token: String) = session.takeIf { McpAuth.constantTimeEquals(token, this@CodexClientE2ETest.token) }
            override fun hasSessions() = true
        })
        transport = McpHttpTransport(0, rpc, {}, { McpHttpTransport.Health(store.snapshot().size, "attached") })
        url = McpSetup.url(transport.start())
        store.add(httpEvent("evt-codex"))
    }

    @AfterEach
    fun stop() = transport.stop()

    private fun post(body: String, protocolVersion: String? = null, sessionId: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "text/event-stream, application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        protocolVersion?.let { b.header("MCP-Protocol-Version", it) }
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `initialize, initialized, the SSE probe, tools list and a read-only tools call`() {
        // 1. initialize — Codex's legacy (default) lifecycle asks for 2025-06-18.
        val init = post(
            """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{"elicitation":{}},"clientInfo":{"name":"codex-mcp-client","title":"Codex","version":"0.140.0"}}}""",
        )
        assertEquals(200, init.statusCode(), init.body())
        assertTrue(init.headers().firstValue("Content-Type").get().startsWith("application/json"))
        val sessionId = init.headers().firstValue("Mcp-Session-Id").orElse(null)
        assertNull(sessionId, "stateless server: Codex then sends no session header and no DELETE")
        val result = Json.parseToJsonElement(init.body()).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("logpose", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        // 2. notifications/initialized — 202, no body.
        val initialized = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "2025-06-18", sessionId)
        assertEquals(202, initialized.statusCode())
        assertEquals("", initialized.body())

        // 3. The GET rmcp opens for server-initiated messages: 405 = "this server has no SSE".
        val sse = http.send(
            HttpRequest.newBuilder(URI(url)).GET()
                .header("Accept", "text/event-stream, application/json")
                .header("Authorization", "Bearer $token")
                .header("MCP-Protocol-Version", "2025-06-18").build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, sse.statusCode())

        // 4. tools/list
        val list = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""", "2025-06-18", sessionId)
        assertEquals(200, list.statusCode())
        val tools = Json.parseToJsonElement(list.body()).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "session_summary" })

        // 5. tools/call session_summary — read-only, sees the captured request.
        val call = post(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}""",
            "2025-06-18", sessionId,
        )
        assertEquals(200, call.statusCode())
        val content = Json.parseToJsonElement(call.body()).jsonObject["result"]!!.jsonObject["content"]!!.jsonArray
        val text = content[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("\"total_events\":1"), text)
    }

    @Test
    fun `a rotated or unset LOGPOSE_TOKEN surfaces as a Bearer challenge rmcp reports as auth-required`() {
        val stale = http.send(
            HttpRequest.newBuilder(URI(url))
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer stale")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{}}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, stale.statusCode())
        assertTrue(stale.headers().firstValue("WWW-Authenticate").get().startsWith("Bearer realm=\"logpose\""))
    }

    private fun httpEvent(id: String): LogEvent.Http {
        val tx = Transaction(
            id = id, startedAtMillis = 1_000,
            request = Request(method = "GET", url = "https://api.example.com/orders", path = "/orders"),
            response = Response(code = 200), durationMillis = 30,
        )
        return LogEvent.Http(
            tx,
            Envelope(kind = Envelope.KIND_HTTP, id = id, at = 1_000, endedAt = 1_030, payload = Json.encodeToJsonElement(Transaction.serializer(), tx)),
        )
    }
}
