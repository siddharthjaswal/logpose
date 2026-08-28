package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.MockStep
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mock half of the wire contract, focused on what 1.7.0 added: matchers and response steps.
 * Every new field is default-valued, so the thing worth proving is that a rule set written by an
 * older plugin still decodes and still means exactly what it used to.
 */
class MockWireTest {

    private val out = Json { encodeDefaults = true; explicitNulls = false }
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `a rule from before matchers existed decodes unconstrained`() {
        val old = """{"id":"a","method":"GET","pathPattern":"/x","status":404,"body":"{}"}"""
        val rule = parser.decodeFromString<MockRule>(old)
        assertEquals(404, rule.status)
        assertTrue("no query constraint", rule.matchQuery.isEmpty())
        assertTrue("no header constraint", rule.matchHeaders.isEmpty())
        assertNull("no body constraint", rule.matchBodyContains)
        assertTrue("no sequence — the single response still applies", rule.responses.isEmpty())
    }

    @Test fun `matchers and steps round-trip inside a rule set`() {
        val set = MockRuleSet(
            revision = 4,
            rules = listOf(
                MockRule(
                    id = "a",
                    method = "POST",
                    pathPattern = "/app/v4/*/order/*/accept",
                    matchQuery = mapOf("city_id" to "79096", "debug" to MockRule.MATCH_ANY),
                    matchHeaders = mapOf("X-App-Version" to "9.1.0", "Authorization" to MockRule.MATCH_ANY),
                    matchBodyContains = "\"reason\":\"EXPIRED\"",
                    serveLimit = 3,
                    responses = listOf(
                        MockStep(status = 500),
                        MockStep(status = 200, body = """{"ok":true}""", latencyMillis = 250),
                    ),
                )
            ),
        )
        val back = parser.decodeFromString<MockRuleSet>(out.encodeToString(MockRuleSet.serializer(), set))
        assertEquals(set, back)
        assertEquals("*", MockRule.MATCH_ANY)
    }

    @Test fun `a bare step serves what a bare rule serves`() {
        // Rule-level fields are ignored once a sequence is present, so the step defaults have to
        // be the rule defaults — otherwise `[{status:500},{status:200}]` would quietly change the
        // content type of every response in the sequence.
        val rule = MockRule(id = "a", method = "GET", pathPattern = "/x")
        val step = MockStep()
        assertEquals(rule.status, step.status)
        assertEquals(rule.body, step.body)
        assertEquals(rule.headers, step.headers)
        assertEquals(rule.contentType, step.contentType)
        assertEquals(rule.latencyMillis, step.latencyMillis)
        assertEquals(rule.behavior, step.behavior)
    }

    @Test fun `a step list from a newer plugin survives unknown keys`() {
        val future = """
            {"id":"a","method":"GET","pathPattern":"/x",
             "responses":[{"status":503,"jitterMillis":40}],"unknownRuleField":true}
        """.trimIndent()
        val rule = parser.decodeFromString<MockRule>(future)
        assertEquals(503, rule.responses.single().status)
    }
}
