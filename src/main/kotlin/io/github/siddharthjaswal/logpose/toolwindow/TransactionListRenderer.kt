package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.KindPresenter
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
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
 * Studio list row for the unified stream. HTTP rows are laid out in FIXED columns so
 * method / status / path align across every row:
 *
 *   `METHOD   [status]   path … …                 size   duration`
 *
 * FCM rows reuse the same column geometry with FCM-specific content:
 *
 *   `FCM      [NOTIF]    title / from … …          n keys  time`
 *
 * Per spec §4, METHOD is plain colored bold text in a fixed column; only the status is a
 * pill. Muted (HTTP) rows are shorter (26px) and faded; hover reveals a cURL affordance.
 */
class TransactionListRenderer : ListCellRenderer<LogEvent> {

    var hoveredIndex: Int = -1

    /** Animation frame for the in-flight spinner; bumped by the panel's timer. */
    var spinnerFrame: Int = 0

    /** Live wall-clock elapsed (ms) for a pending transaction, or null if not pending. */
    var elapsedProvider: (Transaction) -> Long? = { null }

    /** Duplicate-burst mark for a transaction, or null if it isn't a repeated call. */
    var duplicateProvider: (Transaction) -> DuplicateDetector.Mark? = { null }

    private val timeFmt = SimpleDateFormat("HH:mm:ss")

    // ---- HTTP row -------------------------------------------------------------------------
    private val httpIcon = JLabel(TypeIcons.forKind(Envelope.KIND_HTTP)).fixed(JBUI.scale(18), JBUI.scale(20))
    private val methodLabel = JLabel("", SwingConstants.LEFT).fixed(JBUI.scale(46), JBUI.scale(20))
    private val statusTag = TagLabel().fixed(JBUI.scale(46), JBUI.scale(20))
    private val path = JLabel()
    private val dupTag = TagLabel()
    private val mockTag = TagLabel()
    private val sizeLabel = JLabel("", SwingConstants.RIGHT)
    private val duration = JLabel("", SwingConstants.RIGHT)

    private val row = RowPanel().apply {
        border = JBUI.Borders.empty(0, 14)

        methodLabel.font = JBUI.Fonts.label(11f).asBold()
        statusTag.font = JBUI.Fonts.label(11f).asBold()

        val badges = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(httpIcon)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(methodLabel)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(statusTag)
        }
        path.border = JBUI.Borders.emptyLeft(12)
        path.font = JBUI.Fonts.label(12.5f)
        dupTag.font = JBUI.Fonts.label(10f).asBold()
        dupTag.border = JBUI.Borders.empty(2, 7)
        mockTag.font = JBUI.Fonts.label(10f).asBold()
        mockTag.border = JBUI.Borders.empty(2, 7)
        sizeLabel.font = JBUI.Fonts.create("JetBrains Mono", 11)
        duration.font = JBUI.Fonts.create("JetBrains Mono", 11)

