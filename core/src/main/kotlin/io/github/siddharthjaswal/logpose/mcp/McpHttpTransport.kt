package io.github.siddharthjaswal.logpose.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * MCP over HTTP on the JDK's own `com.sun.net.httpserver` — no framework, no added dependency.
 *
 * Shared by the two hosts that have no IDE built-in server to ride: the headless daemon (whose
 * `HttpTransport` is now a thin adapter over this) and the Mac app's *Serve MCP* toggle. The plugin
 * never constructs it — it answers on the IDE's own Netty server — so inside the plugin this class
 * is inert bytecode; it lives in `:core` only because two hosts need the identical behaviour and
 * `:core` is the one module both depend on.
 *
 * Two things make the JDK server sufficient. The protocol is plain request/response HTTP/1.1 (one
 * path, POST only, `Content-Length` the server writes itself), and everything above the bytes —
 * routing, the loopback guard, bearer/header auth, 401/202/405, `/health`, the JSON-RPC — is
 * [McpHttpEndpoint]'s, shared with the plugin's Netty handler. So this file is only bytes and threads.
 *
 * ### The deferred tools, and why this blocks
 *
 * Seven tools answer later: `await_event` can hold two minutes, `inject_fcm` waits for a device
 * ack, the scenario tools wait on disk. Netty lets the plugin return from the handler and write
 * the response from the completing thread; the JDK server does the opposite — it closes the
 * exchange the moment the handler returns — so here the handler thread **blocks** on a
 * [CompletableFuture] the respond-callback completes, and writes when it resolves.
 *
 * That is safe because the number of such threads is bounded on both sides.
 * [io.github.siddharthjaswal.logpose.store.EventStore.MAX_WAITERS] caps concurrent waits at 8, and
 * the executor below is sized comfortably above that plus room for the sync tools, so a burst of
 * waits can never starve a `list_events`. [DEFER_TIMEOUT_SECONDS] sits above `await_event`'s own
 * maximum, so the tool's timeout is what a caller sees — this one only exists so a lost callback
 * cannot pin a thread forever, and it reports as a JSON-RPC error rather than a dead connection.
 */
