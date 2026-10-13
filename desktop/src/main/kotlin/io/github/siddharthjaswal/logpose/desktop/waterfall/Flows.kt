package io.github.siddharthjaswal.logpose.desktop.waterfall

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.analysis.Groupings
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.presentation.RowContent

/** Which grouping a waterfall shows, and the tabs its header switcher offers. */
data class WaterfallRequest(val grouping: Grouping, val alternatives: List<Grouping> = emptyList())

/**
 * One waterfall on screen: the request plus its members — an immutable snapshot taken off the UI
 * thread, which is all the canvas ever reads (the plugin's rule: painting never touches the store).
 */
data class WaterfallView(val request: WaterfallRequest, val events: List<LogEvent>) {
    val grouping: Grouping get() = request.grouping
    val alternatives: List<Grouping> get() = request.alternatives
}

/**
 * Every entry into the waterfall, and what each one opens — the plugin panel's `showWaterfall`,
 * `openBestGrouping`, `traceGrouping`, `membersOf` and the renderer's `paintsFlow`, without Swing.
 *
 * The precedence is core's ([Groupings]): **configured keys first, in the order the user configured
 * them; the trace last.** Which tabs come along depends on what the entry point knows: a row knows
 * every grouping it belongs to, so the switcher offers them; a detail card's trace chip and a pasted
 * value know only themselves, so they open with no switcher rather than the last row's.
 */
object Flows {

    fun traceGrouping(traceId: String) = Grouping(Grouping.Kind.TRACE, null, traceId)

    /** Every grouping a row offers, best first: configured keys in order, then its trace. */
    fun groupingsFor(event: LogEvent, matchable: Map<String, String>): List<Grouping> =
        Groupings.forEvent(matchable, event.traceId)

    /** The row's `⇉ flow` button: its best grouping, with all of its groupings as tabs. */
    fun fromRowButton(event: LogEvent, matchable: Map<String, String>): WaterfallRequest? {
        val all = groupingsFor(event, matchable)
        return all.firstOrNull()?.let { WaterfallRequest(it, all) }
    }

    /** A context-menu `Show waterfall — …` item: that grouping, with the row's others as tabs. */
    fun fromMenu(grouping: Grouping, event: LogEvent, matchable: Map<String, String>): WaterfallRequest =
        WaterfallRequest(grouping, groupingsFor(event, matchable))

    /** A detail card's trace chip: the trace alone. */
    fun fromTraceChip(traceId: String): WaterfallRequest = WaterfallRequest(traceGrouping(traceId))

    /** *Find by value…*: the pasted value alone. */
    fun fromFind(grouping: Grouping): WaterfallRequest = WaterfallRequest(grouping)

    /** The header switcher: another tab of the same row — the tabs stay. */
    fun switchTo(current: WaterfallRequest, grouping: Grouping): WaterfallRequest = current.copy(grouping = grouping)

    /**
     * The events one grouping holds. A trace groups by equality; a key or a pasted value groups by
     * [Correlation.group] — matched on the **value**, delimiter-bounded, over cached haystacks —
     * with a configured key's own length floor and short-value opt-in travelling with it.
     */
    fun membersOf(
        grouping: Grouping,
        all: List<LogEvent>,
        keys: List<CorrelationKey>,
        textOf: (LogEvent) -> String,
    ): List<LogEvent> {
        if (grouping.isTrace) return all.filter { it.traceId == grouping.value }
        val configured = grouping.key?.let { name -> keys.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        return if (configured != null) Correlation.group(all, configured, grouping.value, textOf)
        else Correlation.group(all, grouping.key, grouping.value, textOf = textOf)
    }

    /**
     * Whether an armed row paints `⇉ flow` — the plugin renderer's `paintsFlow`. A pending call's
     * and a running worker's time cell is their live timer, which outranks an affordance; a muted
     * row and a collapsed row paint no buttons at all. [hasKey] must be a cache read, never a scan.
     */
    fun paintsFlow(row: RowCollapse.Row, muted: (Transaction) -> Boolean, hasKey: (LogEvent) -> Boolean): Boolean {
        if (row.isGroup) return false
        val event = row.lead
        val canGroup = hasKey(event) || !event.traceId.isNullOrBlank()
        return when (event) {
            is LogEvent.Http -> !RowContent.isPending(event.tx) && !muted(event.tx) && canGroup
            is LogEvent.Worker -> !event.work.state.equals(WorkerEvent.STATE_RUNNING, true) && canGroup
            else -> canGroup
        }
    }

    /**
     * `Show in flow` on an HTTP row's menu: only when nothing else in the menu already opens the same
     * view — a row with a configured key gets `Show waterfall — order_id …` instead.
     */
    fun offersShowInFlow(groupings: List<Grouping>): Boolean = groupings.isNotEmpty() && groupings.all { it.isTrace }
}
