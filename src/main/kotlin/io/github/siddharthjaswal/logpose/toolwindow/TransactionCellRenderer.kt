package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import java.awt.Color
import javax.swing.JList

/**
 * Renders one transaction as: `METHOD  status  path   (duration)`, with the method
 * color-coded by verb and the status colored by class. Muted endpoints render faded.
 */
class TransactionCellRenderer : ColoredListCellRenderer<Transaction>() {
    override fun customizeCellRenderer(
        list: JList<out Transaction>,
        value: Transaction,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val muted = MutedEndpoints.isMuted(value)

        append(
            value.request.method.padEnd(6),
            attr(methodColor(value.request.method), muted, bold = true),
        )

        val code = value.response?.code
        val statusText = code?.toString() ?: (if (value.error != null) "ERR" else "···")
        append(statusText.padEnd(4), attr(statusColor(code, value.error), muted, bold = true))
        append("  ")

        val label = value.request.path.ifBlank { value.request.url }
        append(label, if (muted) FAINT else SimpleTextAttributes.REGULAR_ATTRIBUTES)

        value.durationMillis?.let {
            append("   ${it}ms", if (muted) FAINT else SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private fun attr(color: Color, muted: Boolean, bold: Boolean): SimpleTextAttributes {
        if (muted) return FAINT
        val style = if (bold) SimpleTextAttributes.STYLE_BOLD else SimpleTextAttributes.STYLE_PLAIN
        return SimpleTextAttributes(style, color)
    }

    private fun methodColor(method: String): Color = when (method.uppercase()) {
        "GET" -> JBColor(0x1750EB, 0x6897BB)      // blue
        "POST" -> JBColor(0x2E7D32, 0x6A8759)     // green
        "PUT", "PATCH" -> JBColor(0xEF6C00, 0xCC7832) // orange
        "DELETE" -> JBColor.RED
        else -> JBColor.GRAY
    }

    private fun statusColor(code: Int?, error: String?): Color = when {
        error != null -> JBColor.RED
        code == null -> JBColor.GRAY
        code in 200..299 -> JBColor(0x2E7D32, 0x6A8759)
        code in 300..399 -> JBColor.BLUE
        code in 400..499 -> JBColor(0xEF6C00, 0xCC7832)
        else -> JBColor.RED
    }

    companion object {
        // Much fainter than GRAYED_ATTRIBUTES — muted rows recede well into the background.
        private val FAINT = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor(Gray._190, Gray._75),
        )
    }
}
