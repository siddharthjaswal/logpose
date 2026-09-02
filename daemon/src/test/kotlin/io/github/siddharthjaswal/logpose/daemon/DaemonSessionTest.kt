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
                capture = Capture(options, settings, log),
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
    fun `read-only by default — the write surfaces are simply absent`() {
        val session = sessionFor(mocks = false)
        assertNull(session.mocks, "create_mock must not be able to write without --mocks")
        assertNull(session.push, "inject_fcm must not be able to write without --mocks")
        assertNull(session.scenarios, "load_scenario must not be able to write without --mocks")
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
