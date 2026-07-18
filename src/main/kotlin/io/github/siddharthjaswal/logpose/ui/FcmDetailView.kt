package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.FcmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

        overview.add(row(hbox(kindPill, Box.createHorizontalStrut(JBUI.scale(8)), eventLabel), fill = false))
        overview.add(vGap(8))
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

    fun show(msg: FcmMessage?) {
        current = msg
        if (msg == null) {
            kindPill.set("—", Theme.textDim, Theme.bg2)
            eventLabel.text = ""
            summary.text = "Select an event"
            chips.removeAll(); chips.revalidate(); chips.repaint()
            payload.setElement(null); payload.setStatus(null)
            return
        }

        val (kind, color) = kindOf(msg)
        kindPill.set(kind, color, Theme.tint(color, 30))
        eventLabel.text = if (msg.event == "token") "Token refresh" else "Push message"
        summary.text = summaryOf(msg)

        chips.removeAll()
        val items = buildList {
            fcmChannel(msg)?.let { add(StatChip("channel", ellipsize(it), tip = it)) }
            if (msg.receivedAtMillis > 0) add(StatChip("received", timeFmt.format(Date(msg.receivedAtMillis))))
            if (msg.sentTimeMillis != null && msg.sentTimeMillis > 0)
                add(StatChip("sent", timeFmt.format(Date(msg.sentTimeMillis))))
            msg.from?.takeIf { it.isNotBlank() }?.let { add(StatChip("from", ellipsize(it), tip = it)) }
            msg.priority?.let { add(StatChip("priority", priorityName(it))) }
            msg.ttlSeconds?.let { add(StatChip("ttl", "${it}s")) }
            msg.collapseKey?.takeIf { it.isNotBlank() }?.let { add(StatChip("collapse", ellipsize(it), tip = it)) }
            if (msg.data.isNotEmpty()) add(StatChip("data", "${msg.data.size}"))
            val idShown = msg.messageId?.takeIf { it.isNotBlank() } ?: msg.id
            add(StatChip("id", ellipsize(idShown), tip = idShown))
        }
        items.forEachIndexed { i, c ->
            if (i > 0) chips.add(Box.createHorizontalStrut(JBUI.scale(8)))
            chips.add(c)
        }
        chips.revalidate(); chips.repaint()

        payload.setStatus(kind.lowercase())
        payload.setElement(payloadJson(msg))
    }

    private fun kindOf(msg: FcmMessage): Pair<String, java.awt.Color> = when {
        msg.event == "token" -> "TOKEN" to Theme.accent
        msg.notification != null -> "NOTIF" to Theme.methodColor("PATCH")
        else -> "DATA" to Theme.textDim
    }

    private fun summaryOf(msg: FcmMessage): String = when {
        msg.event == "token" -> msg.token ?: "(token)"
        msg.notification != null -> listOfNotNull(
            msg.notification.title?.takeIf { it.isNotBlank() },
            msg.notification.body?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { "(notification)" }
        else -> fcmChannel(msg)
            ?: msg.from?.takeIf { it.isNotBlank() }?.let { "from $it" }
            ?: "(data message)"
    }

    /** The channel a data message rides on, if the app put one in the data map. */
    private fun fcmChannel(msg: FcmMessage): String? =
        msg.data.entries.firstOrNull { it.key.equals("channel", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }

    /** A composed view of the event for the tree: notification, data map, and token. */
    private fun payloadJson(msg: FcmMessage): JsonElement = buildJsonObject {
        put("event", msg.event)
        msg.notification?.let { n ->
            put("notification", buildJsonObject {
                n.title?.let { put("title", it) }
                n.body?.let { put("body", it) }
                n.channelId?.let { put("channelId", it) }
                n.clickAction?.let { put("clickAction", it) }
                n.imageUrl?.let { put("imageUrl", it) }
            })
        }
        if (msg.data.isNotEmpty()) put("data", buildJsonObject { msg.data.forEach { (k, v) -> put(k, v) } })
        msg.token?.let { put("token", it) }
    }

    private fun priorityName(p: Int): String = when (p) {
        1 -> "high"; 2 -> "normal"; else -> p.toString()
    }

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
