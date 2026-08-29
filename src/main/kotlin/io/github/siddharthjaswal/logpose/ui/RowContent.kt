package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.analysis.SqlSummary
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.WorkerEvent

/**
 * What each kind of row *says* — decided once, in one place, with no Swing in sight.
 *
 * [KindPresenter] answers "what is this event", in the wire-shaped [GenericEvent] form that MCP,
 * search and the detail pane all read. This answers the narrower question the JList asks: which
 * text goes in which cell, at which weight, in which semantic colour. Splitting them is what keeps
 * §6's row rules out of the wire — and out of the painter, which should look up an answer rather
 * than compute one.
 *
 * Colours are named as [Token]s, never as `Color`s, so this file stays unit-testable without a
 * theme. Two rules from §1 are baked in and must survive any edit:
 *
 *  - **No new hues.** Every token here already exists in `Ui.kt`. Worker states encode themselves
 *    by *semantics plus shape* — a glyph and a weight — not by colour.
 *  - **Success stays neutral.** In a healthy capture nearly every row succeeds; a wall of green is
 *    noise. Colour marks the exceptions only.
 */
object RowContent {

    /** Existing `Theme` tokens, by name. The painter maps these; nothing here knows a colour. */
    enum class Token { TEXT, TEXT_DIM, TEXT_MUTED, ACCENT, DANGER, WARN }

    /** Read vs write is carried by weight, exactly as HTTP methods do it. */
    enum class Weight { REGULAR, BOLD }

    /** Shape, where colour would otherwise have to carry the meaning. */
    enum class Decoration { NONE, STRIKETHROUGH, PULSING_DOT }

    /**
     * What the right-hand time cell shows. §6 lets it count up live for a running worker, which
     * §2.4 did not — §6 is the later round and wins.
     */
    sealed interface TimeCell {
        /** A finished span. */
        data class Duration(val millis: Long) : TimeCell

        /** Still open: the painter counts up from the store's first-seen clock, in `accent`. */
        data object LiveCountUp : TimeCell

        /** Nothing to measure — show when it happened. */
        data class Timestamp(val atMillis: Long) : TimeCell

        data object Empty : TimeCell
    }

    // ---- worker ---------------------------------------------------------------------------------

    /** `✓ succeeded`, `● running`, … — glyph and word together, so the word is never alone. */
    data class StateToken(
        val text: String,
        val token: Token,
        val weight: Weight,
        val decoration: Decoration = Decoration.NONE,
    )

    data class WorkerRow(
        val title: String,
        val state: StateToken,
        /** Outlined `RETRY ×N` in `warn` — the DUP pill's grammar, since it is the same sentence. */
        val retry: String?,
        /** First tag that is not the worker class, mono 10.5 `textMuted`, ellipsized. */
        val tag: String?,
        val fact: String,
        val time: TimeCell,
    )

    fun worker(event: LogEvent.Worker): WorkerRow {
        val work = event.work
        return WorkerRow(
            title = work.worker,
            state = workerState(work.state),
            // Attempt 1 is just "it ran". A blocked *first* attempt has not retried anything yet,
            // so `RETRY ×1` would be a false statement.
            retry = if (work.runAttempt > 1) "RETRY ×${work.runAttempt}" else null,
            tag = workerTag(work),
            fact = workerFact(work),
            time = workerTime(event),
        )
    }

    fun workerState(state: String): StateToken = when (state.lowercase()) {
        WorkerEvent.STATE_SUCCEEDED ->
            StateToken("✓ succeeded", Token.TEXT_DIM, Weight.REGULAR)
        WorkerEvent.STATE_RUNNING ->
            StateToken("● running", Token.ACCENT, Weight.BOLD, Decoration.PULSING_DOT)
        WorkerEvent.STATE_ENQUEUED ->
            StateToken("◦ enqueued", Token.TEXT_MUTED, Weight.REGULAR)
        WorkerEvent.STATE_FAILED ->
            StateToken("✕ failed", Token.DANGER, Weight.BOLD)
        WorkerEvent.STATE_CANCELLED ->
            StateToken("cancelled", Token.TEXT_MUTED, Weight.REGULAR, Decoration.STRIKETHROUGH)
        WorkerEvent.STATE_BLOCKED ->
            StateToken("◦ blocked", Token.TEXT_MUTED, Weight.REGULAR)
        // An unrecognised state is still a state: say it plainly rather than dropping it.
        else -> StateToken(state.lowercase(), Token.TEXT_MUTED, Weight.REGULAR)
    }

