package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.RowContent
import io.github.siddharthjaswal.logpose.ui.RowGeometry
import io.github.siddharthjaswal.logpose.ui.RunLabel
import io.github.siddharthjaswal.logpose.ui.TagLabel
import io.github.siddharthjaswal.logpose.ui.TypeIcons
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.WaterfallPresentation
import io.github.siddharthjaswal.logpose.ui.isPending
import io.github.siddharthjaswal.logpose.ui.spinnerChar
import io.github.siddharthjaswal.logpose.ui.statusText
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.cos
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Studio list row for the unified stream.
 *
 * Every kind obeys the same two alignment rules (see [RowGeometry]): a **glyph edge** at 14px and
 * a **content edge** at 42px. HTTP subdivides its content zone, so method / status / path align
 * down the list:
 *
 *   `METHOD   [status]   path … …                 size   duration`
 *
 * FCM, db, worker and generic rows do not. They used to reserve two empty 46px columns purely to
 * line up with HTTP, which spent 92px of a 400px panel saying nothing; now their text starts at the
 * content edge and each kind leads it with the one token that identifies the row:
 *
 *   `[NOTIF] title … …                            n keys  time`
 *   `[SELECT] orders  SELECT * FROM orders …      12 rows    4ms`
 *   `SyncWorker · ✓ succeeded  nightly              replayed  1.2s`
 *
 * METHOD, the db verb tag and the worker state token all carry **no hue** — read vs write and
 * success vs failure are weight, glyph and (only for the exceptions) a semantic colour. The kind's
 * own hue is painted once per row, on the gutter glyph, per §1's four-site rule. So the only
 * coloured pills in a row are the status (semantic) and the solid-accent MOCK/INJ marks (LogPose's
 * own interventions). Muted (HTTP) rows and folded transactions are shorter (26px) and faded.
 *
 * The renderer draws a [RowCollapse.Row], not an event: a row may stand for a run of polls or a
 * transaction's ceremony. It never *decides* that — collapsing is computed once per refresh, in the
 * panel — it only asks the row what it stands for and paints the difference: a neutral outlined
 * `×N` pill and a `~median` duration on a poll run, a 26px summary line for a folded transaction.
 *
 * The two right-hand cells double as **painted action buttons** (`⧉ cURL`, `⇉ flow`) on the
 * hovered row *and* on the selected one — the invisible pixel bands they replace were only ever
 * discoverable by accident. They are drawn at the cells' own widths, so a column never shifts as
 * the pointer crosses a row. A collapsed row paints neither: one cURL for fifteen requests names
 * none of them.
 */
class TransactionListRenderer : ListCellRenderer<RowCollapse.Row> {

    var hoveredIndex: Int = -1

    /** Animation frame for the in-flight spinner; bumped by the panel's timer. */
    var spinnerFrame: Int = 0

    /** Live wall-clock elapsed (ms) for a pending transaction, or null if not pending. */
    var elapsedProvider: (Transaction) -> Long? = { null }

    /**
     * Live wall-clock elapsed (ms) for any event id — the running worker's count-up.
     *
     * §6 lets the generic time column count up, which §2.4 did not; §6 is the later round and wins.
     * Only a `running` worker uses it, and only because that row genuinely has an open span.
     */
    var eventElapsedProvider: (String) -> Long? = { null }

    /** Duplicate-burst mark for a transaction, or null if it isn't a repeated call. */
    var duplicateProvider: (Transaction) -> DuplicateDetector.Mark? = { null }

    /**
     * Whether this row can open a flow — a configured correlation key value, or a trace.
     *
     * This is asked on every paint, so it **must** answer from a cache
     * ([io.github.siddharthjaswal.logpose.analysis.CorrelationIndex.hasCachedKeyValue]) and never
     * scan a payload. A row whose values aren't cached yet simply doesn't offer the affordance;
     * one click's worth of missing glyph is the right price for never scanning inside a repaint.
     */
    var groupingProvider: (LogEvent) -> Boolean = { false }

    private val timeFmt = SimpleDateFormat("HH:mm:ss")

    // Method weight is the whole read/write signal now that method carries no hue, so both faces
    // are built once rather than derived per row.
    private val methodFontRegular = JBUI.Fonts.label(11f)
    private val methodFontBold = JBUI.Fonts.label(11f).asBold()

    // ---- HTTP row -------------------------------------------------------------------------
    private val httpIcon = JLabel(TypeIcons.forKind(Envelope.KIND_HTTP)).glyphCell()
    private val methodLabel = JLabel("", SwingConstants.LEFT).fixed(RowGeometry.METHOD, CELL_H)
    private val statusTag = TagLabel().fixed(RowGeometry.STATUS, CELL_H)
    private val path = JLabel()
    private val dupTag = TagLabel()
    private val dupGap = pillGap()
    private val mockTag = TagLabel()
    private val mockGap = pillGap()
    private val countTag = TagLabel()
    private val countGap = pillGap()
    private val sizeCell = MetaCell(RowGeometry.SIZE)
    private val durationCell = MetaCell(RowGeometry.TIME)

