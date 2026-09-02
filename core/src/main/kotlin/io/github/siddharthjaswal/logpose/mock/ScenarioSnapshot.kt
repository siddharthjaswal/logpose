package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Transaction

/**
 * Bottles a captured session into a set of mock rules — the "make this app work offline" move.
 *
 * The rules are as honest as the capture: bodies are copied **verbatim**, and an endpoint whose
 * response never arrived (in flight when the snapshot ran, or a response with no readable body)
 * is skipped and *counted*, never invented (PRD FR-C2). Rows that were themselves served by a
 * mock are skipped too — snapshotting them would bake the plugin's own output back into a
 * scenario and quietly launder a hand-written response into "what the backend said".
 *
 * Pure and IntelliJ-free: the grouping, normalization and latest-wins rules are the part worth
 * testing, and they don't need a project to run.
 */
object ScenarioSnapshot {

    /** What a snapshot produced, and what it refused to guess at. */
    data class Result(
        val rules: List<MockRule>,
        /** Endpoints dropped because nothing complete was captured for them. */
        val skippedIncomplete: Int = 0,
        /** Rows skipped because LogPose itself served them. */
        val skippedMocked: Int = 0,
        /** Rows skipped by the 2xx-only filter. */
        val skippedNonSuccess: Int = 0,
    ) {
        val skipped: Int get() = skippedIncomplete + skippedNonSuccess

        /** One line for the confirmation dialog / notification. */
        fun summary(): String = buildString {
            append("${rules.size} endpoint${if (rules.size == 1) "" else "s"}")
            if (skippedIncomplete > 0) append(" · skipped $skippedIncomplete in-flight/bodyless")
            if (skippedNonSuccess > 0) append(" · skipped $skippedNonSuccess non-2xx")
            if (skippedMocked > 0) append(" · skipped $skippedMocked already mocked")
        }
    }

    /**
     * Collapses dynamic path segments, so one rider's `/v3/79096/location` snapshots with a `*`
     * where the id was and then serves every id. A `*` rather than the `#` MutedEndpoints uses,
     * because the device matches [MockRule.pathPattern] as a glob — here the normalized path
     * **is** the pattern.
     */
    fun normalizePath(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        return clean.split("/").joinToString("/") { seg ->
            if (seg.isNotEmpty() && seg.all(Char::isDigit)) "*" else seg
        }
    }

    /** The identity a snapshot groups on: one rule per method + normalized path. */
    fun endpointKey(method: String, path: String): String =
        "${method.uppercase()} ${normalizePath(path)}"

    /**
     * Builds replace-mode rules from [events], newest response per endpoint winning.
     *
     * Order matters and is the store's arrival order, so "latest" means the last one captured —
     * the state the app is actually in when you snapshot it.
     *
     * @param successOnly drop non-2xx responses. Off by default: an error state is very often
     *   the thing worth bottling.
     */
    fun fromEvents(events: List<LogEvent>, successOnly: Boolean = false): Result {
        // Endpoint key -> the newest usable transaction for it. LinkedHashMap keeps first-seen
        // order for the resulting rule list, so a scenario reads in the order the app called.
        val chosen = LinkedHashMap<String, Transaction>()
        val incomplete = LinkedHashSet<String>()
        var mocked = 0
        var nonSuccess = 0

        for (event in events) {
            val tx = (event as? LogEvent.Http)?.tx ?: continue
            if (tx.mocked) { mocked++; continue }
            val key = endpointKey(tx.request.method, requestPath(tx))
            val code = tx.response?.code
            val body = tx.response?.body?.text
            if (code == null || body == null) { incomplete.add(key); continue }
            if (successOnly && code !in 200..299) { nonSuccess++; continue }
            chosen[key] = tx
        }

        val rules = chosen.map { (key, tx) -> ruleFor(key, tx) }
        return Result(
            rules = rules,
            // Only endpoints that never produced anything usable count as skipped — one pending
            // call to an endpoint that also answered isn't a hole in the scenario.
            skippedIncomplete = incomplete.count { it !in chosen },
            skippedMocked = mocked,
            skippedNonSuccess = nonSuccess,
        )
    }

    private fun requestPath(tx: Transaction): String =
        tx.request.path.ifBlank { tx.request.url }

    private fun ruleFor(key: String, tx: Transaction): MockRule = MockRule(
        // Derived from the endpoint, not random: re-snapshotting the same session updates the
        // same rules, and loading a scenario over a rule for the same endpoint replaces it
        // instead of stacking a second, shadowed rule behind it.
        id = ruleId(key),
        method = tx.request.method.uppercase(),
        pathPattern = normalizePath(requestPath(tx)),
        status = tx.response?.code ?: 200,
        body = tx.response?.body?.text,
        contentType = tx.response?.body?.contentType?.substringBefore(';')?.trim()?.ifBlank { null }
            ?: "application/json",
        mode = MockRule.MODE_REPLACE,
    )

    /** Short, stable, filename-safe id for an endpoint. */
    fun ruleId(key: String): String = "s" + Integer.toHexString(key.hashCode()).padStart(8, '0')
}
