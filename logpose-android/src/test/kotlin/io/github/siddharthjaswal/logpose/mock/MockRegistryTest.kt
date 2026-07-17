package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockRegistryTest {

    @Before fun setUp() = MockRegistry.reset()
    @After fun tearDown() = MockRegistry.reset()

    private fun rule(
        id: String,
        method: String = "GET",
        path: String = "/app/v1/x",
        status: Int = 200,
        serveLimit: Int = 0,
        enabled: Boolean = true,
    ) = MockRule(id = id, method = method, pathPattern = path, status = status, serveLimit = serveLimit, enabled = enabled)

    @Test fun `applies a rule set and matches by method and exact path`() {
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(rule("a", "GET", "/app/v1/x"))))
        assertEquals("a", MockRegistry.match("GET", "/app/v1/x")?.id)
        assertNull(MockRegistry.match("POST", "/app/v1/x"))
        assertNull(MockRegistry.match("GET", "/app/v1/y"))
    }

    @Test fun `wildcard method matches any verb`() {
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(rule("a", "*", "/ping"))))
        assertEquals("a", MockRegistry.match("DELETE", "/ping")?.id)
    }

    @Test fun `glob path matches and escapes regex metacharacters`() {
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(rule("a", "GET", "/app/v4/*/order/*"))))
        assertEquals("a", MockRegistry.match("GET", "/app/v4/79096/order/21047484/")?.id)
        // A literal dot in the pattern must not act as a wildcard.
        MockRegistry.apply(MockRuleSet(revision = 2, rules = listOf(rule("b", "GET", "/a.b"))))
        assertNull(MockRegistry.match("GET", "/axb"))
    }

    @Test fun `first matching rule in list order wins`() {
        MockRegistry.apply(
            MockRuleSet(revision = 1, rules = listOf(rule("first", "GET", "/x", 500), rule("second", "GET", "/x", 200)))
        )
        assertEquals("first", MockRegistry.match("GET", "/x")?.id)
    }

    @Test fun `disabled rules never match`() {
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(rule("a", enabled = false))))
        assertNull(MockRegistry.match("GET", "/app/v1/x"))
    }

    @Test fun `serve limit deactivates a rule after N serves`() {
        val r = rule("a", serveLimit = 2)
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(r)))
        MockRegistry.match("GET", "/app/v1/x")!!.also { MockRegistry.recordServe(it) }
        MockRegistry.match("GET", "/app/v1/x")!!.also { MockRegistry.recordServe(it) }
        assertNull("rule should be exhausted after 2 serves", MockRegistry.match("GET", "/app/v1/x"))
        assertEquals(2, MockRegistry.hits()["a"])
    }

    @Test fun `stale revision is ignored, newer is applied`() {
        assertTrue(MockRegistry.apply(MockRuleSet(revision = 5, rules = listOf(rule("new")))))
        assertFalse(MockRegistry.apply(MockRuleSet(revision = 4, rules = listOf(rule("old")))))
        assertEquals("new", MockRegistry.match("GET", "/app/v1/x")?.id)
        assertEquals(5, MockRegistry.revision)
    }

    @Test fun `hit counts survive a re-push with stable ids`() {
        MockRegistry.apply(MockRuleSet(revision = 1, rules = listOf(rule("a"))))
        MockRegistry.match("GET", "/app/v1/x")!!.also { MockRegistry.recordServe(it) }
        // Re-push (e.g. user edited an unrelated field) — the same id keeps its count.
        MockRegistry.apply(MockRuleSet(revision = 2, rules = listOf(rule("a", status = 404))))
        assertEquals(1, MockRegistry.hits()["a"])
    }
}
