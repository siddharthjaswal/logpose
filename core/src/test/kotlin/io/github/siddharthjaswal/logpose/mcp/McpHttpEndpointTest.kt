package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transport-neutral half, with no socket: the paths the IDE's Netty handler takes that a
 * loopback socket can't reach (a remote peer, no capture registered), the guard and token parsing
 * on their own, the snippets, and token rotation.
 */
class McpHttpEndpointTest {

    private val session = McpSessions.Session("fake", EventStore(), { 0 }, { true })

    private fun endpoint(hasSessions: Boolean = true) = McpHttpEndpoint(
        McpRpc(sessions = object : McpRpc.SessionLookup {
            override fun byToken(token: String) = session.takeIf { hasSessions && token == "tok" }
            override fun hasSessions() = hasSessions
        }),
        "/api/logpose/mcp", "/api/logpose/health",
    ) { McpHttpEndpoint.HealthInfo("ide", "1.11.0", 63342) }

    private fun handle(
        e: McpHttpEndpoint,
        method: String = "POST",
        path: String = "/api/logpose/mcp",
        headers: Map<String, String> = mapOf("Authorization" to "Bearer tok", "Host" to "127.0.0.1:63342"),
        body: String = """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        loopback: Boolean = true,
    ): McpHttpEndpoint.Response {
        var out: McpHttpEndpoint.Response? = null
        val lower = headers.mapKeys { it.key.lowercase() }
        e.handle(McpHttpEndpoint.Request(method, path, { lower[it.lowercase()] }, body, loopback)) { out = it }
        return out!!
    }

    @Test
    fun `a remote peer is refused before auth`() {
        assertEquals(403, handle(endpoint(), loopback = false).status)
    }

    @Test
    fun `no capture registered is 503, not a misleading 401`() {
        val res = handle(endpoint(hasSessions = false))
        assertEquals(503, res.status)
        assertTrue(res.body.contains("tool window"), res.body)
    }

    @Test
    fun `the IDE health path answers without a token and carries nothing sensitive`() {
        val res = handle(endpoint(), method = "GET", path = "/api/logpose/health", headers = mapOf("Host" to "localhost:63342"))
        assertEquals(200, res.status)
        assertEquals("""{"ok":true,"app":"logpose","host":"ide","version":"1.11.0","port":63342,"status":"ok"}""", res.body)
    }

    @Test
    fun `the IDE 401 names the tool window as the fix`() {
        val res = handle(endpoint(), headers = mapOf("Host" to "127.0.0.1:63342"))
        assertEquals(401, res.status)
        assertTrue(res.body.contains("Connect Coding Agent"), res.body)
    }

    @Test
    fun `handles matches only the two paths, query strings and trailing slashes aside`() {
        val e = endpoint()
        assertTrue(e.handles("/api/logpose/mcp"))
        assertTrue(e.handles("/api/logpose/mcp/?x=1"))
        assertTrue(e.handles("/api/logpose/health"))
        assertFalse(e.handles("/api/logpose/mcpx"))
        assertFalse(e.handles("/api/other"))
    }

    @Test
    fun `Host guard`() {
        for (ok in listOf("localhost", "localhost:63342", "127.0.0.1:1", "127.1.2.3:80", "[::1]:63342", "LOCALHOST.", null)) {
            assertTrue(LoopbackGuard.hostAllowed(ok), "$ok should pass")
        }
        for (bad in listOf("evil.example", "evil.example:63342", "127.0.0.1.evil.example", "localhost.evil:1", "", "::1:80", "[::1", "10.0.0.2:63342", "0.0.0.0:63342")) {
            assertFalse(LoopbackGuard.hostAllowed(bad), "$bad should be refused")
        }
    }

    @Test
    fun `Origin guard`() {
        for (ok in listOf(null, "http://localhost", "http://127.0.0.1:5173", "https://[::1]:8443")) {
            assertTrue(LoopbackGuard.originAllowed(ok), "$ok should pass")
        }
        for (bad in listOf("null", "http://evil.example", "file://", "chrome-extension://abc", "http://localhost.evil.example")) {
            assertFalse(LoopbackGuard.originAllowed(bad), "$bad should be refused")
        }
    }

    @Test
    fun `token extraction prefers Bearer and ignores blanks and other schemes`() {
        assertEquals("a", McpAuth.tokenFrom("Bearer a", "b"))
        assertEquals("a", McpAuth.tokenFrom("BEARER   a ", null))
        assertEquals("b", McpAuth.tokenFrom("Basic a", "b"))
        assertEquals("b", McpAuth.tokenFrom("Bearer ", "b"))
        assertNull(McpAuth.tokenFrom(null, "  "))
        assertNull(McpAuth.tokenFrom("Bearer", null))
    }

    @Test
    fun `constant-time equality is still equality`() {
        assertTrue(McpAuth.constantTimeEquals("abc", "abc"))
        assertFalse(McpAuth.constantTimeEquals("abc", "abd"))
        assertFalse(McpAuth.constantTimeEquals("abc", "abcd"))
    }

    @Test
    fun `rotating a token persists it, and replace moves the session off the old one at once`() {
        val store = KeyValueStore.InMemory()
        val old = McpSessions.tokenFor(store)
        McpSessions.register(old, session)
        try {
            val fresh = McpSessions.rotateToken(store)
            assertNotEquals(old, fresh)
            assertEquals(fresh, McpSessions.tokenFor(store), "persisted")
            McpSessions.replace(old, fresh, session)
            assertNull(McpSessions.byToken(old), "the old token stops working immediately")
            assertSame(session, McpSessions.byToken(fresh))
            assertEquals(McpRpc.Auth.Invalid, McpRpc().authenticate(old))
            assertEquals(McpRpc.Auth.Ok, McpRpc().authenticate(fresh))
            McpSessions.unregister(fresh)
        } finally {
            McpSessions.unregister(old)
        }
    }

    @Test
    fun `the snippets are the exact commands each client documents`() {
        val url = McpSetup.url(63342)
        assertEquals("http://127.0.0.1:63342/api/logpose/mcp", url)
        assertEquals(
            listOf(
                "claude mcp add --transport http logpose http://127.0.0.1:63342/api/logpose/mcp --header \"Authorization: Bearer T\"",
            ),
            McpSetup.snippets(McpSetup.Client.CLAUDE_CODE, url, "T").map { it.text },
        )
        assertEquals(
            listOf(
                "export LOGPOSE_TOKEN=T",
                "codex mcp add logpose --url http://127.0.0.1:63342/api/logpose/mcp --bearer-token-env-var LOGPOSE_TOKEN",
                "[mcp_servers.logpose]\nurl = \"http://127.0.0.1:63342/api/logpose/mcp\"\nbearer_token_env_var = \"LOGPOSE_TOKEN\"",
            ),
            McpSetup.snippets(McpSetup.Client.CODEX, url, "T").map { it.text },
        )
        val json = McpSetup.snippets(McpSetup.Client.OTHER, url, "T").single().text
        assertTrue(json.contains("\"Authorization\": \"Bearer T\""), json)
        kotlinx.serialization.json.Json.parseToJsonElement(json) // valid JSON
    }
}
