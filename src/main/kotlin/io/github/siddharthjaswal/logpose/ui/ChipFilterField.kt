package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The Studio filter input: a `filter:` label, the active terms as removable chips,
 * and a text field. Space/Enter commits the current text as a chip; Backspace on an
 * empty field removes the last chip. [queryString] feeds the existing filter grammar.
 */
class ChipFilterField : JPanel(BorderLayout()) {

    var onChange: () -> Unit = {}

    private val terms = mutableListOf<String>()
    private val input = JBTextField()
    private val chipsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 4)

        input.border = JBUI.Borders.empty()
        input.isOpaque = false
        input.emptyText.text = "/orders   status:5xx   method:POST   -heartbeat"
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE -> {
                        if (commit()) e.consume()
                    }
                    e.keyCode == KeyEvent.VK_BACK_SPACE && input.text.isEmpty() && terms.isNotEmpty() -> {
                        terms.removeAt(terms.size - 1); rebuild(); onChange()
                    }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER && e.keyCode != KeyEvent.VK_SPACE &&
                    e.keyCode != KeyEvent.VK_BACK_SPACE
                ) onChange()
            }
        })

        add(chipsRow, BorderLayout.WEST)
        add(input, BorderLayout.CENTER)
        rebuild()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Theme.bg2
        g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
        g2.color = Theme.borderStrong
        g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        g2.dispose()
        super.paintComponent(g)
    }

    /** The full filter query: committed chips plus whatever's still being typed. */
    fun queryString(): String =
        (terms + input.text.trim()).filter { it.isNotBlank() }.joinToString(" ")

    fun clearAll() {
        terms.clear(); input.text = ""; rebuild(); onChange()
    }

    private fun commit(): Boolean {
        val t = input.text.trim()
        if (t.isEmpty()) return false
        terms.add(t); input.text = ""; rebuild(); onChange()
        return true
    }

    private fun rebuild() {
        chipsRow.removeAll()
        chipsRow.add(JBLabel("filter:").apply {
            foreground = Theme.textMuted; border = JBUI.Borders.emptyRight(2)
        })
        terms.forEach { term ->
            chipsRow.add(Chip(term) { terms.remove(term); rebuild(); onChange() })
        }
        chipsRow.revalidate(); chipsRow.repaint()
    }

    /** A removable filter chip. */
    private class Chip(text: String, onRemove: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, 8, 1, 6)
            add(JLabel(text).apply { foreground = Theme.text; font = JBUI.Fonts.label(11f) })
            add(JLabel("✕").apply {
                foreground = Theme.textDim
                font = JBUI.Fonts.label(10f)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onRemove()
                })
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Theme.accentTint
            g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
