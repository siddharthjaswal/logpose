package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockStep

/**
 * The parts of [MockRuleDialog] that are logic rather than layout: turning typed text into
 * matcher maps, turning a rule's response into an editable list of steps and back, and phrasing
 * what a rule constrains.
 *
 * Free of Swing and IntelliJ on purpose — a dialog can't be unit-tested, and these are exactly
 * the rules that decide what a mock ends up matching, which is the thing that must not be wrong.
 */
object MockRuleForm {

    /** One editable response in a sequence, as strings — what the fields actually hold. */
    data class StepDraft(
        val status: String = "200",
        val body: String = "",
        val headers: String = "",
        val contentType: String = "application/json",
        val latency: String = "0",
        val behavior: String = MockRule.BEHAVIOR_NORMAL,
    )

    /**
     * Parses `key: value` / `key=value` entries into a matcher map. Entries are separated by a
     * newline **or** a `;`, so the same parser serves the multi-line matcher boxes and the
     * one-line header field on a response step.
     *
     * A key on its own (or with an empty value) means [MockRule.MATCH_ANY] — "present, whatever
     * the value" — which is the common case for a header you only care exists.
     *
     * The key/value separator is whichever of `:` / `=` comes first, so a value may contain the
     * other one (`token=a=b`). A value containing `;` has to go in a box of its own — for the
     * one case where that matters, a response's content type, there is a dedicated field.
     */
    fun parsePairs(text: String): Map<String, String> =
        text.splitToSequence('\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                val equals = line.indexOf('=')
                val sep = when {
                    colon < 0 -> equals
                    equals < 0 -> colon
                    else -> minOf(colon, equals)
                }
                val key = (if (sep < 0) line else line.substring(0, sep)).trim()
                if (key.isEmpty()) return@mapNotNull null
                val value = if (sep < 0) "" else line.substring(sep + 1).trim()
                key to value.ifEmpty { MockRule.MATCH_ANY }
            }
            .toMap()

    /** The inverse of [parsePairs], for seeding the editor from an existing rule. */
    fun formatPairs(pairs: Map<String, String>): String =
        pairs.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /**
     * The steps to show when the sequential editor is opened. An existing sequence is kept; a
     * rule that has only a single response contributes it as step 1, so turning a captured mock
     * into "fail once, then succeed" is one click and one edit rather than retyping the body.
     */
    fun draftsOf(rule: MockRule): List<StepDraft> =
        if (rule.responses.isNotEmpty()) rule.responses.map { draftOf(it) }
        else listOf(
            StepDraft(
                status = rule.status.toString(),
                body = rule.body.orEmpty(),
                headers = formatPairs(rule.headers),
                contentType = rule.contentType,
                latency = rule.latencyMillis.toString(),
                behavior = rule.behavior,
            )
        )

    fun draftOf(step: MockStep) = StepDraft(
        status = step.status.toString(),
        body = step.body.orEmpty(),
        headers = formatPairs(step.headers),
        contentType = step.contentType,
        latency = step.latencyMillis.toString(),
        behavior = step.behavior,
    )

    fun toStep(draft: StepDraft) = MockStep(
        status = draft.status.trim().toIntOrNull() ?: 200,
        body = draft.body.ifBlank { null },
        headers = parsePairs(draft.headers),
        contentType = draft.contentType.trim().ifBlank { "application/json" },
        latencyMillis = draft.latency.trim().toLongOrNull() ?: 0,
        behavior = draft.behavior,
    )

    fun toSteps(drafts: List<StepDraft>): List<MockStep> = drafts.map { toStep(it) }

    /** The first status a rule will actually serve — step 1's when it has steps. */
    fun effectiveStatus(rule: MockRule): Int = rule.responses.firstOrNull()?.status ?: rule.status

    /** The first behavior a rule will actually apply — step 1's when it has steps. */
    fun effectiveBehavior(rule: MockRule): String =
        rule.responses.firstOrNull()?.behavior ?: rule.behavior

    /** `×3 steps` for the rule row, or null for a plain single-response rule. */
    fun stepsLabel(rule: MockRule): String? =
        rule.responses.size.takeIf { it > 0 }?.let { "×$it steps" }

    /**
     * Human phrasing of everything narrowing this rule beyond method + path, for the row tooltip.
     * Null when the rule matches on path alone — the row already says that.
     */
    fun matchSummary(rule: MockRule): String? {
        val parts = buildList {
            if (rule.matchQuery.isNotEmpty()) {
                add("query " + rule.matchQuery.entries.joinToString(", ") {
                    if (it.value == MockRule.MATCH_ANY) "?${it.key} present" else "?${it.key}=${it.value}"
                })
            }
            if (rule.matchHeaders.isNotEmpty()) {
                add("header " + rule.matchHeaders.entries.joinToString(", ") {
                    if (it.value == MockRule.MATCH_ANY) "${it.key} present" else "${it.key}: ${it.value}"
                })
            }
            rule.matchBodyContains?.takeIf { it.isNotBlank() }?.let {
                add("body contains \"${it.take(60)}\"")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "only when ")
    }

    /** How a sequence will play out, for the row tooltip. */
    fun stepsSummary(rule: MockRule): String? {
        if (rule.responses.isEmpty()) return null
        val played = rule.responses.joinToString(" → ") { step ->
            when (step.behavior) {
                MockRule.BEHAVIOR_TIMEOUT -> "timeout"
                MockRule.BEHAVIOR_CONNECTION_FAILURE -> "connection failure"
                else -> step.status.toString()
            }
        }
        return "responds $played, then repeats the last"
    }
}
