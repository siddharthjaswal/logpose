package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.MockStep
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

    // ---- match constraints ------------------------------------------------------------------

    private fun apply(vararg rules: MockRule) =
        MockRegistry.apply(MockRuleSet(revision = 1, rules = rules.toList()))

    private fun match(
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ) = MockRegistry.match("GET", "/app/v1/x", query, headers, body)?.id

    @Test fun `every query pair must match, and a star means any value`() {
        apply(rule("a").copy(matchQuery = mapOf("city_id" to "79096", "debug" to MockRule.MATCH_ANY)))

        assertEquals("a", match(query = mapOf("city_id" to "79096", "debug" to "1")))
        assertEquals("a", match(query = mapOf("city_id" to "79096", "debug" to "", "extra" to "ok")))
        assertNull("wrong value", match(query = mapOf("city_id" to "12", "debug" to "1")))
        assertNull("missing the wildcard key", match(query = mapOf("city_id" to "79096")))
        assertNull("no query at all", match())
    }

    @Test fun `an unconstrained rule ignores query, headers and body entirely`() {
        apply(rule("a"))
        assertEquals("a", match(query = mapOf("anything" to "goes"), headers = mapOf("X" to "y")))
    }

    @Test fun `header names match case-insensitively, values exactly`() {
        apply(rule("a").copy(matchHeaders = mapOf("X-App-Version" to "9.1.0")))

        assertEquals("a", match(headers = mapOf("x-app-version" to "9.1.0")))
        assertEquals("a", match(headers = mapOf("X-APP-VERSION" to "9.1.0")))
        assertNull("values are exact — 9.1.0 is not 9.1.1", match(headers = mapOf("X-App-Version" to "9.1.1")))
        assertNull("value case is not folded", match(headers = mapOf("X-App-Version" to "9.1.0 ")))
        assertNull(match(headers = mapOf("X-Other" to "9.1.0")))
    }

    @Test fun `a star header only asks that the header be present`() {
        apply(rule("a").copy(matchHeaders = mapOf("Authorization" to MockRule.MATCH_ANY)))
        assertEquals("a", match(headers = mapOf("authorization" to "Bearer whatever")))
        assertNull(match(headers = emptyMap()))
    }

    @Test fun `body matching is a case-insensitive substring`() {
        apply(rule("a").copy(matchBodyContains = "\"reason\":\"EXPIRED\""))

        assertEquals("a", match(body = """{"id":7,"reason":"EXPIRED"}"""))
        assertEquals("case folds, so a serializer's casing choice doesn't break the rule",
            "a", match(body = """{"Reason":"Expired"}"""))
        assertNull(match(body = """{"reason":"CANCELLED"}"""))
    }

    @Test fun `a body matcher fails closed when the body could not be read`() {
        // A streaming/one-shot body is never buffered, so the interceptor hands null. Matching it
        // anyway would mock a request on a guess — the one thing a trust tool must not do.
        apply(rule("a").copy(matchBodyContains = "order"))
        assertNull(match(body = null))
    }

    @Test fun `the registry only asks for a body when a rule wants one`() {
        apply(rule("a"))
        assertFalse("no body matcher — the interceptor must not read bodies", MockRegistry.needsBody)

        apply(rule("a").copy(matchBodyContains = "order"))
        assertTrue(MockRegistry.needsBody)

        apply(rule("a").copy(matchBodyContains = "order", enabled = false))
        assertFalse("a disabled rule cannot cost every request a body read", MockRegistry.needsBody)
    }

    @Test fun `constraints compose - all of them must hold`() {
        apply(
            rule("a").copy(
                matchQuery = mapOf("city_id" to "79096"),
                matchHeaders = mapOf("X-App-Version" to "9.1.0"),
                matchBodyContains = "accept",
            )
        )
        val query = mapOf("city_id" to "79096")
        val headers = mapOf("X-App-Version" to "9.1.0")

        assertEquals("a", match(query, headers, body = """{"action":"accept"}"""))
        assertNull(match(query, headers, body = """{"action":"reject"}"""))
        assertNull(match(query, emptyMap(), body = """{"action":"accept"}"""))
        assertNull(match(emptyMap(), headers, body = """{"action":"accept"}"""))
    }

    @Test fun `a narrower rule listed first wins over a broad one behind it`() {
        apply(
            rule("specific", status = 500).copy(matchBodyContains = "EXPIRED"),
            rule("broad", status = 200),
        )
        assertEquals("specific", match(body = """{"reason":"EXPIRED"}"""))
        assertEquals("broad", match(body = """{"reason":"OTHER"}"""))
    }

    // ---- response sequences -----------------------------------------------------------------

    private fun sequenced(vararg steps: MockStep) =
        rule("seq").copy(responses = steps.toList())

    @Test fun `a rule without a sequence serves its own fields`() {
        apply(rule("a", status = 418))
        assertNull("no step means the rule-level response applies", MockRegistry.stepFor(rule("a")))
    }

    @Test fun `steps advance with the hit count`() {
        val r = sequenced(MockStep(status = 500), MockStep(status = 502), MockStep(status = 200))
        apply(r)

        assertEquals(500, MockRegistry.stepFor(r)!!.status)
        MockRegistry.recordServe(r)
        assertEquals(502, MockRegistry.stepFor(r)!!.status)
        MockRegistry.recordServe(r)
        assertEquals(200, MockRegistry.stepFor(r)!!.status)
    }

    @Test fun `the last step sticks once the sequence runs out`() {
        val r = sequenced(MockStep(status = 500), MockStep(status = 200, body = """{"ok":true}"""))
        apply(r)

        repeat(5) { MockRegistry.recordServe(r) }
        assertEquals(200, MockRegistry.stepFor(r)!!.status)
        assertEquals("""{"ok":true}""", MockRegistry.stepFor(r)!!.body)
    }

    @Test fun `a serve limit still applies across a sequence`() {
        val r = sequenced(MockStep(status = 500), MockStep(status = 200)).copy(serveLimit = 2)
        apply(r)

        MockRegistry.match("GET", "/app/v1/x")!!.also { MockRegistry.recordServe(it) }
        MockRegistry.match("GET", "/app/v1/x")!!.also { MockRegistry.recordServe(it) }
        assertNull("serveLimit is a rule-level policy, not a per-step one", MockRegistry.match("GET", "/app/v1/x"))
    }

    @Test fun `step progression follows the rule id, so an unrelated re-push cannot reset it`() {
        val r = sequenced(MockStep(status = 500), MockStep(status = 200))
        apply(r)
        MockRegistry.recordServe(r)

        apply(r.copy(pathPattern = "/app/v1/x", latencyMillis = 10))
        assertEquals(200, MockRegistry.stepFor(MockRegistry.match("GET", "/app/v1/x")!!)!!.status)
    }
}
