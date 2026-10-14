package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * The "hero" overview card: a header row ("Overview" + copy-JSON icon), then the duplicate banner,
 * the `MOCK` intervention pill, the semantic status pill and the weighted (never hued) method, the
 * full URL in mono, a row of stat chips, and the primary copy actions.
 */
class OverviewPanel : CardPanel(null) {

    /**
     * Which call of a collapsed polling run is on screen — `3 / 30`, 1-based.
     *
     * The row shows one line for a run of identical calls, with a `~median` duration; this is how
     * the card says which of them it is actually describing. Absent (null) for every ordinary row,
     * and then the stepper is not merely disabled but invisible, so a single-occurrence hero lays
     * out exactly as it did before the stepper existed.
     */
    data class Occurrence(val index: Int, val total: Int)

    var onCopyCurl: () -> Unit = {}
    var onCopyJson: () -> Unit = {}

    /**
     * Walks the shown occurrence by ±1. The owner ([TransactionDetailView]) re-renders the whole
     * card, not just this hero: the Request and Response JSON belong to the occurrence too.
     *
     * Stepping changes **which response you are looking at** and nothing else — not the list
     * selection (the other occurrences have no row of their own to move to) and not the waterfall.
     */
    var onStep: (Int) -> Unit = {}

    /**
     * Opens the trace waterfall. Only the **LogPose** trace chip is a link: a server-side
     * `x-request-id` names a request in someone else's system, and LogPose has nothing to show
     * for it.
     */
    var onOpenTrace: (String) -> Unit = {}

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")

    private val statusPill = TagLabel().apply { font = JBUI.Fonts.label(13f).asBold() }

