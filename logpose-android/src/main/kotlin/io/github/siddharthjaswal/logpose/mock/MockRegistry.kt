package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.wire.MockRule
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
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
     * First enabled, non-exhausted rule matching [method] + [path], in rule-set order (the
     * IDE sends newest-first). Does NOT count a hit — call [recordServe] once actually served.
     */
    fun match(method: String, path: String): MockRule? = rules.firstOrNull { rule ->
        rule.enabled &&
            !isExhausted(rule) &&
            (rule.method == "*" || rule.method.equals(method, ignoreCase = true)) &&
            pathMatches(rule.pathPattern, path)
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

    /** Test hook: clear all state. */
    @Synchronized
    internal fun reset() {
        rules = emptyList()
        revision = 0
        hitCounts.clear()
    }
}
