package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.MockRule

/**
 * A capability the device-side library has to have for a plugin feature to work, keyed to the
 * `logpose-android` version that first shipped it. The IDE learns the device's version from the
 * `Hello` handshake, so gating is a version compare rather than a probe.
 */
enum class DeviceFeature(val since: String) {
    /** Mock rules at all (`MockCommandReceiver`). */
    MOCKS("1.1.0"),

    /** `mode = patch` — merge into the real response instead of replacing it. */
    PATCH_MOCKS("1.2.1"),

    /** `matchQuery` / `matchHeaders` / `matchBodyContains` narrowing. */
    RICH_MATCHERS("1.7.0"),

    /** `responses` — sequential per-hit steps. */
    SEQUENTIAL_RESPONSES("1.7.0"),

    /** `cmd=push` injection of a synthetic FCM message + the `push_ack` reply. */
    PUSH_INJECTION("1.7.0"),
}

/**
 * Version gating for the device library.
 *
 * The rule LogPose follows (see the flow-driver PRD, D3): when the device is too old for a
 * feature, **exclude** rather than degrade. An old library ignores fields it doesn't know, so a
 * rule that meant "mock this endpoint *only when* `?debug=1`" would silently mock every call to
 * it — a mock matching more broadly than it reads is exactly the trust failure LogPose exists to
 * prevent. An unknown version is treated as unsupported for the same reason: fail closed.
 *
 * Pure and IntelliJ-free so it can be unit-tested.
 */
object DeviceCapability {

    /** True when [libVersion] (as reported in `Hello`) is new enough for [feature]. */
    fun supports(libVersion: String?, feature: DeviceFeature): Boolean =
        atLeast(libVersion, feature.since)

    /** True when [libVersion] >= [minimum]. A null/blank/unparseable version is never enough. */
    fun atLeast(libVersion: String?, minimum: String): Boolean {
        val actual = parse(libVersion) ?: return false
        val required = parse(minimum) ?: return true
        return compare(actual, required) >= 0
    }

    /**
     * The features [rule] needs beyond the mocking baseline — empty for a rule that only uses
     * fields that predate this gating, so no rule anyone already has starts being withheld.
     * ([DeviceFeature.PATCH_MOCKS] is deliberately not checked here: patch rules shipped long
     * before version gating did, and dropping them on a device whose version we haven't learned
     * yet would break working setups to guard a case the handshake resolves a second later.)
     */
    fun featuresUsedBy(rule: MockRule): Set<DeviceFeature> = buildSet {
        if (rule.matchQuery.isNotEmpty() ||
            rule.matchHeaders.isNotEmpty() ||
            !rule.matchBodyContains.isNullOrEmpty()
        ) {
            add(DeviceFeature.RICH_MATCHERS)
        }
        if (rule.responses.isNotEmpty()) add(DeviceFeature.SEQUENTIAL_RESPONSES)
    }

    /** True when every feature [rule] uses is available on a device running [libVersion]. */
    fun canPush(rule: MockRule, libVersion: String?): Boolean =
        featuresUsedBy(rule).all { supports(libVersion, it) }

    /** The oldest library version that can serve [rule] as written, or null if any version can. */
    fun requiredVersion(rule: MockRule): String? =
        featuresUsedBy(rule).map { it.since }.maxWithOrNull { a, b ->
            compare(parse(a) ?: emptyList(), parse(b) ?: emptyList())
        }

    // "1.7.0", "1.7.0-SNAPSHOT", "v1.7" → [1, 7, 0]; anything unparseable → null.
    private fun parse(version: String?): List<Int>? {
        val raw = version?.trim()?.removePrefix("v") ?: return null
        if (raw.isEmpty()) return null
        val numbers = raw.takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        if (numbers.isEmpty()) return null
        return numbers
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
