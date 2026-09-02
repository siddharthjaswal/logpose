package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.model.PushInject
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JSON-RPC envelope, tested where it now lives. Two things are being protected here:
 *
 *  - **The bytes.** `mcp-golden.txt` holds the exact responses the Netty handler produced before
 *    the extraction (captured by running its own code at commit aad7650); the tests below compare
 *    against those literally, so the refactor can't have moved a field or reworded a hint.
 *  - **The outcomes.** Which requests reply, which stay silent, and which hold the request open —
 *    the contract every transport (IDE Netty, daemon HTTP, daemon stdio) builds on.
 */
class McpRpcTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val golden: Map<String, String> = buildMap {
        val text = McpRpcTest::class.java.getResourceAsStream("/mcp-golden.txt")!!
            .bufferedReader().readText()
        var key: String? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("#") && !line.startsWith("###") -> Unit
                line.startsWith("### ") -> key = line.removePrefix("### ")
                key != null -> { put(key!!, line); key = null }
            }
        }
    }

    // ---- fakes ----------------------------------------------------------------------------

    private class Sessions(private val good: String?, private val session: McpSessions.Session?) :
        McpRpc.SessionLookup {
        override fun byToken(token: String): McpSessions.Session? =
            session.takeIf { good != null && token == good }

        override fun hasSessions(): Boolean = session != null
    }

    private fun session(
        store: EventStore = EventStore(),
        push: McpTools.Push? = null,
    ) = McpSessions.Session(
        projectName = "test",
        store = store,
        hostAgeMillis = { 0 },
        exposeBodies = { true },
        push = push,
    )

    /** A live capture reachable with "good-token". */
    private fun rpc(session: McpSessions.Session? = session()) =
        McpRpc(Sessions("good-token", session))

    /** No capture registered at all — the other 401. */
    private fun emptyRpc() = McpRpc(Sessions(null, null))

    private fun request(method: String, params: JsonObject? = null, id: Int? = 1): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    private fun call(name: String, args: JsonObject = buildJsonObject {}): JsonObject =
        request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        })

    /** Dispatch expecting an immediate reply, and return it encoded exactly as a transport writes it. */
    private fun replyText(rpc: McpRpc, request: JsonObject, token: String = "good-token"): String {
        val outcome = rpc.dispatch(request, token) { error("unexpected deferred answer") }
        return rpc.encode((outcome as McpRpc.Outcome.Reply).body)
    }

    // ---- handshake --------------------------------------------------------------------------

    @Test
    fun `initialize echoes the client protocol version`() {
        val body = replyText(
            rpc(),
            request("initialize", buildJsonObject { put("protocolVersion", "2024-11-05") }),
        )
        assertEquals(golden.getValue("initialize.echo"), body)
    }

    @Test
    fun `initialize falls back to the server protocol version`() {
        assertEquals(
            golden.getValue("initialize.default"),
            replyText(rpc(), request("initialize", buildJsonObject {})),
        )
    }

    @Test
    fun `initialize needs no token`() {
        assertEquals(
            golden.getValue("initialize.default"),
            replyText(emptyRpc(), request("initialize"), token = ""),
        )
    }

    @Test
    fun `ping replies with an empty result`() {
        assertEquals(golden.getValue("ping"), replyText(rpc(), request("ping")))
    }

    @Test
    fun `notifications produce no reply`() {
        for (method in listOf("notifications/initialized", "notifications/cancelled")) {
            val outcome = rpc().dispatch(request(method, id = null), "") { error("no answer expected") }
            assertSame(McpRpc.Outcome.NoReply, outcome, method)
        }
    }

    // ---- catalogue --------------------------------------------------------------------------

    @Test
    fun `tools list carries the catalogue unchanged`() {
        val body = json.parseToJsonElement(replyText(rpc(), request("tools/list"), token = "")).jsonObject
        assertEquals("2.0", body["jsonrpc"]!!.jsonPrimitive.content)
        val tools = body["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(golden.getValue("tools.list.count").toInt(), tools.size)
        assertEquals(json.parseToJsonElement(golden.getValue("tools.list.first")), tools.first())
        assertEquals(json.parseToJsonElement(golden.getValue("tools.list.last")), tools.last())
    }

    // ---- tools/call, synchronous --------------------------------------------------------------

    @Test
    fun `sync tool call is wrapped as text content`() {
        assertEquals(golden.getValue("tools.call.sync"), replyText(rpc(), call("session_summary")))
    }

    @Test
    fun `unknown tool answers as tool output, not a transport error`() {
        assertEquals(golden.getValue("tools.call.unknownTool"), replyText(rpc(), call("no_such_tool")))
    }

    @Test
    fun `a throwing tool becomes an isError result`() {
        // exposeBodies() is read inside call()'s runCatching, so a host whose accessor blows up
        // is the shortest honest reproduction of a tool that throws.
        val exploding = McpSessions.Session(
            projectName = "test",
            store = EventStore(),
            hostAgeMillis = { 0 },
            exposeBodies = { throw IllegalStateException("kaboom") },
        )
        assertEquals(
            golden.getValue("tools.call.isError"),
            replyText(McpRpc(Sessions("good-token", exploding)), call("boom")),
        )
    }

    // ---- tools/call, deferred ------------------------------------------------------------------

    @Test
    fun `async tool defers and completes through the callback`() {
        val answers = mutableListOf<JsonObject>()
        // No Scenarios wired: load_scenario is async and fails fast, which still travels the
        // deferred path — dispatch returns Deferred and the answer arrives via respond.
        val outcome = rpc().dispatch(
            call("load_scenario", buildJsonObject { put("name", "offline") }),
            "good-token",
        ) { answers += it }

        assertSame(McpRpc.Outcome.Deferred, outcome)
        assertEquals(1, answers.size)
        val text = answers.single()["result"]!!.jsonObject["content"]!!.jsonArray
            .single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("error"), text)
        assertEquals("2.0", answers.single()["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1, answers.single()["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a deferred answer is written exactly once`() {
        // The tool acks (one answer) and then throws out of callAsync — without the latch in
        // McpRpc that failure would write a second body onto the same request.
        val push = object : McpTools.Push {
            override fun deviceHint(): String = "fake · lib 1.7.3"
            override fun notReady(): String? = null
            override fun inject(inject: PushInject, onAck: (McpTools.Push.Ack?) -> Unit) {
                onAck(McpTools.Push.Ack(delivered = "handler"))
                throw IllegalStateException("late failure")
            }
        }
        val answers = mutableListOf<JsonObject>()
        val outcome = rpc(session(push = push)).dispatch(
            call("inject_fcm", buildJsonObject {
                put("await", true)
                put("data", buildJsonObject { put("type", "order") })
            }),
            "good-token",
        ) { answers += it }

        assertSame(McpRpc.Outcome.Deferred, outcome)
        assertEquals(1, answers.size)
        val result = answers.single()["result"]!!.jsonObject
        assertNull(result["isError"])
        val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("\"delivered\":\"handler\""), text)
    }

    @Test
    fun `deferred answers reaching the transport are counted once per request`() {
        val calls = AtomicInteger()
        val push = object : McpTools.Push {
            override fun deviceHint(): String = "fake"
            override fun notReady(): String? = null
            override fun inject(inject: PushInject, onAck: (McpTools.Push.Ack?) -> Unit) {
                // Two acks for one injection — a device that reports after its own deadline.
                onAck(McpTools.Push.Ack(delivered = "handler"))
                onAck(McpTools.Push.Ack(delivered = "service"))
            }
        }
        rpc(session(push = push)).dispatch(
            call("inject_fcm", buildJsonObject {
                put("await", true)
                put("data", buildJsonObject { put("type", "order") })
            }),
            "good-token",
        ) { calls.incrementAndGet() }
        assertEquals(1, calls.get())
    }

    // ---- errors ---------------------------------------------------------------------------------

    @Test
    fun `unknown method is -32601`() {
        assertEquals(
            golden.getValue("error.unknownMethod"),
            replyText(rpc(), request("frobnicate")),
        )
    }

    @Test
    fun `malformed json is a parse error`() {
        val outcome = rpc().dispatch("{not json", "good-token") { error("no answer expected") }
        assertEquals(
            golden.getValue("error.parse"),
            rpc().encode((outcome as McpRpc.Outcome.Reply).body),
        )
    }

    @Test
    fun `a json array body is a parse error too`() {
        val outcome = rpc().dispatch("[1,2,3]", "good-token") { error("no answer expected") }
        assertTrue(outcome is McpRpc.Outcome.Reply)
        assertEquals(golden.getValue("error.parse"), rpc().encode((outcome as McpRpc.Outcome.Reply).body))
    }

    @Test
    fun `a request with no method is method-not-found for the empty name`() {
        val body = replyText(rpc(), buildJsonObject { put("jsonrpc", "2.0"); put("id", 1) })
        assertEquals(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found: "}}""",
            body,
        )
    }

    // ---- auth -------------------------------------------------------------------------------

    @Test
    fun `tools call without a token is 401 with the tool-window hint`() {
        assertEquals(
            golden.getValue("error.401.hasSessions"),
            replyText(rpc(), call("session_summary"), token = ""),
        )
    }

    @Test
    fun `tools call with a wrong token is 401`() {
        assertEquals(
            golden.getValue("error.401.hasSessions"),
            replyText(rpc(), call("session_summary"), token = "nope"),
        )
    }

    @Test
    fun `with no capture registered the 401 says so instead`() {
        assertEquals(
            golden.getValue("error.401.noSessions"),
            replyText(emptyRpc(), call("session_summary"), token = "anything"),
        )
    }

    @Test
    fun `the default hint is the wording the plugin ships`() {
        assertEquals(
            "Missing or unknown X-LogPose-Token. Copy the current token from the LogPose tool window.",
            McpRpc.AuthHint.Ide.missingToken,
        )
        assertEquals(
            "No LogPose capture is registered. Open the LogPose tool window in the project you " +
                "want to inspect, then retry.",
            McpRpc.AuthHint.Ide.noCapture,
        )
        assertEquals("X-LogPose-Token", McpRpc.TOKEN_HEADER)
    }

    @Test
    fun `a host may supply its own hint without touching the wire shape`() {
        val custom = McpRpc(
            Sessions("t", session()),
            McpRpc.AuthHint(missingToken = "Run `logpose token`.", noCapture = "No capture."),
        )
        val body = json.parseToJsonElement(replyText(custom, call("session_summary"), token = ""))
            .jsonObject["error"]!!.jsonObject
        assertEquals(-32001, body["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("Run `logpose token`.", body["message"]!!.jsonPrimitive.content)
    }

}
