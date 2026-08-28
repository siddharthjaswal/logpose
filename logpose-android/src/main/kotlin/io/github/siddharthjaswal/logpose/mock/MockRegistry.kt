package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.MockStep
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide holder for the active mock rules, consulted by `LogPoseInterceptor` before
 * every network call.
 *
 * Rules only ever arrive from the developer's machine via [MockCommandReceiver] (adb
 * broadcast); the IDE pushes the FULL rule set each time with a monotonically increasing
 * revision, so application is atomic and stale/duplicate deliveries are ignored. State is
 * in-memory only — a process death empties the registry, which the IDE detects via the
 * `Hello` handshake (mockRevision resets to 0) and re-pushes.
 *
 * Pure Kotlin (no Android imports) so matching/serve-limit behavior stays unit-testable.
 */
internal object MockRegistry {

    @Volatile private var rules: List<MockRule> = emptyList()

    /** Revision of the last applied rule set; 0 = nothing applied in this process. */
    @Volatile var revision: Int = 0
        private set

    /** Rules currently applied (0 after a process restart / wipe). */
    val ruleCount: Int get() = rules.size

    /**
     * Whether it's worth asking [match] anything at all. The overwhelmingly common case is a
     * session with no mocks, and describing a request (its query pairs, its headers) costs
     * allocations on every single call — this keeps that off the path until a rule exists.
     */
    val hasRules: Boolean get() = rules.isNotEmpty()

    // rule id → times served in this process. Survives re-pushes (ids are stable), so the
    // IDE's hit counts don't reset every time the user edits an unrelated rule.
    private val hitCounts = ConcurrentHashMap<String, Int>()

    /** Applies [set] if it isn't older than what we already have. Returns true if applied. */
    @Synchronized
    fun apply(set: MockRuleSet): Boolean {
        if (set.revision < revision) return false
        rules = set.rules
        revision = set.revision
        return true
    }

    /**
     * True when any active rule matches on the request body. The interceptor checks this before
     * it goes looking for body text, so a capture with no body matchers pays nothing for them.
     */
    val needsBody: Boolean
        get() = rules.any { it.enabled && it.matchBodyContains != null }

    /**
     * First enabled, non-exhausted rule matching [method] + [path] and every extra constraint it
     * declares, in rule-set order (the IDE sends newest-first). Does NOT count a hit — call
     * [recordServe] once actually served.
     *
     * [query] and [headers] come from the request; [body] is its text **only when the interceptor
     * could buffer it** (see [needsBody]). A null [body] fails a `matchBodyContains` rule closed:
     * a body LogPose can't read is a body it must not pretend to have matched.
     */
    fun match(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): MockRule? = rules.firstOrNull { rule ->
        rule.enabled &&
            !isExhausted(rule) &&
            (rule.method == "*" || rule.method.equals(method, ignoreCase = true)) &&
            pathMatches(rule.pathPattern, path) &&
            queryMatches(rule, query) &&
            headersMatch(rule, headers) &&
            bodyMatches(rule, body)
    }

    /**
     * The response [rule] serves on its next hit, or null when it has no sequence and the
     * rule-level fields apply.
     *
     * Hit *N* (0-based) serves step `min(N, lastIndex)`, so a `[500, 200]` sequence fails the
     * first call and succeeds every one after it. **Best effort under concurrency:** the
     * match → [recordServe] pair isn't atomic (it spans the network call), so two identical
     * calls racing can read the same step. Locking across a network call would be worse.
     */
    fun stepFor(rule: MockRule): MockStep? {
        if (rule.responses.isEmpty()) return null
        val hits = hitCounts[rule.id] ?: 0
        return rule.responses[minOf(hits, rule.responses.lastIndex)]
    }

    fun recordServe(rule: MockRule) {
        hitCounts.merge(rule.id, 1, Int::plus)
    }

    /** Per-rule serve counts for this process (rides back to the IDE in every ack). */
    fun hits(): Map<String, Int> = HashMap(hitCounts)

    private fun isExhausted(rule: MockRule): Boolean =
        rule.serveLimit > 0 && (hitCounts[rule.id] ?: 0) >= rule.serveLimit

    /**
     * Exact match, or glob where '*' matches any run of characters. The pattern is regex-escaped
     * around the stars so dots/braces in real paths never act as metacharacters.
     */
    private fun pathMatches(pattern: String, path: String): Boolean {
        if (!pattern.contains('*')) return pattern == path
        val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(path)
    }

    /** Every declared pair must be present; `"*"` means "present, whatever the value". */
    private fun queryMatches(rule: MockRule, query: Map<String, String>): Boolean =
        rule.matchQuery.all { (key, expected) ->
            val actual = query[key] ?: return@all false
            expected == MockRule.MATCH_ANY || expected == actual
        }

    /** Header names are case-insensitive (HTTP says so); values are exact, or `"*"` for any. */
    private fun headersMatch(rule: MockRule, headers: Map<String, String>): Boolean =
        rule.matchHeaders.all { (name, expected) ->
            val actual = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: return@all false
            expected == MockRule.MATCH_ANY || expected == actual
        }

    /** Case-insensitive substring; fails closed when the body couldn't be read (see [match]). */
    private fun bodyMatches(rule: MockRule, body: String?): Boolean {
        val needle = rule.matchBodyContains ?: return true
        return body != null && body.contains(needle, ignoreCase = true)
    }

    /** Test hook: clear all state. */
    @Synchronized
    internal fun reset() {
        rules = emptyList()
        revision = 0
        hitCounts.clear()
    }
}
