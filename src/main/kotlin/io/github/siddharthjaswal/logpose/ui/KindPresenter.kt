package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.analysis.SqlSummary
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a structured event into the presentation the row and detail pane render.
 *
 * This is the counterpart to the wire decision that db/worker/config carry **structure, not
 * presentation**: the device says what happened, and this decides how it reads. Keeping it here
 * means the wire never encodes a theme choice, and the same [GenericEvent] shape that a
 * self-describing app event supplies for itself is produced here for the kinds LogPose knows —
 * so one row renderer and one detail view serve all of them.
 */
object KindPresenter {

    /** Short label for the kind column. Never truncated, so it can't read as junk. */
    fun kindLabel(event: LogEvent): String = when (event) {
        is LogEvent.Db -> "DB"
        is LogEvent.Worker -> "WORK"
        is LogEvent.Config -> "CONF"
        is LogEvent.Generic -> when (event.kind) {
            Envelope.KIND_ANALYTICS -> "ANLY"
            Envelope.KIND_EVENT -> "EVENT"
            else -> "APP"
        }
        else -> event.kind.uppercase()
    }

    /**
     * The presentation for a structured event, or the device's own for a self-describing one.
     * Null when the kind has a bespoke view instead (HTTP, FCM).
     */
    fun present(event: LogEvent): GenericEvent? = when (event) {
        is LogEvent.Db -> db(event.query)
        is LogEvent.Worker -> worker(event.work)
        is LogEvent.Config -> config(event.update)
        is LogEvent.Generic -> event.event
        else -> null
    }

    // ---- db ---------------------------------------------------------------------------------

    private fun db(query: DbQuery): GenericEvent {
        val summary = SqlSummary.of(query.sql)
        val operation = query.operation ?: summary.operation
        val table = query.table ?: summary.table

        return GenericEvent(
            title = table ?: operation,
            subtitle = compact(query.sql),
            badges = buildList {
                add(Badge(operation.uppercase(), dbTone(operation, query.error)))
                query.rows?.let { add(Badge(if (it == 1) "1 row" else "$it rows", Badge.TONE_MUTED)) }
                query.database?.let { add(Badge(it, Badge.TONE_MUTED)) }
                query.error?.let { add(Badge("ERROR", Badge.TONE_ERROR)) }
            },
            sections = buildList {
                add(Section("SQL", Section.TYPE_CODE, JsonPrimitive(query.sql)))
                if (query.args.isNotEmpty()) {
                    add(
                        Section(
                            "Bound arguments", Section.TYPE_KV,
                            buildJsonObject {
                                // Positional, so number them — "?" alone tells you nothing.
                                query.args.forEachIndexed { i, arg -> put("${i + 1}", arg) }
                            },
                        )
                    )
                }
                query.error?.let { add(Section("Error", Section.TYPE_TEXT, JsonPrimitive(it))) }
            },
        )
    }

    /**
     * Reads stay quiet and mutations stand out — a busy screen is mostly SELECTs, and colouring
     * those would make the timeline shout at its least interesting rows.
     */
    private fun dbTone(operation: String, error: String?): String = when {
        error != null -> Badge.TONE_ERROR
        operation == SqlSummary.DELETE -> Badge.TONE_WARN
        operation == SqlSummary.INSERT || operation == SqlSummary.UPDATE -> Badge.TONE_INFO
        else -> Badge.TONE_MUTED
    }

    // ---- worker -----------------------------------------------------------------------------

