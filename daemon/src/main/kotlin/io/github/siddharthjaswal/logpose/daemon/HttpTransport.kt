package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpHttpTransport
import io.github.siddharthjaswal.logpose.mcp.McpRpc

/**
 * The daemon's MCP-over-HTTP endpoint.
 *
 * The transport itself — JDK `com.sun.net.httpserver`, the blocking idiom for deferred tools, the
 * bounded pool, `/health` — moved to `:core` as [McpHttpTransport] so the Mac app's *Serve MCP*
 * toggle runs the very same bytes. This class keeps the daemon's API exactly as it was (the
 * constructor taking a [Log], the nested [Health], the constants) and delegates, so the daemon's
 * behaviour and its tests are unchanged by the move.
 */
class HttpTransport(
    port: Int,
    rpc: McpRpc,
    log: Log,
    /** For `/health` — event count and whether logcat is attached. */
    health: () -> Health,
    deferTimeoutSeconds: Long = DEFER_TIMEOUT_SECONDS,
) {

    data class Health(val events: Int, val capture: String)

    private val delegate = McpHttpTransport(
        port = port,
        rpc = rpc,
        warn = log::warn,
        health = { health().let { McpHttpTransport.Health(it.events, it.capture) } },
        deferTimeoutSeconds = deferTimeoutSeconds,
    )

    /** Binds and starts serving. Returns the actual port (the same one, or the OS's when 0). */
    fun start(): Int = delegate.start()

    fun stop() = delegate.stop()

    companion object {
        const val PATH = McpHttpTransport.PATH
        const val HEALTH_PATH = McpHttpTransport.HEALTH_PATH
        const val DEFER_TIMEOUT_SECONDS = McpHttpTransport.DEFER_TIMEOUT_SECONDS
        const val MAX_THREADS = McpHttpTransport.MAX_THREADS

        fun healthJson(health: Health): String =
            McpHttpTransport.healthJson(McpHttpTransport.Health(health.events, health.capture))
    }
}
