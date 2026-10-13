package io.github.siddharthjaswal.logpose.desktop.mcp

import io.github.siddharthjaswal.logpose.desktop.advanced.McpSurfaces
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedController
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

class DesktopMcpTest {

    @TempDir lateinit var tmp: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = KeyValueStore.InMemory()
    private val session = CaptureSession(store = Fixtures.m4aStore())
    private val advanced by lazy { AdvancedController(scope, settings, tmp, { "emu" }, { false }, { _, _ -> }) }
    private val mcp by lazy { DesktopMcp(settings, session, advanced, { "gandalf" }, warn = {}) }

    @AfterEach fun stop() { mcp.stop(remember = false); scope.cancel() }

    @Test fun `the session's surfaces follow the mock-writer state`() {
        val ro = mcp.sessionFor(McpSurfaces.of(MockWriter.Mode.READ_ONLY))
        assertNull(ro.push, "push is absent, not merely refusing, when read-only — the daemon's shape")
        assertFalse(ro.mocks!!.writable())
        assertFalse(ro.scenarios!!.writable())
        assertNotNull(ro.correlations)
        val rw = mcp.sessionFor(McpSurfaces.of(MockWriter.Mode.WRITER))
        assertNotNull(rw.push)
        assertTrue(rw.mocks!!.writable())
        // And live: handing the device over changes the very next request's session.
        advanced.setAdvanced(true)
        assertNull(mcp.currentSession().push)
        advanced.setWriter("emu", true)
        assertNotNull(mcp.currentSession().push)
    }

    private fun post(port: Int, body: String, token: String?): String {
        val c = URI("http://127.0.0.1:$port/api/logpose/mcp").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        token?.let { c.setRequestProperty(McpRpc.TOKEN_HEADER, it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.readBytes().decodeToString()
    }

    private fun call(port: Int, tool: String, args: String = "{}", token: String? = mcp.token): String =
        post(port, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""", token)

    @Test fun `serves on the next free port when the preferred one is taken, and says so`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { held ->
            val port = mcp.start(preferred = held.localPort)!!
            assertEquals(held.localPort + 1, port)
            assertTrue(mcp.ui.value.portNote!!.contains("${held.localPort}"))
            assertTrue(mcp.ui.value.command!!.contains("http://localhost:$port/api/logpose/mcp"))
            assertTrue(mcp.ui.value.command!!.contains(mcp.token))
            assertTrue(settings.getBoolean(DesktopMcp.SERVE_KEY, false), "remembered for the next launch")
        }
    }

    @Test fun `tools answer over HTTP with the token, refuse without it, and writes are read-only`() {
        val free = ServerSocket(0).use { it.localPort }
        val port = mcp.start(preferred = free)!!
        val tools = Json.parseToJsonElement(post(port, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", null))
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(21, tools.size)
        val summary = call(port, "session_summary"); assertTrue(summary.contains("total_events"), summary)
        val refused = Json.parseToJsonElement(call(port, "session_summary", token = "wrong")).jsonObject["error"]!!.jsonObject
        assertEquals(-32001, refused["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(refused["message"]!!.jsonPrimitive.content.contains("LogPose for Mac"))
        val create = call(port, "create_mock", """{"method":"GET","path":"/x","status":200}""")
        assertTrue(create.contains("read-only"), create)
        assertTrue(call(port, "list_mocks").contains("result"))
    }
}