class McpHttpTransport(
    private val port: Int,
    private val rpc: McpRpc,
    /** Where a dropped client or a failed request is reported — stderr for the daemon, a log for the app. */
    private val warn: (String) -> Unit,
    /** For `/health` — event count and whether logcat is attached. */
    private val health: () -> Health,
    private val deferTimeoutSeconds: Long = DEFER_TIMEOUT_SECONDS,
    /** `/health`'s `host` field: `daemon` or `mac`. */
    private val hostName: String = "daemon",
    /** `/health`'s `version` field: the host's own version. */
    private val version: String = "dev",
) {

    data class Health(val events: Int, val capture: String)

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    @Volatile private var boundPort: Int = port

    private val endpoint = McpHttpEndpoint(rpc, PATH, HEALTH_PATH) {
        val h = health()
        McpHttpEndpoint.HealthInfo(hostName, version, boundPort, h.events, h.capture)
    }

    /** Binds and starts serving. Returns the actual port (the same one, or the OS's when 0). */
    fun start(): Int {
        // 127.0.0.1 explicitly, never the wildcard: a capture holds auth tokens and user data, and
        // this is the plugin's security posture too (the IDE's built-in server is loopback-only).
        val address = InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
        val http = HttpServer.create(address, BACKLOG)

        // A bounded pool, not a cached one: blocked deferred handlers are the whole risk here, and
        // an unbounded pool would answer thread exhaustion by making more threads until the JVM
        // gives up. The queue is a handoff, so work past the cap is rejected loudly (503) rather
        // than queued behind two-minute waits.
        val pool = ThreadPoolExecutor(
            2, MAX_THREADS, 60L, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "logpose-http").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        http.executor = pool
        // One root context: the JDK matches contexts by prefix, so `/health` would also catch
        // `/healthz`. The endpoint does exact routing (and the 404) itself.
        http.createContext("/") { exchange -> handle(exchange) }
        http.start()

        server = http
        executor = pool
        boundPort = http.address.port
        return boundPort
    }

    fun stop() {
        server?.stop(1)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = McpHttpEndpoint.Request(
                method = exchange.requestMethod.uppercase(),
                path = exchange.requestURI.rawPath ?: "/",
                header = { name -> exchange.requestHeaders.getFirst(name) },
                body = body,
                remoteIsLoopback = exchange.remoteAddress?.address?.isLoopbackAddress == true,
            )
            val answer = CompletableFuture<McpHttpEndpoint.Response>()
            endpoint.handle(request) { response -> answer.complete(response) }
            // Sync answers are already complete; a deferred tool parks this thread until it
            // answers (see the class comment), bounded by the backstop below.
            val response = runCatching { answer.get(deferTimeoutSeconds, TimeUnit.SECONDS) }.getOrNull()
                ?: McpHttpEndpoint.Response(200, McpHttpEndpoint.timeoutBody(rpc, body, deferTimeoutSeconds))
            respond(exchange, response)
        } catch (e: IOException) {
            // The client hung up mid-wait. Nothing is leaked — the tool's own waiter still expires.
            warn("client disconnected: ${e.message}")
            runCatching { exchange.close() }
        } catch (t: Throwable) {
            warn("request failed: ${t.message ?: t::class.java.simpleName}")
            runCatching { respond(exchange, McpHttpEndpoint.Response(500, """{"error":"internal"}""")) }
        }
    }

    private fun respond(exchange: HttpExchange, response: McpHttpEndpoint.Response) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", response.contentType)
        exchange.responseHeaders.set("Cache-Control", "no-cache, no-store, must-revalidate")
        response.headers.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
        // A 202 with no body must send length 0, not "unknown" (-1) — the latter makes the JDK
        // server chunk an empty response, which some clients read as a malformed reply.
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    companion object {
        const val PATH = "/api/logpose/mcp"
        const val HEALTH_PATH = "/health"

        /** One above the IDE's built-in 63342: what the daemon and the Mac app ask for first. */
        const val DEFAULT_PORT = 63343

        /**
         * Above `await_event`'s documented maximum (120s) with margin, so a caller's timeout is
         * always the tool's rather than the transport's.
         */
        const val DEFER_TIMEOUT_SECONDS = 150L

        /**
         * `EventStore.MAX_WAITERS` (8) is the hard cap on simultaneously blocked deferred
         * handlers; the rest is headroom for sync calls and for pushes/scenario I/O, which block
         * for far less.
         */
        const val MAX_THREADS = 24

        private const val BACKLOG = 32

        /** Built here rather than in the handler so a test can assert the shape without a socket. */
        fun healthJson(health: Health, host: String = "daemon", version: String = "dev", port: Int = DEFAULT_PORT): String =
            McpHttpEndpoint.HealthInfo(host, version, port, health.events, health.capture).toJson()

        /** The exact line a developer pastes to point Claude Code at a server on [port]. */
        fun connectCommand(port: Int, token: String): String = McpSetup.claudeCommand(McpSetup.url(port), token)

        /**
         * Ports to try, in order: [preferred], then the next [span] − 1 above it. A daemon already
         * on 63343 is the usual reason the first one is taken, and "the next free one, shown to the
         * user" beats failing — the connect line carries whichever port won.
         */
        fun candidatePorts(preferred: Int = DEFAULT_PORT, span: Int = 10): List<Int> =
            (0 until span.coerceAtLeast(1)).map { preferred + it }.filter { it in 1..65535 }

        /**
         * Starts on the first of [candidates] that binds. [start] is called per port and either
         * returns the bound port or throws [BindException] for a taken one; any other failure is not
         * "taken" and propagates. Returns null when every candidate was taken. Pure over [start],
         * which is what lets a test decide which ports are busy.
         */
        fun <T> firstFree(candidates: List<Int>, start: (Int) -> T): Pair<Int, T>? {
            for (port in candidates) {
                val bound = try { start(port) } catch (e: BindException) { continue }
                return port to bound
            }
            return null
        }
    }
}
