package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single-writer gate (PRD §7), asserted where it is actually implemented: which write surfaces
 * the session exposes.
 */
class DaemonSessionTest {

    @TempDir lateinit var dir: File

    private val pool = Executors.newCachedThreadPool()
    private val log = Log(verboseStateChanges = false)

    private fun sessionFor(mocks: Boolean, exposeBodies: Boolean = true) =
        options(mocks, exposeBodies).let { options ->
            val settings: KeyValueStore = KeyValueStore.InMemory()
            DaemonSession(
                capture = Capture(options, settings, KeyValueStore.InMemory(), log),
                options = options,
                scenarioStore = io.github.siddharthjaswal.logpose.mock.ScenarioStore(File(dir, "scenarios")),
                pool = pool,
                log = log,
            ).session()
        }

    private fun options(mocks: Boolean, exposeBodies: Boolean = true) = Cli.ServeOptions(
        projectDir = dir,
        mocks = mocks,
        exposeBodies = exposeBodies,
    )

    @Test
    fun `read-only by default — push is absent, mocks and scenarios are present but not writable`() {
        val session = sessionFor(mocks = false)
        // Every push tool is a write, so the surface goes entirely.
        assertNull(session.push, "inject_fcm must not be able to write without --mocks")
        // These two also serve reads (list_mocks, list_scenarios, save_scenario), so they stay —
        // and say they can't write.
        assertNotNull(session.mocks, "list_mocks is a read and must still answer")
        assertNotNull(session.scenarios, "list_scenarios is a read and must still answer")
        assertFalse(session.mocks!!.writable(), "create_mock must not be able to write without --mocks")
        assertFalse(session.scenarios!!.writable(), "load_scenario must not push rules without --mocks")
    }

    @Test
    fun `create_mock declines in read-only mode, with the daemon's wording`() {
        val session = sessionFor(mocks = false)
        val payload = McpTools.call(
            name = "create_mock",
            args = kotlinx.serialization.json.buildJsonObject {},
            events = emptyList(),
            hostAgeMillis = session.hostAgeMillis,
            includeBodies = true,
            mocks = session.mocks,
            captureRunning = session.captureRunning,
            clearCapture = session.clearCapture,
            unavailable = DaemonSession.Unavailable,
        ).jsonObject
        val error = payload["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("--mocks"), "was: $error")
        assertFalse(error.contains("tool window"), "the daemon must not send the IDE's fix: $error")
    }

    @Test
    fun `list_mocks answers in read-only mode instead of declining`() {
        val session = sessionFor(mocks = false)
        val payload = McpTools.call(
            name = "list_mocks",
            args = kotlinx.serialization.json.buildJsonObject {},
            events = emptyList(),
            hostAgeMillis = session.hostAgeMillis,
            includeBodies = true,
            mocks = session.mocks,
            captureRunning = session.captureRunning,
            clearCapture = session.clearCapture,
            unavailable = DaemonSession.Unavailable,
        ).jsonObject
        assertNull(payload["error"], "a read must not be gated by the single-writer rule: $payload")
        assertNotNull(payload["rules"] ?: payload["mocks"], "was: $payload")
    }

    @Test
    fun `list_scenarios reads the shared directory in read-only mode, load_scenario declines`() {
        val session = sessionFor(mocks = false)

        val listed = awaitAsync(session, "list_scenarios")
        assertNull(listed["error"], "list_scenarios reads .logpose/scenarios and is never gated: $listed")

        val loaded = awaitAsync(
            session, "load_scenario",
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("anything"))
            },
        )
        val error = loaded["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("--mocks"), "was: $error")
        assertFalse(error.contains("tool window"), "was: $error")
    }

