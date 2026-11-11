package io.github.siddharthjaswal.logpose.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest

/**
 * Token handling shared by every LogPose MCP host.
 *
 * Two ways to send the token, both accepted everywhere:
 *  - `Authorization: Bearer <token>` — the standard form, and the only one Codex can send from an
 *    environment variable (`bearer_token_env_var`).
 *  - `X-LogPose-Token: <token>` — the original header, kept so every `claude mcp add` line saved
 *    before 1.11 keeps working.
 */
object McpAuth {

    const val AUTHORIZATION = "Authorization"
    const val LEGACY_HEADER = McpRpc.TOKEN_HEADER

    /** The RFC 6750 realm every 401 names. */
    const val REALM = "logpose"

    /**
     * The token a request carries: a `Bearer` credential first (scheme case-insensitive, per RFC
     * 7235), else the legacy header. Null when neither holds a non-blank value.
     */
    fun tokenFrom(authorization: String?, legacy: String?): String? {
        val bearer = authorization?.trim()?.let { value ->
            val space = value.indexOf(' ')
            if (space > 0 && value.substring(0, space).equals("Bearer", ignoreCase = true)) {
                value.substring(space + 1).trim()
            } else null
        }
        return bearer?.takeIf { it.isNotEmpty() } ?: legacy?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Constant-time over the bytes: [MessageDigest.isEqual] doesn't short-circuit on the first
     * differing byte, so a caller can't learn a token one character at a time from response timing.
     * (A length mismatch returns early, which only reveals the length of a fixed-format token.)
     */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/**
 * DNS-rebinding and remote-caller guard, as the MCP transport spec asks of a local server: bind
 * loopback, and validate `Host` and `Origin`.
 *
 * Binding 127.0.0.1 stops other machines; it does not stop a web page the developer has open. A
 * page on `evil.example` can re-point its own hostname at 127.0.0.1 and then fetch this port — the
 * browser happily sends it, with `Host: evil.example` and `Origin: http://evil.example`. Refusing
 * any Host or Origin that isn't a loopback name closes that hole without bothering real clients:
 * CLIs (Claude Code, Codex, curl) send `Host: 127.0.0.1:<port>` or `localhost:<port>` and no Origin.
 */
object LoopbackGuard {

    private val loopbackNames = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** A Host header value (`name[:port]`) that names this machine. Absent is allowed (HTTP/1.0). */
    fun hostAllowed(host: String?): Boolean {
        if (host == null) return true
        val name = hostName(host.trim()) ?: return false
        return isLoopbackName(name)
    }

    /**
     * An Origin header that is absent (every non-browser client) or a loopback http(s) origin.
     * `null` — what sandboxed iframes and file:// pages send — is refused.
     */
    fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val name = uri.host ?: return false
        return isLoopbackName(name)
    }

    private fun isLoopbackName(name: String): Boolean {
        val n = name.lowercase().removeSuffix(".")
        if (n in loopbackNames) return true
        // The whole 127.0.0.0/8 block is loopback; written as a dotted quad only.
        val parts = n.split('.')
        return parts.size == 4 && parts[0] == "127" && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    /** `name`, `name:port`, `[v6]` or `[v6]:port` → the name, or null when malformed. */
    private fun hostName(host: String): String? {
        if (host.isEmpty()) return null
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end < 0) return null
            val rest = host.substring(end + 1)
            if (rest.isNotEmpty() && !(rest.startsWith(":") && rest.drop(1).all(Char::isDigit))) return null
            return host.substring(0, end + 1)
        }
        val colon = host.lastIndexOf(':')
        if (colon < 0) return host
        // A bare IPv6 literal without brackets isn't a valid Host; refuse rather than guess.
        if (host.indexOf(':') != colon) return null
        val port = host.substring(colon + 1)
        if (port.isNotEmpty() && !port.all(Char::isDigit)) return null
        return host.substring(0, colon)
    }
}

/**
 * MCP's Streamable HTTP transport (spec 2025-03-26 / 2025-06-18), minus the server push half, as
 * one transport-neutral function — so the IDE's Netty handler and the JDK server the daemon and the
 * Mac app share answer every request identically by construction.
 *
 * A transport adapts its request into a [Request], calls [handle], and writes the [Response] it is
 * handed — possibly later and on another thread, when a deferred tool answers. What lives here:
 *
 *  - **Routing**: the MCP path takes POST (GET/DELETE → 405, which tells a client there is no SSE
 *    stream and no session to delete); the health path takes GET.
 *  - **The loopback guard** ([LoopbackGuard]): remote peer, `Host`, `Origin` → 403.
 *  - **Auth**: a missing token is 401 with `WWW-Authenticate: Bearer realm="logpose"`; a wrong one
 *    is 401 with `error="invalid_token"` (RFC 6750 §3.1 — 403 is reserved for insufficient scope).
 *    Both bodies are JSON-RPC errors that say how to send the token. No capture registered at all
 *    (an IDE with no LogPose tool window open) is 503: no token could be right yet.
 *  - **JSON-RPC**: [McpRpc.dispatchMessage]; a lone notification (or a batch of them) is 202 with
 *    no body; everything else is 200 `application/json`, whatever the `Accept` header offered —
 *    the spec lets a server answer a POST with plain JSON even when the client also accepts SSE.
 *
 * Sessions: no `Mcp-Session-Id` is issued. Every request carries the token, which already selects
 * the capture, so the server is stateless; a client that sends a session id anyway (or an
 * `MCP-Protocol-Version` header, whatever its value) is simply served. Issuing one would buy
 * nothing and cost a 404-and-reinitialize dance after every IDE restart.
 */
