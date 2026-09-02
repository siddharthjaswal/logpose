package io.github.siddharthjaswal.logpose.settings

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The property names LogPose has written to `PropertiesComponent` since before `:core` existed.
 *
 * These are not internal detail: they are user data already on disk — the MCP token pasted into
 * a `claude mcp add` line, a project's mock rule set, a team's correlation vocabulary. Moving the
 * controllers behind [KeyValueStore] must not rename one, so every key is pinned here as a
 * literal, exactly as the pre-split code spelled it.
 */
class PersistedKeysTest {

    private fun store() = KeyValueStore.InMemory()

    @Test fun `mcp token and body exposure keep their keys`() {
        val s = store()
        val token = McpSessions.tokenFor(s)
        assertEquals(token, s.get("logpose.mcp.token"))
        // Regenerating from the same store returns the stored token, not a new one.
        assertEquals(token, McpSessions.tokenFor(s))

        assertTrue(McpSessions.exposeBodies(s), "defaults to on")
        McpSessions.setExposeBodies(s, false)
        assertEquals("false", s.get("logpose.mcp.exposeBodies"))
        assertFalse(McpSessions.exposeBodies(s))
        // Back to the default stores nothing, which is how PropertiesComponent behaves.
        McpSessions.setExposeBodies(s, true)
        assertNull(s.get("logpose.mcp.exposeBodies"))
    }

    @Test fun `mock rules revision and package keep their keys and round-trip`() {
        val s = store()
        val rule = MockRule(id = "r1", method = "GET", pathPattern = "/riders")
        MocksController(s).addOrUpdate(rule, baseBody = """{"a":1}""")

        assertTrue(s.get("logpose.mock.rules")!!.contains("\"r1\""))
        assertEquals("1", s.get("logpose.mock.revision"))
        assertTrue(s.get("logpose.mock.baseBodies")!!.contains("""{\"a\":1}"""))

        val reloaded = MocksController(s)
        assertEquals(listOf("r1"), reloaded.rules().map { it.id })
        assertEquals("""{"a":1}""", reloaded.baseBodyFor("r1"))
    }

    @Test fun `correlation keys keep their keys`() {
        val s = store()
        assertFalse(CorrelationSettings.configured(s))
        CorrelationSettings.setKeys(s, listOf(CorrelationKey("order_id")))
        assertTrue(s.get("logpose.correlation.keys")!!.contains("order_id"))
        assertEquals("true", s.get("logpose.correlation.configured"))
        assertTrue(CorrelationSettings.configured(s))
        assertEquals(listOf("order_id"), CorrelationSettings.keys(s).map { it.name })

        // An emptied vocabulary clears the value rather than storing a blank one.
        CorrelationSettings.setKeys(s, emptyList())
        assertNull(s.get("logpose.correlation.keys"))
    }

    @Test fun `endpoint mutes keep their application-level key`() {
        val s = store()
        MutedEndpoints.store = s
        try {
            val tx = Transaction(id = "t1", request = Request(method = "GET", url = "https://x/app/v3/79096/location/", path = "/app/v3/79096/location/"))
            assertTrue(MutedEndpoints.toggle(tx))
            assertEquals("/app/v3/#/location/", s.get("io.github.siddharthjaswal.logpose.mutedEndpoints"))
            assertFalse(MutedEndpoints.toggle(tx))
            assertNull(s.get("io.github.siddharthjaswal.logpose.mutedEndpoints"))
        } finally {
            MutedEndpoints.store = KeyValueStore.InMemory()
        }
    }
}
