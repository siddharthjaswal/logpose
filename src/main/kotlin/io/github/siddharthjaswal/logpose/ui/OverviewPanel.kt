package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The "hero" overview card: a header row ("Overview" + copy-JSON icon), then a
 * prominent status + method, the full URL (green mono), a row of stat chips, and
 * the primary copy actions.
 */
class OverviewPanel : CardPanel(null) {

    var onCopyCurl: () -> Unit = {}
    var onCopyJson: () -> Unit = {}

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")

    private val statusPill = TagLabel().apply { font = JBUI.Fonts.label(13f).asBold() }
    private val methodPill = TagLabel().apply { font = JBUI.Fonts.label(12f).asBold() }
    private val url = JBTextArea(2, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = false
        foreground = Theme.jsonString
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        border = JBUI.Borders.empty(2, 0)
    }
    private val chips = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        arc = 10
        fill = Theme.bg1
        stroke = Theme.borderSubtle
        border = JBUI.Borders.empty(0)

        add(header())
        add(body())
        show(null)
    }

    private fun header(): Component {
        val title = JBLabel("Overview").apply {
            foreground = Theme.text; font = JBUI.Fonts.label(13f).asBold()
        }
        val copy = JLabel(AllIcons.Actions.Copy).apply {
            toolTipText = "Copy transaction JSON"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onCopyJson()
            })
        }
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Theme.borderSubtle, 0, 0, 1, 0),
                JBUI.Borders.empty(8, 14),
            )
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

        val inner = JPanel().apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 14)

            add(row(hbox(statusPill, Box.createHorizontalStrut(JBUI.scale(8)), methodPill), fill = false))
            add(vGap(8))
            add(row(url, fill = true))
            add(vGap(8))
            add(row(chips, fill = false))
            add(vGap(12))
            add(row(hbox(curlBtn, Box.createHorizontalStrut(JBUI.scale(10)), jsonBtn), fill = false))
            add(Box.createVerticalGlue())
        }
        return inner
    }

    fun show(tx: Transaction?) {
        if (tx == null) {
            statusPill.set("—", Theme.textDim, Theme.bg2)
            methodPill.set("", Theme.textDim, null)
            url.text = "Select a request"
            chips.removeAll(); chips.revalidate(); chips.repaint()
            return
        }

        val code = tx.response?.code
        val sColor = Theme.statusColor(code, tx.error)
        val label = when {
            tx.error != null -> "ERR"
            code != null -> "$code ${tx.response?.message?.ifBlank { reason(code) } ?: reason(code)}".trim()
            else -> "pending"
        }
        statusPill.set(label, sColor, Theme.statusTint(code, tx.error))
        val mColor = Theme.methodColor(tx.request.method)
        methodPill.set(tx.request.method, mColor, Theme.bg2)

        url.text = tx.request.url

        chips.removeAll()
        val items = buildList {
            tx.durationMillis?.let { add(StatChip("duration", "$it ms")) }
            tx.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { add(StatChip("size", Theme.humanSize(it))) }
            if (tx.startedAtMillis > 0) add(StatChip("started", timeFmt.format(Date(tx.startedAtMillis))))
            if (tx.request.host.isNotBlank()) add(StatChip("host", tx.request.host.substringBefore('.')))
            add(StatChip("id", tx.id))
        }
        items.forEachIndexed { i, c ->
            if (i > 0) chips.add(Box.createHorizontalStrut(JBUI.scale(8)))
            chips.add(c)
        }
        chips.revalidate(); chips.repaint()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> ""
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
