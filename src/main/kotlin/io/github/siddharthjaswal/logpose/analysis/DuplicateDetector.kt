package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction

/**
 * Flags repeated identical requests fired in a short burst — the tell-tale sign of a
 * double-tapped button, a missing debounce, or a retry-loop hammering the backend.
 *
 * A request is a *duplicate* of an earlier one when they share a [signature]
 * (method + normalized URL + body fingerprint) AND the later one started within
 * [Config.windowMillis] of its predecessor. Only the 2nd+ call in a burst is marked;
 * the first is the legitimate original.
 *
 * Severity separates "wasteful" from "dangerous":
 *  - [Severity.STRONG]  non-idempotent (POST/PUT/PATCH/DELETE) AND overlapping in-flight
 *                       (the 2nd fired before the 1st responded) → classic double-submit.
 *  - [Severity.MEDIUM]  non-idempotent, both completed but within the window → redundant write.
 *  - [Severity.INFO]    idempotent (GET/HEAD/…) repeat → usually just a redundant fetch.
 *
 * False-positive guards: query params known to be cache-busters/nonces are stripped before
 * matching; an `Idempotency-Key` header is folded into the signature so deliberately-distinct
 * keys never collide; and a genuine retry (which follows a *failed* attempt rather than
 * overlapping a live one) lands at MEDIUM/INFO rather than STRONG.
 */
object DuplicateDetector {

    data class Config(
        /** Max gap between two identical requests for the later to count as a duplicate. */
        val windowMillis: Long = 1_500,
        /** Include the request body in the signature for non-idempotent methods. */
        val matchBody: Boolean = true,
        /** Query-param keys stripped before matching (cache-busters, nonces, timestamps). */
        val volatileParams: Set<String> = setOf(
            "_", "t", "ts", "_t", "time", "timestamp", "nonce", "cb", "cachebuster", "rand", "_r", "v",
        ),
    )

    enum class Severity { INFO, MEDIUM, STRONG }

    /**
     * @param ordinal 1-based position of this call within its burst — 2 = first duplicate,
     *                3 = second duplicate, … (shown to the user as "×N").
     * @param originalId id of the first request in the burst, for "jump to original".
     */
    data class Mark(
        val ordinal: Int,
        val severity: Severity,
        val originalId: String,
    )

    private val IDEMPOTENT = setOf("GET", "HEAD", "OPTIONS", "TRACE")

    /**
     * Analyses an ordered list of transactions and returns marks keyed by transaction id.
     * Only duplicate (2nd+) calls appear in the map; everything else is absent.
     */
    fun analyze(items: List<Transaction>, config: Config = Config()): Map<String, Mark> {
        if (items.size < 2) return emptyMap()

        val groups = LinkedHashMap<String, MutableList<Transaction>>()
        for (tx in items) groups.getOrPut(signature(tx, config)) { mutableListOf() }.add(tx)

        val marks = HashMap<String, Mark>()
        for (group in groups.values) {
            if (group.size < 2) continue
            val ordered = group.sortedBy { it.startedAtMillis }
            var chainStart = 0
            var ordinal = 1
            for (i in 1 until ordered.size) {
                val prev = ordered[i - 1]
                val cur = ordered[i]
                val timed = prev.startedAtMillis > 0 && cur.startedAtMillis > 0
                val gap = cur.startedAtMillis - prev.startedAtMillis
                if (timed && gap in 0..config.windowMillis) {
                    ordinal += 1
                    marks[cur.id] = Mark(ordinal, severityOf(prev, cur), ordered[chainStart].id)
                } else {
                    chainStart = i
                    ordinal = 1
                }
            }
        }
        return marks
    }

    private fun severityOf(prev: Transaction, cur: Transaction): Severity {
        if (cur.request.method.uppercase() in IDEMPOTENT) return Severity.INFO
        // Overlap = the 2nd request started before the 1st finished. If prev never completed
        // (still in flight / no response), it's overlapping by definition. A genuine retry,
        // by contrast, starts only after prev has ended → not an overlap → MEDIUM.
        val prevDuration = prev.durationMillis
        val overlap = prevDuration == null || cur.startedAtMillis < prev.startedAtMillis + prevDuration
        return if (overlap) Severity.STRONG else Severity.MEDIUM
    }

    private fun signature(tx: Transaction, config: Config): String {
        val method = tx.request.method.uppercase()
        val sb = StringBuilder(method).append(' ').append(normalizeUrl(tx.request.url, config))
        idempotencyKey(tx)?.let { sb.append(" idem=").append(it) }
        if (config.matchBody && method !in IDEMPOTENT) {
            bodyFingerprint(tx.request.body)?.let { sb.append(" body=").append(it) }
        }
        return sb.toString()
    }

    /** Drops volatile query params and sorts the rest so param order doesn't defeat matching. */
    private fun normalizeUrl(url: String, config: Config): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val kept = url.substring(q + 1)
            .split('&')
            .filter { it.isNotEmpty() }
            .filter { it.substringBefore('=').lowercase() !in config.volatileParams }
            .sorted()
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    private fun idempotencyKey(tx: Transaction): String? =
        tx.request.headers.entries.firstOrNull {
            it.key.equals("Idempotency-Key", true) || it.key.equals("X-Idempotency-Key", true)
        }?.value

    private fun bodyFingerprint(body: Body?): String? {
        body ?: return null
        body.text?.let { return it.hashCode().toString() }
        body.parts?.let { parts ->
            return parts.joinToString(",") { "${it.name}:${it.filename}:${it.sizeBytes}" }.hashCode().toString()
        }
        return body.sizeBytes.takeIf { it > 0 }?.toString()
    }
}
