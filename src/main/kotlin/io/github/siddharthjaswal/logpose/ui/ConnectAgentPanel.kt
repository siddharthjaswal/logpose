package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.mcp.McpSetup
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Connect Coding Agent popup's content: a Claude Code / Codex / Other (JSON) picker over the
 * setup snippets from [McpSetup] (each with Copy), the health URL, the token with Regenerate, and
 * the expose-bodies switch.
 *
 * A plain component over values and callbacks — no project, no popup — so the tool window owns the
 * side effects (clipboard, persistence, re-registering the session under a new token) and the panel
 * can be rendered on its own.
 */
class ConnectAgentPanel(
    private val url: String,
    private var token: String,
    private val healthUrl: String,
    initialClient: McpSetup.Client,
    exposeBodies: Boolean,
    private val onCopy: (text: String, toast: String) -> Unit,
    private val onClient: (McpSetup.Client) -> Unit = {},
    /** Rotates the token and returns the new one; the old must be refused from the next request. */
    private val onRegenerate: () -> String,
    private val onExposeBodies: (Boolean) -> Unit = {},
) : JPanel() {

    private var client = initialClient
    private val tabs = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
    }
    private val snippets = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val tokenLabel = JBLabel().apply {
        foreground = Theme.textMuted
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
    }
    private val tokenNote = JBLabel().apply {
        foreground = Theme.warn
        font = JBUI.Fonts.label(11f)
        isVisible = false
    }

    init {
        isOpaque = true
        background = Theme.bg1
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)

        add(left(JBLabel("Connect a coding agent").apply {
            foreground = Theme.text
            font = JBUI.Fonts.label(13f).asBold()
        }))
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(left(muted("MCP on 127.0.0.1 · one token per project · works with any Streamable HTTP client")))
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(left(tabs))
        add(Box.createVerticalStrut(JBUI.scale(10)))
        add(left(snippets))
        add(Box.createVerticalStrut(JBUI.scale(8)))

        add(left(row(
            tokenLabel,
            Box.createHorizontalStrut(JBUI.scale(10)),
            LinkLabel("Regenerate token") { regenerate() },
            Box.createHorizontalGlue(),
            LinkLabel("Copy health URL") { onCopy(healthUrl, "Health URL copied — GET it, no token needed") }
                .apply { toolTipText = healthUrl },
        )))
        add(left(tokenNote))
        add(Box.createVerticalStrut(JBUI.scale(12)))

        lateinit var expose: ToggleSwitch
        expose = ToggleSwitch(exposeBodies) { onExposeBodies(expose.on) }
        add(left(row(expose, JBLabel("Expose response bodies to agents").apply {
            foreground = Theme.text
            font = JBUI.Fonts.label(12f).asBold()
            border = JBUI.Borders.emptyLeft(8)
        })))
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(left(muted(
            "<html>When off, agents still see every request, status and timing —<br/>" +
                "bodies come back as <code>payload_withheld</code> over MCP.</html>",
        )))
        refresh()
    }

    private fun regenerate() {
        token = onRegenerate()
        tokenNote.text = "New token issued — the old one stopped working. Re-copy the snippet into each agent."
        tokenNote.isVisible = true
        refresh()
    }

    private fun refresh() {
        tokenLabel.text = "token ${token.take(8)}…"
        tabs.removeAll()
        McpSetup.Client.entries.forEachIndexed { i, c ->
            if (i > 0) tabs.add(Box.createHorizontalStrut(JBUI.scale(4)))
            tabs.add(Tab(c.label, c == client) {
                client = c
                onClient(c)
                refresh()
            })
        }
        snippets.removeAll()
        McpSetup.snippets(client, url, token).forEachIndexed { i, snippet ->
            if (i > 0) snippets.add(Box.createVerticalStrut(JBUI.scale(10)))
            snippets.add(left(row(
                JBLabel(snippet.title).apply {
                    foreground = Theme.text
                    font = JBUI.Fonts.label(12f).asBold()
                },
                Box.createHorizontalGlue(),
                LinkLabel("Copy") { onCopy(snippet.text, "${client.label} snippet copied — paste it in your terminal") },
            )))
            snippets.add(Box.createVerticalStrut(JBUI.scale(4)))
            snippets.add(left(code(snippet.text)))
            snippet.note?.let {
                snippets.add(Box.createVerticalStrut(JBUI.scale(3)))
                snippets.add(left(muted(it)))
            }
        }
        revalidate()
        repaint()
        // A popup sizes itself once; growing content (Codex has three blocks) must ask again.
        javax.swing.SwingUtilities.getWindowAncestor(this)?.pack()
    }

    private fun code(text: String): JComponent = JBTextArea(text).apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
        foreground = Theme.text
        background = Theme.bg0
        border = JBUI.Borders.compound(JBUI.Borders.customLine(Theme.borderSubtle, 1), JBUI.Borders.empty(6, 8))
    }

    private fun muted(text: String) = JBLabel(text).apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
    }

    private fun row(vararg children: java.awt.Component) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        children.forEach { add(it) }
    }

    private fun <T : JComponent> left(c: T): T = c.apply { alignmentX = LEFT_ALIGNMENT }

    /** One segment of the client picker: filled accent when selected, ghost otherwise. */
    private class Tab(text: String, private val selected: Boolean, onSelect: () -> Unit) : JBLabel(text) {
        init {
            foreground = if (selected) Theme.onAccent else Theme.text
            font = JBUI.Fonts.label(12f).let { if (selected) it.asBold() else it }
            border = JBUI.Borders.empty(4, 12)
            horizontalAlignment = CENTER
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onSelect()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (selected) Theme.accent else Theme.bg2
            g2.fillRoundRect(0, 0, width, height, 8, 8)
            if (!selected) {
                g2.color = Theme.borderStrong
                g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }

    companion object {
        /** Where the tool window remembers the picker's choice, per project. */
        const val CLIENT_KEY = "logpose.mcp.client"

        fun clientFrom(saved: String?): McpSetup.Client =
            McpSetup.Client.entries.firstOrNull { it.name == saved } ?: McpSetup.Client.CLAUDE_CODE
    }
}
