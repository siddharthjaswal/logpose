package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.Color
import javax.swing.JList

/**
 * Renders one transaction as: `METHOD  status  path   (duration)`, with the
 * status colored by class (2xx green, 3xx blue, 4xx orange, 5xx/error red).
 */
class TransactionCellRenderer : ColoredListCellRenderer<Transaction>() {
    override fun customizeCellRenderer(
        list: JList<out Transaction>,
        value: Transaction,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.request.method.padEnd(6), SimpleTextAttributes.GRAYED_ATTRIBUTES)

        val code = value.response?.code
        val statusText = code?.toString() ?: (if (value.error != null) "ERR" else "···")
        append(
            statusText.padEnd(4),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, colorFor(code, value.error)),
        )
        append("  ")

        val label = value.request.path.ifBlank { value.request.url }
        append(label)

        value.durationMillis?.let { append("   ${it}ms", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
    }

    private fun colorFor(code: Int?, error: String?): Color = when {
        error != null -> JBColor.RED
        code == null -> JBColor.GRAY
        code in 200..299 -> JBColor(0x2E7D32, 0x6A8759) // green
        code in 300..399 -> JBColor.BLUE
        code in 400..499 -> JBColor(0xEF6C00, 0xCC7832) // orange
        else -> JBColor.RED
    }
}
