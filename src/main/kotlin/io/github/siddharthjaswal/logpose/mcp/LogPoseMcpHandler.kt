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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * The JSON-RPC is hand-rolled rather than pulled from an MCP SDK: the surface needed is three
 * methods, and an SDK would drag its own ktor/serialization versions into a plugin that has to
 * coexist with whatever the platform ships.
 *
 * **This runs on a Netty IO thread, never the EDT** — hence no Swing access here, and reads go
 * through [io.github.siddharthjaswal.logpose.store.EventStore]'s synchronized snapshot.
 */
class LogPoseMcpHandler : HttpRequestHandler() {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

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
        val rpc = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return send(context, request, errorResponse(JsonNull, -32700, "Parse error"))

        val id = rpc["id"] ?: JsonNull
        val method = (rpc["method"] as? JsonPrimitive)?.content.orEmpty()
        val params = rpc["params"] as? JsonObject ?: JsonObject(emptyMap())

        // Token is required for everything except the handshake, so a misconfigured client gets
        // a clear 401 from its very first real call rather than an empty tool list.
        val token = request.headers().get(TOKEN_HEADER).orEmpty()
        val session = McpSessions.byToken(token)

        return when (method) {
            "initialize" -> send(context, request, result(id, initialize(params)))
            "ping" -> send(context, request, result(id, buildJsonObject {}))
            // Notifications carry no id and expect no body.
            "notifications/initialized", "notifications/cancelled" ->
                sendStatus(context, request, HttpResponseStatus.ACCEPTED)
            "tools/list" -> send(context, request, result(id, buildJsonObject {
                put("tools", McpTools.catalogue())
            }))
            "tools/call" -> {
                if (session == null) return send(context, request, unauthorized(id))
                send(context, request, result(id, callTool(params, session)))
            }
            else -> send(context, request, errorResponse(id, -32601, "Method not found: $method"))
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        // Echo the client's protocol version when it names one — safer than pinning a version
        // this plugin would then have to chase.
        val version = (params["protocolVersion"] as? JsonPrimitive)?.content ?: PROTOCOL_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
            put("serverInfo", buildJsonObject {
                put("name", "logpose")
                put("title", "LogPose — live Android capture")
                put("version", PROTOCOL_SERVER_VERSION)
            })
            put(
                "instructions",
                "LogPose exposes the HTTP traffic, push messages, and app events captured from " +
                    "an Android device that is running right now. Start with session_summary or " +
                    "list_events to see what the app did, then get_event for full bodies. When a " +
                    "call failed, get_trace shows what led to it.",
            )
        }
    }

    private fun callTool(params: JsonObject, session: McpSessions.Session): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content.orEmpty()
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        val payload = runCatching {
            McpTools.call(
                name = name,
                args = args,
                events = session.store.snapshot(),
                hostAgeMillis = session.hostAgeMillis,
                includeBodies = session.exposeBodies(),
                mocks = session.mocks,
                sessions = session.store.sessions(),
                sessionOf = { id -> session.store.sessionOf(id) },
            )
        }.getOrElse { e ->
            // Surface the failure as tool output: an agent can read and route around it, where a
            // transport error just looks like the server is broken.
            return toolResult(json.encodeToString(JsonElement.serializer(), buildJsonObject {
                put("error", "Tool '$name' failed: ${e.message ?: e::class.java.simpleName}")
            }), isError = true)
        }
        return toolResult(json.encodeToString(JsonElement.serializer(), payload), isError = false)
    }

    private fun toolResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
        if (isError) put("isError", true)
    }

    private fun unauthorized(id: JsonElement): JsonObject {
        val hint = if (McpSessions.hasSessions()) {
            "Missing or unknown $TOKEN_HEADER. Copy the current token from the LogPose tool window."
        } else {
            "No LogPose capture is registered. Open the LogPose tool window in the project you " +
                "want to inspect, then retry."
        }
        return errorResponse(id, -32001, hint)
    }

    private fun result(id: JsonElement, value: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private fun send(context: ChannelHandlerContext, request: FullHttpRequest, payload: JsonObject): Boolean =
        write(context, request, HttpResponseStatus.OK, json.encodeToString(JsonElement.serializer(), payload))

    private fun sendStatus(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
    ): Boolean = write(context, request, status, "")

    /**
     * Write the response by hand rather than via the platform's `Responses` helper — that class
     * ships in a jar the plugin compile classpath doesn't see, and Netty alone is enough here.
     */
    private fun write(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        status: HttpResponseStatus,
        text: String,
    ): Boolean {
        val buffer = Unpooled.copiedBuffer(text, CharsetUtil.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes())
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, must-revalidate")

        val keepAlive = HttpUtil.isKeepAlive(request)
        if (keepAlive) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        val future = context.channel().writeAndFlush(response)
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE)
        return true
    }

    companion object {
        const val PATH = "/api/logpose/mcp"
        const val TOKEN_HEADER = "X-LogPose-Token"
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val PROTOCOL_SERVER_VERSION = "1.6.0"
    }
}
