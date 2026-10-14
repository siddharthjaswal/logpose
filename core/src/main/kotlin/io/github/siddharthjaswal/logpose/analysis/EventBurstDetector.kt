package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [DuplicateDetector]'s sibling for everything that isn't HTTP: the same analytics event (or
 * self-describing app event) fired repeatedly in a burst — a fragment that logs `BTH_HIDDEN` once
 * per `onResume` *and* once per layout pass, a listener registered twice, an observer that
 * re-delivers. Each repeat inflates a funnel metric somebody is reading, and nothing on the device
 * says so.
 *
 * Two events are *the same* when they share a [signature]: kind + name (title) + screen
 * (subtitle) + every parameter, values included, minus the [Config.volatileParams] a client stamps
 * on each event. Parameters are the false-positive guard: `item_viewed {id: 1}` and
 * `item_viewed {id: 2}` fired 10ms apart are a list rendering, not a bug, and never match. The
 * app run is in the signature too, so a burst can't straddle a restart.
 *
 * A chain of same-signature events, each within [Config.chainMillis] of the previous, is flagged
 * when either:
 *  - [Pattern.BURST] — at least [Config.burstMin] of them fall inside [Config.burstWindowMillis]; or
 *  - [Pattern.DOUBLE_FIRE] — two consecutive ones are within [Config.doubleFireMillis], which no
 *    human interaction produces.
 *
 * Like [DuplicateDetector], only the 2nd+ member is marked; the first is the legitimate original.
 * Pure, and run over the **whole** capture so a filter can never break a chain or shift an ordinal.
 *
 * Db and worker rows are deliberately out of scope: repeated queries are `query_hotspots`' N+1
 * signal (with its own grouping by statement shape), and a worker's states already update one row.
 */
object EventBurstDetector {

    data class Config(
        val burstWindowMillis: Long = 1_000,
        val burstMin: Int = 3,
        val doubleFireMillis: Long = 300,
        /** Max gap between two members of one chain. */
        val chainMillis: Long = 1_000,
        /** Parameter keys a client stamps per event — never part of what makes two events the same. */
        val volatileParams: Set<String> = setOf(
            "_", "t", "ts", "_t", "time", "timestamp", "event_time", "event_timestamp", "client_ts",
            "nonce", "event_id", "uuid", "seq", "sequence",
        ),
    )

    enum class Pattern { DOUBLE_FIRE, BURST }

    /**
     * @param ordinal 1-based position in its burst — 2 = first repeat (shown as `×N`, like HTTP).
     * @param count   events in the whole burst, the original included.
     */
    data class Mark(val ordinal: Int, val count: Int, val originalId: String, val pattern: Pattern)

    /** One flagged burst, for summaries: the original plus every repeat, in time order. */
    data class Group(
        val kind: String,
        val name: String,
        val screen: String?,
        val pattern: Pattern,
        val eventIds: List<String>,
        val firstAt: Long,
        val lastAt: Long,
    ) {
        val count: Int get() = eventIds.size
        val spanMillis: Long get() = lastAt - firstAt
    }

    data class Result(val marks: Map<String, Mark>, val groups: List<Group>) {
        companion object { val EMPTY = Result(emptyMap(), emptyList()) }
    }

    fun analyze(
        events: List<LogEvent>,
        sessionOf: (String) -> Int = { 0 },
        config: Config = Config(),
    ): Result {
        val bySignature = LinkedHashMap<String, MutableList<LogEvent.Generic>>()
        for (event in events) {
            if (event !is LogEvent.Generic || event.event == null || event.timestampMillis <= 0) continue
            bySignature.getOrPut(signature(event, sessionOf(event.id), config)) { mutableListOf() } += event
        }
        if (bySignature.values.none { it.size > 1 }) return Result.EMPTY

        val marks = HashMap<String, Mark>()
        val groups = ArrayList<Group>()
        for (same in bySignature.values) {
            if (same.size < 2) continue
            val ordered = same.sortedBy { it.timestampMillis }
            var chain = arrayListOf(ordered[0])
            for (i in 1..ordered.size) {
                val cur = ordered.getOrNull(i)
                if (cur != null && cur.timestampMillis - chain.last().timestampMillis <= config.chainMillis) {
                    chain += cur
                    continue
                }
                flag(chain, config)?.let { pattern ->
                    val original = chain.first()
                    chain.forEachIndexed { idx, e ->
                        if (idx > 0) marks[e.id] = Mark(idx + 1, chain.size, original.id, pattern)
                    }
                    groups += Group(
                        kind = original.kind,
                        name = original.event?.title.orEmpty(),
                        screen = original.event?.subtitle?.takeIf { it.isNotBlank() },
                        pattern = pattern,
                        eventIds = chain.map { it.id },
                        firstAt = original.timestampMillis,
                        lastAt = chain.last().timestampMillis,
                    )
                }
                if (cur != null) chain = arrayListOf(cur)
            }
        }
        groups.sortBy { it.firstAt }
        return Result(marks, groups)
    }

    private fun flag(chain: List<LogEvent>, config: Config): Pattern? {
        if (chain.size < 2) return null
        val n = config.burstMin
        if (n >= 2 && chain.size >= n) {
            for (i in 0..chain.size - n) {
                if (chain[i + n - 1].timestampMillis - chain[i].timestampMillis <= config.burstWindowMillis) return Pattern.BURST
            }
        }
        for (i in 1 until chain.size) {
            if (chain[i].timestampMillis - chain[i - 1].timestampMillis <= config.doubleFireMillis) return Pattern.DOUBLE_FIRE
        }
        return null
    }

    /** kind | run | name | screen | every non-volatile parameter, order-independent. */
    fun signature(event: LogEvent.Generic, session: Int, config: Config = Config()): String {
        val g = event.event ?: return event.id
        return buildString {
            append(event.kind).append('|').append(session).append('|').append(g.title)
            append('|').append(g.subtitle.orEmpty())
            for (section in g.sections) {
                append('|').append(section.label).append(':').append(section.type).append('=')
                val body = section.body
                if (section.type == Section.TYPE_KV && body is JsonObject) {
                    body.entries
                        .filter { it.key.lowercase() !in config.volatileParams }
                        .sortedBy { it.key }
                        .joinTo(this, ",") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content ?: v.toString()}" }
                } else {
                    append(body.toString())
                }
            }
        }
    }
}