    /**
     * `MOCK`, as its own solid-[Theme.intervention] pill in front of the status.
     *
     * It used to be a prefix — `MOCK · 200 OK` inside the neutral status pill — which made the one
     * thing LogPose itself did to this response look like part of what the server said. The two are
     * different facts and they get different pills: the status keeps its semantic colour, and the
     * intervention keeps the solid accent fill it wears everywhere else in the UI.
     */
    private val mockPill = TagLabel().apply {
        font = JBUI.Fonts.label(10f).asBold()
        border = JBUI.Borders.empty(2, 7)
        isVisible = false
    }
    private val mockGap = Box.createHorizontalStrut(JBUI.scale(8)).apply { isVisible = false }
    private val methodFontRegular = JBUI.Fonts.label(12f)
    private val methodFontBold = JBUI.Fonts.label(12f).asBold()
    private val methodPill = TagLabel().apply { font = methodFontBold }
    private val dupBanner = JBLabel().apply {
        font = JBUI.Fonts.label(11.5f).asBold()
        border = JBUI.Borders.empty(5, 10)
        isOpaque = false
    }
    private lateinit var dupRow: JPanel
    private var setBannerFill: (java.awt.Color?) -> Unit = {}
    private val url = JBTextArea(2, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = false
        foreground = Theme.jsonString
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        border = JBUI.Borders.empty(2, 0)
    }
    // Shown only for failed calls (no response): the exception text from the interceptor.
    private val errorText = JBTextArea(1, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = false
        foreground = Theme.danger
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        border = JBUI.Borders.empty(2, 0)
    }
    private lateinit var errorRow: JPanel
    private val chips = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }

    // ---- occurrence stepper ------------------------------------------------------------------
    // Sits at the EAST end of the pill row, which was empty: the stepper is a property of *which
    // response you are looking at*, and the pills are what it changes. The chips row below reflows
    // as its contents change, so it is the wrong neighbour for a control that must hold still.
    private val prevOccurrence = IconButton(AllIcons.Actions.PreviousOccurence, "Previous occurrence") { onStep(-1) }
    private val nextOccurrence = IconButton(AllIcons.Actions.NextOccurence, "Next occurrence") { onStep(+1) }
    private val occurrenceCaption = JBLabel("occurrence").apply {
        foreground = Theme.textMuted; font = JBUI.Fonts.label(11f)
    }
    private val occurrenceCount = JBLabel().apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.create("JetBrains Mono", 11)
        border = JBUI.Borders.empty(0, 2)
    }
    private val stepper = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(occurrenceCaption)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(prevOccurrence)
        add(occurrenceCount)
        add(nextOccurrence)
        // BorderLayout skips invisible children, so a hidden stepper costs the pill row no height.
        isVisible = false
    }

    private lateinit var headerComp: Component

    private var pending = false
    private var liveDurationChip: StatChip? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0)

        headerComp = header()
        add(headerComp)
        add(body())
        show(null)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height; val a = JBUI.scale(10)
        g2.color = Theme.bg1
        g2.fillRoundRect(0, 0, w - 1, h - 1, a, a)
        val hh = if (::headerComp.isInitialized) headerComp.height else 0
        if (hh > 0) {
            val clip = g2.clip
            g2.clip(RoundRectangle2D.Float(0f, 0f, (w - 1).toFloat(), (h - 1).toFloat(), a.toFloat(), a.toFloat()))
            g2.color = Theme.bg2
            g2.fillRect(0, 0, w, hh)
            g2.clip = clip
            g2.color = Theme.borderStrong
            g2.drawLine(0, hh - 1, w - 1, hh - 1)
        }
        g2.color = Theme.borderSubtle
        g2.drawRoundRect(0, 0, w - 1, h - 1, a, a)
        g2.dispose()
    }

    private fun header(): Component {
        val title = JBLabel("Overview").apply {
            foreground = Theme.text; font = JBUI.Fonts.label(13f).asBold()
        }
        val copy = IconButton(AllIcons.Actions.Copy, "Copy transaction JSON") { onCopyJson() }
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 14)
            add(title, BorderLayout.WEST)
            add(copy, BorderLayout.EAST)
        }
    }

    private fun body(): Component {
        val curlBtn = PillButton("</>  Copy as cURL", filled = true).apply {
            addActionListener { onCopyCurl() }
        }
        val jsonBtn = PillButton("Copy JSON", filled = false).apply {
            icon = AllIcons.Actions.Copy
            iconTextGap = JBUI.scale(6)
            addActionListener { onCopyJson() }
        }

        // A tinted, rounded warning strip shown only when the selected call is a duplicate.
        var bannerFill: java.awt.Color? = null
        val bannerHolder = object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            override fun paintComponent(g: Graphics) {
                bannerFill?.let {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = it
                    g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(dupBanner, BorderLayout.WEST)
        }
        dupRow = bannerHolder
        setBannerFill = { bannerFill = it }

        val inner = JPanel().apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 14)

            add(bannerHolder)
            add(vGap(8))
            add(
                pillRow(
                    hbox(mockPill, mockGap, statusPill, Box.createHorizontalStrut(JBUI.scale(8)), methodPill),
                    stepper,
                ),
            )
            add(vGap(8))
            add(row(url, fill = true))
            errorRow = row(errorText, fill = true)
            add(errorRow)
            add(vGap(8))
            add(row(chips, fill = false))
            add(vGap(12))
            add(row(hbox(curlBtn, Box.createHorizontalStrut(JBUI.scale(10)), jsonBtn), fill = false))
            add(Box.createVerticalGlue())
        }
        return inner
    }

    /**
     * Shows [tx]. [envelope] is the transport it arrived in — the only carrier of the LogPose
     * trace id, which the transaction payload itself doesn't hold. [occurrence] is set only when
     * the selected row stands for a collapsed run of calls, and is what puts the stepper on screen.
     */
    fun show(
        tx: Transaction?,
        dup: DuplicateDetector.Mark? = null,
        envelope: io.github.siddharthjaswal.logpose.model.Envelope? = null,
        occurrence: Occurrence? = null,
        /** Set when the call can never finish because its app run ended — see [LogEvent.abandoned]. */
        abandoned: String? = null,
    ) {
        applyDuplicate(tx, dup)
        if (tx != null && abandoned != null && dup == null) applyAbandoned(abandoned)
        applyError(tx)
        applyOccurrence(if (tx == null) null else occurrence)
        if (tx == null) {
            showMock(false)
            statusPill.set("—", Theme.statusNeutral, Theme.statusNeutralBg)
            methodPill.set("", Theme.textDim, null)
            url.text = "Select a request"
            chips.removeAll(); chips.revalidate(); chips.repaint()
            return
        }

        pending = tx.isPending() && abandoned == null
        val code = tx.response?.code
        // The intervention is stated once, by its own pill, whether the call is finished or still
        // in flight — a mocked response that hasn't landed yet is still a mocked response.
        showMock(tx.mocked)
        if (abandoned != null && tx.isPending()) {
            // Neutral, not a status colour: nothing failed on the device, it just can't finish.
            statusPill.set(DetailContent.statusLabel(tx, abandoned), Theme.textDim, Theme.statusNeutralBg)
        } else if (pending) {
            statusPill.set("${spinnerChar(0)}  pending", Theme.accent, Theme.tint(Theme.accent, 30))
        } else {
            val sColor = Theme.statusColor(code, tx.error)
            statusPill.set(DetailContent.statusLabel(tx), sColor, Theme.statusTint(code, tx.error))
        }
        // Method carries no hue — read vs write is weight, so a POST looks heavier than a GET
        // without borrowing a colour that status already means something with.
        methodPill.font = if (Theme.isRead(tx.request.method)) methodFontRegular else methodFontBold
        methodPill.set(tx.request.method, Theme.methodTextColor(tx.request.method), Theme.bg2)

        url.text = tx.request.url

        chips.removeAll()
        liveDurationChip = null
        // Which chips, in which order, saying what: :core's (DetailContent), shared with the Mac
        // app. Prefer a server trace/request id (useful for backend log lookup) over the internal
        // LogPose correlation id; the LogPose trace, when the app set one, is a link, because it's
        // the flow this call belongs to and not just another identifier.
        val items = DetailContent.httpChips(tx, envelope, abandoned?.takeIf { tx.isPending() }).map { chip ->
            val flow = chip.traceLink
            when {
                flow != null -> StatChip(chip.caption, chip.value, chip.tip)
                    .clickable("LogPose trace $flow — open the waterfall for this flow") { onOpenTrace(flow) }
                pending && chip.caption == DetailContent.CAPTION_DURATION ->
                    StatChip(chip.caption, chip.value, chip.tip).also { liveDurationChip = it }
                else -> StatChip(chip.caption, chip.value, chip.tip)
            }
        }
        items.forEachIndexed { i, c ->
            if (i > 0) chips.add(Box.createHorizontalStrut(JBUI.scale(8)))
            chips.add(c)
        }
        chips.revalidate(); chips.repaint()
    }

    /**
     * Shows or hides the `occurrence ‹ n / N ›` stepper.
     *
     * The arrows disable rather than wrap at the ends: a run is a stretch of time, and stepping off
     * the newest call back to the oldest would say something about the order that isn't true.
     */
    private fun applyOccurrence(occurrence: Occurrence?) {
        if (occurrence == null || occurrence.total <= 1) {
            stepper.isVisible = false
            return
        }
        occurrenceCount.text = "${occurrence.index} / ${occurrence.total}"
        prevOccurrence.isEnabled = occurrence.index > 1
        nextOccurrence.isEnabled = occurrence.index < occurrence.total
        occurrenceCount.toolTipText =
            "Call ${occurrence.index} of ${occurrence.total} folded into this row (matching the current filter)"
        stepper.isVisible = true
        stepper.revalidate()
        stepper.repaint()
    }

    /** Shows or hides the `MOCK` pill and the gap that only exists to separate it from the status. */
    private fun showMock(mocked: Boolean) {
        if (mocked) mockPill.set("MOCK", Theme.onAccent, Theme.intervention) else mockPill.set("", Theme.text, null)
        mockPill.isVisible = mocked
        mockGap.isVisible = mocked
    }

    /** Live update while a request is in flight: ticking duration + spinning status. */
    fun tick(elapsedMs: Long, frame: Int) {
        if (!pending) return
        liveDurationChip?.value("$elapsedMs ms")
        statusPill.set("${spinnerChar(frame)}  pending", Theme.accent, Theme.tint(Theme.accent, 30))
    }

    /** Shows the exception text for a failed call (no response), hidden otherwise. */
    private fun applyError(tx: Transaction?) {
        if (!::errorRow.isInitialized) return
        val err = tx?.error?.takeIf { tx.response == null }
        if (err == null) {
            errorRow.isVisible = false
            return
        }
        errorText.text = err
        errorRow.isVisible = true
    }

    /** Re-uses the strip for an abandoned call, in muted words (it is neither advice nor failure). */
    private fun applyAbandoned(reason: String) {
        if (!::dupRow.isInitialized) return
        dupBanner.foreground = Theme.textDim
        dupBanner.text = DetailContent.abandonedBanner(reason)
        setBannerFill(Theme.tint(Theme.textDim, 22))
        dupRow.isVisible = true
    }

    /** Shows/hides the duplicate warning strip and styles it by severity. */
    private fun applyDuplicate(tx: Transaction?, dup: DuplicateDetector.Mark?) {
        if (!::dupRow.isInitialized) return
        if (tx == null || dup == null) {
            dupRow.isVisible = false
            return
        }
        val (fg, tint) = when (dup.severity) {
            DuplicateDetector.Severity.STRONG -> Theme.danger to Theme.dangerTint
            DuplicateDetector.Severity.MEDIUM -> Theme.warn to Theme.warnTint
            DuplicateDetector.Severity.INFO -> Theme.textDim to Theme.tint(Theme.textDim, 22)
        }
        dupBanner.foreground = fg
        // The words are :core's (shared with the Mac app); the severity colours are ours.
        dupBanner.text = DetailContent.duplicateBanner(tx, dup).text
        setBannerFill(tint)
        dupRow.isVisible = true
    }

    private fun ellipsize(s: String, max: Int = 14): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /** The status/method pills at the west end, the occurrence stepper (when there is one) at the east. */
    private fun pillRow(pills: Component, east: Component): JPanel =
        object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(pills, BorderLayout.WEST)
            add(east, BorderLayout.EAST)
            // §3.3's narrow rule applied to the hero: in a cramped detail pane the word
            // "occurrence" goes and the stepper is just `‹ 3 / 30 ›`, so a long status message
            // ("503 Service Unavailable" next to a MOCK pill) never has to fight it for the row.
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    val roomy = width >= JBUI.scale(340)
                    if (occurrenceCaption.isVisible == roomy) return
                    occurrenceCaption.isVisible = roomy
                    stepper.revalidate()
                }
            })
        }

    private fun row(c: Component, fill: Boolean): JPanel =
        object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(c, if (fill) BorderLayout.CENTER else BorderLayout.WEST)
        }

    private fun hbox(vararg comps: Component): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
    }

    private fun vGap(px: Int) = Box.createVerticalStrut(JBUI.scale(px))
}