class McpHttpEndpoint(
    private val rpc: McpRpc,
    private val mcpPath: String,
    private val healthPath: String,
    /** Builds the `/health` body; called per request, so it can carry live counts and the bound port. */
    private val health: () -> HealthInfo,
) {

    /** `/health`'s body: liveness and identity, nothing a capture contains. */
    data class HealthInfo(
        /** Which host answered: `ide`, `daemon` or `mac`. */
        val host: String,
        val version: String,
        val port: Int,
        /** Event count, where the host has exactly one capture. */
        val events: Int? = null,
        /** `attached` / `waiting` / …, where the host has exactly one capture. */
        val capture: String? = null,
    ) {
        fun toJson(): String = buildJsonObject {
            put("ok", true)
            put("app", "logpose")
            put("host", host)
            put("version", version)
            put("port", port)
            // `status` predates `ok` and is what existing CI greps for; kept alongside.
            put("status", "ok")
            events?.let { put("events", it) }
            capture?.let { put("capture", it) }
        }.toString()
    }

    data class Request(
        val method: String,
        /** The path without the query string. */
        val path: String,
        /** Case-insensitive header lookup; null when absent. */
        val header: (String) -> String?,
        val body: String,
        /** Whether the TCP peer is a loopback address. */
        val remoteIsLoopback: Boolean,
    )

    data class Response(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
        val contentType: String get() = "application/json; charset=utf-8"
    }

    /** True for the two paths this endpoint answers; anything else is the host's 404. */
    fun handles(path: String): Boolean = normalize(path) == mcpPath || normalize(path) == healthPath

    /**
     * Answers [request]. [respond] is called exactly once — synchronously for everything but a
     * deferred tool, whose answer arrives on the completing thread.
     */
    fun handle(request: Request, respond: (Response) -> Unit) {
        val path = normalize(request.path)
        if (path != mcpPath && path != healthPath) {
            respond(Response(404, plainError("not_found", "Nothing here. MCP is at $mcpPath, health at $healthPath.")))
            return
        }
        if (!request.remoteIsLoopback) {
            respond(Response(403, plainError("forbidden", "LogPose's MCP server only answers this machine.")))
            return
        }
        if (!LoopbackGuard.hostAllowed(request.header("Host"))) {
            respond(Response(403, plainError("forbidden_host", "Host must be localhost or 127.0.0.1 (DNS-rebinding guard).")))
            return
        }
        if (!LoopbackGuard.originAllowed(request.header("Origin"))) {
            respond(Response(403, plainError("forbidden_origin", "Cross-origin requests are refused (DNS-rebinding guard).")))
            return
        }

        if (path == healthPath) {
            if (request.method != "GET") {
                respond(Response(405, plainError("method_not_allowed", "GET only"), mapOf("Allow" to "GET")))
            } else {
                respond(Response(200, health().toJson()))
            }
            return
        }

        if (request.method != "POST") {
            // GET is how a Streamable HTTP client asks for a server-push SSE stream; DELETE is how
            // it ends a session. LogPose offers neither, and 405 is the answer both clients and the
            // spec expect for that (rmcp maps it to "no SSE" / "nothing to delete").
            respond(Response(405, plainError("method_not_allowed", "POST only — this server offers no SSE stream."), mapOf("Allow" to "POST")))
            return
        }

        val token = McpAuth.tokenFrom(request.header(McpAuth.AUTHORIZATION), request.header(McpAuth.LEGACY_HEADER))
        when (rpc.authenticate(token)) {
            McpRpc.Auth.Ok -> Unit
            McpRpc.Auth.Missing -> {
                respond(
                    Response(
                        401,
                        rpcError(-32001, "No LogPose token was sent. $HOW_TO_SEND ${rpc.authHint.fix}"),
                        mapOf("WWW-Authenticate" to "Bearer realm=\"${McpAuth.REALM}\""),
                    ),
                )
                return
            }
            McpRpc.Auth.Invalid -> {
                respond(
                    Response(
                        401,
                        rpcError(-32001, "That LogPose token isn't valid — it was probably regenerated. ${rpc.authHint.fix} $HOW_TO_SEND"),
                        mapOf(
                            "WWW-Authenticate" to "Bearer realm=\"${McpAuth.REALM}\", error=\"invalid_token\", " +
                                "error_description=\"The token is unknown or was regenerated\"",
                        ),
                    ),
                )
                return
            }
            McpRpc.Auth.NoCapture -> {
                respond(Response(503, rpcError(-32002, rpc.authHint.noCapture), mapOf("Retry-After" to "5")))
                return
            }
        }

        rpc.dispatchMessage(request.body, token.orEmpty()) { reply ->
            respond(if (reply == null) Response(202, "") else Response(200, rpc.encodeElement(reply)))
        }
    }

    private fun normalize(path: String) = path.substringBefore('?').trimEnd('/').ifEmpty { "/" }

    private fun plainError(code: String, message: String): String =
        buildJsonObject { put("error", code); put("message", message) }.toString()

    private fun rpcError(code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString()

    companion object {
        const val HOW_TO_SEND =
            "Send it as `Authorization: Bearer <token>` (Codex: bearer_token_env_var = \"LOGPOSE_TOKEN\"), " +
                "or as the `X-LogPose-Token: <token>` header."

        /** Builds a deferred-timeout JSON-RPC error, echoing the request id when the body has one. */
        fun timeoutBody(rpc: McpRpc, body: String, seconds: Long): String {
            val id: JsonElement = runCatching {
                (kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject)?.get("id")
            }.getOrNull() ?: JsonNull
            return rpc.encode(buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32000)
                    put("message", "The tool never answered within ${seconds}s.")
                })
            })
        }
    }
}
