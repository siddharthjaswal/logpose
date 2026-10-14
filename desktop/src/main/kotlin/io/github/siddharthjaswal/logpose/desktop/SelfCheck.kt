package io.github.siddharthjaswal.logpose.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.use
import io.github.siddharthjaswal.logpose.desktop.adb.PlatformTools
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedController
import io.github.siddharthjaswal.logpose.desktop.mcp.DesktopMcp
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.update.UpdateCheck
import io.github.siddharthjaswal.logpose.mcp.McpHttpTransport
import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

/**
 * `LogPose.app/Contents/MacOS/LogPose --self-check` — proves the **packaged** app's trimmed
 * runtime (the jlink `modules(...)` in the build) can do everything the app needs, without a
 * window, a device or a click, then exits 0 (all passed) or 1. Each line is one capability:
 *
 *  - Serve MCP: the real [DesktopMcp] binds on 127.0.0.1 (jdk.httpserver), answers `initialize`
 *    and `tools/list` with the token, and `/health`.
 *  - HTTPS + XML: Google's SDK manifest fetched and parsed exactly as the first-run adb download
 *    does (TLS → jdk.crypto.ec, java.xml).
 *  - HTTPS + JSON: GitHub's Releases API fetched and filtered as the update check does.
 *  - Compose: one frame rendered off-screen through Skiko (the native library loads, java.desktop).
 *
 * Touches nothing on disk but a temp directory, and nothing in the user's settings.
 */
object SelfCheck {

    fun run(): Int {
        println("LogPose self-check — java.home=${System.getProperty("java.home")}, version=${javaClass.`package`?.implementationVersion ?: "dev"}")
        println("runtime modules: " + ModuleLayer.boot().modules().map { it.name }.sorted().joinToString(" "))
        val results = listOf(
            check("Serve MCP (jdk.httpserver)", ::serveMcp),
            check("HTTPS + XML: Google SDK manifest", ::platformToolsManifest),
            check("HTTPS + JSON: GitHub releases", ::githubReleases),
            check("Compose off-screen frame (Skiko)", ::composeFrame),
        )
        val ok = results.all { it }
        println(if (ok) "SELF-CHECK PASSED" else "SELF-CHECK FAILED")
        return if (ok) 0 else 1
    }

    private fun check(name: String, body: () -> String): Boolean =
        runCatching(body).fold(
            onSuccess = { println("  ok    $name — $it"); true },
            onFailure = { println("  FAIL  $name — ${it::class.java.name}: ${it.message}"); false },
        )

    private fun serveMcp(): String {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("logpose-selfcheck").toFile()
        val settings = KeyValueStore.InMemory()
        val session = CaptureSession()
        val advanced = AdvancedController(scope, settings, dir, currentSerial = { null }, capturing = { false }, say = { _, _ -> })
        val mcp = DesktopMcp(settings, session, advanced, projectName = { "self-check" })
        try {
            // Away from 63343 so a running daemon or app is never disturbed.
            val port = mcp.start(preferred = 47_000 + (System.nanoTime() % 1_000).toInt()) ?: error(mcp.ui.value.error ?: "did not bind")
            val base = "http://127.0.0.1:$port"
            val health = http("GET", "$base${McpHttpTransport.HEALTH_PATH}")
            check(health.contains("\"status\":\"ok\"")) { "health said $health" }
            val init = http("POST", "$base${McpHttpTransport.PATH}", mcp.token,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"self-check","version":"1"}}}""")
            check(init.contains("serverInfo")) { "initialize said $init" }
            val tools = http("POST", "$base${McpHttpTransport.PATH}", mcp.token, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            val count = Regex("\"inputSchema\"").findAll(tools).count()
            check(count >= 10) { "tools/list listed $count tools" }
            return "port $port, /health ok, initialize ok, tools/list → $count tools"
        } finally {
            mcp.stop(remember = false)
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private fun platformToolsManifest(): String {
        val archive = PlatformTools.parseManifest(PlatformTools.fetchManifest()) ?: error("no macOS platform-tools in the manifest")
        return "${archive.fileName} (${archive.sizeBytes} bytes)"
    }

    private fun githubReleases(): String {
        val body = UpdateCheck.fetch("self-check")
        val mac = UpdateCheck.parseReleases(body)
        return "${body.length} bytes, ${mac.size} desktop-v release(s)"
    }

    private fun composeFrame(): String {
        val image = ImageComposeScene(64, 64) {
            Box(Modifier.fillMaxSize().background(Color(0xFF161222)))
        }.use { it.render() }
        check(image.width == 64 && image.height == 64) { "rendered ${image.width}x${image.height}" }
        return "64x64 frame rendered"
    }

    private fun http(method: String, url: String, token: String? = null, body: String? = null): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            token?.let { conn.setRequestProperty(McpRpc.TOKEN_HEADER, it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            check(code in 200..299) { "$method $url → HTTP $code $text" }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
