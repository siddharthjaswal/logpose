package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * Folds a filtered event list into the rows the timeline draws.
 *
 * **Collapsing is presentation, and it must never lie.** The [EventStore][io.github.siddharthjaswal.logpose.store.EventStore]
 * still holds every event; MCP, the waterfall, `get_trace`, correlation, duplicate detection and
 * export all read the store rather than the list model, so none of them can be affected by
 * anything here. What changes is only how many JList rows stand for those events — and every
 * event that went in comes back out inside exactly one [Row.memberIds], which is the mechanical
 * property the tests pin.
 *
 * Two collapses live here:
 *
 *  - **[GroupKind.NET_POLL]** — a run of consecutive same-method+path 2xx calls, per §6. A run
 *    breaks on any non-2xx, error or still-pending call of the same endpoint, so a failed poll
 *    always stands alone. It also breaks on a [Options.maxPollGapMillis] gap, because "polled 30
 *    times in a minute" and "polled 30 times over an hour" are different facts and one `×30` pill
 *    cannot say both. A call carrying a [DuplicateDetector.Severity.STRONG] mark is never folded:
 *    an overlapping double-submit is precisely the bug LogPose exists to show, and burying it in a
 *    neutral `×N` pill would be the collapse lying.
 *  - **[GroupKind.DB_TXN]** — the ceremony of a transaction (`BEGIN` / `SELECT changes()` /
 *    `TRANSACTION SUCCESSFUL` / `END`) folded to one row. The statements *wrapped* by the
 *    transaction stay normal rows; only the ceremony collapses, and only when there was something
 *    to wrap.
 *
 * There is deliberately **no worker collapse**: the library emits every state of a request under
 * the same envelope id (`workId`), and the store's re-put updates that row in place, so
 * enqueued → running → succeeded is already one mutating row before this function is reached.
 *
 * The anchor of a group is its **first** member's real event id — never an index, never a hash of
 * mutable content. It is stable while a run grows (new members append; the anchor does not move),
 * which is what lets selection-by-id, scroll-to-event and expansion state survive the panel's
 * 150ms model rebuild.
 */
object RowCollapse {

    enum class GroupKind { NET_POLL, DB_TXN }

    /**
     * What a collapsed row says about its members.
     *
     * Every number here is **relative to the list that was collapsed**, which is the filtered one —
     * a TYPE chip or a search can change `count`. Callers that surface these must say so.
     */
    data class Facts(
        /** Members standing behind this row. For a transaction, the ceremony rows. */
        val count: Int,
        /** Lower-middle of the members' durations, or null when none reported one. */
        val medianDurationMillis: Long?,
        /** Wall span for a transaction; sum of member durations for a poll run. Null if unknown. */
        val totalDurationMillis: Long?,
        /** Device millis of the most recent member — what §6 asks a collapsed row to show. */
        val latestAtMillis: Long,
        /** Non-ceremony statements a transaction wrapped. Always 0 for a poll run. */
        val statements: Int = 0,
        /** A rolled-back transaction, or one whose members reported an error. */
        val failed: Boolean = false,
        /** A transaction that positively reported `TRANSACTION SUCCESSFUL`. */
        val succeeded: Boolean = false,
    )

    /**
     * One line in the list. [lead] is the event whose content the row paints; [memberIds] is
     * everything the row stands for, in arrival order.
     */
    sealed interface Row {
        val lead: LogEvent
        val anchorId: String
        val memberIds: List<String>

        /** Stable identity across model rebuilds — an event id, or a kind-tagged anchor. */
        val key: String

        val isGroup: Boolean get() = this is Group

        data class Single(override val lead: LogEvent) : Row {
            override val anchorId: String get() = lead.id
            override val memberIds: List<String> get() = listOf(lead.id)
            override val key: String get() = lead.id
        }

        /**
         * A collapsed run. Note [lead] is not necessarily the anchor: a poll run leads with its
         * **latest** member (§6 asks for the latest timestamp, and the detail opens on the latest
         * occurrence) while sitting at the **first** member's position, so its identity cannot
         * churn once per arrival.
         */
        data class Group(
            val groupKind: GroupKind,
            override val lead: LogEvent,
            override val anchorId: String,
            override val memberIds: List<String>,
            val facts: Facts,
        ) : Row {
            override val key: String get() = keyOf(groupKind, anchorId)
        }
    }

