package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.waterfall.Flows
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation
import io.github.siddharthjaswal.logpose.presentation.FilterState

/**
 * One snapshot of the timeline as the screen draws it: the filtered, folded rows and everything the
 * filter bar says about them. Built off the UI thread (see `AppModel`), once per published store
 * snapshot or filter/expansion/mute change — never during composition of a row.
 *
 * Nothing here decides a rule. The order of operations is the plugin panel's `refreshList`, step
 * for step: duplicate marks over the **whole** capture, then [FilterPresentation.passes] (core's
 * [FilterState] plus duplicates), then — only when that empties a non-empty capture — core's
 * measured [FilterPresentation.relaxations] and [FilterPresentation.emptyState], and finally core's
 * [RowCollapse] over the filtered list.
 */
data class TimelineView(
    val version: Long,
    val rows: List<RowCells>,
    /** The filtered events the rows were folded from (what occurrences and copies resolve against). */
    val filtered: List<LogEvent>,
    val total: Int,
    val duplicateMarks: Map<String, DuplicateDetector.Mark>,
    /** The echo row's "db hidden by default · show" input. */
    val hiddenDbMatch: Boolean,
    /** Set exactly when the capture is non-empty and the filter matched nothing. */
    val empty: FilterPresentation.EmptyState?,
    /** Every event id in the list → the row standing for it (folded members included). */
    val memberIndex: Map<String, RowCollapse.Row>,
    /** Anchor ids of rows that paint the `⇉ flow` button when armed (M4b) — decided off the UI thread. */
    val flowable: Set<String> = emptySet(),
    /** The correlation chip this view was filtered by, if any. */
    val correlation: Grouping? = null,
) {
    val shown: Int get() = filtered.size

    /** The counter at the end of the permanent row: `shown/total`. */
    val countLabel: String get() = "$shown/$total"

    /** The row that holds [id] — its anchor first, then any row that folded it in. */
    fun rowFor(id: String): RowCells? =
        rows.firstOrNull { it.row.anchorId == id } ?: rows.firstOrNull { id in it.row.memberIds }

    companion object {
        val EMPTY = TimelineView(0, emptyList(), emptyList(), 0, emptyMap(), false, null, emptyMap())
    }
}

object TimelineViews {

    fun build(
        events: List<LogEvent>,
        state: FilterState,
        expanded: Set<String> = emptySet(),
        sessionOf: (String) -> Int = { 0 },
        version: Long = 0,
        /** The correlation chip (M4b): a key or pasted-value grouping the list is narrowed to. */
        correlation: Grouping? = null,
        /** Whether [LogEvent] carries the chip's value — the host's cached-haystack match. */
        inGroup: (LogEvent, Grouping) -> Boolean = { _, _ -> false },
        /** Whether a row can offer a key grouping — a cache read (`CorrelationIndex.hasCachedKeyValue`). */
        hasKey: (LogEvent) -> Boolean = { false },
    ): TimelineView {
        // Over the FULL capture: filtering must not break a burst chain or change a call's ordinal.
        val marks = DuplicateDetector.analyze(events.filterIsInstance<LogEvent.Http>().map { it.tx })
        val chip = correlation?.let { g -> { e: LogEvent -> inGroup(e, g) } }
        val passesWith = { e: LogEvent, s: FilterState, withChip: Boolean ->
            FilterPresentation.passes(e, s, { it in marks }, if (withChip) chip else null)
        }
        val passes = { e: LogEvent, s: FilterState -> passesWith(e, s, true) }
        val filtered = events.filter { passes(it, state) }
        val hiddenDb = FilterPresentation.wouldMatchHiddenDb(state, events)
        val empty = if (events.isEmpty() || filtered.isNotEmpty()) null else FilterPresentation.emptyState(
            total = events.size,
            kinds = events.groupingBy { it.kind }.eachCount(),
            state = state,
            correlationLabel = correlation?.shortLabel,
            wouldMatchHiddenDb = hiddenDb,
            relaxations = FilterPresentation.relaxations(events, state, correlation?.shortLabel) { e, s, withChip -> passesWith(e, s, withChip) },
        )
        // Collapsing LAST, over the filtered list — the store keeps every event.
        val rows = RowCollapse.collapse(filtered, expanded, marks, sessionOf)
        return TimelineView(
            version = version,
            rows = rows.map { Rows.of(it, marks) },
            filtered = filtered,
            total = events.size,
            duplicateMarks = marks,
            hiddenDbMatch = hiddenDb,
            empty = empty,
            memberIndex = RowCollapse.memberIndex(rows),
            flowable = rows.filter { Flows.paintsFlow(it, MutedEndpoints::isMuted, hasKey) }.mapTo(HashSet()) { it.anchorId },
            correlation = correlation,
        )
    }

