package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.toolwindow.PluginVersion
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.net.InetSocketAddress

/**
 * Serves the LogPose capture to MCP clients — a coding agent working in the repo can read what
 * the running app actually did instead of the developer pasting a truncated logcat line into
 * chat.
 *
 * Transport is MCP Streamable HTTP on the IDE's own built-in web server (default port 63342), so
 * there's no extra port to manage. Connect with (the tool window's Connect Coding Agent popup has
 * Claude Code / Codex / JSON forms with the real port and token):
 *
 * ```
 * claude mcp add --transport http logpose http://127.0.0.1:63342/api/logpose/mcp \
 *   --header "Authorization: Bearer <token from the LogPose tool window>"
 * ```
 *
 * Health, unauthenticated: `GET http://127.0.0.1:63342/api/logpose/health`.
 *
 * This class is only the Netty half: adapting the request, and writing a response — possibly a
 * deferred one, from a foreign thread. Routing, the loopback/Host/Origin guard, bearer + header
 * auth with real 401s, 202/405, health and the JSON-RPC itself live in [McpHttpEndpoint] and
 * [McpRpc] in `:core`, shared byte for byte with the daemon and the Mac app.
 *
 * **This runs on a Netty IO thread, never the EDT** — hence no Swing access here, and reads go
 * through [io.github.siddharthjaswal.logpose.store.EventStore]'s synchronized snapshot.
 */
class LogPoseMcpHandler : HttpRequestHandler() {

    private val rpc = McpRpc()

    private val endpoint = McpHttpEndpoint(rpc, PATH, HEALTH_PATH) {
        McpHttpEndpoint.HealthInfo(host = "ide", version = pluginVersion(), port = builtInPort())
    }

    // Every method on our two paths, not just POST: a GET on the MCP path must be our 405 (how a
    // Streamable HTTP client learns there's no SSE stream), not the platform's 404.
    override fun isSupported(request: FullHttpRequest): Boolean = endpoint.handles(request.uri())

    // The platform's default gate is aimed at browsers (its own _ijt token and origin rules) and
    // would refuse every CLI client. LogPose applies its own instead, in McpHttpEndpoint: loopback
    // peer only (the built-in server can be switched to accept external connections), Host and
    // Origin must be loopback names (DNS rebinding), and a per-project token on every MCP call.
    override fun isAccessible(request: HttpRequest): Boolean = true

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        // Read everything the request owns *before* dispatching: a deferred answer is written long
        // after the platform has released the request and its refcounted buffer, so the only
        // things that may cross that line are the keep-alive flag and the channel context.
        val keepAlive = HttpUtil.isKeepAlive(request)
        val headers = request.headers()
        val adapted = McpHttpEndpoint.Request(
            method = request.method().name().uppercase(),
            path = urlDecoder.path(),
            header = { name -> headers.get(name) },
            body = request.content().toString(CharsetUtil.UTF_8),
            remoteIsLoopback = (context.channel().remoteAddress() as? InetSocketAddress)?.address?.isLoopbackAddress == true,
        )

        // Called synchronously for everything but a deferred tool, whose answer arrives on the
        // completing thread — the store's waiter executor, the device-ack thread, or a pooled
        // thread doing file I/O, never Netty's event loop and never the EDT. `writeAndFlush` is
        // thread-safe by design, and the endpoint calls back exactly once, so no latch here. A
        // client that disconnects mid-wait simply makes the eventual write fail; the waiter still
        // expires on its own timeout, so nothing is leaked.
        endpoint.handle(adapted) { response -> write(context, keepAlive, response) }
        return true
    }

    /**
     * Write the response by hand rather than via the platform's `Responses` helper — that class
     * ships in a jar the plugin compile classpath doesn't see, and Netty alone is enough here.
     *
     * Takes [keepAlive] rather than the request, since a deferred answer outlives the request
     * object. Callable from any thread — `writeAndFlush` hands the write to the channel's event
     * loop.
     */
    private fun write(context: ChannelHandlerContext, keepAlive: Boolean, reply: McpHttpEndpoint.Response) {
        val buffer = Unpooled.copiedBuffer(reply.body, CharsetUtil.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(reply.status), buffer)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, reply.contentType)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes())
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        reply.headers.forEach { (name, value) -> response.headers().set(name, value) }

        if (keepAlive) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        val future = context.channel().writeAndFlush(response)
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE)
    }

    private fun builtInPort(): Int = runCatching { BuiltInServerManager.getInstance().port }.getOrDefault(0)

    private fun pluginVersion(): String = PluginVersion.value ?: "dev"

    companion object {
        const val PATH = "/api/logpose/mcp"

        /** The IDE's health URL — under `/api/logpose/` because the built-in server's root is the platform's. */
        const val HEALTH_PATH = "/api/logpose/health"
        const val TOKEN_HEADER = McpRpc.TOKEN_HEADER
    }
}
