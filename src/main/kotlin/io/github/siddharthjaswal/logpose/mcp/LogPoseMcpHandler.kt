package io.github.siddharthjaswal.logpose.mcp

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.HttpRequestHandler

/**
 * Serves the LogPose capture to MCP clients — a coding agent working in the repo can read what
 * the running app actually did instead of the developer pasting a truncated logcat line into
 * chat.
 *
 * Transport is MCP over HTTP on the IDE's own built-in web server (default port 63342), so
 * there's no extra port to manage and it's bound to localhost like the rest of that server.
 * Connect with:
 *
 * ```
 * claude mcp add --transport http logpose http://localhost:63342/api/logpose/mcp \
 *   --header "X-LogPose-Token: <token from the LogPose tool window>"
 * ```
 *
 * This class is only the HTTP half: path and method matching, the token header, keep-alive,
 * Content-Length, and the write of a deferred answer from a foreign thread. The JSON-RPC itself
 * — every literal a client sees — lives in [McpRpc] in `:core`, shared with the headless daemon's
 * transports.
 *
 * **This runs on a Netty IO thread, never the EDT** — hence no Swing access here, and reads go
 * through [io.github.siddharthjaswal.logpose.store.EventStore]'s synchronized snapshot.
 */
class LogPoseMcpHandler : HttpRequestHandler() {

    private val rpc = McpRpc()

    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.POST && request.uri().substringBefore('?').trimEnd('/') == PATH

    // The platform's default gate is aimed at browsers (Origin checks, its own _ijt token).
    // LogPose authenticates every call with its own per-project token instead, which also
    // selects which project's capture to read.
    override fun isAccessible(request: HttpRequest): Boolean = true

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val body = request.content().toString(CharsetUtil.UTF_8)
        val token = request.headers().get(TOKEN_HEADER).orEmpty()

        // Read everything the request owns *before* dispatching: a deferred answer is written long
        // after the platform has released the request and its refcounted buffer, so the only
        // things that may cross that line are the keep-alive flag and the channel context.
        val keepAlive = HttpUtil.isKeepAlive(request)

        val outcome = rpc.dispatch(body, token) { payload ->
            write(context, keepAlive, HttpResponseStatus.OK, rpc.encode(payload))
        }

        return when (outcome) {
            is McpRpc.Outcome.Reply -> write(context, keepAlive, HttpResponseStatus.OK, rpc.encode(outcome.body))
            // A notification: acknowledge with no body.
            McpRpc.Outcome.NoReply -> write(context, keepAlive, HttpResponseStatus.ACCEPTED, "")
            // The response is written by the callback above, on the completing thread — the
            // store's waiter executor, the device-ack thread, or a pooled thread doing file I/O,
            // never Netty's event loop and never the EDT. `writeAndFlush` is thread-safe by
            // design, and McpRpc guarantees the callback fires at most once, so no latch here.
            // A client that disconnects mid-wait simply makes the eventual write fail; the waiter
            // still expires on its own timeout, so nothing is leaked.
            McpRpc.Outcome.Deferred -> true
        }
    }

    /**
     * Write the response by hand rather than via the platform's `Responses` helper — that class
     * ships in a jar the plugin compile classpath doesn't see, and Netty alone is enough here.
     *
     * Takes [keepAlive] rather than the request, since a deferred answer outlives the request
     * object. Callable from any thread — `writeAndFlush` hands the write to the channel's event
     * loop.
     */
    private fun write(
        context: ChannelHandlerContext,
        keepAlive: Boolean,
        status: HttpResponseStatus,
        text: String,
    ): Boolean {
        val buffer = Unpooled.copiedBuffer(text, CharsetUtil.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes())
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, must-revalidate")

        if (keepAlive) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        val future = context.channel().writeAndFlush(response)
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE)
        return true
    }

    companion object {
        const val PATH = "/api/logpose/mcp"
        const val TOKEN_HEADER = McpRpc.TOKEN_HEADER
    }
}
