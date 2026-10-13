package io.github.siddharthjaswal.logpose.desktop.advanced

import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter.Mode
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockWriterTest {

    @Test fun `read-only unless the user handed this device to the app`() {
        assertEquals(Mode.HIDDEN, MockWriter.mode(false, setOf("emu"), "emu"))
        assertEquals(Mode.READ_ONLY, MockWriter.mode(true, emptySet(), "emu"))
        assertEquals(Mode.READ_ONLY, MockWriter.mode(true, setOf("other"), "emu"))
        assertEquals(Mode.READ_ONLY, MockWriter.mode(true, setOf("emu"), null))
        assertEquals(Mode.WRITER, MockWriter.mode(true, setOf("emu"), "emu"))
    }

    @Test fun `only the writer arms the device or clears it on stop`() {
        for (m in Mode.entries) {
            assertEquals(m == Mode.WRITER, MockWriter.armsDevice(m))
            assertEquals(m == Mode.WRITER, MockWriter.clearsOnStop(m))
        }
    }

    @Test fun `writers round-trip through the settings value`() {
        assertEquals(setOf("a", "emulator-5554"), MockWriter.parse(MockWriter.serialize(setOf("emulator-5554", "a"))))
        assertNull(MockWriter.serialize(emptySet()))
        assertEquals(emptySet<String>(), MockWriter.parse(" , "))
    }

    @Test fun `MCP surfaces follow the mode - reads always, writes only as the writer`() {
        assertEquals(McpSurfaces(false, false, false), McpSurfaces.of(Mode.HIDDEN))
        assertEquals(McpSurfaces(false, false, false), McpSurfaces.of(Mode.READ_ONLY))
        assertEquals(McpSurfaces(true, true, true), McpSurfaces.of(Mode.WRITER))
    }
}

class DraftsTest {

    private val tx = Transaction(
        id = "t", startedAtMillis = 1,
        request = Request("GET", "https://x/app/v1/location?x=1", "x", "/app/v1/location"),
        response = Response(200, "OK", emptyMap(), Body("application/json", 10, """{"lat":1}""")),
    )

    @Test fun `mock this endpoint seeds method, path, status and the captured body`() {
        val d = MockDraft.fromTransaction(tx, id = "r1")
        val rule = d.toRule()
        assertEquals("GET", rule.method)
        assertEquals("/app/v1/location", rule.pathPattern)
        assertEquals(200, rule.status)
        assertEquals("{\n    \"lat\": 1\n}", rule.body)
        assertNull(d.problem())
    }

    @Test fun `validation is the plugin dialog's`() {
        val d = MockDraft.fromTransaction(tx, id = "r1")
        assertEquals("Status must be 100–599", d.copy(status = "42").problem())
        assertNull(d.copy(status = "42", behavior = MockRule.BEHAVIOR_TIMEOUT).problem())
        assertEquals("Latency must be ≥ 0", d.copy(latency = "-1").problem())
        assertEquals("Serve limit must be ≥ 0", d.copy(serveLimit = "x").problem())
        assertTrue(d.copy(mode = MockRule.MODE_PATCH, body = "{}").problem()!!.contains("JSON object"))
        assertTrue(d.copy(mode = MockRule.MODE_PATCH, body = "[1]").problem()!!.contains("JSON object"))
        assertNull(d.copy(mode = MockRule.MODE_PATCH, body = """{"status":"ACCEPTED"}""").problem())
    }

    @Test fun `merge forces normal behaviour, matchers and steps reach the rule`() {
        val d = MockDraft.fromTransaction(tx, id = "r1").copy(
            mode = MockRule.MODE_PATCH, behavior = MockRule.BEHAVIOR_TIMEOUT, body = """{"a":1}""",
            matchQuery = "debug=1", matchHeaders = "X-Env", matchBody = " rider ", serveLimit = "3",
        )
        val r = d.toRule()
        assertEquals(MockRule.BEHAVIOR_NORMAL, r.behavior)
        assertEquals(mapOf("debug" to "1"), r.matchQuery)
        assertEquals(mapOf("X-Env" to MockRule.MATCH_ANY), r.matchHeaders)
        assertEquals("rider", r.matchBodyContains)
        assertEquals(3, r.serveLimit)

        val steps = MockDraft.fromTransaction(tx, id = "r2").withSteps(true)
        assertEquals(1, steps.steps.size)
        val failThenOk = steps.copy(steps = listOf(steps.steps[0].copy(status = "500"), steps.steps[0]))
        assertEquals(listOf(500, 200), failThenOk.toRule().responses.map { it.status })
        assertEquals("Step 1: status must be 100–599", failThenOk.copy(steps = listOf(steps.steps[0].copy(status = "9"))).problem())
        // An existing rule round-trips through the editor unchanged.
        assertEquals(failThenOk.toRule(), MockDraft.of(failThenOk.toRule()).toRule().copy(body = failThenOk.toRule().body))
    }

    @Test fun `compose push - data must be a flat object, numbers stringified`() {
        val d = PushDraft(data = """{"orderId": 42, "urgent": true}""", title = " New order ")
        assertNull(d.problem())
        val m = d.toMessage()
        assertEquals(mapOf("orderId" to "42", "urgent" to "true"), m.data)
        assertEquals("New order", m.notificationTitle)
        assertNull(m.from)
        assertTrue(PushDraft(data = """{"a":{"b":1}}""").problem() != null)
        assertFalse(PushDraft(data = "").problem() != null)
    }
}
