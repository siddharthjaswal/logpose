package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.KindPresenter
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.RowGeometry
import io.github.siddharthjaswal.logpose.ui.TagLabel
import io.github.siddharthjaswal.logpose.ui.TypeIcons
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.isPending
import io.github.siddharthjaswal.logpose.ui.spinnerChar
import io.github.siddharthjaswal.logpose.ui.statusText
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.SimpleDateFormat
import java.util.Date
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
 * FCM and generic rows do not. They used to reserve two empty 46px columns purely to line up with
 * HTTP, which spent 92px of a 400px panel saying nothing; now their text starts at the content
 * edge and the FCM kind tag leads it:
 *
 *   `[NOTIF] title … …                            n keys  time`
 *
 * METHOD is plain text carrying **no hue** — reads are regular `methodRead`, writes bold
 * `methodWrite` — so the only coloured pills in a row are the status (semantic) and the
 * solid-accent MOCK/INJ marks (LogPose's own interventions). Muted (HTTP) rows are shorter (26px)
 * and faded.
 *
 * The two right-hand cells double as **painted action buttons** (`⧉ cURL`, `⇉ flow`) on the
 * hovered row *and* on the selected one — the invisible pixel bands they replace were only ever
 * discoverable by accident. They are drawn at the cells' own widths, so a column never shifts as
 * the pointer crosses a row.
 */
class TransactionListRenderer : ListCellRenderer<LogEvent> {

    var hoveredIndex: Int = -1

    /** Animation frame for the in-flight spinner; bumped by the panel's timer. */
    var spinnerFrame: Int = 0

    /** Live wall-clock elapsed (ms) for a pending transaction, or null if not pending. */
    var elapsedProvider: (Transaction) -> Long? = { null }

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
        dupTag.font = JBUI.Fonts.label(10f).asBold()
        dupTag.border = JBUI.Borders.empty(2, 7)
        mockTag.font = JBUI.Fonts.label(10f).asBold()
        mockTag.border = JBUI.Borders.empty(2, 7)

        // The path fills the centre; the MOCK / duplicate pills (when present) tuck in at its
        // right end, just before the size/duration meta — so path-start alignment is never
        // disturbed. Each pill's 8px lead-in is a strut that hides with it, so a row carrying no
        // pills gives the path back every pixel.
        val tags = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
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

        val gutter = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(fcmIcon)
            add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.GAP)))
        }
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

        add(gutter, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(metaPair(fcmCount, fcmTime), BorderLayout.EAST)
    }

    // ---- Generic row (db / worker / config / analytics / app-defined) ----------------------
    // Icon gutter, then `title · subtitle` straight at the content edge, so the type is stated
    // exactly once (the coloured glyph) and the state is a full word, never a truncated pill.
    private val genIcon = JLabel().glyphCell()
    private val genText = JLabel()
    private val genCount = MetaCell(RowGeometry.FACT)
    private val genTime = MetaCell(RowGeometry.TIME)

    private val genRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, RowGeometry.EDGE)

        val gutter = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(genIcon)
            add(Box.createHorizontalStrut(JBUI.scale(RowGeometry.GAP)))
        }
        genText.font = JBUI.Fonts.label(12.5f)

        add(gutter, BorderLayout.WEST)
        add(genText, BorderLayout.CENTER)
        add(metaPair(genCount, genTime), BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out LogEvent>,
        value: LogEvent,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component = when (value) {
        is LogEvent.Http -> httpRow(value, index, isSelected)
        is LogEvent.Fcm -> fcmRow(value, index, isSelected)
        // Everything else — db, worker, config, and app-defined kinds — shares one row, driven
        // by KindPresenter. Their differences are presentational, so they don't warrant
        // separate layouts; both alignment rules stay identical to HTTP and FCM.
        else -> structuredRow(value, index, isSelected)
    }

    /**
     * Row for every structured / app-defined kind: glyph, then `title · subtitle` at the content
     * edge, then a full-word fact in a column wide enough to hold one.
     */
    private fun structuredRow(value: LogEvent, index: Int, isSelected: Boolean): Component {
        genRow.selected = isSelected
        genRow.hovered = index == hoveredIndex && !isSelected
        genRow.rowHeight = 34

        val presentation = KindPresenter.present(value)

        // The type is the coloured gutter glyph — stated once, never as text and never as a
        // truncated pill.
        genIcon.icon = TypeIcons.forEvent(value)

        // Primary · secondary: the row's own identifier, then the state / one telling fact — the
        // state as a full word (`succeeded`, `running`), the bug this replaces was `SUCC…`.
        val badges = presentation?.badges.orEmpty()
        val title = presentation?.title ?: value.kind
        val secondary = presentation?.subtitle?.takeIf { it.isNotBlank() }
            ?: badges.firstOrNull()?.text
        genText.text = if (secondary != null) "$title  ·  $secondary" else title
        genText.foreground = Theme.text

        // Where HTTP shows body size: a second telling fact (a retry attempt, a row count), full
        // text — 120px, because `hyperlocal_feature_db` is the answer and `+1` is not.
        genCount.meta(badges.getOrNull(1)?.text.orEmpty(), Theme.textMuted)

        if (actionsArmed(index, isSelected) && canGroup(value)) {
            genTime.action(FLOW)
            return genRow
        }
        val time = when {
            // A worker that's still running is genuinely open and the state badge already says
            // so, so a spinner would be redundant here too.
            value.durationMillis != null -> formatDuration(value.durationMillis!!)
            // Deliberately no spinner, unlike HTTP. A foreign producer that never sets endedAt
            // is far more likely than a genuinely long-running span, and a row that spins
            // forever reads as a hang. If the close does arrive, the row updates in place and
            // the duration replaces the timestamp.
            value.timestampMillis > 0 -> timeFmt.format(Date(value.timestampMillis))
            else -> ""
        }
        genTime.meta(time, Theme.textMuted)
        return genRow
    }

    /** Workers run for minutes; "94210ms" in a 56px column is unreadable. */
    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> String.format("%.1fs", millis / 1000.0)
        else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
    }

    private fun httpRow(event: LogEvent.Http, index: Int, isSelected: Boolean): Component {
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

        // MOCK is the one solid-accent fill a row may carry: it says LogPose itself caused this
        // response. Solid (not tinted) keeps it distinct from the 15%-accent selection tint.
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
        val dup = duplicateProvider(value)
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
        // live timer, which outranks both.
        val armed = actionsArmed(index, isSelected)
        when {
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
        else -> canGroup(event)
    }

    /**
     * Whether an armed row paints the `⧉ cURL` button. HTTP only, and not while in flight: a
     * pending row's size cell is empty, and a button you can click but can't see is the bug the
     * painted buttons exist to remove.
     */
    fun paintsCurl(event: LogEvent): Boolean =
        event is LogEvent.Http && !event.tx.isPending() && !MutedEndpoints.isMuted(event.tx)

    private fun canGroup(event: LogEvent): Boolean =
        groupingProvider(event) || !event.traceId.isNullOrBlank()

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
     * Painting is one rounded rect and one stroke. Nothing is measured, so a button costs a
     * repaint exactly what the label swap it replaces cost.
     */
    private class MetaCell(width: Int) : JLabel("", SwingConstants.RIGHT) {

        private var button = false

        init {
            font = META_FONT
            val d = Dimension(JBUI.scale(width), JBUI.scale(CELL_H))
            preferredSize = d; minimumSize = d; maximumSize = d
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
