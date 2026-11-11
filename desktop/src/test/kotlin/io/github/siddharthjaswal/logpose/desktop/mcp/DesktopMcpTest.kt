package io.github.siddharthjaswal.logpose.desktop.mcp

import io.github.siddharthjaswal.logpose.desktop.advanced.McpSurfaces
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedController
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.mcp.McpSessions
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

    @Test fun `restart_app follows the mock-writer state and refuses read-only in the app's words`() {
        val apps = io.github.siddharthjaswal.logpose.appcontrol.AppControlService(
            store = session.store, serial = { "emu" },
            writable = { McpSurfaces.of(advanced.mode()).mocksWritable },
        )
        val withApps = DesktopMcp(settings, session, advanced, { "gandalf" }, warn = {}, apps = apps)
        assertFalse(withApps.currentSession().apps!!.writable(), "read-only by default")
        var out: kotlinx.serialization.json.JsonElement? = null
        io.github.siddharthjaswal.logpose.mcp.McpTools.callAsync(
            "restart_app", kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(),
            unavailable = DesktopMcp.Unavailable, apps = withApps.currentSession().apps,
        ) { out = it }
        val error = out!!.jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("This app controls mocks"), "was: $error")
        assertFalse(error.contains("tool window"), "was: $error")
        advanced.setAdvanced(true)
        advanced.setWriter("emu", true)
        assertTrue(withApps.currentSession().apps!!.writable(), "the writer may restart the app")
    }

    @Test fun `attach_device answers with the app's wording when no device surface is wired`() {
        assertNull(mcp.currentSession().devices)
        var out: kotlinx.serialization.json.JsonElement? = null
        io.github.siddharthjaswal.logpose.mcp.McpTools.callAsync(
            "attach_device", kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(),
            devices = mcp.currentSession().devices, unavailable = DesktopMcp.Unavailable,
        ) { out = it }
        val error = out!!.jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("LogPose for Mac"), "was: $error")
        assertFalse(error.contains("tool window"), "the app must not send the IDE's fix: $error")
    }

    private fun post(port: Int, body: String, token: String?): String {
        val c = URI("http://127.0.0.1:$port/api/logpose/mcp").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        token?.let { c.setRequestProperty(McpRpc.TOKEN_HEADER, it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        return (if (c.responseCode >= 400) c.errorStream else c.inputStream).readBytes().decodeToString()
    }

    @Test fun `regenerating the token refuses the old one on the next request, and Bearer works`() {
        val free = ServerSocket(0).use { it.localPort }
        val port = mcp.start(preferred = free)!!
        val old = mcp.token
        assertTrue(call(port, "session_summary", token = old).contains("total_events"))
        val fresh = mcp.regenerateToken()
        assertTrue(fresh != old)
        assertEquals(fresh, mcp.ui.value.token)
        assertTrue(mcp.ui.value.snippets.any { it.text.contains(fresh) })
        assertEquals(fresh, McpSessions.tokenFor(settings), "persisted")
        assertTrue(call(port, "session_summary", token = old).contains("-32001"), "old token refused")
        val c = URI("http://127.0.0.1:$port/api/logpose/mcp").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $fresh")
        c.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()) }
        assertEquals(200, c.responseCode)
        val health = (URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection).inputStream.readBytes().decodeToString()
        assertTrue(health.contains("\"host\":\"mac\"") && health.contains("\"port\":$port"), health)
    }

    @Test fun `the client picker is remembered`() {
        mcp.selectClient(io.github.siddharthjaswal.logpose.mcp.McpSetup.Client.CODEX)
        assertEquals(io.github.siddharthjaswal.logpose.mcp.McpSetup.Client.CODEX, mcp.ui.value.client)
        assertEquals("CODEX", settings.get(DesktopMcp.CLIENT_KEY))
    }

    private fun call(port: Int, tool: String, args: String = "{}", token: String? = mcp.token): String =
        post(port, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""", token)

    @Test fun `serves on the next free port when the preferred one is taken, and says so`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { held ->
            val port = mcp.start(preferred = held.localPort)!!
            assertEquals(held.localPort + 1, port)
            assertTrue(mcp.ui.value.portNote!!.contains("${held.localPort}"))
            assertTrue(mcp.ui.value.command!!.contains("http://127.0.0.1:$port/api/logpose/mcp"))
            assertTrue(mcp.ui.value.command!!.contains(mcp.token))
            assertTrue(settings.getBoolean(DesktopMcp.SERVE_KEY, false), "remembered for the next launch")
        }
    }

    @Test fun `tools answer over HTTP with the token, refuse without it, and writes are read-only`() {
        val free = ServerSocket(0).use { it.localPort }
        val port = mcp.start(preferred = free)!!
        val tools = Json.parseToJsonElement(post(port, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", mcp.token))
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(25, tools.size)
        val summary = call(port, "session_summary"); assertTrue(summary.contains("total_events"), summary)
        val refused = Json.parseToJsonElement(call(port, "session_summary", token = "wrong")).jsonObject["error"]!!.jsonObject
        assertEquals(-32001, refused["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(refused["message"]!!.jsonPrimitive.content.contains("LogPose for Mac"))
        val create = call(port, "create_mock", """{"method":"GET","path":"/x","status":200}""")
        assertTrue(create.contains("read-only"), create)
        assertTrue(call(port, "list_mocks").contains("result"))
    }
}
