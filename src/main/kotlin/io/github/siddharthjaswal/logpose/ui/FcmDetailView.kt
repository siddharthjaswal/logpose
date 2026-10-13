package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.presentation.DetailContent
import io.github.siddharthjaswal.logpose.presentation.RowContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Detail pane for an FCM event ([LogEvent.Fcm][io.github.siddharthjaswal.logpose.model.LogEvent.Fcm]):
 * a hero overview card (event kind, notification title/body, metadata chips) on top, and a
 * [JsonTreePanel] rendering the full payload — data map, notification, and metadata — as a
 * tree or raw JSON below. The sibling of [TransactionDetailView] for the push half of the
 * unified stream.
 */
class FcmDetailView(project: Project) : JPanel(BorderLayout()) {

    /** Opens the trace waterfall for the flow this push belongs to. */
    var onOpenTrace: (String) -> Unit = {}

    /** The FCM hue, stated once in the header — the same glyph this event's row gutter carries. */
    private val typeIcon = JBLabel(TypeIcons.forKind(Envelope.KIND_FCM))
    private val kindPill = TagLabel().apply { font = JBUI.Fonts.label(13f).asBold() }
    private val eventLabel = JBLabel().apply {
        foreground = Theme.text; font = JBUI.Fonts.label(13f).asBold()
    }
    private val summary = JBTextArea(2, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
        foreground = Theme.text
        font = JBUI.Fonts.create("JetBrains Mono", 13)
        border = JBUI.Borders.empty(2, 0)
    }
    private val chips = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    /** Says out loud that LogPose delivered this push — the detail half of the row's INJ pill. */
    private val injectedBanner = JBLabel(DetailContent.INJECTED_BANNER).apply {
        // Intervention, not kind: kind hues are confined to the glyph, the TYPE chip, the kind
        // pill and waterfall marks, and this banner is the detail half of the row's INJ pill.
        foreground = Theme.intervention
        font = JBUI.Fonts.label(11.5f).asBold()
        isVisible = false
    }
    private val overview = CardPanel(null).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12, 14)
    }
    private val payload = JsonTreePanel("Payload", project)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }
    private var current: FcmMessage? = null

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty(8)

        overview.add(row(hbox(
            typeIcon, Box.createHorizontalStrut(JBUI.scale(6)),
            kindPill, Box.createHorizontalStrut(JBUI.scale(8)), eventLabel,
        ), fill = false))
        overview.add(vGap(6))
        overview.add(row(injectedBanner, fill = false))
        overview.add(vGap(6))
        overview.add(row(summary, fill = true))
        overview.add(vGap(8))
        overview.add(row(chips, fill = false))
        overview.add(vGap(12))
        val copyBtn = PillButton("Copy JSON", filled = false).apply {
            addActionListener { current?.let { copy(pretty.encodeToString(FcmMessage.serializer(), it), "FCM JSON copied") } }
        }
        overview.add(row(hbox(copyBtn), fill = false))
        overview.add(Box.createVerticalGlue())

        val outer = OnePixelSplitter(true, 0.34f).apply {
            firstComponent = pad(overview, 0, 96)
            secondComponent = pad(payload, 160, 60)
            setHonorComponentsMinimumSize(true)
        }
        add(outer, BorderLayout.CENTER)
    }

    /**
     * Shows [msg]. [envelope] is the transport it arrived in — the only place the trace, parent
     * and (for an injected push, which has no device receive-time of its own) the timestamp live,
     * so an FCM row gets the same at/trace/parent/id chips a structured row has.
     */
    fun show(msg: FcmMessage?, envelope: Envelope? = null) {
        current = msg
        if (msg == null) {
            kindPill.set("—", Theme.textDim, Theme.bg2)
            eventLabel.text = ""
            summary.text = "Select an event"
            injectedBanner.isVisible = false
            chips.removeAll(); chips.revalidate(); chips.repaint()
            payload.setElement(null); payload.setStatus(null)
            return
        }

        val kind = kindOf(msg)
        kindPill.set(kind, Theme.textDim, Theme.statusNeutralBg)
        eventLabel.text = DetailContent.fcmEventLabel(msg)
        summary.text = DetailContent.fcmSummary(msg)
        injectedBanner.isVisible = msg.injected

        chips.removeAll()
        // The chips are :core's (DetailContent), shared with the Mac app. The trace chip is also
        // the way into the flow this push started.
        val items = DetailContent.fcmChips(msg, envelope).map { chip ->
            val trace = chip.traceLink
            if (trace != null) StatChip(chip.caption, chip.value, chip.tip)
                .clickable("$trace — open the waterfall for this flow") { onOpenTrace(trace) }
            else StatChip(chip.caption, chip.value, chip.tip)
        }
        items.forEachIndexed { i, c ->
            if (i > 0) chips.add(Box.createHorizontalStrut(JBUI.scale(8)))
            chips.add(c)
        }
        chips.revalidate(); chips.repaint()

        payload.setStatus(kind.lowercase())
        payload.setElement(payloadJson(msg))
    }

    /**
     * TOKEN / NOTIF / DATA — a label, not a severity. All three render neutral (the same pill the
     * timeline row shows), because which of the three a push is says nothing about how much it
     * matters; the FCM hue is stated once, by the [typeIcon] glyph beside this pill.
     */
    private fun kindOf(msg: FcmMessage): String = RowContent.fcmLabel(msg)

    /** A composed view of the event for the tree: notification, data map, and token. */
    private fun payloadJson(msg: FcmMessage): JsonElement = DetailContent.fcmPayloadJson(msg)

    private fun ellipsize(s: String, max: Int = 16): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun copy(text: String, label: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Toast.show(overview, label)
    }

    private fun pad(c: Component, minW: Int, minH: Int): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        minimumSize = Dimension(JBUI.scale(minW), JBUI.scale(minH))
        add(c, BorderLayout.CENTER)
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