    data class Options(
        val collapsePolls: Boolean = true,
        val foldTransactions: Boolean = true,
        /** Two identical calls is information; three is a wall. */
        val minPollRun: Int = 3,
        /** A quiet stretch ends a run — the same endpoint later is a new episode, not more of this one. */
        val maxPollGapMillis: Long = 120_000,
    )

    fun keyOf(kind: GroupKind, anchorId: String): String = "$kind:$anchorId"

    /**
     * @param events the already-filtered, arrival-ordered list the panel is about to render.
     * @param expanded group [Row.key]s the user opened; those emit their members verbatim.
     * @param duplicateMarks the marks [DuplicateDetector] produced over the *whole* capture.
     * @param sessionOf app-run index for an event id, so a run cannot straddle a restart.
     */
    fun collapse(
        events: List<LogEvent>,
        expanded: Set<String> = emptySet(),
        duplicateMarks: Map<String, DuplicateDetector.Mark> = emptyMap(),
        sessionOf: (String) -> Int = { 0 },
        options: Options = Options(),
    ): List<Row> {
        if (events.isEmpty()) return emptyList()

        val groups = LinkedHashMap<String, Row.Group>()
        val anchorOf = HashMap<String, String>()

        if (options.collapsePolls) {
            netPollRuns(events, duplicateMarks, sessionOf, options).forEach { record(it, groups, anchorOf) }
        }
        if (options.foldTransactions) {
            dbTransactionFolds(events).forEach { record(it, groups, anchorOf) }
        }

        val out = ArrayList<Row>(events.size)
        for (event in events) {
            val anchor = anchorOf[event.id]
            if (anchor == null) {
                out += Row.Single(event)
                continue
            }
            val group = groups.getValue(anchor)
            when {
                group.key in expanded -> out += Row.Single(event)
                event.id == anchor -> out += group
                // A folded member: represented by the group row already emitted at the anchor.
                else -> Unit
            }
        }
        return out
    }

    /** Every member id of every row, mapped back to the row that stands for it. */
    fun memberIndex(rows: List<Row>): Map<String, Row> {
        val index = HashMap<String, Row>()
        for (row in rows) for (id in row.memberIds) index[id] = row
        return index
    }

    private fun record(
        group: Row.Group,
        groups: MutableMap<String, Row.Group>,
        anchorOf: MutableMap<String, String>,
    ) {
        groups[group.anchorId] = group
        for (id in group.memberIds) anchorOf[id] = group.anchorId
    }

    // ---- net polling ---------------------------------------------------------------------------

    private class Run(val signature: String) {
        val members = ArrayList<LogEvent>()
        var lastAt = 0L
    }

    /**
     * A run's signature excludes the query string (a poll's cache-buster would defeat every match)
     * and the body (a location poll sends new coordinates every time) — which is exactly why this
     * cannot reuse [DuplicateDetector]'s signature. It *includes* `mocked`, so a LogPose-served
     * response can never hide inside a wall of real ones, and the app-run index, so a run cannot
     * merge two sessions into one median.
     */
    private fun netPollRuns(
        events: List<LogEvent>,
        marks: Map<String, DuplicateDetector.Mark>,
        sessionOf: (String) -> Int,
        options: Options,
    ): List<Row.Group> {
        val open = LinkedHashMap<String, Run>()
        val done = ArrayList<Row.Group>()

        fun flush(run: Run?) {
            run ?: return
            if (run.members.size >= options.minPollRun) done += pollGroup(run)
        }

        for (event in events) {
            if (event !is LogEvent.Http) continue
            val tx = event.tx
            val path = tx.request.path.ifBlank { tx.request.url.substringBefore('?') }
            val signature = buildString {
                append(tx.request.method.uppercase()).append(' ').append(path)
                append("|mocked=").append(tx.mocked)
                append("|session=").append(sessionOf(event.id))
            }

            val code = tx.response?.code
            val eligible = tx.error == null &&
                code != null &&
                code in 200..299 &&
                marks[event.id]?.severity != DuplicateDetector.Severity.STRONG

            val current = open[signature]
            if (!eligible) {
                // The spec's break rule: a failed, errored or still-pending call of the same
                // endpoint ends the run and stands alone.
                flush(current)
                open.remove(signature)
                continue
            }

            val at = event.timestampMillis
            if (current != null &&
                current.lastAt > 0 && at > 0 &&
                at - current.lastAt > options.maxPollGapMillis
            ) {
                flush(current)
                open.remove(signature)
            }

            val run = open.getOrPut(signature) { Run(signature) }
            run.members += event
            run.lastAt = at
        }
        open.values.forEach(::flush)
        return done
    }