        // The path fills the centre; the MOCK / duplicate pills (when present) tuck in at its
        // right end, just before the size/duration meta — so path-start alignment is never
        // disturbed.
        val tags = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(mockTag)
            add(dupTag)
        }
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(path, BorderLayout.CENTER)
            add(tags, BorderLayout.EAST)
        }

        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sizeLabel.fixed(JBUI.scale(64), JBUI.scale(20)))
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(duration.fixed(JBUI.scale(56), JBUI.scale(20)))
        }

        add(badges, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(meta, BorderLayout.EAST)
    }

    // ---- FCM row --------------------------------------------------------------------------
    private val fcmIcon = JLabel(TypeIcons.forKind(Envelope.KIND_FCM)).fixed(JBUI.scale(18), JBUI.scale(20))
    private val fcmLabel = JLabel("", SwingConstants.LEFT).fixed(JBUI.scale(46), JBUI.scale(20)) // empty method slot
    private val fcmTag = TagLabel().fixed(JBUI.scale(46), JBUI.scale(20))
    private val fcmText = JLabel()
    private val injTag = TagLabel()
    private val fcmCount = JLabel("", SwingConstants.RIGHT)
    private val fcmTime = JLabel("", SwingConstants.RIGHT)

    private val fcmRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, 14)

        fcmTag.font = JBUI.Fonts.label(10f).asBold()
        injTag.font = JBUI.Fonts.label(10f).asBold()
        injTag.border = JBUI.Borders.empty(2, 7)

        val badges = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(fcmIcon)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(fcmLabel)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(fcmTag)
        }
        fcmText.border = JBUI.Borders.emptyLeft(12)
        fcmText.font = JBUI.Fonts.label(12.5f)
        fcmCount.font = JBUI.Fonts.create("JetBrains Mono", 11)
        fcmTime.font = JBUI.Fonts.create("JetBrains Mono", 11)

        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(fcmCount.fixed(JBUI.scale(64), JBUI.scale(20)))
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(fcmTime.fixed(JBUI.scale(56), JBUI.scale(20)))
        }

        // Same geometry as the HTTP row: the summary fills the centre and the INJ pill tucks in
        // at its right end, so it reads as a property of the row rather than a new column.
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fcmText, BorderLayout.CENTER)
            add(injTag, BorderLayout.EAST)
        }

        add(badges, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(meta, BorderLayout.EAST)
    }

    // ---- Generic row (db / worker / config / analytics / app-defined) ----------------------
    // Icon gutter + empty method/status columns + primary · secondary in the centre, so the type
    // is stated exactly once (the coloured glyph) and the state is a full word in the centre,
    // never a truncated pill. The blank method/status labels keep the centre aligned with HTTP.
    private val genIcon = JLabel().fixed(JBUI.scale(18), JBUI.scale(20))
    private val genMethodPad = JLabel().fixed(JBUI.scale(46), JBUI.scale(20)) // empty, for alignment
    private val genStatusPad = JLabel().fixed(JBUI.scale(46), JBUI.scale(20)) // empty, for alignment
    private val genText = JLabel()
    private val genCount = JLabel("", SwingConstants.RIGHT)
    private val genTime = JLabel("", SwingConstants.RIGHT)

    private val genRow = RowPanel().apply {
        border = JBUI.Borders.empty(0, 14)

        val badges = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(genIcon)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(genMethodPad)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(genStatusPad)
        }
        genText.border = JBUI.Borders.emptyLeft(12)
        genText.font = JBUI.Fonts.label(12.5f)
        genCount.font = JBUI.Fonts.create("JetBrains Mono", 11)
        genTime.font = JBUI.Fonts.create("JetBrains Mono", 11)

        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(genCount.fixed(JBUI.scale(64), JBUI.scale(20)))
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(genTime.fixed(JBUI.scale(56), JBUI.scale(20)))
        }

        add(badges, BorderLayout.WEST)
        add(genText, BorderLayout.CENTER)
        add(meta, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out LogEvent>,
        value: LogEvent,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component = when (value) {
        is LogEvent.Http -> httpRow(value.tx, index, isSelected)
        is LogEvent.Fcm -> fcmRow(value, index, isSelected)
        // Everything else — db, worker, config, and app-defined kinds — shares one row, driven
        // by KindPresenter. Their differences are presentational, so they don't warrant
        // separate layouts; the column geometry stays identical to HTTP and FCM.
        else -> structuredRow(value, index, isSelected)
    }

    /**
     * Row for every structured / app-defined kind: the kind takes the method column, the first
     * badge takes the status column, and title · subtitle fill the centre — so a database query
     * or a worker lines up with HTTP instead of looking bolted on.
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
        // text — not a "+1" count, which tells you nothing.
        genCount.text = badges.getOrNull(1)?.text.orEmpty()
        genCount.foreground = Theme.textDim

        genTime.text = when {
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
        genTime.foreground = Theme.textDim
        return genRow
    }

    /** Workers run for minutes; "94210ms" in a 56px column is unreadable. */
    private fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        millis < 60_000 -> String.format("%.1fs", millis / 1000.0)
        else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
    }

    private fun httpRow(value: Transaction, index: Int, isSelected: Boolean): Component {
        val muted = MutedEndpoints.isMuted(value)
        val hovered = index == hoveredIndex
        row.selected = isSelected
        row.hovered = hovered && !isSelected
        row.rowHeight = if (muted) 26 else 34

        fun shade(c: Color): Color = if (!muted) c else Theme.fade(c, if (hovered) 0.7f else 0.34f)

        val mColor = Theme.methodColor(value.request.method)
        methodLabel.text = value.request.method
        methodLabel.foreground = shade(mColor)

        val pending = value.isPending()
        val code = value.response?.code
        if (pending) {
            statusTag.set(spinnerChar(spinnerFrame).toString(), Theme.accent, Theme.tint(Theme.accent, 22))
        } else {
            val sColor = Theme.statusColor(code, value.error)
            val sBg = if (muted) Theme.tint(sColor, 14) else Theme.statusTint(code, value.error)
            statusTag.set(value.statusText(), shade(sColor), sBg)
        }

        path.text = value.request.path.ifBlank { value.request.url }
        path.foreground = shade(Theme.text)

        if (value.mocked) {
            val mColorMock = Theme.methodColor("PATCH")
            mockTag.isVisible = true
            mockTag.set("MOCK", shade(mColorMock), if (muted) Theme.tint(mColorMock, 14) else Theme.tint(mColorMock, 30))
        } else {
            mockTag.isVisible = false
            mockTag.set("", Theme.text, null)
        }

        val dup = duplicateProvider(value)
        if (dup != null) {
            val fg = when (dup.severity) {
                DuplicateDetector.Severity.STRONG -> Theme.danger
                DuplicateDetector.Severity.MEDIUM -> Theme.warn
                DuplicateDetector.Severity.INFO -> Theme.textDim
            }
            val bg = when (dup.severity) {
                DuplicateDetector.Severity.STRONG -> Theme.dangerTint
                DuplicateDetector.Severity.MEDIUM -> Theme.warnTint
                DuplicateDetector.Severity.INFO -> Theme.tint(Theme.textDim, 22)
            }
            dupTag.isVisible = true
            dupTag.set("DUP ×${dup.ordinal}", shade(fg), if (muted) Theme.tint(fg, 14) else bg)
        } else {
            dupTag.isVisible = false
            dupTag.set("", Theme.text, null)
        }

        when {
            pending -> {
                sizeLabel.text = ""
                duration.text = elapsedProvider(value)?.let { "${it}ms" } ?: "…"
                duration.foreground = Theme.accent
            }
            hovered && !muted -> {
                sizeLabel.text = "⧉ cURL"
                sizeLabel.foreground = Theme.accent
                duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
                duration.foreground = shade(Theme.textMuted)
            }
            else -> {
                sizeLabel.text = value.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { Theme.humanSize(it) } ?: ""
                sizeLabel.foreground = shade(Theme.textMuted)
                duration.text = value.durationMillis?.let { "${it}ms" } ?: ""
                duration.foreground = shade(Theme.textMuted)
            }
        }

        return row
    }

    private fun fcmRow(event: LogEvent.Fcm, index: Int, isSelected: Boolean): Component {
        val msg = event.msg
        fcmRow.selected = isSelected
        fcmRow.hovered = index == hoveredIndex && !isSelected
        fcmRow.rowHeight = 34

        val kind = fcmKind(msg)
        val kColor = when (kind) {
            "TOKEN" -> Theme.accent
            "NOTIF" -> Theme.methodColor("PATCH")
            else -> Theme.textDim // DATA
        }
        fcmTag.set(kind, kColor, Theme.tint(kColor, 22))

        fcmText.text = fcmSummary(msg)
        fcmText.foreground = Theme.text

        // A push LogPose itself delivered is marked, always — the same trust rule as the purple
        // MOCK pill, in the FCM hue: the timeline never passes an injected push off as a real one.
        if (msg.injected) {
            val c = Theme.typeColor(Envelope.KIND_FCM)
            injTag.isVisible = true
            injTag.set("INJ", c, Theme.tint(c, 30))
        } else {
            injTag.isVisible = false
            injTag.set("", Theme.text, null)
        }

        // "size" column: number of data keys (data messages carry the payload of interest).
        fcmCount.text = msg.data.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "key" else "keys"}" } ?: ""
        fcmCount.foreground = Theme.textMuted

        // An injected push has no device receive-time of its own, so fall back to the envelope's
        // clock rather than leaving the column blank.
        val at = msg.receivedAtMillis.takeIf { it > 0 } ?: event.timestampMillis
        fcmTime.text = at.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) } ?: ""
        fcmTime.foreground = Theme.textMuted

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

    /** The "⧉ cURL" affordance occupies the size column; match that band, not the whole right edge. */
    fun isInCurlZone(rowWidth: Int, x: Int): Boolean =
        x in (rowWidth - JBUI.scale(146))..(rowWidth - JBUI.scale(74))

    private fun <T : JLabel> T.fixed(w: Int, h: Int): T = apply {
        val d = Dimension(w, h); preferredSize = d; minimumSize = d; maximumSize = d
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
                    g2.color = Theme.accent
                    g2.fillRoundRect(m, y, JBUI.scale(2), h, 2, 2)
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