    private val row = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)

        methodLabel.font = methodFontBold
        statusTag.font = JBUI.Fonts.label(11f).asBold()

        val badges = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(httpIcon)
            add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.GAP)))
            add(methodLabel)
            add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.GAP)))
            add(statusTag)
        }
        path.border = JBUI.Borders.emptyLeft(RowGeometry.TEXT_GAP)
        path.font = JBUI.Fonts.label(12.5f)
        listOf(dupTag, mockTag, countTag).forEach {
            it.font = JBUI.Fonts.label(10f).asBold()
            it.border = JBUI.Borders.empty(2, 7)
        }

        // The path fills the centre; the ×N / MOCK / duplicate pills (when present) tuck in at its
        // right end, just before the size/duration meta — so path-start alignment is never
        // disturbed. Each pill's 8px lead-in is a strut that hides with it, so a row carrying no
        // pills gives the path back every pixel.
        //
        // Order matters, and it is a third pill grammar worth naming: solid accent = intervention
        // (MOCK/INJ), outlined-in-a-severity-colour = advisory (DUP), outlined-neutral =
        // structural (×N). ×N sits closest to the path because it modifies the subject — "this
        // call happened 30 times" — while MOCK and DUP annotate it.
        val tags = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(countGap)
            add(countTag)
            add(mockGap)
            add(mockTag)
            add(dupGap)
            add(dupTag)
        }
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(path, BorderLayout.CENTER)
            add(tags, BorderLayout.EAST)
        }

        add(badges, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(metaPair(sizeCell, durationCell), BorderLayout.EAST)
    }

    // ---- FCM row --------------------------------------------------------------------------
    // Glyph, then straight into the content zone: the kind tag leads the summary rather than
    // sitting in a borrowed status column, and no empty method column precedes it.
    private val fcmIcon = JLabel(TypeIcons.forKind(Envelope.KIND_FCM)).glyphCell()
    private val fcmTag = TagLabel()
    private val fcmText = JLabel()
    private val injTag = TagLabel()
    private val injGap = pillGap()
    private val fcmCount = MetaCell(RowGeometry.SIZE)
    private val fcmTime = MetaCell(RowGeometry.TIME)

    private val fcmRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)

        fcmTag.font = JBUI.Fonts.label(10f).asBold()
        fcmTag.border = JBUI.Borders.empty(2, 7)
        injTag.font = JBUI.Fonts.label(10f).asBold()
        injTag.border = JBUI.Borders.empty(2, 7)

        fcmText.border = JBUI.Borders.emptyLeft(RowGeometry.PILL_GAP)
        fcmText.font = JBUI.Fonts.label(12.5f)

        val trailing = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(injGap)
            add(injTag)
        }

        // Kind tag, summary, then the INJ mark at the right end of the text zone — the same
        // "pills tuck in after the text" grammar the HTTP row uses.
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fcmTag, BorderLayout.WEST)
            add(fcmText, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
        }

        add(gutter(fcmIcon), BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(metaPair(fcmCount, fcmTime), BorderLayout.EAST)
    }

    // ---- DB row ---------------------------------------------------------------------------
    // Verb tag, table, then the statement greyed behind it. The verb tag carries no hue: reads are
    // regular `textDim`, writes bold `text` — the same weight rule HTTP methods use, for the same
    // reason. The amber db hue stays where §1 allows it, on the gutter glyph.
    private val dbIcon = JLabel(TypeIcons.forKind(Envelope.KIND_DB)).glyphCell()
    private val dbVerb = TagLabel(arc = 6)
    private val dbText = RunLabel()
    private val dbFactCell = MetaCell(RowGeometry.FACT)
    private val dbTimeCell = MetaCell(RowGeometry.TIME)

    private val dbRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)
        dbVerb.border = JBUI.Borders.empty(2, 7)
        dbText.border = JBUI.Borders.emptyLeft(RowGeometry.PILL_GAP)

        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(dbVerb, BorderLayout.WEST)
            add(dbText, BorderLayout.CENTER)
        }
        add(gutter(dbIcon), BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(metaPair(dbFactCell, dbTimeCell), BorderLayout.EAST)
    }

    // ---- Folded transaction row -------------------------------------------------------------
    // One 26px line standing for a transaction's ceremony. Muted like a muted endpoint — the same
    // fade, so "muted" means exactly one thing in this codebase — unless it failed, which is
    // rendered at full strength with a `✕` in danger. Success stays neutral: in a healthy capture
    // almost every transaction commits.
    private val txnIcon = JLabel(TypeIcons.forKind(Envelope.KIND_DB)).glyphCell()
    private val txnText = RunLabel()
    private val txnFact = MetaCell(RowGeometry.FACT)
    private val txnTime = MetaCell(RowGeometry.TIME)

    private val txnRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)
        add(gutter(txnIcon), BorderLayout.WEST)
        add(txnText, BorderLayout.CENTER)
        add(metaPair(txnFact, txnTime), BorderLayout.EAST)
    }

    // ---- Worker row -------------------------------------------------------------------------
    // `SyncWorker · ✓ succeeded  nightly`. The state is semantics plus shape — a glyph, a weight,
    // and a colour only for the exceptions — never a new hue, and the glyph stays worker-teal in
    // every state.
    private val workIcon = JLabel(TypeIcons.forKind(Envelope.KIND_WORKER)).glyphCell()
    private val workTitle = JLabel()
    private val workDot = JLabel("  ·  ")
    private val workState = StateLabel()
    private val workRetry = TagLabel()
    private val workRetryGap = pillGap()
    private val workTag = JLabel()
    private val workFact = MetaCell(RowGeometry.FACT)
    private val workTime = MetaCell(RowGeometry.TIME)

    private val workRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)
        workTitle.font = JBUI.Fonts.label(12.5f)
        workDot.font = JBUI.Fonts.label(12.5f)
        workDot.foreground = Theme.textMuted
        workRetry.font = JBUI.Fonts.label(10f).asBold()
        workRetry.border = JBUI.Borders.empty(2, 7)
        // The tag is the least important run, so it is the one that ellipsizes first: it takes the
        // flexible centre while the identity strip keeps its intrinsic width.
        workTag.font = JBUI.Fonts.create("JetBrains Mono", 10)
        workTag.foreground = Theme.textMuted
        workTag.border = JBUI.Borders.emptyLeft(RowGeometry.PILL_GAP)

        val strip = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(workTitle)
            add(workDot)
            add(workState)
            add(workRetryGap)
            add(workRetry)
        }
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(strip, BorderLayout.WEST)
            add(workTag, BorderLayout.CENTER)
        }
        add(gutter(workIcon), BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(metaPair(workFact, workTime), BorderLayout.EAST)
    }

    // ---- Generic row (config / analytics / app-defined) ------------------------------------
    // Icon gutter, then `title · subtitle` straight at the content edge, so the type is stated
    // exactly once (the coloured glyph) and the state is a full word, never a truncated pill.
    private val genIcon = JLabel().glyphCell()
    private val genText = RunLabel()
    private val genCount = MetaCell(RowGeometry.FACT)
    private val genTime = MetaCell(RowGeometry.TIME)

    private val genRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)
        add(gutter(genIcon), BorderLayout.WEST)
        add(genText, BorderLayout.CENTER)
        add(metaPair(genCount, genTime), BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out RowCollapse.Row>,
        value: RowCollapse.Row,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val group = value as? RowCollapse.Row.Group
        if (group?.groupKind == RowCollapse.GroupKind.DB_TXN) {
            return txnFoldRow(group, index, isSelected)
        }
        return when (val event = value.lead) {
            is LogEvent.Http -> httpRow(event, group, index, isSelected)
            is LogEvent.Fcm -> fcmRow(event, index, isSelected)
            is LogEvent.Db -> dbRow(event, index, isSelected)
            is LogEvent.Worker -> workerRow(event, index, isSelected)
            // config, analytics, and every app-defined kind share one row, driven by KindPresenter
            // through RowContent. Their differences are presentational, so they don't warrant
            // separate layouts; both alignment rules stay identical to HTTP and FCM.
            else -> structuredRow(event, list.width, index, isSelected)
        }
    }

    /**
     * Row for config / analytics / app-defined kinds: glyph, then `title · subtitle` at the content
     * edge, then a full-word fact in a column wide enough to hold one — 150px for analytics, whose
     * fact is a screen name, 120px otherwise.
     */
    private fun structuredRow(value: LogEvent, rowWidth: Int, index: Int, isSelected: Boolean): Component {
        genRow.selected = isSelected
        genRow.hovered = index == hoveredIndex && !isSelected
        genRow.rowHeight = 34

        // The type is the coloured gutter glyph — stated once, never as text and never as a
        // truncated pill.
        genIcon.icon = TypeIcons.forEvent(value)

        val content = RowContent.generic(value)
        genText.runs = buildList {
            add(RunLabel.Run(content.title, LABEL_12_5, Theme.text))
            content.subtitle?.let {
                add(RunLabel.Run("  ·  ", LABEL_12_5, Theme.textMuted))
                add(RunLabel.Run(it, LABEL_12_5, Theme.textDim))
            }
        }

        // Where HTTP shows body size: the one telling fact this kind has, in full — 120px, because
        // `hyperlocal_feature_db` is the answer and `+1` is not.
        genCount.cellWidth(RowGeometry.fact(value.kind, rowWidth))
        genCount.meta(content.fact, Theme.textMuted)

        if (actionsArmed(index, isSelected) && canGroup(value)) {
            genTime.action(FLOW)
        } else {
            genTime.meta(timeText(content.time), Theme.textMuted)
        }
        return genRow
    }

    /**
     * A db row: `[SELECT] orders  SELECT * FROM orders WHERE …`.
     *
     * The table is the row's primary text and the statement follows it greyed, which is only
     * possible because the verb/table parser owns the split — the row used to lead with whatever
     * [io.github.siddharthjaswal.logpose.analysis.SqlSummary] returned, so a mis-parse became the
     * row's headline (`OR · UPDATE OR ABORT …`).
     */
    private fun dbRow(event: LogEvent.Db, index: Int, isSelected: Boolean): Component {
        dbRow.selected = isSelected
        dbRow.hovered = index == hoveredIndex && !isSelected
        dbRow.rowHeight = 34

        val content = RowContent.db(event.query)
        val read = content.weight == RowContent.Weight.REGULAR
        dbVerb.font = if (read) MONO_10 else MONO_10_BOLD
        dbVerb.set(content.verb, if (read) Theme.textDim else Theme.text, Theme.bg2)

        val table = content.table
        dbText.runs = if (table != null) {
            listOf(
                RunLabel.Run(table, LABEL_12_5, Theme.text),
                RunLabel.Run("  ", LABEL_12_5, Theme.text),
                RunLabel.Run(content.sql, MONO_11, Theme.textMuted),
            )
        } else {
            // Nothing was parsed confidently, so the statement carries the row alone — and at full
            // strength, because it is now the only thing identifying it.
            listOf(RunLabel.Run(content.sql, MONO_11, Theme.text))
        }

        dbFactCell.meta(content.fact, Theme.textMuted)
        if (actionsArmed(index, isSelected) && canGroup(event)) {
            dbTimeCell.action(FLOW)
        } else {
            dbTimeCell.meta(
                event.durationMillis?.let { formatDuration(it) }
                    ?: event.timestampMillis.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) }.orEmpty(),
                Theme.textMuted,
            )
        }
        return dbRow
    }

    /** `transaction ✓ · 4 statements · 128ms` — a whole transaction's ceremony as one 26px line. */
    private fun txnFoldRow(group: RowCollapse.Row.Group, index: Int, isSelected: Boolean): Component {
        val hovered = index == hoveredIndex
        txnRow.selected = isSelected
        txnRow.hovered = hovered && !isSelected
        txnRow.rowHeight = 26

        val facts = group.facts
        txnIcon.icon = TypeIcons.forEvent(group.lead)

        // A failed transaction is not noise, so it is not faded; everything else is, with the same
        // fade a muted endpoint gets.
        fun shade(c: Color): Color =
            if (facts.failed) c else Theme.fade(c, if (hovered) 0.7f else 0.34f)

        val tail = buildString {
            append(" · ").append(facts.statements)
            append(if (facts.statements == 1) " statement" else " statements")
            // Room's callback carries no per-statement timing, so a true Σ would print `0ms` on
            // almost every capture. This is the wall span BEGIN → END, which is the number a reader
            // actually wants, and it is omitted rather than faked when the clocks can't give it.
            facts.totalDurationMillis?.let { append(" · ").append(formatDuration(it)) }
        }
        txnText.runs = buildList {
            add(RunLabel.Run("transaction", LABEL_12_5, shade(Theme.textDim)))
            when {
                facts.failed -> {
                    add(RunLabel.Run(" ", LABEL_12_5, shade(Theme.textDim)))
                    add(RunLabel.Run("✕", LABEL_12_5_BOLD, Theme.danger))
                }
                // A ✓ is only painted where one was actually reported. Integrations that never emit
                // `TRANSACTION SUCCESSFUL` get no glyph rather than an asserted success.
                facts.succeeded -> {
                    add(RunLabel.Run(" ", LABEL_12_5, shade(Theme.textDim)))
                    add(RunLabel.Run("✓", LABEL_12_5, shade(Theme.textDim)))
                }
            }
            add(RunLabel.Run(tail, MONO_11, shade(Theme.textMuted)))
        }

        // The counts are in the text, and a fold is not a single event with a size or a clock.
        txnFact.cellWidth(RowGeometry.FACT)
        txnFact.meta("", Theme.textMuted)
        txnTime.meta("", Theme.textMuted)
        return txnRow
    }

    /**
     * A worker row. The state is the second run of the identity strip, so it reads as part of the
     * sentence (`SyncWorker · ✓ succeeded`) rather than as a badge; the first tag that isn't the
     * worker's own class follows, and ellipsizes first.
     */
    private fun workerRow(event: LogEvent.Worker, index: Int, isSelected: Boolean): Component {
        workRow.selected = isSelected
        workRow.hovered = index == hoveredIndex && !isSelected
        workRow.rowHeight = 34

        val content = RowContent.worker(event)
        workTitle.text = content.title
        workTitle.foreground = Theme.text
        workState.render(content.state)

        val retry = content.retry
        workRetry.isVisible = retry != null
        workRetryGap.isVisible = retry != null
        // Outlined warn, `text` label — the DUP pill's exact grammar, because it is the same kind of
        // sentence: advice about the call rather than a fact about its outcome.
        if (retry != null) workRetry.set(retry, Theme.text, null, stroke = Theme.warn)
        else workRetry.set("", Theme.text, null)

        workTag.text = content.tag.orEmpty()

        workFact.cellWidth(RowGeometry.FACT)
        workFact.meta(content.fact, Theme.textMuted)

        val armed = actionsArmed(index, isSelected)
        when {
            // A running worker's live count-up outranks the affordance, exactly as a pending HTTP
            // row's timer does; the flow action stays reachable from the context menu.
            content.time is RowContent.TimeCell.LiveCountUp ->
                workTime.meta(
                    // The provider's age runs from the row's first sighting, which for a worker
                    // includes the queue. Subtracting the device-reported queue leaves the run —
                    // floored at zero, because a device clock adjusted mid-run can overshoot.
                    eventElapsedProvider(event.id)
                        ?.let { formatDuration((it - content.time.offsetMillis).coerceAtLeast(0)) }
                        ?: "…",
                    Theme.accent,
                )
            armed && canGroup(event) -> workTime.action(FLOW)
            else -> workTime.meta(timeText(content.time), Theme.textMuted)
        }
        return workRow
    }

    /** The right-hand time cell's text for a resolved [RowContent.TimeCell]. */
    private fun timeText(cell: RowContent.TimeCell): String = when (cell) {
        is RowContent.TimeCell.Duration -> formatDuration(cell.millis)
        is RowContent.TimeCell.Timestamp -> cell.atMillis.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) }.orEmpty()
        // Deliberately no spinner, unlike HTTP. A foreign producer that never sets endedAt is far
        // more likely than a genuinely long-running span, and a row that spins forever reads as a
        // hang. A running worker is the one exception, and it is handled by its own painter.
        is RowContent.TimeCell.LiveCountUp -> "…"
        RowContent.TimeCell.Empty -> ""
    }

    /**
     * Workers run for minutes; "94210ms" in a 56px column is unreadable. The rule itself lives in
     * [RowContent.shortDuration], beside the cells that use it — the fact and time cells of one row
     * must not disagree about what a duration looks like.
     */
    private fun formatDuration(millis: Long): String = RowContent.shortDuration(millis)

    private fun httpRow(
        event: LogEvent.Http,
        group: RowCollapse.Row.Group?,
        index: Int,
        isSelected: Boolean,
    ): Component {
        val value = event.tx
        val muted = MutedEndpoints.isMuted(value)
        val hovered = index == hoveredIndex
        row.selected = isSelected
        row.hovered = hovered && !isSelected
        row.rowHeight = if (muted) 26 else 34

        fun shade(c: Color): Color = if (!muted) c else Theme.fade(c, if (hovered) 0.7f else 0.34f)

        // Method carries no hue: read vs write is weight. An unknown verb is treated as a write,
        // because a verb LogPose doesn't recognise is far likelier to change state than not.
        val read = Theme.isRead(value.request.method)
        methodLabel.text = value.request.method
        methodLabel.font = if (read) methodFontRegular else methodFontBold
        methodLabel.foreground = shade(Theme.methodTextColor(value.request.method))

        val pending = value.isPending()
        val code = value.response?.code
        if (pending) {
            // No fill at all: the spinner *is* the pill while a call is in flight. A tinted accent
            // plate behind it would be a second accent surface in a row whose accent budget belongs
            // to the MOCK pill and the selection tint, and at 46px it reads as a filled status.
            statusTag.set(spinnerChar(spinnerFrame).toString(), Theme.accent, null)
        } else {
            val sColor = Theme.statusColor(code, value.error)
            val sBg = if (muted) Theme.tint(sColor, 14) else Theme.statusTint(code, value.error)
            statusTag.set(value.statusText(), shade(sColor), sBg)
        }

        path.text = value.request.path.ifBlank { value.request.url }
        path.foreground = shade(Theme.text)

        // The ×N pill: **outlined and neutral**, because a repeated success is structure, not
        // advice and not a status. The stroke is `borderStrong`, which is decoration — the meaning
        // is carried by `×30` in `textDim`, which clears 4.5:1 in both themes. Promoting the stroke
        // to a semantic colour would make a boring poll look like a warning.
        countTag.isVisible = group != null
        countGap.isVisible = group != null
        if (group != null) countTag.set("×${group.facts.count}", shade(Theme.textDim), null, stroke = shade(Theme.borderStrong))
        else countTag.set("", Theme.text, null)

        // MOCK is the one solid-accent fill a row may carry: it says LogPose itself caused this
        // response. Solid (not tinted) keeps it distinct from the 15%-accent selection tint. A
        // collapsed run is uniformly mocked or uniformly real — `mocked` is in the run signature —
        // so this pill can never flicker as the run grows.
        mockTag.isVisible = value.mocked
        mockGap.isVisible = value.mocked
        if (value.mocked) mockTag.set("MOCK", shade(Theme.onAccent), shade(Theme.intervention))
        else mockTag.set("", Theme.text, null)

        // DUP is outlined, never filled. A filled amber/red pill states a fact about the response;
        // an outlined one offers advice about the call — the border keeps the two grammars apart.
        //
        // Outlining moved the label off a tint plate and onto the bare row, where amber reads at
        // 4.17:1 light (3.47 on a selected row). So the severity lives on the **stroke**, which is
        // a non-text indicator needing 3:1 and clears it on every row ground, and the label is
        // `text`. The grammar and the encoding both survive; only the legibility changes.
        //
        // A collapsed run suppresses it: `×30` already says "this repeated", and two outlined pills
        // saying the same thing is noise. Nothing is hidden by that — a STRONG (double-submit) mark
        // makes a call ineligible for folding in the first place, so the marks a fold can cover are
        // only the ones the ×N restates, and expanding the run shows each of them.
        val dup = if (group == null) duplicateProvider(value) else null
        dupTag.isVisible = dup != null
        dupGap.isVisible = dup != null
        if (dup != null) {
            val fg = when (dup.severity) {
                DuplicateDetector.Severity.STRONG -> Theme.danger
                DuplicateDetector.Severity.MEDIUM -> Theme.warn
                DuplicateDetector.Severity.INFO -> Theme.textDim
            }
            dupTag.set("DUP ×${dup.ordinal}", shade(Theme.text), null, stroke = shade(fg))
        } else {
            dupTag.set("", Theme.text, null)
        }

        // The meta pair. On the hovered row — and, so the affordance is findable at all, on the
        // selected one — each cell becomes the button its own predicate allows: cURL wherever a
        // request can be rebuilt, flow wherever there's a flow to open. A pending row keeps its
        // live timer, which outranks both; a collapsed run paints neither button, because one cURL
        // for thirty requests names none of them.
        val armed = actionsArmed(index, isSelected) && group == null
        when {
            group != null -> {
                // The latest occurrence's size, and the run's median duration — a time some call in
                // this run really took, prefixed `~` so it never reads as a single measurement.
                val size = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) }
                sizeCell.meta(size.orEmpty(), shade(Theme.textMuted))
                durationCell.meta(
                    group.facts.medianDurationMillis?.let { "~${it}ms" }.orEmpty(),
                    shade(Theme.textMuted),
                )
            }
            pending -> {
                sizeCell.meta("", shade(Theme.textMuted))
                durationCell.meta(elapsedProvider(value)?.let { "${it}ms" } ?: "…", Theme.accent)
            }
            else -> {
                if (armed && paintsCurl(event)) {
                    sizeCell.action(CURL)
                } else {
                    val size = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) }
                    sizeCell.meta(size.orEmpty(), shade(Theme.textMuted))
                }
                if (armed && paintsFlow(event)) {
                    durationCell.action(FLOW)
                } else {
                    durationCell.meta(value.durationMillis?.let { "${it}ms" }.orEmpty(), shade(Theme.textMuted))
                }
            }
        }

        return row
    }

    private fun fcmRow(event: LogEvent.Fcm, index: Int, isSelected: Boolean): Component {
        val msg = event.msg
        fcmRow.selected = isSelected
        fcmRow.hovered = index == hoveredIndex && !isSelected
        fcmRow.rowHeight = 34

        // TOKEN / NOTIF / DATA is a *label*, not a severity: which of the three it is changes
        // nothing about how urgent the row is, so all three are neutral. The FCM hue is already
        // stated once, by the gutter glyph. It leads the summary rather than occupying a column,
        // because there is nothing for it to line up with.
        fcmTag.set(fcmKind(msg), Theme.textDim, Theme.bg2)

        fcmText.text = fcmSummary(msg)
        fcmText.foreground = Theme.text

        // A push LogPose itself delivered is marked, always — the same trust rule and the same
        // solid-intervention fill as the MOCK pill: the timeline never passes an injected push
        // off as a real one, and both interventions look alike whatever kind they land on.
        injTag.isVisible = msg.injected
        injGap.isVisible = msg.injected
        if (msg.injected) injTag.set("INJ", Theme.onAccent, Theme.intervention)
        else injTag.set("", Theme.text, null)

        // "size" column: number of data keys (data messages carry the payload of interest).
        val keys = msg.data.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "key" else "keys"}" }
        fcmCount.meta(keys.orEmpty(), Theme.textMuted)

        // An injected push has no device receive-time of its own, so fall back to the envelope's
        // clock rather than leaving the column blank.
        val at = msg.receivedAtMillis.takeIf { it > 0 } ?: event.timestampMillis
        if (actionsArmed(index, isSelected) && canGroup(event)) {
            fcmTime.action(FLOW)
        } else {
            fcmTime.meta(at.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) }.orEmpty(), Theme.textMuted)
        }

        return fcmRow
    }

    private fun fcmKind(msg: FcmMessage): String = when {
        msg.event == "token" -> "TOKEN"
        msg.notification != null -> "NOTIF"
        else -> "DATA"
    }

    private fun fcmSummary(msg: FcmMessage): String = when {
        msg.event == "token" -> "Registration token refreshed"
        msg.notification != null ->
            msg.notification.title?.takeIf { it.isNotBlank() }
                ?: msg.notification.body?.takeIf { it.isNotBlank() }
                ?: "(notification)"
        // A data message's most meaningful label is its channel (apps commonly carry one in the
        // data map); the raw `from` is just an FCM sender/project number, so it's the last resort.
        else -> fcmChannel(msg)
            ?: msg.collapseKey?.takeIf { it.isNotBlank() }
            ?: msg.from?.takeIf { it.isNotBlank() }
            ?: "(data message)"
    }

    /** The channel a data message rides on, if the app put one in the data map. */
    private fun fcmChannel(msg: FcmMessage): String? =
        msg.data.entries.firstOrNull { it.key.equals("channel", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }

    /**
     * Whether a row paints its action buttons: the hovered one, and the selected one.
     *
     * Selection is the discoverability fix. Hover-only actions are invisible until the pointer is
     * already on them, so the row you are *reading* — the selected one — is the row that has to
     * say out loud what can be done to it.
     */
    fun actionsArmed(index: Int, isSelected: Boolean): Boolean = isSelected || index == hoveredIndex

    /** The `⧉ cURL` button's hit target: the size cell, exactly as painted. */
    fun isInCurlZone(rowWidth: Int, x: Int): Boolean = x in RowGeometry.sizeCell(rowWidth)

    /**
     * The `⇉ flow` button's hit target: the time cell, exactly as painted — the band immediately
     * right of the cURL one, with [RowGeometry.META_GAP] of dead space between them so a click
     * near the boundary can only ever mean one of the two.
     */
    fun isInFlowZone(rowWidth: Int, x: Int): Boolean = x in RowGeometry.timeCell(rowWidth)

    /**
     * Whether an armed row paints the `⇉ flow` button — the click routing asks this so it can
     * only ever fire where something was drawn. Cache-backed; never scans.
     */
    fun paintsFlow(event: LogEvent): Boolean = when (event) {
        // A pending row's duration column is its live timer, which outranks an affordance; a
        // muted row paints no affordances at all.
        is LogEvent.Http -> !event.tx.isPending() && !MutedEndpoints.isMuted(event.tx) && canGroup(event)
        // Same rule for a running worker, whose time cell is its count-up.
        is LogEvent.Worker -> !event.work.state.equals(WorkerEvent.STATE_RUNNING, true) && canGroup(event)
        else -> canGroup(event)
    }

    /**
     * Whether an armed row paints the `⧉ cURL` button. HTTP only, and not while in flight: a
     * pending row's size cell is empty, and a button you can click but can't see is the bug the
     * painted buttons exist to remove.
     */
    fun paintsCurl(event: LogEvent): Boolean =
        event is LogEvent.Http && !event.tx.isPending() && !MutedEndpoints.isMuted(event.tx)

    /** A collapsed row paints no action buttons at all — see [httpRow]. */
    fun paintsCurl(row: RowCollapse.Row): Boolean = !row.isGroup && paintsCurl(row.lead)

    /** A collapsed row paints no action buttons at all — see [httpRow]. */
    fun paintsFlow(row: RowCollapse.Row): Boolean = !row.isGroup && paintsFlow(row.lead)

    private fun canGroup(event: LogEvent): Boolean =
        groupingProvider(event) || !event.traceId.isNullOrBlank()

    /** The glyph gutter every non-HTTP row starts with: icon cell, then the content-edge gap. */
    private fun gutter(icon: JLabel): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(icon)
        add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.GAP)))
    }

    /** The right-hand meta pair — fact cell, [RowGeometry.META_GAP], time cell — for any row. */
    private fun metaPair(fact: MetaCell, time: MetaCell): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(fact)
        add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.META_GAP)))
        add(time)
    }

    /** An 8px lead-in for a trailing pill, hidden with the pill so it costs nothing when absent. */
    private fun pillGap(): Component = Box.createHorizontalStrut(JBUI.scale(RowGeometry.PILL_GAP))

    private fun <T : JLabel> T.glyphCell(): T = fixed(RowGeometry.GLYPH, CELL_H)

    /** Fixes a label to a logical-px cell, scaling here so call sites read as the spec's numbers. */
    private fun <T : JLabel> T.fixed(w: Int, h: Int): T = apply {
        val d = Dimension(JBUI.scale(w), JBUI.scale(h))
        preferredSize = d; minimumSize = d; maximumSize = d
    }

    private companion object {
        /** The action label for "open this row's flow", paired with [CURL] beside it. */
        const val FLOW = "⇉ flow"

        /** The action label for "copy this request as a cURL command". */
        const val CURL = "⧉ cURL"

        /** Every fixed cell is this tall; the row's own height (34 / 26) centres it. */
        const val CELL_H = 20

        // Built once: a renderer is a rubber stamp, so deriving a font per paint would be one
        // allocation per visible row per 150ms tick.
        val LABEL_12_5: Font = JBUI.Fonts.label(12.5f)
        val LABEL_12_5_BOLD: Font = JBUI.Fonts.label(12.5f).asBold()
        val MONO_10: Font = JBUI.Fonts.create("JetBrains Mono", 10)
        val MONO_10_BOLD: Font = JBUI.Fonts.create("JetBrains Mono", 10).asBold()
        val MONO_11: Font = JBUI.Fonts.create("JetBrains Mono", 11)
        val MONO_11_BOLD: Font = JBUI.Fonts.create("JetBrains Mono", 11).asBold()
    }

    /**
     * The worker state token: `✓ succeeded`, `● running`, `cancelled`…
     *
     * Shape does the work colour is not allowed to do. Strikethrough is **painted**, not derived as
     * a font attribute, because a struck-through face changes advance widths and a row that
     * repaints every 250ms would visibly twitch against its neighbours. The running dot is painted
     * for the same class of reason: it pulses, and a pulsing glyph inside the string would drag the
     * word with it.
     */
    private class StateLabel : JLabel() {

        private var struck = false
        private var pulsing = false

        init { horizontalAlignment = SwingConstants.LEFT }

        fun render(token: RowContent.StateToken) {
            pulsing = token.decoration == RowContent.Decoration.PULSING_DOT
            struck = token.decoration == RowContent.Decoration.STRIKETHROUGH
            // The dot is painted, so it comes out of the string and its space is reserved instead.
            text = if (pulsing) token.text.removePrefix("● ") else token.text
            border = if (pulsing) JBUI.Borders.emptyLeft(DOT_CELL) else JBUI.Borders.empty()
            font = if (token.weight == RowContent.Weight.BOLD) MONO_11_BOLD else MONO_11
            foreground = colorOf(token.token)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (pulsing) {
                val d = JBUI.scale(6)
                val base = foreground
                // A cosine ease sampled on the **wall clock**, not on the repaint counter: a
                // renderer is a rubber stamp with no timer of its own, and the list repaints at a
                // rate that jitters, so a frame-counted curve would breathe unevenly. Period and
                // range match the ones already in the UI — the waterfall's open spans and
                // StatusDot's 0.45→1 alpha — so every "still going" signal breathes alike.
                val period = WaterfallPresentation.PULSE_PERIOD_MILLIS
                val phase = 0.5 - 0.5 * cos(2 * Math.PI * (System.currentTimeMillis() % period) / period)
                val alpha = (DOT_ALPHA_MIN + (255 - DOT_ALPHA_MIN) * phase).toInt().coerceIn(0, 255)
                g2.color = Color(base.red, base.green, base.blue, alpha)
                g2.fillOval(JBUI.scale(1), (height - d) / 2, d, d)
            }
            g2.dispose()
            super.paintComponent(g)
            if (struck) {
                val g3 = g.create() as Graphics2D
                val fm = getFontMetrics(font)
                val x = insets.left
                val w = fm.stringWidth(text)
                val y = (height + fm.ascent - fm.descent) / 2 - fm.ascent / 3
                g3.color = foreground
                g3.fillRect(x, y, w, JBUI.scale(1))
                g3.dispose()
            }
        }

        private companion object {
            /** Dot diameter plus its gap — the space the painted dot occupies in the text run. */
            const val DOT_CELL = 10

            /** The trough of the breath — [io.github.siddharthjaswal.logpose.ui.StatusDot]'s 0.45. */
            const val DOT_ALPHA_MIN = 115
        }
    }

    /**
     * A fixed-width right-hand meta cell that renders either right-aligned text or a painted
     * action button.
     *
     * The button **is** the cell — same width, showing or not — which is what makes the hit test
     * ([RowGeometry.sizeCell] / [RowGeometry.timeCell]) able to be the cell rectangle itself, and
     * what stops the meta columns from shifting as the pointer crosses a row. (The spec draws the
     * buttons 2px wider than their cells; matching the cells instead is the one deliberate
     * deviation, and it buys a timeline that doesn't twitch.)
     *
     * Its width is settable because one instance serves every generic kind and analytics wants a
     * wider fact column ([RowGeometry.fact]). Only the *left* cell of the meta pair varies — the
     * time cell is 56px for every kind — so timestamps still line up down the list.
     *
     * Painting is one rounded rect and one stroke. Nothing is measured, so a button costs a
     * repaint exactly what the label swap it replaces cost.
     */
    private class MetaCell(width: Int) : JLabel("", SwingConstants.RIGHT) {

        private var button = false
        private var cellW = width
        private var size = Dimension(JBUI.scale(width), JBUI.scale(CELL_H))

        init { font = META_FONT }

        // Answered from a field rather than an assigned `preferredSize`: an explicitly *set*
        // preferred size wins over any later resize, and CellRendererPane validates the tree it is
        // handed, so a stale cached value would show up only while scrolling a mixed capture — the
        // hardest kind of layout bug to see in a screenshot.
        override fun getPreferredSize(): Dimension = size
        override fun getMinimumSize(): Dimension = size
        override fun getMaximumSize(): Dimension = size

        /** Resizes the cell, invalidating only when the width actually changed. */
        fun cellWidth(w: Int) {
            if (w == cellW) return
            cellW = w
            size = Dimension(JBUI.scale(w), JBUI.scale(CELL_H))
            invalidate()
        }

        /** Renders as plain right-aligned meta text. */
        fun meta(value: String, fg: Color) {
            button = false
            horizontalAlignment = SwingConstants.RIGHT
            font = META_FONT
            this.text = value
            foreground = fg
        }

        /**
         * Renders as a bordered button filling the cell.
         *
         * The label is `text`, not `accent`: mono 11 bold accent on the `bg1` plate reads at
         * 4.28:1 light / 3.45:1 dark, under the 4.5:1 bar in both themes. The accent moved to the
         * **stroke**, where 3:1 is the bar and it clears it against both the plate and the row
         * behind it — which also fixes the other half of the problem, a `borderStrong` outline
         * barely visible against the row it sits on.
         */
        fun action(label: String) {
            button = true
            horizontalAlignment = SwingConstants.CENTER
            font = ACTION_FONT
            this.text = label
            foreground = Theme.text
        }

        override fun paintComponent(g: Graphics) {
            if (button) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                val inset = JBUI.scale(1)
                val h = height - 2 * inset
                g2.color = Theme.bg1
                g2.fillRoundRect(0, inset, width, h, arc, arc)
                g2.color = Theme.accent
                g2.drawRoundRect(0, inset, width - 1, h - 1, arc, arc)
                g2.dispose()
            }
            super.paintComponent(g)
        }

        private companion object {
            val META_FONT = JBUI.Fonts.create("JetBrains Mono", 11)
            val ACTION_FONT = JBUI.Fonts.create("JetBrains Mono", 11).asBold()
        }
    }

    private class RowPanel : JPanel(BorderLayout()) {
        var selected = false
        var hovered = false
        var rowHeight = 34

        init { isOpaque = false }

        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width, JBUI.scale(rowHeight))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val m = JBUI.scale(6)
            val w = width - 2 * m
            val h = height - JBUI.scale(2)
            val y = JBUI.scale(1)
            when {
                selected -> {
                    g2.color = Theme.accentTint
                    g2.fillRoundRect(m, y, w, h, 8, 8)
                    // The rail is a marker, not an edge band: 4px in from the plate's top and
                    // bottom, so two adjacent selected rows read as two selections rather than
                    // one continuous stripe down the list.
                    val inset = JBUI.scale(4)
                    val arc = JBUI.scale(2)
                    g2.color = Theme.accent
                    g2.fillRoundRect(m, y + inset, JBUI.scale(2), h - 2 * inset, arc, arc)
                }
                hovered -> {
                    g2.color = Theme.rowHover
                    g2.fillRoundRect(m, y, w, h, 8, 8)
                }
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
}

/** Maps a [RowContent.Token] onto the `Theme` token it names. No new hues, by construction. */
private fun colorOf(token: RowContent.Token): Color = when (token) {
    RowContent.Token.TEXT -> Theme.text
    RowContent.Token.TEXT_DIM -> Theme.textDim
    RowContent.Token.TEXT_MUTED -> Theme.textMuted
    RowContent.Token.ACCENT -> Theme.accent
    RowContent.Token.DANGER -> Theme.danger
    RowContent.Token.WARN -> Theme.warn
}
