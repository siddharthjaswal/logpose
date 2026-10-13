package io.github.siddharthjaswal.logpose.desktop.detail

import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineView
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * What the detail pane is showing: one event, or one occurrence of a collapsed polling run with the
 * `occurrence ‹ n / N ›` stepper. Resolved from the folded list, the way the plugin panel's
 * `showDetailFor` does it.
 */
data class DetailTarget(
    val event: LogEvent,
    /** Non-null only for a NET_POLL run of more than one call. */
    val occurrences: List<LogEvent.Http>? = null,
    val index: Int = 0,
    val groupKey: String? = null,
) {
    val stepper: Boolean get() = (occurrences?.size ?: 0) > 1
}

object DetailModel {

    /**
     * The target for the selected row. A NET_POLL run opens on its **latest** call (the row shows
     * the latest timestamp, so the card agrees), unless the user stepped to another occurrence of
     * this same run ([pinnedId]); a pin the run no longer contains falls back to the latest. A
     * folded transaction is ceremony around statements still listed individually, so it gets no
     * stepper — its lead is shown, as in the plugin.
     */
    fun resolve(view: TimelineView, selectedId: String?, pinnedId: String?, fallback: (String) -> LogEvent?): DetailTarget? {
        selectedId ?: return null
        val cells = view.rowFor(selectedId) ?: return fallback(selectedId)?.let { DetailTarget(it) }
        val group = (cells.row as? RowCollapse.Row.Group)?.takeIf { it.groupKind == RowCollapse.GroupKind.NET_POLL }
            ?: return DetailTarget(cells.row.lead)
        val occurrences = TimelineViews.occurrencesOf(view, group).takeIf { it.size > 1 } ?: return DetailTarget(group.lead)
        val index = pinnedId?.let { id -> occurrences.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: occurrences.lastIndex
        return DetailTarget(occurrences[index], occurrences, index, group.key)
    }
}
