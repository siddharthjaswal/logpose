package io.github.siddharthjaswal.logpose.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * The MCP JSON-RPC envelope, with no transport in it.
 *
 * Everything a caller sees — the `initialize` payload, the tool catalogue, the
 * `content:[{type:"text"}]` result wrapper, the error bodies — is built here, so the IDE plugin's
 * Netty handler and the daemon's HTTP/stdio transports all speak the same protocol by
 * construction rather than by two copies staying in sync. A transport's job shrinks to: read a
 * request body and a token, hand them to [dispatch], and turn the [Outcome] into bytes.
 *
 * Thread-safety: [dispatch] itself is called on whatever thread the transport uses (a Netty IO
 * thread in the plugin), and a deferred answer arrives later on a foreign thread — see [Outcome].
 */
class McpRpc(
    private val sessions: SessionLookup = GlobalSessions,
    private val hint: AuthHint = AuthHint.Ide,
    /** What a tool says when this host can't serve it — see [McpTools.Unavailable]. */
    private val unavailable: McpTools.Unavailable = McpTools.Unavailable.Ide,
) {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Where a token is resolved to a capture. The IDE registry by default; a daemon supplies
     *  its own single-capture lookup. */
    interface SessionLookup {
        fun byToken(token: String): McpSessions.Session?

        /** True once any capture is registered — the 401 says something different when none is. */
        fun hasSessions(): Boolean
    }

    /** The application-wide IDE registry, kept behind [SessionLookup] so core has one seam. */
    object GlobalSessions : SessionLookup {
        override fun byToken(token: String): McpSessions.Session? = McpSessions.byToken(token)
        override fun hasSessions(): Boolean = McpSessions.hasSessions()
    }

    /**
     * The two 401 texts. Parameterized because the fix differs by host — the plugin points at the
     * tool window, a daemon will point at its own config — while the wire shape stays identical.
     * [Ide] is what the plugin sends and must not drift.
     */
    data class AuthHint(
        val missingToken: String,
        val noCapture: String,
        /**
         * Where, in this host, the developer gets the current token and setup snippet — appended to
         * the HTTP 401 body ([McpHttpEndpoint]) after the host-neutral "how to send it" sentence.
         */
        val fix: String = missingToken,
    ) {
        companion object {
            const val TOKEN_HEADER = "X-LogPose-Token"

            val Ide = AuthHint(
                missingToken =
                    "Missing or unknown $TOKEN_HEADER. Copy the current token from the LogPose tool window.",
                noCapture =
                    "No LogPose capture is registered. Open the LogPose tool window in the project you " +
                        "want to inspect, then retry.",
                fix = "Copy the current setup snippet from the LogPose tool window → Connect Coding Agent " +
                    "(the token is per project, and Regenerate there replaces it).",
            )
        }
    }

    /** What a transport's token resolves to, before any JSON-RPC is read. See [authenticate]. */
    enum class Auth {
        /** A registered capture answers to this token. */
        Ok,
        /** No token at all — the client was never configured with one. */
        Missing,
        /** A token nothing answers to: regenerated, mistyped, or (IDE) its project isn't open. */
        Invalid,
        /** No capture is registered at all, so no token could be right yet. */
        NoCapture,
    }

    /**
     * Resolves [token] the way `tools/call` does, so an HTTP transport can refuse a request with a
     * real 401 before dispatching it. Blank and null both mean [Auth.Missing].
     */
    fun authenticate(token: String?): Auth = when {
        token.isNullOrBlank() -> if (sessions.hasSessions()) Auth.Missing else Auth.NoCapture
        sessions.byToken(token) != null -> Auth.Ok
        sessions.hasSessions() -> Auth.Invalid
        else -> Auth.NoCapture
    }

    /** The host's wording, for a transport that writes its own auth refusal. */
    val authHint: AuthHint get() = hint

    /** What the transport must do with a dispatched request. */
    sealed interface Outcome {
        /** Write [body] as the response now. */
        data class Reply(val body: JsonObject) : Outcome

        /** A notification: no id, no body. HTTP answers 202 with an empty body; stdio stays quiet. */
        object NoReply : Outcome

        /**
         * The answer arrives later — a wait that ends when the app acts, a device ack, a disk
         * read. The `respond` callback passed to [dispatch] is invoked with the complete JSON-RPC
         * body, **on the completing thread** (never the transport's IO loop), possibly minutes
         * later, and possibly already before [dispatch] returned when a tool fails its arguments.
         *
         * It is invoked **at most once** — the guard lives here rather than in each transport so
         * that a push acking after its own deadline can't write twice onto any of them.
         */
        object Deferred : Outcome
    }

    /**
     * Parse and dispatch a raw request body. A body that isn't a JSON object is answered with the
     * standard -32700, so no transport has to know the parse-error shape.
     */
    fun dispatch(body: String, token: String, respond: (JsonObject) -> Unit): Outcome {
        val rpc = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Outcome.Reply(errorResponse(JsonNull, -32700, "Parse error"))
        return dispatch(rpc, token, respond)
    }

    /**
     * Dispatch one JSON-RPC request. [token] is whatever the transport carries (an
     * `X-LogPose-Token` header over HTTP); only `tools/call` requires it, so a misconfigured
     * client gets a clear 401 from its first real call rather than an empty tool list.
     */
    fun dispatch(request: JsonObject, token: String, respond: (JsonObject) -> Unit): Outcome {
        val id = request["id"] ?: JsonNull
        val method = (request["method"] as? JsonPrimitive)?.content.orEmpty()
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        // JSON-RPC: a message without an id is a notification and is never answered — whatever its
        // method, including one this server doesn't know. And a message carrying `result`/`error`
        // instead of a method is the client answering *us*; LogPose sends no server requests, so
        // there is nothing to route it to.
        if ("id" !in request && method.isNotEmpty()) return Outcome.NoReply
        if (method.isEmpty() && ("result" in request || "error" in request)) return Outcome.NoReply

        return when (method) {
            "initialize" -> Outcome.Reply(result(id, initialize(params)))
            "ping" -> Outcome.Reply(result(id, buildJsonObject {}))
            // Notifications carry no id and expect no body.
            "notifications/initialized", "notifications/cancelled" -> Outcome.NoReply
            "tools/list" -> Outcome.Reply(result(id, buildJsonObject { put("tools", McpTools.catalogue()) }))
            "tools/call" -> {
                val session = sessions.byToken(token)
                    ?: return Outcome.Reply(unauthorized(id))
                val tool = (params["name"] as? JsonPrimitive)?.content.orEmpty()
                // Most tools answer from the snapshot they're handed and can be written straight
                // back. A few can't: a wait ends when the app does something, a push when the
                // device reports, a scenario when the disk read finishes. Those defer.
                if (McpTools.isAsync(tool)) callToolDeferred(tool, params, session, id, respond)
                else Outcome.Reply(result(id, callTool(params, session)))
            }
            else -> Outcome.Reply(errorResponse(id, -32601, "Method not found: $method"))
        }
    }

    /**
     * Any JSON-RPC message an HTTP POST may carry: one request/notification object, or (MCP
     * 2025-03-26, which we still negotiate) a batch array. [respond] is called exactly once — with
     * the reply object, the batch's array of replies, or `null` when nothing is owed (a lone
     * notification, or a batch of only notifications: HTTP 202). It may be called on a foreign
     * thread, and possibly before this returns, exactly like [Outcome.Deferred].
     */
    fun dispatchMessage(body: String, token: String, respond: (JsonElement?) -> Unit) {
        when (val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()) {
            is JsonObject -> dispatchOne(parsed, token, respond)
            is JsonArray -> dispatchBatch(parsed, token, respond)
            else -> respond(errorResponse(JsonNull, -32700, "Parse error"))
        }
    }

    private fun dispatchOne(request: JsonObject, token: String, respond: (JsonElement?) -> Unit) {
        when (val outcome = dispatch(request, token) { payload -> respond(payload) }) {
            is Outcome.Reply -> respond(outcome.body)
            Outcome.NoReply -> respond(null)
            Outcome.Deferred -> Unit // the callback above writes it, at most once (McpRpc's latch)
        }
    }

    /**
     * A batch answers once every member has, in member order, leaving out the notifications. The
     * members may finish on different threads (one deferred tool among sync ones), hence the
     * atomic slots and countdown rather than a list.
     */
    private fun dispatchBatch(batch: JsonArray, token: String, respond: (JsonElement?) -> Unit) {
        if (batch.isEmpty()) {
            respond(errorResponse(JsonNull, -32600, "Invalid Request: empty batch"))
            return
        }
        val slots = AtomicReferenceArray<JsonElement?>(batch.size)
        val remaining = AtomicInteger(batch.size)
        fun settle(index: Int, reply: JsonElement?) {
            slots.set(index, reply)
            if (remaining.decrementAndGet() == 0) {
                val replies = (0 until batch.size).mapNotNull { slots.get(it) }
                respond(if (replies.isEmpty()) null else JsonArray(replies))
            }
        }
        batch.forEachIndexed { index, member ->
            val settled = AtomicBoolean(false)
            val once: (JsonElement?) -> Unit = { reply -> if (settled.compareAndSet(false, true)) settle(index, reply) }
            if (member is JsonObject) dispatchOne(member, token, once)
            else once(errorResponse(JsonNull, -32600, "Invalid Request"))
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val version = negotiateVersion((params["protocolVersion"] as? JsonPrimitive)?.content)
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
                    "call failed, get_trace shows what led to it — and when the app never " +
                    "propagated a trace (the usual case), get_related groups the whole flow by a " +
                    "business id such as order_id instead. LogPose can also drive the app: " +
                    "create_mock changes what a request returns, inject_fcm delivers a push that " +
                    "starts a flow, and await_event waits for the result instead of polling — so " +
                    "a check reads mock → inject → await → assert. Those three change what the " +
                    "running app receives; the timeline always shows which rows they produced. If " +
                    "capture has stopped (an emulator reboot, an adb drop), attach_device " +
                    "reattaches it. restart_app relaunches the app and clear_app_data clears its " +
                    "cache (scope='cache') or all of its data (scope='all', which logs it out) " +
                    "before relaunching — each starts a new app run and leaves a marker row on " +
                    "the timeline.",
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
                captureRunning = session.captureRunning,
                clearCapture = session.clearCapture,
                workerTransitions = session.workerTransitions,
                unavailable = unavailable,
                devices = session.devices,
            )
        }.getOrElse { e -> return failed(name, e) }
        return toolResult(json.encodeToString(JsonElement.serializer(), payload), isError = false)
    }

    /**
     * Run a tool whose answer arrives later and hand the finished JSON-RPC body to [respond].
     *
     * The exactly-once latch lives here, not in the transport: every transport needs it (an HTTP
     * request must not be written twice, a stdio stream must not carry two replies for one id),
     * and [McpTools.callAsync]'s own guard doesn't cover the case this one does — a tool that
     * answers and *then* throws out of `callAsync`, which would otherwise produce both a result
     * and an error body.
     */
    private fun callToolDeferred(
        name: String,
        params: JsonObject,
        session: McpSessions.Session,
        id: JsonElement,
        respond: (JsonObject) -> Unit,
    ): Outcome {
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val written = AtomicBoolean(false)
        val answer: (JsonObject) -> Unit = { payload ->
            if (written.compareAndSet(false, true)) respond(result(id, payload))
        }

        runCatching {
            McpTools.callAsync(
                name = name,
                args = args,
                events = session.store.snapshot(),
                push = session.push,
                waits = session.waits,
                scenarios = session.scenarios,
                correlations = session.correlations,
                devices = session.devices,
                captureRunning = session.captureRunning,
                unavailable = unavailable,
                apps = session.apps,
            ) { payload ->
                answer(toolResult(json.encodeToString(JsonElement.serializer(), payload), isError = false))
            }
        }.onFailure { e -> answer(failed(name, e)) }
        return Outcome.Deferred
    }

    /**
     * Surface a tool failure as tool output: an agent can read and route around it, where a
     * transport error just looks like the server is broken.
     */
    private fun failed(name: String, e: Throwable): JsonObject = toolResult(
        json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("error", "Tool '$name' failed: ${e.message ?: e::class.java.simpleName}")
        }),
        isError = true,
    )

    private fun toolResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
        if (isError) put("isError", true)
    }

    private fun unauthorized(id: JsonElement): JsonObject =
        errorResponse(id, -32001, if (sessions.hasSessions()) hint.missingToken else hint.noCapture)

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

    /** Serialize a response body the way every LogPose transport must write it. */
    fun encode(body: JsonObject): String = json.encodeToString(JsonElement.serializer(), body)

    /** [encode] for a batch reply (an array) as well as a single object. */
    fun encodeElement(body: JsonElement): String = json.encodeToString(JsonElement.serializer(), body)

    companion object {
        const val TOKEN_HEADER = AuthHint.TOKEN_HEADER
        private const val PROTOCOL_SERVER_VERSION = "1.6.0"

        /**
         * The MCP revisions this server speaks. For a tools-only server they differ in nothing it
         * sends: 2025-11-25 is additive over 2025-06-18 (and was already echoed before negotiation
         * existed, so clients sending it keep the exact reply they got), and 2025-03-26 is why a
         * batch body is still accepted ([dispatchMessage]). 2026-07-28 (`server/discover`) is
         * deliberately absent: a client that prefers it — Codex behind its `mcp_2026_07_28`
         * feature — gets `-32601` for `server/discover` and falls back to `initialize` with
         * 2025-06-18, the path its own legacy-fallback code is written for.
         */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /**
         * Answered when the client names a version we don't speak, or none. 2025-06-18 rather than
         * the newest because it is what this server always answered with — byte-compatible.
         */
        const val LATEST_PROTOCOL_VERSION = "2025-06-18"

        /**
         * The spec's negotiation: echo the client's version when we support it, otherwise answer
         * with [LATEST_PROTOCOL_VERSION] and let the client decide whether it can live with that.
         */
        fun negotiateVersion(requested: String?): String =
            requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
    }
}
