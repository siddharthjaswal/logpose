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

    @Test fun `correlation keys migrate once from the old private store to the shared file`() {
        // A user who configured keys before sharing has them in the IDE's private store…
        val old = store()
        CorrelationSettings.setKeys(old, listOf(CorrelationKey("order_id"), CorrelationKey("rider_id")))

        // …and the first shared-file access copies them across.
        val shared = store()
        CorrelationSettings.migrateIfNeeded(from = old, to = shared)
        assertEquals(listOf("order_id", "rider_id"), CorrelationSettings.keys(shared).map { it.name })
        assertTrue(CorrelationSettings.configured(shared))
        // The old store is left intact — a downgrade must not lose the vocabulary.
        assertEquals(listOf("order_id", "rider_id"), CorrelationSettings.keys(old).map { it.name })
    }

    @Test fun `migration is idempotent and never clobbers keys already in the shared file`() {
        val old = store().apply { CorrelationSettings.setKeys(this, listOf(CorrelationKey("order_id"))) }
        val shared = store().apply { CorrelationSettings.setKeys(this, listOf(CorrelationKey("chain_vehicle_id"))) }

        // The shared file already has a vocabulary — migration must leave it exactly as-is.
        CorrelationSettings.migrateIfNeeded(from = old, to = shared)
        assertEquals(listOf("chain_vehicle_id"), CorrelationSettings.keys(shared).map { it.name })
    }

    @Test fun `migration of a 'configured but empty' project carries the configured flag`() {
        // A user who opened the dialog and ticked nothing is "configured" with no keys — that
        // decision (don't re-seed suggestions) must survive the move.
        val old = store().apply { CorrelationSettings.setKeys(this, emptyList()) }
        assertTrue(CorrelationSettings.configured(old))
        assertNull(old.get("logpose.correlation.keys"))

        val shared = store()
        CorrelationSettings.migrateIfNeeded(from = old, to = shared)
        assertTrue(CorrelationSettings.configured(shared))
    }

    @Test fun `a never-configured project migrates nothing`() {
        val old = store()
        val shared = store()
        CorrelationSettings.migrateIfNeeded(from = old, to = shared)
        assertFalse(CorrelationSettings.configured(shared))
        assertNull(shared.get("logpose.correlation.keys"))
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
