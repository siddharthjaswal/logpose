package io.github.siddharthjaswal.logpose.ui

import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.PushMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Composes a synthetic push to deliver into the running app — the flow-starting counterpart to
 * mocking a response.
 *
 * The data map is edited as **raw JSON** rather than a key/value grid: an FCM data payload is
 * usually pasted from a backend log or a ticket, and pasting it whole is the fastest path from
 * "here's the push that broke it" to "the app just received it". Same editor treatment as
 * [MockRuleDialog]'s body, and the same hidden-not-disabled disclosure for the notification
 * fields, which most data-message flows never set.
 */
class ComposePushDialog(
    project: Project,
    private val initial: PushMessage = PushMessage(),
) : DialogWrapper(project) {

    private val from = JBTextField(initial.from.orEmpty())
    private val collapseKey = JBTextField(initial.collapseKey.orEmpty())

    private val notificationTitle = JBTextField(initial.notificationTitle.orEmpty())
    private val notificationBody = JBTextField(initial.notificationBody.orEmpty())
    private var notificationExpanded =
        !initial.notificationTitle.isNullOrBlank() || !initial.notificationBody.isNullOrBlank()
    private val notificationToggle = JBLabel().apply {
        foreground = Theme.accent
        font = JBUI.Fonts.label(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                notificationExpanded = !notificationExpanded
                updateNotification()
            }
        })
    }
    private lateinit var notificationFields: JComponent

    private val data = EditorTextField(
        EditorFactory.getInstance().createDocument(prettyData(initial.data)),
        project, JsonFileType.INSTANCE, false, false,
    ).apply {
        setOneLineMode(false)
        addSettingsProvider { (it as EditorEx).setVerticalScrollbarVisible(true); it.settings.isLineNumbersShown = true }
    }

    init {
        title = "Compose push"
        setOKButtonText("Send")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val column = JPanel(VerticalLayout(JBUI.scale(3))).apply {
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(500))
        }

        column.add(TitledSeparator("Envelope"))
        column.add(row("From", from))
        column.add(row("Collapse key", collapseKey))
        column.add(hint("Both are optional — a data message needs neither to reach your handler."))

        column.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 2, 0)
            add(notificationToggle, BorderLayout.WEST)
        })
        notificationFields = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(row("Title", notificationTitle))
            add(row("Body", notificationBody))
            add(hint("Set these to simulate a notification message; leave empty for a pure data push."))
        }
        column.add(notificationFields)
        updateNotification()

        column.add(TitledSeparator("Data payload"))
        column.add(hint("A JSON object of string values — exactly what your app reads from data[…]."))
        column.add(JBScrollPane(data).apply {
            border = JBUI.Borders.customLine(Theme.borderStrong, 1)
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(260))
        })
        return column
    }

    private fun updateNotification() {
        notificationToggle.text = (if (notificationExpanded) "▾  " else "▸  ") + "Notification (optional)"
        if (::notificationFields.isInitialized) {
            notificationFields.isVisible = notificationExpanded
            notificationFields.parent?.let { it.revalidate(); it.repaint() }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val text = data.text.trim()
        if (text.isNotEmpty() && parseData(text) == null) {
            return ValidationInfo("Data must be a JSON object of simple values, e.g. {\"orderId\":\"42\"}", data)
        }
        return null
    }

    /** The composed push. `messageId` / `sentTimeMillis` are stamped by the caller at send time. */
    fun result(): PushMessage = initial.copy(
        from = from.text.trim().ifBlank { null },
        collapseKey = collapseKey.text.trim().ifBlank { null },
        notificationTitle = notificationTitle.text.trim().ifBlank { null },
        notificationBody = notificationBody.text.trim().ifBlank { null },
        data = parseData(data.text.trim()) ?: emptyMap(),
    )

    // ---- layout helpers (mirroring MockRuleDialog) -------------------------------------------

    private fun row(labelText: String, field: Component): JComponent =
        object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0)
            add(JBLabel(labelText).apply {
                foreground = Theme.textDim
                preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
            }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

    private fun hint(text: String): JComponent = JBLabel(text).apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
        border = JBUI.Borders.empty(0, 106, 4, 0)
    }

    @Suppress("unused")
    private fun hbox(vararg comps: Component) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
        add(Box.createHorizontalGlue())
    }

    companion object {
        private val pretty = Json { prettyPrint = true }
        private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

        /**
         * Reads a JSON object as the string map FCM actually carries. Numbers and booleans are
         * accepted and stringified (that's what a real push does to them); anything nested is
         * rejected, because silently JSON-encoding a sub-object would deliver something the
         * backend never would.
         */
        fun parseData(text: String): Map<String, String>? {
            if (text.isBlank()) return emptyMap()
            val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return null
            val out = LinkedHashMap<String, String>(obj.size)
            for ((key, value) in obj) {
                val primitive = value as? JsonPrimitive ?: return null
                out[key] = primitive.content
            }
            return out
        }

        private fun prettyData(data: Map<String, String>): String {
            if (data.isEmpty()) return "{\n  \n}"
            val obj = JsonObject(data.mapValues { JsonPrimitive(it.value) })
            return pretty.encodeToString(JsonObject.serializer(), obj)
        }
    }
}