    private fun worker(work: WorkerEvent): GenericEvent = GenericEvent(
        title = work.worker,
        subtitle = work.uniqueName ?: work.error ?: work.state,
        badges = buildList {
            add(Badge(work.state.uppercase(), workerTone(work.state)))
            // Attempt 1 is just "it ran"; anything above is a retry and worth flagging.
            if (work.runAttempt > 1) add(Badge("attempt ${work.runAttempt}", Badge.TONE_WARN))
            // Replayed history from WorkManager's store on attach — not a run this session. Muted
            // so it reads as "prior", and it's the answer to "why did this worker 'run' 20 times?".
            if (work.replayedAtAttach) add(Badge("replayed", Badge.TONE_MUTED))
        },
        sections = buildList {
            if (work.inputData.isNotEmpty()) {
                add(Section("Input", Section.TYPE_KV, jsonOf(work.inputData)))
            }
            if (work.outputData.isNotEmpty()) {
                add(Section("Output", Section.TYPE_KV, jsonOf(work.outputData)))
            }
            work.error?.let { add(Section("Error", Section.TYPE_TEXT, JsonPrimitive(it))) }
            if (work.tags.isNotEmpty()) {
                add(Section("Tags", Section.TYPE_TEXT, JsonPrimitive(work.tags.joinToString("\n"))))
            }
            add(
                Section(
                    "Request", Section.TYPE_KV,
                    buildJsonObject {
                        work.workId?.let { put("workId", it) }
                        work.uniqueName?.let { put("unique", it) }
                        put("attempt", work.runAttempt.toString())
                        // WorkInfo reports state, not execution time, so say what the number is.
                        put("timing", "includes queue time (from WorkInfo state changes)")
                    },
                )
            )
        },
    )

    private fun workerTone(state: String): String = when (state.lowercase()) {
        WorkerEvent.STATE_FAILED -> Badge.TONE_ERROR
        WorkerEvent.STATE_CANCELLED -> Badge.TONE_WARN
        WorkerEvent.STATE_RUNNING, WorkerEvent.STATE_SUCCEEDED -> Badge.TONE_INFO
        else -> Badge.TONE_MUTED
    }

    // ---- config -----------------------------------------------------------------------------

    private fun config(update: ConfigUpdate): GenericEvent {
        val changed = update.changes.size
        return GenericEvent(
            title = when {
                update.baseline -> "Config baseline"
                changed == 1 -> update.changes.single().key
                else -> "$changed flags changed"
            },
            subtitle = when {
                update.baseline -> "${update.totalKeys} flags recorded"
                changed == 1 -> valueTransition(update.changes.single())
                else -> update.changes.take(4).joinToString(", ") { it.key } +
                    if (changed > 4) " +${changed - 4} more" else ""
            },
            badges = buildList {
                if (update.baseline) add(Badge("BASELINE", Badge.TONE_MUTED))
                else add(Badge("$changed CHANGED", Badge.TONE_INFO))
                update.source?.let { add(Badge(it.uppercase(), Badge.TONE_MUTED)) }
                update.changes.count { it.isNew }.takeIf { it > 0 }
                    ?.let { add(Badge("$it new", Badge.TONE_MUTED)) }
            },
            sections = buildList {
                if (update.changes.isNotEmpty()) {
                    add(
                        Section(
                            "Changes", Section.TYPE_KV,
                            buildJsonObject {
                                update.changes.forEach { put(it.key, valueTransition(it)) }
                            },
                        )
                    )
                }
                add(
                    Section(
                        "Fetch", Section.TYPE_KV,
                        buildJsonObject {
                            update.source?.let { put("source", it) }
                            update.fetchStatus?.let { put("status", it) }
                            put("keys in config", update.totalKeys.toString())
                        },
                    )
                )
            },
        )
    }

    /** `old → new`, or just the value when it's newly defined. */
    private fun valueTransition(change: io.github.siddharthjaswal.logpose.model.ConfigChange): String =
        if (change.previous == null) change.value else "${change.previous} → ${change.value}"

    // ---- shared -----------------------------------------------------------------------------

    private fun jsonOf(values: Map<String, String>) = buildJsonObject {
        values.forEach { (k, v) -> put(k, v) }
    }

    /** One-line preview for a row; the full text is always in the detail section. */
    private fun compact(text: String, max: Int = 120): String {
        val single = text.trim().replace(Regex("\\s+"), " ")
        return if (single.length <= max) single else single.take(max - 1) + "…"
    }
}