    /**
     * Expansion keys whose group still exists — pruned by whether the group's **anchor** is still in
     * the list, not by whether a Group row was emitted (an expanded group emits only its members, so
     * pruning on emitted rows would re-collapse it on the very next snapshot). The plugin's rule.
     */
    fun prune(expanded: Set<String>, view: TimelineView): Set<String> =
        expanded.filterTo(HashSet()) { key -> key.substringAfter(':') in view.memberIndex }

    /**
     * The group a row would belong to if nothing were expanded — so "Collapse …" can find its run
     * from any of the members it was expanded into. Costs one fold, and only on a right-click.
     */
    fun naturalGroupFor(
        view: TimelineView,
        row: RowCollapse.Row,
        expanded: Set<String>,
        sessionOf: (String) -> Int = { 0 },
    ): RowCollapse.Row.Group? {
        if (row is RowCollapse.Row.Group) return row
        if (expanded.isEmpty()) return null
        val natural = RowCollapse.memberIndex(RowCollapse.collapse(view.filtered, emptySet(), view.duplicateMarks, sessionOf))
        return (natural[row.lead.id] as? RowCollapse.Row.Group)?.takeIf { it.key in expanded }
    }

    /** The calls a collapsed run stands for, in arrival order, from the list it was folded from. */
    fun occurrencesOf(view: TimelineView, group: RowCollapse.Row.Group): List<LogEvent.Http> {
        val wanted = group.memberIds.toHashSet()
        val byId = HashMap<String, LogEvent.Http>(wanted.size)
        for (event in view.filtered) if (event is LogEvent.Http && event.id in wanted) byId[event.id] = event
        return group.memberIds.mapNotNull { byId[it] }
    }
}

/**
 * The filter bar's edits, as functions over [FilterState] — the widget behaviour of the plugin's
 * `FilterBar` (which clears its chips and switches in place). What each id *means* is core's.
 */
object FilterEdits {

    /** Drops one filter. The db opt-in is granted rather than dropped: showing db is selecting it. */
    fun loosen(state: FilterState, id: FilterPresentation.FilterId): FilterState =
        if (id == FilterPresentation.FilterId.DB_OPT_IN) state.copy(types = state.types + EventType.DB) else clear(state, id)

    fun clear(state: FilterState, id: FilterPresentation.FilterId): FilterState = when (id) {
        FilterPresentation.FilterId.STATUS -> state.copy(statusClasses = emptySet())
        FilterPresentation.FilterId.METHOD -> state.copy(methods = emptySet())
        // The correlation chip lives beside the FilterState (it is a Grouping, not a field): the
        // host clears it alongside — see AppModel.setCorrelationFilter.
        FilterPresentation.FilterId.CORRELATION -> state
        FilterPresentation.FilterId.HIDE_NOISE -> state.copy(hideNoise = false)
        FilterPresentation.FilterId.DUPES -> state.copy(duplicatesOnly = false)
        FilterPresentation.FilterId.TYPES -> state.copy(types = emptySet())
        FilterPresentation.FilterId.SEARCH -> state.copy(urlQuery = "")
        // db hidden *is* the cleared state, so a bulk clear leaves it alone.
        FilterPresentation.FilterId.DB_OPT_IN -> state
    }

    /** The echo row's `Clear all`: every hidden filter, but not the visible search box or TYPE chips. */
    fun clearHidden(state: FilterState): FilterState =
        FilterPresentation.FilterId.entries
            .filter { it != FilterPresentation.FilterId.SEARCH && it != FilterPresentation.FilterId.TYPES }
            .fold(state) { s, id -> clear(s, id) }

    /** The filtered-to-nothing state's `Clear filters`: everything, visible ones included. */
    fun clearAll(state: FilterState): FilterState = FilterPresentation.FilterId.entries.fold(state) { s, id -> clear(s, id) }

    /** The explainer link's mode switch. */
    fun switchMode(state: FilterState, action: FilterPresentation.Explainer.Action): FilterState = when (action) {
        FilterPresentation.Explainer.Action.SHOW_ALL_KINDS -> state.copy(statusClasses = emptySet(), methods = emptySet())
        FilterPresentation.Explainer.Action.SHOW_DB -> state.copy(types = state.types + EventType.DB)
    }

    fun toggleType(state: FilterState, type: EventType) =
        state.copy(types = if (type in state.types) state.types - type else state.types + type)

    fun toggleMethod(state: FilterState, method: String) =
        state.copy(methods = if (method in state.methods) state.methods - method else state.methods + method)

    fun toggleStatus(state: FilterState, cls: Int) =
        state.copy(statusClasses = if (cls in state.statusClasses) state.statusClasses - cls else state.statusClasses + cls)
}