    private fun awaitAsync(
        session: io.github.siddharthjaswal.logpose.mcp.McpSessions.Session,
        name: String,
        args: JsonObject = kotlinx.serialization.json.buildJsonObject {},
    ): JsonObject {
        val latch = CountDownLatch(1)
        var payload: JsonObject? = null
        McpTools.callAsync(
            name = name,
            args = args,
            events = emptyList(),
            push = session.push,
            waits = session.waits,
            scenarios = session.scenarios,
            correlations = session.correlations,
            captureRunning = session.captureRunning,
            unavailable = DaemonSession.Unavailable,
        ) { result -> payload = result.jsonObject; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "$name never answered")
        return payload!!
    }

    @Test
    fun `reads are never gated`() {
        val session = sessionFor(mocks = false)
        assertNotNull(session.correlations, "correlation is a read and stays available")
        assertNotNull(session.waits, "await_event reads the capture and stays available")
        assertNotNull(session.store)
    }

    @Test
    fun `with --mocks every write surface is wired`() {
        val session = sessionFor(mocks = true)
        assertNotNull(session.mocks)
        assertNotNull(session.push)
        assertNotNull(session.scenarios)
    }

    @Test
    fun `a gated write tool returns McpTools' own not-available result, not a transport error`() {
        val session = sessionFor(mocks = false)
        val latch = CountDownLatch(1)
        var payload: JsonObject? = null
        McpTools.callAsync(
            name = "inject_fcm",
            args = kotlinx.serialization.json.buildJsonObject {},
            events = emptyList(),
            push = session.push,
            waits = session.waits,
            scenarios = session.scenarios,
            correlations = session.correlations,
            captureRunning = session.captureRunning,
        ) { result -> payload = result.jsonObject; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val error = payload!!["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("Push injection isn't available"), "was: $error")
    }

    @Test
    fun `a gated read tool still answers from the capture`() {
        val session = sessionFor(mocks = false)
        val payload = McpTools.call(
            name = "session_summary",
            args = kotlinx.serialization.json.buildJsonObject {},
            events = session.store.snapshot(),
            hostAgeMillis = session.hostAgeMillis,
            includeBodies = session.exposeBodies(),
            mocks = session.mocks,
            sessions = session.store.sessions(),
            sessionOf = { id -> session.store.sessionOf(id) },
            captureRunning = session.captureRunning,
            clearCapture = session.clearCapture,
        )
        assertNotNull(payload.jsonObject)
    }

    @Test
    fun `--no-bodies rides through to the session's exposeBodies`() {
        assertTrue(sessionFor(mocks = false, exposeBodies = true).exposeBodies())
        assertFalse(sessionFor(mocks = false, exposeBodies = false).exposeBodies())
    }

    @Test
    fun `the name a session reports is the flag, else the directory`() {
        assertEquals(dir.name, sessionFor(mocks = false).projectName)
    }

    @Test
    fun `the daemon's not-available wording never mentions the tool window`() {
        val words = DaemonSession.Unavailable
        listOf(words.mocks, words.push, words.scenarios, words.waits, words.correlations)
            .forEach { assertFalse(it.contains("tool window"), "was: $it") }
        listOf(words.mocks, words.push, words.scenarios)
            .forEach { assertTrue(it.contains("--mocks"), "a write refusal must name the flag: $it") }
    }

    @Test
    fun `the daemon's 401 hint names the daemon, never the tool window`() {
        val hint = DaemonSession.AuthHint
        assertFalse(hint.missingToken.contains("tool window"))
        assertFalse(hint.noCapture.contains("tool window"))
        assertTrue(hint.missingToken.contains("daemon"))
        assertTrue(hint.missingToken.contains("claude mcp add"))
    }

    @Test
    fun `token comparison is exact`() {
        assertTrue(DaemonSession.constantTimeEquals("abc", "abc"))
        assertFalse(DaemonSession.constantTimeEquals("abc", "abd"))
        assertFalse(DaemonSession.constantTimeEquals("abc", "abcd"))
        assertFalse(DaemonSession.constantTimeEquals("", "a"))
    }
}