    /**
     * WorkManager always tags a request with its own fully-qualified worker class, and that is
     * already the row's title — so the first *useful* tag is the first one that is neither the
     * class nor a WorkManager internal.
     */
    fun workerTag(work: WorkerEvent): String? = work.tags.firstOrNull { tag ->
        val trimmed = tag.trim()
        trimmed.isNotEmpty() &&
            !trimmed.equals(work.worker, ignoreCase = true) &&
            !trimmed.substringAfterLast('.').equals(work.worker, ignoreCase = true) &&
            !trimmed.startsWith("androidx.work.", ignoreCase = true)
    }?.trim()

    /**
     * §6 asks for `queued 6.2s` here. **That number is not on the wire**: the envelope's `at` is
     * the library's first sighting of the `workId`, not WorkManager's enqueue time, and the instant
     * the request went `running` is overwritten when the terminal state lands on the same envelope
     * id. Approximating it from arrival times would be wrong for every worker enqueued before the
     * observer attached, for every replayed row, and whenever the observer coalesced two states —
     * so the cell stays blank instead. A blank cell is honest; `queued 0s` is not.
     */
    fun workerFact(work: WorkerEvent): String =
        if (work.replayedAtAttach) "replayed" else ""

    /**
     * Terminal rows show their span, which — for the same reason [workerFact] is blank — is queue
     * *plus* run, not run alone. The detail pane already says so in its `timing` line.
     */
    fun workerTime(event: LogEvent.Worker): TimeCell {
        val terminal = event.work.state.lowercase() in WorkerEvent.TERMINAL
        val duration = event.durationMillis
        return when {
            terminal && duration != null -> TimeCell.Duration(duration)
            event.work.state.lowercase() == WorkerEvent.STATE_RUNNING -> TimeCell.LiveCountUp
            else -> TimeCell.Timestamp(event.timestampMillis)
        }
    }

    // ---- db -------------------------------------------------------------------------------------

    data class DbRow(
        /** The verb tag's text: `SELECT` / `INSERT` / `UPDATE` / `DELETE` / `TXN` / `PRAGMA` / … */
        val verb: String,
        /** Reads render regular `textDim`, writes bold `text` — the HTTP method rule, reused. */
        val weight: Weight,
        /** Primary text. Null when the statement could not be read confidently. */
        val table: String?,
        /** The full statement, single-lined, greyed and ellipsized after the table. */
        val sql: String,
        val fact: String,
    )

    fun db(query: DbQuery): DbRow {
        val summary = SqlSummary.of(query.sql)
        val operation = query.operation ?: summary.operation
        val table = query.table ?: summary.table
        return DbRow(
            verb = verbTag(operation, query.sql),
            // Everything that is not a plain read gets weight, including an unrecognised verb: an
            // unknown statement is likelier to change state than not.
            weight = if (operation == SqlSummary.SELECT) Weight.REGULAR else Weight.BOLD,
            table = table,
            sql = compact(query.sql),
            fact = dbFact(query),
        )
    }

    /**
     * The display abbreviation lives here, not in [SqlSummary] — that object's vocabulary goes out
     * over MCP verbatim, and `TXN` is a pixel decision.
     */
    fun verbTag(operation: String, sql: String): String = when (operation) {
        SqlSummary.SELECT -> "SELECT"
        SqlSummary.INSERT -> "INSERT"
        SqlSummary.UPDATE -> "UPDATE"
        SqlSummary.DELETE -> "DELETE"
        SqlSummary.TRANSACTION -> "TXN"
        SqlSummary.PRAGMA -> "PRAGMA"
        // Schema DDL names itself; so does an unparsed statement, up to a tag's worth.
        SqlSummary.SCHEMA, SqlSummary.OTHER -> leadingWord(sql) ?: "SQL"
        else -> operation.uppercase().take(6)
    }

