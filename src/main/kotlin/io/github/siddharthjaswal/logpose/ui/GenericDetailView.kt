package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Detail pane for every kind without a bespoke view — database queries, background work,
 * config changes, and app-defined events.
 *
 * All of them arrive here as a title, subtitle, semantic badges and typed sections: either
 * supplied by the device (a self-describing app event) or derived by [KindPresenter] from a
 * structured payload. One view serves all of them because their differences are presentational,
 * and that is exactly what the presenter already expresses.
 *
 * The sibling of [TransactionDetailView] (HTTP) and [FcmDetailView] (push). Sections render as
 * read-only blocks rather than full editors: a single event can carry many, and spinning up an
 * IntelliJ editor per section on every selection change would be wasteful. The complete payload
 * is still available as a tree/raw [JsonTreePanel] below.
 */
class GenericDetailView(project: Project) : JPanel(BorderLayout()) {

    private val kindPill = TagLabel().apply { font = JBUI.Fonts.label(13f).asBold() }
    private val titleLabel = JBLabel().apply {
        foreground = Theme.text; font = JBUI.Fonts.label(13f).asBold()
    }
    private val subtitle = JBTextArea(2, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
        foreground = Theme.text
        font = JBUI.Fonts.create("JetBrains Mono", 13)
        border = JBUI.Borders.empty(2, 0)
    }
    private val chips = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val badges = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val sections = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(0, 2)
    }
    private val overview = CardPanel(null).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12, 14)
    }
    private val payload = JsonTreePanel("Payload", project)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")
    private val pretty = Json { prettyPrint = true }

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty(8)

        overview.add(row(hbox(kindPill, Box.createHorizontalStrut(JBUI.scale(8)), titleLabel), fill = false))
        overview.add(vGap(8))
        overview.add(row(subtitle, fill = true))
        overview.add(vGap(8))
        overview.add(row(badges, fill = false))
        overview.add(vGap(8))
        overview.add(row(chips, fill = false))
        overview.add(vGap(12))
        overview.add(row(JBScrollPane(sections).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, fill = true))

        val outer = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = pad(overview, 0, 120)
            secondComponent = pad(payload, 160, 60)
            setHonorComponentsMinimumSize(true)
        }
        add(outer, BorderLayout.CENTER)
    }

    fun show(event: LogEvent?) {
        if (event == null) {
            kindPill.set("—", Theme.textDim, Theme.bg2)
            titleLabel.text = ""
            subtitle.text = "Select an event"
            badges.removeAll(); chips.removeAll(); sections.removeAll()
            repaintAll()
            payload.setElement(null); payload.setStatus(null)
            return
        }

        val presentation = KindPresenter.present(event)
        kindPill.set(KindPresenter.kindLabel(event), Theme.accent, Theme.tint(Theme.accent, 30))
        // A payload that isn't self-describing still gets a row and a readable payload tree —
        // it just has nothing but its kind to show up top.
        titleLabel.text = presentation?.title ?: event.kind
        subtitle.text = presentation?.subtitle.orEmpty()

        badges.removeAll()
        presentation?.badges?.forEachIndexed { i, badge ->
            if (i > 0) badges.add(Box.createHorizontalStrut(JBUI.scale(6)))
            badges.add(badgeLabel(badge))
        }

        chips.removeAll()
        val items = buildList {
            if (event.timestampMillis > 0) add(StatChip("at", timeFmt.format(Date(event.timestampMillis))))
            event.durationMillis?.let { add(StatChip("took", "${it}ms")) }
            if (event.isOpen) add(StatChip("state", "running"))
            event.traceId?.let { add(StatChip("trace", it, tip = it)) }
            event.envelope.parentId?.let { add(StatChip("parent", it, tip = it)) }
            add(StatChip("id", event.id, tip = event.id))
        }
        items.forEachIndexed { i, c ->
            if (i > 0) chips.add(Box.createHorizontalStrut(JBUI.scale(8)))
            chips.add(c)
        }

        sections.removeAll()
        presentation?.sections?.forEach { section ->
            sections.add(sectionBlock(section))
            sections.add(vGap(8))
        }

        repaintAll()
        payload.setStatus(event.kind)
        payload.setElement(event.envelope.payload)
    }

    private fun repaintAll() {
        listOf(badges, chips, sections).forEach { it.revalidate(); it.repaint() }
    }

    private fun badgeLabel(badge: Badge): Component {
        val color = toneColor(badge.tone)
        return TagLabel().apply {
            font = JBUI.Fonts.label(11f).asBold()
            set(badge.text, color, Theme.tint(color, 30))
        }
    }

    /**
     * Badge tones arrive semantic, never as colors — that's what keeps the wire independent of
     * the active IDE theme.
     */
    private fun toneColor(tone: String): Color = when (tone) {
        Badge.TONE_INFO -> Theme.accent
        Badge.TONE_WARN -> Theme.methodColor("PUT")
        Badge.TONE_ERROR -> Theme.statusColor(500, null)
        else -> Theme.textDim
    }

    private fun sectionBlock(section: Section): JComponent {
        val body = when (section.type) {
            Section.TYPE_TEXT, Section.TYPE_CODE ->
                (section.body as? JsonPrimitive)?.content ?: section.body.toString()
            // json / kv and anything unrecognised: pretty-print rather than dumping one line.
            else -> runCatching { pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), section.body) }
                .getOrElse { section.body.toString() }
        }
        val mono = section.type != Section.TYPE_TEXT

        val area = JBTextArea(body).apply {
            isEditable = false
            isOpaque = false
            lineWrap = !mono
            wrapStyleWord = !mono
            foreground = Theme.text
            font = if (mono) JBUI.Fonts.create("JetBrains Mono", 12) else JBUI.Fonts.label(12f)
            border = JBUI.Borders.empty(6, 8)
        }

        return CardPanel().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(section.label).apply {
                foreground = Theme.textDim
                font = JBUI.Fonts.label(11f).asBold()
                border = JBUI.Borders.empty(6, 10, 0, 10)
            }, BorderLayout.NORTH)
            add(
                if (mono) JBScrollPane(area).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    preferredSize = Dimension(0, JBUI.scale(minOf(160, 24 + body.lines().size * 16)))
                } else area,
                BorderLayout.CENTER,
            )
        }
    }

    // ---- layout helpers (mirroring FcmDetailView) -------------------------------------

    private fun pad(c: Component, minW: Int, minH: Int): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        minimumSize = Dimension(JBUI.scale(minW), JBUI.scale(minH))
        add(c, BorderLayout.CENTER)
    }

    private fun row(c: Component, fill: Boolean): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(c, BorderLayout.CENTER)
            if (!fill) maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun hbox(vararg comps: Component): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
    }

    private fun vGap(px: Int) = Box.createVerticalStrut(JBUI.scale(px))
}
