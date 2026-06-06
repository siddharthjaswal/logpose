package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The "hero" overview card: a prominent status + method, the full URL, a row of
 * stat chips (duration / size / started / host / id), and the primary copy actions.
 */
class OverviewPanel : CardPanel(null) {

    var onCopyCurl: () -> Unit = {}
    var onCopyJson: () -> Unit = {}

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")

    private val statusPill = TagLabel().apply { font = JBUI.Fonts.label(13f).asBold() }
    private val methodPill = TagLabel().apply { font = JBUI.Fonts.label(12f).asBold() }
    private val title = JLabel("Overview").apply {
        foreground = Theme.text; font = JBUI.Fonts.label(13f).asBold()
    }
    private val url = JBTextArea(2, 10).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = false
        foreground = Theme.textDim; font = JBUI.Fonts.create("Monospaced", 12)
        border = JBUI.Borders.empty(2, 0)
    }
    private val chips = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(6))).apply { isOpaque = false }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12, 14)

        add(row(title, fill = false))
        add(vGap(8))
        add(row(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false; add(statusPill); add(methodPill)
        }, fill = false))
        add(vGap(8))
        add(row(url, fill = true))
        add(vGap(6))
        add(row(chips, fill = false))
        add(vGap(10))
        add(row(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(PillButton("Copy as cURL", filled = true).apply { addActionListener { onCopyCurl() } })
            add(PillButton("Copy JSON", filled = false).apply { addActionListener { onCopyJson() } })
        }, fill = false))
        add(Box.createVerticalGlue())

        show(null)
    }

    fun show(tx: Transaction?) {
        if (tx == null) {
            statusPill.set("—", Theme.textDim, Theme.chipBg)
            methodPill.set("", Theme.textDim, null)
            url.text = "Select a request"
            chips.removeAll(); chips.revalidate(); chips.repaint()
            return
        }

        val code = tx.response?.code
        val sColor = Theme.statusColor(code, tx.error)
        val statusLabel = when {
            tx.error != null -> "ERR"
            code != null -> "$code ${tx.response?.message.orEmpty()}".trim()
            else -> "pending"
        }
        statusPill.set(statusLabel, sColor, Theme.tint(sColor))
        val mColor = Theme.methodColor(tx.request.method)
        methodPill.set(tx.request.method, mColor, Theme.tint(mColor))

        url.text = tx.request.url

        chips.removeAll()
        tx.durationMillis?.let { chips.add(StatChip("duration", "$it ms")) }
        tx.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { chips.add(StatChip("size", Theme.humanSize(it))) }
        if (tx.startedAtMillis > 0) chips.add(StatChip("started", timeFmt.format(Date(tx.startedAtMillis))))
        if (tx.request.host.isNotBlank()) chips.add(StatChip("host", tx.request.host.substringBefore('.')))
        chips.add(StatChip("id", tx.id))
        chips.revalidate(); chips.repaint()
    }

    /** A left-aligned row whose height is capped to its content (so BoxLayout won't stretch it). */
    private fun row(c: Component, fill: Boolean): JPanel =
        object : JPanel(java.awt.BorderLayout()) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(c, if (fill) java.awt.BorderLayout.CENTER else java.awt.BorderLayout.WEST)
        }

    private fun vGap(px: Int) = Box.createVerticalStrut(JBUI.scale(px))
}