    private fun leadingWord(sql: String): String? =
        SqlSummary.normalize(sql).substringBefore(' ').takeIf { it.isNotBlank() }
            ?.filter { it.isLetter() }?.takeIf { it.isNotBlank() }?.uppercase()?.take(6)

    /** §6: rows affected/returned when reported, else the database name. */
    fun dbFact(query: DbQuery): String =
        query.rows?.let { if (it == 1) "1 row" else "$it rows" } ?: query.database.orEmpty()

    // ---- generic (analytics / config / app-defined) -----------------------------------------------

    data class GenericRow(
        val title: String,
        /** Null when the row should print the title alone — no middot, no filler. */
        val subtitle: String?,
        val fact: String,
        val time: TimeCell,
    )

    /**
     * The whole row in one pass.
     *
     * [KindPresenter.present] rebuilds its [io.github.siddharthjaswal.logpose.model.GenericEvent]
     * on every call, so the presentation is resolved **once** here and handed to each accessor —
     * the painter asks four questions about the same event, and a repaint should not pay for four
     * reconstructions of it.
     */
    fun generic(event: LogEvent): GenericRow {
        val presentation = KindPresenter.present(event)
        val badges = KindPresenter.rowBadges(event, presentation)
        return GenericRow(
            title = presentation?.title ?: event.kind,
            subtitle = rowSubtitle(event, presentation, badges),
            fact = rowFact(event, presentation, badges),
            time = event.durationMillis?.let { TimeCell.Duration(it) }
                ?: TimeCell.Timestamp(event.timestampMillis),
        )
    }

    /**
     * The row's secondary text.
     *
     * The badge fallback is load-bearing for db/worker/config/app — it is what puts a worker's
     * state or a db operation on a row whose payload set no subtitle — but it is filtered through
     * [KindPresenter.rowBadges] now, which is what kills `checkout_viewed · ANALYTICS` at the
     * source (and `· EVENT` / `· APP` for any app that badges its own kind).
     *
     * Analytics returns null on purpose: its wire subtitle is the screen, which §6 moves to the
     * fact column, and the slot is left free for a real subtitle rather than filled with a repeat.
     */
    fun rowSubtitle(event: LogEvent): String? =
        rowSubtitle(event, KindPresenter.present(event), KindPresenter.rowBadges(event))

    private fun rowSubtitle(event: LogEvent, presentation: GenericEvent?, badges: List<Badge>): String? {
        if (event.kind == Envelope.KIND_ANALYTICS) return null
        presentation ?: return null
        return presentation.subtitle?.takeIf { it.isNotBlank() } ?: badges.firstOrNull()?.text
    }

    /**
     * The right-hand fact cell, per kind: the screen for analytics, rows-or-database for db,
     * `replayed` (never a fabricated queue wait) for a worker, and otherwise the first badge that
     * is not the row's own kind echoed back.
     *
     * The analytics screen is *read* from the wire subtitle because that is where `logAnalytics`
     * puts it and where MCP's `analytics_events` reads it. The wire is unchanged; only the pixel
     * it lands on moves.
     */
    fun rowFact(event: LogEvent): String =
        rowFact(event, KindPresenter.present(event), KindPresenter.rowBadges(event))

    private fun rowFact(event: LogEvent, presentation: GenericEvent?, badges: List<Badge>): String = when {
        event is LogEvent.Db -> dbFact(event.query)
        event is LogEvent.Worker -> workerFact(event.work)
        event.kind == Envelope.KIND_ANALYTICS ->
            presentation?.subtitle?.takeIf { it.isNotBlank() }.orEmpty()
        // Index into the *filtered* badges: an app event whose first badge echoes its kind used to
        // have its real fact silently shifted out of this cell.
        else -> badges.getOrNull(1)?.text.orEmpty()
    }

    /** One-line preview; the untruncated statement is always in the detail section. */
    private fun compact(text: String, max: Int = 300): String {
        val single = SqlSummary.normalize(text)
        return if (single.length <= max) single else single.take(max - 1) + "…"
    }
}
