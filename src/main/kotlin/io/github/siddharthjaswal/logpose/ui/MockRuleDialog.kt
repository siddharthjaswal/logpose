package io.github.siddharthjaswal.logpose.ui

import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.Dimension
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Create/edit dialog for a single [MockRule], grouped into Match / Response / Delivery
 * sections. Pre-filled either from a captured [Transaction] ("Mock this endpoint…") or from
 * an existing rule (edit). Produces the edited rule via [result] on OK.
 */
class MockRuleDialog(
    private val project: Project,
    private val initial: MockRule,
    private val titleText: String,
) : DialogWrapper(project) {

    // ---- Match ----------------------------------------------------------------------------
    private val methodItems = buildList {
        add("*"); add("GET"); add("POST"); add("PUT"); add("PATCH"); add("DELETE"); add("HEAD")
        if (initial.method.uppercase() !in this) add(initial.method.uppercase())
    }
    private val method = ComboBox(methodItems.toTypedArray()).apply {
        selectedItem = initial.method.uppercase()
        renderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value
            label.font = JBUI.Fonts.label(12f).asBold()
            if (value != null && value != "*") label.foreground = Theme.methodColor(value)
        }
    }
    private val path = JBTextField(initial.pathPattern)

    // ---- Response -------------------------------------------------------------------------
    private val status = JBTextField(initial.status.toString()).fixedWidth(70)
    private val contentType = JBTextField(initial.contentType).fixedWidth(230)
    private val headers = JBTextArea(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }, 3, 40).apply {
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        emptyText.text = "X-Header: value"
    }
    private val body = EditorTextField(
        EditorFactory.getInstance().createDocument(prettyBody(initial.body)),
        project, JsonFileType.INSTANCE, false, false,
    ).apply {
        setOneLineMode(false)
        addSettingsProvider { editor ->
            (editor as EditorEx).setVerticalScrollbarVisible(true)
            editor.settings.apply { isLineNumbersShown = true; isFoldingOutlineShown = true }
        }
    }

    // ---- Delivery -------------------------------------------------------------------------
    private val modeItems = linkedMapOf(
        MockRule.MODE_REPLACE to "Replace the response",
        MockRule.MODE_PATCH to "Merge into the real response (keep backend data)",
    )
    private val mode = ComboBox(modeItems.values.toTypedArray()).apply {
        selectedItem = modeItems[initial.mode] ?: modeItems.values.first()
        addActionListener { updateEnabledState() }
    }
    private val bodyHint = JBLabel().apply { foreground = Theme.textMuted; font = JBUI.Fonts.label(11f) }

    private val behaviorItems = linkedMapOf(
        MockRule.BEHAVIOR_NORMAL to "Serve the response",
        MockRule.BEHAVIOR_TIMEOUT to "Timeout (SocketTimeoutException)",
        MockRule.BEHAVIOR_CONNECTION_FAILURE to "Connection failure (ConnectException)",
    )
    private val behavior = ComboBox(behaviorItems.values.toTypedArray()).apply {
        selectedItem = behaviorItems[initial.behavior] ?: behaviorItems.values.first()
        addActionListener { updateEnabledState() }
    }
    private val latency = JBTextField(initial.latencyMillis.toString()).fixedWidth(80)
    private val serveLimit = JBTextField(initial.serveLimit.toString()).fixedWidth(60)

    init {
        title = titleText
        init()
        updateEnabledState()
    }

    override fun createCenterPanel(): JComponent {
        val pathHint = JBLabel("Path may use * as a wildcard, e.g. /app/v4/*/order/*").apply {
            foreground = Theme.textMuted
            font = JBUI.Fonts.label(11f)
            border = JBUI.Borders.emptyBottom(4)
        }

        val matchRow = hbox(method, hGap(8), path)

        val statusRow = hbox(
            status, hGap(12),
            fieldLabel("Content-Type"), hGap(6), contentType, hGap(12),
            fieldLabel("Serve limit"), hGap(6), serveLimit,
            fieldHint("0 = always"),
            Box.createHorizontalGlue(),
        )

        val deliveryRow = hbox(
            behavior, hGap(12),
            fieldLabel("Latency"), hGap(6), latency, fieldHint("ms"),
            Box.createHorizontalGlue(),
        )

        val headersScroll = JBScrollPane(headers).apply {
            border = JBUI.Borders.customLine(Theme.borderStrong, 1)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(64))
        }

        body.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(260))

        return FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Match"))
            .addLabeledComponent("Method / path:", matchRow)
            .addComponentToRightColumn(pathHint)
            .addComponent(TitledSeparator("Behavior"))
            .addLabeledComponent("Mode:", hbox(mode, Box.createHorizontalGlue()))
            .addLabeledComponent("On match:", deliveryRow)
            .addComponent(TitledSeparator("Response"))
            .addLabeledComponent("Status:", statusRow)
            .addLabeledComponent("Headers:", headersScroll)
            .addComponentToRightColumn(fieldHint("One per line — Key: Value").apply {
                border = JBUI.Borders.emptyBottom(2)
            })
            .addLabeledComponentFillVertically("Body:", body)
            .addComponentToRightColumn(bodyHint)
            .panel
            .apply { preferredSize = Dimension(JBUI.scale(680), JBUI.scale(620)) }
    }

    /**
     * Gray out fields that don't apply. Patch mode keeps the backend's status/headers (only
     * the body patch matters); timeout / connection-failure rules send no response at all.
     */
    private fun updateEnabledState() {
        val patch = selectedModeKey() == MockRule.MODE_PATCH
        behavior.isEnabled = !patch
        val fullResponse = !patch && selectedBehaviorKey() == MockRule.BEHAVIOR_NORMAL
        status.isEnabled = fullResponse
        contentType.isEnabled = fullResponse
        headers.isEnabled = fullResponse
        body.isEnabled = patch || fullResponse
        bodyHint.text = if (patch)
            "Deep-merged into the real response: set a key to override it, add new keys freely — the rest stays backend-generated."
        else
            "The full response body sent to the app."
    }

    private fun selectedModeKey(): String =
        modeItems.entries.firstOrNull { it.value == mode.selectedItem }?.key ?: MockRule.MODE_REPLACE

    private fun selectedBehaviorKey(): String =
        behaviorItems.entries.firstOrNull { it.value == behavior.selectedItem }?.key
            ?: MockRule.BEHAVIOR_NORMAL

    override fun doValidate(): ValidationInfo? {
        if (path.text.isBlank()) return ValidationInfo("Path pattern is required", path)
        if (selectedModeKey() == MockRule.MODE_PATCH) {
            val text = body.text.trim()
            if (text.isEmpty()) return ValidationInfo("Merge needs at least one key to override/add", body)
            if (runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() == null)
                return ValidationInfo("Merge patch must be valid JSON", body)
        } else {
            val code = status.text.toIntOrNull()
            if (code == null || code !in 100..599) return ValidationInfo("Status must be 100–599", status)
        }
        if (latency.text.toLongOrNull() == null || latency.text.toLong() < 0)
            return ValidationInfo("Latency must be ≥ 0", latency)
        if (serveLimit.text.toIntOrNull() == null || serveLimit.text.toInt() < 0)
            return ValidationInfo("Serve limit must be ≥ 0", serveLimit)
        return null
    }

    fun result(): MockRule = MockRule(
        id = initial.id,
        method = (method.selectedItem as? String)?.trim()?.ifBlank { "*" }?.uppercase() ?: "*",
        pathPattern = path.text.trim(),
        status = status.text.trim().toInt(),
        headers = parseHeaders(headers.text),
        body = body.text.ifBlank { null },
        contentType = contentType.text.trim().ifBlank { "application/json" },
        latencyMillis = latency.text.trim().toLong(),
        behavior = selectedBehaviorKey(),
        serveLimit = serveLimit.text.trim().toInt(),
        enabled = initial.enabled,
        mode = selectedModeKey(),
    )

    private fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()

    // ---- Small UI helpers -----------------------------------------------------------------

    private fun hbox(vararg comps: java.awt.Component): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
    }

    private fun hGap(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))

    private fun fieldLabel(text: String) = JBLabel(text).apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.label(12f)
    }

    private fun fieldHint(text: String) = JBLabel(text).apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
        border = JBUI.Borders.emptyLeft(6)
    }

    private fun JBTextField.fixedWidth(px: Int): JBTextField = apply {
        val d = Dimension(JBUI.scale(px), preferredSize.height)
        preferredSize = d; minimumSize = d; maximumSize = d
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }
        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

        /** Pretty-prints [text] if it parses as JSON, otherwise returns it unchanged. */
        private fun prettyBody(text: String?): String {
            if (text.isNullOrBlank()) return ""
            return runCatching {
                prettyJson.encodeToString(JsonElement.serializer(), lenientJson.parseToJsonElement(text))
            }.getOrDefault(text)
        }

        /** Seeds a new rule from a captured transaction (method, path, status, body, type). */
        fun fromTransaction(tx: Transaction): MockRule = MockRule(
            id = UUID.randomUUID().toString().substring(0, 8),
            method = tx.request.method,
            pathPattern = tx.request.path.ifBlank { tx.request.url },
            status = tx.response?.code ?: 200,
            headers = emptyMap(),
            body = tx.response?.body?.text,
            contentType = tx.response?.body?.contentType ?: "application/json",
        )
    }
}
