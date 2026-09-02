package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The mock dialog's logic, tested without the dialog. What a matcher box means and what a step
 * list serializes to is exactly the part that decides whether a rule matches what it says it
 * matches — the thing LogPose can least afford to get wrong.
 */
class MockRuleFormTest {

    @Test
    fun `a key on its own means present-with-any-value`() {
        assertEquals(
            mapOf("cursor" to MockRule.MATCH_ANY, "debug" to "1", "X-Env" to "staging"),
            MockRuleForm.parsePairs("cursor\ndebug=1\nX-Env: staging"),
        )
        assertEquals(mapOf("trace" to MockRule.MATCH_ANY), MockRuleForm.parsePairs("trace:"))
        assertEquals(mapOf("trace" to MockRule.MATCH_ANY), MockRuleForm.parsePairs("trace = *"))
    }

    @Test
    fun `the first separator wins, so a value may contain the other one`() {
        assertEquals(mapOf("token" to "a=b"), MockRuleForm.parsePairs("token=a=b"))
        assertEquals(mapOf("Referer" to "https://x/y"), MockRuleForm.parsePairs("Referer: https://x/y"))
        // ':' before '=' and vice versa.
        assertEquals(mapOf("a" to "b=c"), MockRuleForm.parsePairs("a: b=c"))
        assertEquals(mapOf("a" to "b:c"), MockRuleForm.parsePairs("a=b:c"))
    }

    @Test
    fun `semicolons separate entries too, so one line of step headers parses`() {
        assertEquals(
            mapOf("X-A" to "1", "X-B" to "2"),
            MockRuleForm.parsePairs("X-A: 1; X-B: 2"),
        )
    }

    @Test
    fun `blank lines, comments and keyless lines are dropped rather than becoming empty matchers`() {
        assertEquals(emptyMap<String, String>(), MockRuleForm.parsePairs("\n\n   \n# a note\n: orphan\n"))
    }

    @Test
    fun `formatting round-trips a matcher map`() {
        val pairs = mapOf("debug" to "1", "cursor" to MockRule.MATCH_ANY)
        assertEquals(pairs, MockRuleForm.parsePairs(MockRuleForm.formatPairs(pairs)))
    }

    @Test
    fun `a single-response rule opens the step editor pre-filled from the capture`() {
        val rule = MockRule(
            id = "r", method = "GET", pathPattern = "/v1/x",
            status = 503, body = """{"down":true}""", headers = mapOf("Retry-After" to "5"),
            contentType = "text/plain", latencyMillis = 250, behavior = MockRule.BEHAVIOR_NORMAL,
        )
        val drafts = MockRuleForm.draftsOf(rule)
        assertEquals(1, drafts.size)
        val step = MockRuleForm.toStep(drafts.single())
        assertEquals(
            MockStep(
                status = 503, body = """{"down":true}""", headers = mapOf("Retry-After" to "5"),
                contentType = "text/plain", latencyMillis = 250,
            ),
            step,
        )
    }

    @Test
    fun `an existing sequence is edited as itself, not flattened into one step`() {
        val rule = MockRule(
            id = "r", method = "GET", pathPattern = "/v1/x",
            responses = listOf(MockStep(status = 500), MockStep(status = 200, body = "{}", latencyMillis = 30)),
        )
        val drafts = MockRuleForm.draftsOf(rule)
        assertEquals(2, drafts.size)
        assertEquals(rule.responses, MockRuleForm.toSteps(drafts))
    }

    @Test
    fun `an empty step body becomes null, not an empty body`() {
        val step = MockRuleForm.toStep(MockRuleForm.StepDraft(status = "204", body = "  "))
        assertNull(step.body)
        assertEquals(204, step.status)
    }

    @Test
    fun `unparseable numbers fall back to the field defaults rather than throwing`() {
        val step = MockRuleForm.toStep(MockRuleForm.StepDraft(status = "abc", latency = "-", contentType = " "))
        assertEquals(200, step.status)
        assertEquals(0, step.latencyMillis)
        assertEquals("application/json", step.contentType)
    }

    @Test
    fun `the row shows the response the next hit will get, not the rule-level leftovers`() {
        val rule = MockRule(
            id = "r", method = "GET", pathPattern = "/v1/x",
            status = 200, behavior = MockRule.BEHAVIOR_NORMAL,
            responses = listOf(
                MockStep(status = 500, behavior = MockRule.BEHAVIOR_TIMEOUT),
                MockStep(status = 200),
            ),
        )
        assertEquals(500, MockRuleForm.effectiveStatus(rule))
        assertEquals(MockRule.BEHAVIOR_TIMEOUT, MockRuleForm.effectiveBehavior(rule))
        assertEquals("×2 steps", MockRuleForm.stepsLabel(rule))
        assertTrue(MockRuleForm.stepsSummary(rule)!!.contains("timeout → 200"), MockRuleForm.stepsSummary(rule)!!)
    }

    @Test
    fun `a plain rule advertises neither steps nor constraints`() {
        val plain = MockRule(id = "r", method = "GET", pathPattern = "/v1/x")
        assertNull(MockRuleForm.stepsLabel(plain))
        assertNull(MockRuleForm.stepsSummary(plain))
        assertNull(MockRuleForm.matchSummary(plain))
        assertEquals(200, MockRuleForm.effectiveStatus(plain))
    }

    @Test
    fun `the tooltip says what narrows the rule, in the words the dialog used`() {
        val rule = MockRule(
            id = "r", method = "GET", pathPattern = "/v1/x",
            matchQuery = mapOf("debug" to "1", "cursor" to MockRule.MATCH_ANY),
            matchHeaders = mapOf("X-Env" to "staging"),
            matchBodyContains = "\"force\":true",
        )
        val summary = MockRuleForm.matchSummary(rule)!!
        assertTrue(summary.startsWith("only when "), summary)
        assertTrue(summary.contains("?debug=1"), summary)
        assertTrue(summary.contains("?cursor present"), summary)
        assertTrue(summary.contains("X-Env: staging"), summary)
        assertTrue(summary.contains("body contains"), summary)
    }
}