    private fun pollGroup(run: Run): Row.Group {
        val durations = run.members.mapNotNull { it.durationMillis }
        return Row.Group(
            groupKind = GroupKind.NET_POLL,
            // Leads with the newest call: §6 shows the latest timestamp, and the detail's
            // occurrence stepper opens on n/N.
            lead = run.members.last(),
            anchorId = run.members.first().id,
            memberIds = run.members.map { it.id },
            facts = Facts(
                count = run.members.size,
                medianDurationMillis = median(durations),
                totalDurationMillis = durations.sum().takeIf { durations.isNotEmpty() },
                latestAtMillis = run.members.last().timestampMillis,
            ),
        )
    }

    // ---- db transactions -----------------------------------------------------------------------

    private class Txn(val begin: LogEvent.Db) {
        val ceremony = arrayListOf<LogEvent>(begin)
        var statements = 0
        var failed = begin.query.error != null
        var succeeded = false
    }

    /**
     * Folds transaction ceremony, bucketed by database name.
     *
     * The wire carries no thread or connection id, so two threads transacting on the same unnamed
     * database are indistinguishable in the stream. The defence is to **abandon** a candidate when
     * a second `BEGIN` arrives before its terminator: a busy multi-threaded app then folds nothing,
     * which is the right trade — a fold that merged two transactions would be a lie, and a lie is
     * the one thing collapsing may not be.
     *
     * Non-db events are ignored entirely: a worker row landing between two statements neither opens,
     * closes nor breaks a transaction.
     */
    private fun dbTransactionFolds(events: List<LogEvent>): List<Row.Group> {
        val open = HashMap<String, Txn>()
        val done = ArrayList<Row.Group>()

        for (event in events) {
            if (event !is LogEvent.Db) continue
            val bucket = event.query.database ?: ""
            // A caller-supplied operation means a non-SQL store; its statement is not SQL ceremony.
            val role = if (event.query.operation != null) SqlSummary.Role.NONE
            else SqlSummary.role(event.query.sql)
            val current = open[bucket]

            when (role) {
                SqlSummary.Role.OPEN -> {
                    // Nested or interleaved: emit nothing rather than a wrong fold.
                    open[bucket] = Txn(event)
                }

                SqlSummary.Role.SUCCESS -> {
                    if (current == null) continue
                    current.ceremony += event
                    current.succeeded = true
                    if (event.query.error != null) current.failed = true
                }

                SqlSummary.Role.CHANGES -> {
                    if (current == null) continue
                    current.ceremony += event
                    if (event.query.error != null) current.failed = true
                }

                SqlSummary.Role.CLOSE, SqlSummary.Role.ABORT -> {
                    if (current == null) continue
                    open.remove(bucket)
                    current.ceremony += event
                    if (role == SqlSummary.Role.ABORT || event.query.error != null) current.failed = true
                    // A BEGIN/END pair with nothing between it is already one or two rows; folding
                    // it would shorten nothing and would hide an empty transaction.
                    if (current.statements == 0) continue
                    done += txnGroup(current, event)
                }

                SqlSummary.Role.NONE -> {
                    if (current == null) continue
                    current.statements += 1
                    if (event.query.error != null) current.failed = true
                }
            }
        }
        // A transaction still open when the capture ends never folds — we do not know how it went.
        return done
    }

    private fun txnGroup(txn: Txn, close: LogEvent): Row.Group {
        val span = ((close.envelope.endedAt ?: close.timestampMillis) - txn.begin.timestampMillis)
            .takeIf { it > 0 }
        return Row.Group(
            groupKind = GroupKind.DB_TXN,
            lead = txn.begin,
            anchorId = txn.begin.id,
            memberIds = txn.ceremony.map { it.id },
            facts = Facts(
                count = txn.ceremony.size,
                medianDurationMillis = median(txn.ceremony.mapNotNull { it.durationMillis }),
                totalDurationMillis = span,
                latestAtMillis = close.timestampMillis,
                statements = txn.statements,
                failed = txn.failed,
                succeeded = txn.succeeded && !txn.failed,
            ),
        )
    }

    // ---- shared --------------------------------------------------------------------------------

    /** Lower-middle of the sorted values — no averaging, so the number is one a call really took. */
    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }
}
