package io.github.siddharthjaswal.logpose.analysis

/**
 * One way to group the timeline: by a configured key's value, by a pasted value, or by a trace.
 *
 * The waterfall, the row menu and the filter chip all used to take a bare `traceId: String`,
 * which is exactly the label the PRD calls meaningless — `Show waterfall d107086f` tells a human
 * nothing. A grouping carries *what it is grouping by*, so every entry point can read
 * `order_id 21053953` and every one of them means the same thing by it.
 *
 * [key] is a label, never a matcher: [Correlation.group] matches on the value alone, which is the
 * whole reason a group can span events that never mention the key (a bare `/order/21053953/` path
 * segment) or carry no trace at all.
 */
data class Grouping(val kind: Kind, val key: String?, val value: String) {

    enum class Kind {
        /** A configured [CorrelationKey] held this value on the originating row. */
        KEY,

        /** A value the user pasted that no configured key claims — matched by value alone. */
        VALUE,

        /** The device's own trace id: the fallback, and the only one that predates this feature. */
        TRACE,
    }

    /** How this grouping reads everywhere it appears: `order_id 21053953`, `trace d107086f`. */
    val label: String
        get() = when (kind) {
            Kind.KEY -> "${key.orEmpty()}  $value"
            Kind.VALUE -> "value  $value"
            Kind.TRACE -> "trace  $value"
        }

    /** The same thing on one line, for a menu item or a chip. */
    val shortLabel: String
        get() = when (kind) {
            Kind.KEY -> "${key.orEmpty()} $value"
            Kind.VALUE -> "value $value"
            Kind.TRACE -> "trace $value"
        }

    /** Just the name of the grouping, for a segmented switcher's tab. */
    val tab: String
        get() = when (kind) {
            Kind.KEY -> key.orEmpty()
            Kind.VALUE -> "value"
            Kind.TRACE -> "trace"
        }

    val isTrace: Boolean get() = kind == Kind.TRACE
}

/**
 * Which groupings a row offers, and which one a single click should open.
 *
 * The precedence is the PRD's, stated once here rather than re-derived at each entry point:
 * **configured keys first, in the order the user configured them; the trace last, as a
 * fallback.** A business key correlates what tracing structurally cannot — it spans traces, and
 * it works on rows that have no trace — so a row carrying both should not open the weaker one.
 */
object Groupings {

    /**
     * Every grouping [values] and [traceId] offer, best first.
     *
     * [values] is [Correlation.valuesFor]'s output — matchable values only, in configured key
     * order. A blank trace is no trace: an entry point that opens an empty view is worse than no
     * entry point (the same rule the trace actions have always followed).
     */
    fun forEvent(values: Map<String, String>, traceId: String?): List<Grouping> {
        val out = ArrayList<Grouping>(values.size + 1)
        for ((key, value) in values) {
            if (value.isBlank()) continue
            out.add(Grouping(Grouping.Kind.KEY, key, value))
        }
        traceId?.takeIf { it.isNotBlank() }?.let { out.add(Grouping(Grouping.Kind.TRACE, null, it)) }
        return out
    }

    /** The one grouping a single click opens: the first configured key, else the trace. */
    fun best(values: Map<String, String>, traceId: String?): Grouping? =
        forEvent(values, traceId).firstOrNull()

    /**
     * A pasted value as a grouping: labelled with [key] when one is known (either typed as
     * `key=value` or found by [Correlation.keyLabelFor]), and as a bare value otherwise.
     */
    fun forValue(value: String, key: String?): Grouping =
        if (key.isNullOrBlank()) Grouping(Grouping.Kind.VALUE, null, value)
        else Grouping(Grouping.Kind.KEY, key, value)
}
