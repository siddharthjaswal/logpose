package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** What a row-menu item does. The screen runs it; building the menu decides nothing else. */
sealed interface RowCommand {
    data class Copy(val text: String, val toast: String) : RowCommand
    data class ToggleMute(val tx: Transaction) : RowCommand
    data object ClearMutes : RowCommand
    data class ToggleExpand(val groupKey: String) : RowCommand
}

data class MenuItem(val label: String, val command: RowCommand)

/**
 * The row context menu, item for item the plugin's (`LogPosePanel.ListMouse.maybePopup`) minus
 * what arrives with M4b: no correlation-key or trace waterfall/filter items, no `Mock this
 * endpoint…`, no push re-send. Order and labels match, so a tester moving between the IDE and the
 * app finds the same menu.
 */
object RowMenu {

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * @param shown the event the detail card is showing for [row] — its lead, unless the user has
     *   stepped a collapsed run back to an earlier occurrence, so `Copy as cURL` copies the call on
     *   screen rather than a different one from the same run.
     * @param collapse the run this row is (or was expanded from), with whether it is expanded now.
     */
    fun items(
        row: RowCollapse.Row,
        shown: LogEvent,
        collapse: Pair<RowCollapse.Row.Group, Boolean>?,
    ): List<MenuItem> = buildList {
        collapse?.let { (group, expanded) ->
            val label = when (group.groupKind) {
                RowCollapse.GroupKind.NET_POLL ->
                    if (expanded) "Collapse ${group.facts.count} repeated calls" else "Expand ${group.facts.count} occurrences"
                RowCollapse.GroupKind.DB_TXN -> if (expanded) "Collapse transaction" else "Expand transaction"
            }
            add(MenuItem(label, RowCommand.ToggleExpand(group.key)))
        }
        when (shown) {
            is LogEvent.Http -> addAll(http(shown.tx))
            is LogEvent.Fcm -> addAll(fcm(shown.msg))
            else -> addAll(structured(shown))
        }
    }

    /** ⌘C / the menu on a multi-selection: one line per event, a collapsed row expanded into all of its. */
    fun timelineItem(lines: List<String>): MenuItem =
        MenuItem("Copy timeline (${lines.size} rows)", RowCommand.Copy(lines.joinToString("\n"), copiedToast(lines.size)))

    fun copiedToast(n: Int) = if (n == 1) "Copied 1 row" else "Copied $n rows"

    private fun http(tx: Transaction): List<MenuItem> = buildList {
        add(MenuItem("Copy as cURL", RowCommand.Copy(CurlBuilder.build(tx), "cURL copied")))
        add(MenuItem("Copy as JSON", RowCommand.Copy(pretty.encodeToString(Transaction.serializer(), tx), "Transaction JSON copied")))
        add(MenuItem("Copy URL", RowCommand.Copy(tx.request.url, "URL copied")))
        tx.response?.body?.text?.let { add(MenuItem("Copy response body", RowCommand.Copy(it, "Response body copied"))) }
        val key = MutedEndpoints.keyOf(tx)
        add(MenuItem(if (MutedEndpoints.isMuted(tx)) "Unmute  $key" else "Mute  $key", RowCommand.ToggleMute(tx)))
        if (MutedEndpoints.patterns().isNotEmpty()) add(MenuItem("Clear all mutes", RowCommand.ClearMutes))
    }

    private fun fcm(msg: FcmMessage): List<MenuItem> = buildList {
        add(MenuItem("Copy as JSON", RowCommand.Copy(pretty.encodeToString(FcmMessage.serializer(), msg), "FCM JSON copied")))
        if (msg.data.isNotEmpty()) {
            add(
                MenuItem(
                    "Copy data payload",
                    RowCommand.Copy(pretty.encodeToString(MapSerializer(String.serializer(), String.serializer()), msg.data), "Data payload copied"),
                ),
            )
        }
    }

    private fun structured(ev: LogEvent): List<MenuItem> = listOf(
        MenuItem("Copy as JSON", RowCommand.Copy(pretty.encodeToString(Envelope.serializer(), ev.envelope), "Event JSON copied")),
        MenuItem("Copy payload", RowCommand.Copy(pretty.encodeToString(JsonElement.serializer(), ev.envelope.payload), "Payload copied")),
    )

    /** The lines a copied timeline carries for [rows], in list order, folds expanded into members. */
    fun timelineLines(rows: List<RowCollapse.Row>, events: Map<String, LogEvent>): List<String> =
        rows.flatMap { row -> row.memberIds.mapNotNull { events[it] } }.map(DetailContent::timelineLine)
}

/**
 * Multi-row selection over the folded list: ⌘-click toggles a row, shift-click extends from the
 * last clicked row. Selection is stored as **event ids**, so a collapsed row contributes all of its
 * members — Share's "only selected rows" and Copy timeline then carry the truth, not the summary.
 */
object Selection {

    fun toggle(selected: Set<String>, row: RowCollapse.Row): Set<String> =
        if (row.memberIds.any { it in selected }) selected - row.memberIds.toSet() else selected + row.memberIds

    /** Every row between [fromAnchor]'s row and [to], inclusive, in list order. */
    fun range(rows: List<RowCollapse.Row>, fromAnchor: String?, to: RowCollapse.Row): Set<String> {
        val end = rows.indexOfFirst { it.key == to.key }
        if (end < 0) return to.memberIds.toSet()
        val start = fromAnchor?.let { a -> rows.indexOfFirst { it.anchorId == a || a in it.memberIds } }?.takeIf { it >= 0 } ?: end
        return rows.subList(minOf(start, end), maxOf(start, end) + 1).flatMapTo(LinkedHashSet()) { it.memberIds }
    }

    fun isSelected(row: RowCollapse.Row, selectedId: String?, selectedIds: Set<String>): Boolean =
        row.anchorId == selectedId || (selectedIds.isNotEmpty() && row.memberIds.any { it in selectedIds })
}
