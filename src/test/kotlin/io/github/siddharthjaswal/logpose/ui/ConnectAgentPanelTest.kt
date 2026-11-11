package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.mcp.McpSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JLabel
import javax.swing.text.JTextComponent

/**
 * The Connect Coding Agent popup's content, headless: the picker shows each client's snippets with
 * the real URL and token, Copy hands over the exact text, and Regenerate swaps the token in every
 * snippet. With `-Dlogpose.renderDir=<dir>` it also writes PNGs of the Claude Code and Codex tabs.
 */
class ConnectAgentPanelTest {

    private val url = McpSetup.url(63342)

    private fun texts(c: Container): List<String> = c.components.flatMap { child ->
        when (child) {
            is JTextComponent -> listOf(child.text)
            is Container -> texts(child)
            else -> emptyList()
        }
    }

    private fun labels(c: Container): List<JLabel> = c.components.flatMap { child ->
        (if (child is JLabel) listOf(child) else emptyList()) + (if (child is Container) labels(child) else emptyList())
    }

    private fun click(c: Component) = c.mouseListeners.forEach {
        it.mouseClicked(java.awt.event.MouseEvent(c, 0, 0, 0, 1, 1, 1, false))
    }

    private fun panel(client: McpSetup.Client, copied: MutableList<String> = mutableListOf(), next: String = "fffffffffffffffffffffffffffffff1") =
        ConnectAgentPanel(
            url, "3f9c2a71d0b84e6f9a5c1e27b4d8f063", "http://127.0.0.1:63342/api/logpose/health", client, true,
            onCopy = { text, _ -> copied += text }, onRegenerate = { next },
        )

    @Test
    fun `each tab shows that client's snippets`() {
        assertEquals(McpSetup.snippets(McpSetup.Client.CLAUDE_CODE, url, "3f9c2a71d0b84e6f9a5c1e27b4d8f063").map { it.text }, texts(panel(McpSetup.Client.CLAUDE_CODE)))
        assertEquals(3, texts(panel(McpSetup.Client.CODEX)).size)
        val p = panel(McpSetup.Client.CLAUDE_CODE)
        click(labels(p).first { it.text == "Codex" })
        assertTrue(texts(p).first().startsWith("export LOGPOSE_TOKEN="))
    }

    @Test
    fun `copy hands over the exact snippet, and regenerate swaps the token everywhere`() {
        val copied = mutableListOf<String>()
        val p = panel(McpSetup.Client.CODEX, copied)
        click(labels(p).first { it.text == "Copy" })
        assertEquals("export LOGPOSE_TOKEN=3f9c2a71d0b84e6f9a5c1e27b4d8f063", copied.single())
        click(labels(p).first { it.text == "Regenerate token" })
        assertEquals("export LOGPOSE_TOKEN=fffffffffffffffffffffffffffffff1", texts(p).first())
        assertTrue(labels(p).any { it.isVisible && it.text.startsWith("New token issued") })
    }

    @Test
    fun `renders the Claude Code and Codex tabs when asked`() {
        val dir = System.getProperty("logpose.renderDir") ?: return
        for ((client, name) in listOf(McpSetup.Client.CLAUDE_CODE to "claude", McpSetup.Client.CODEX to "codex", McpSetup.Client.OTHER to "json")) {
            val p = panel(client)
            p.size = p.preferredSize
            layoutAll(p)
            val img = BufferedImage(p.width * 2, p.height * 2, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.scale(2.0, 2.0)
            p.printAll(g)
            g.dispose()
            javax.imageio.ImageIO.write(img, "png", File(dir, "plugin-connect-agent-$name.png"))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layoutAll(it) }
    }
}
