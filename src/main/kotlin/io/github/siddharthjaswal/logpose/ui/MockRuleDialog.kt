package io.github.siddharthjaswal.logpose.ui

import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Transaction
import java.awt.Dimension
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Create/edit dialog for a single [MockRule]. Pre-filled either from a captured [Transaction]
 * ("Mock this endpoint…") or from an existing rule (edit). Produces the edited rule via
 * [result] on OK.
 */
class MockRuleDialog(
    private val project: Project,
    private val initial: MockRule,
    private val titleText: String,
) : DialogWrapper(project) {

    private val method = JBTextField(initial.method, 6)
    private val path = JBTextField(initial.pathPattern, 32)
    private val status = JBTextField(initial.status.toString(), 5)
    private val contentType = JBTextField(initial.contentType, 20)
    private val latency = JBTextField(initial.latencyMillis.toString(), 6)
    private val serveLimit = JBTextField(initial.serveLimit.toString(), 4)
    private val behavior = ComboBox(arrayOf(
        MockRule.BEHAVIOR_NORMAL, MockRule.BEHAVIOR_TIMEOUT, MockRule.BEHAVIOR_CONNECTION_FAILURE,
    )).apply { selectedItem = initial.behavior }
    private val headers = JBTextArea(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }, 4, 32)
    private val body = EditorTextField(
        EditorFactory.getInstance().createDocument(initial.body ?: ""),
        project, JsonFileType.INSTANCE, false, false,
    ).apply {
        setOneLineMode(false)
        addSettingsProvider { (it as EditorEx).setVerticalScrollbarVisible(true) }
        preferredSize = Dimension(JBUI.scale(420), JBUI.scale(160))
    }

    init {
        title = titleText
        init()
    }

    override fun createCenterPanel(): JComponent {
        val methodPath = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            add(method); add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8))); add(path)
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Method / path pattern:", methodPath)
            .addComponent(JBLabel("Path may use * as a wildcard, e.g. /app/v4/*/order/*").apply {
                foreground = Theme.textMuted; font = JBUI.Fonts.label(11f)
            })
            .addLabeledComponent("Status:", status)
            .addLabeledComponent("Content-Type:", contentType)
            .addLabeledComponent("Latency (ms):", latency)
            .addLabeledComponent("Behavior:", behavior)
            .addLabeledComponent("Serve limit (0 = always):", serveLimit)
            .addLabeledComponent("Response headers (one per line, Key: Value):", headers)
            .addLabeledComponentFillVertically("Response body:", body)
            .panel
            .apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(560)) }
    }

    override fun doValidate(): ValidationInfo? {
        if (path.text.isBlank()) return ValidationInfo("Path pattern is required", path)
        val code = status.text.toIntOrNull()
        if (code == null || code !in 100..599) return ValidationInfo("Status must be 100–599", status)
        if (latency.text.toLongOrNull() == null || latency.text.toLong() < 0)
            return ValidationInfo("Latency must be ≥ 0", latency)
        if (serveLimit.text.toIntOrNull() == null || serveLimit.text.toInt() < 0)
            return ValidationInfo("Serve limit must be ≥ 0", serveLimit)
        return null
    }

    fun result(): MockRule = MockRule(
        id = initial.id,
        method = method.text.trim().ifBlank { "*" }.uppercase(),
        pathPattern = path.text.trim(),
        status = status.text.trim().toInt(),
        headers = parseHeaders(headers.text),
        body = body.text.ifBlank { null },
        contentType = contentType.text.trim().ifBlank { "application/json" },
        latencyMillis = latency.text.trim().toLong(),
        behavior = behavior.selectedItem as String,
        serveLimit = serveLimit.text.trim().toInt(),
        enabled = initial.enabled,
    )

    private fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()

    companion object {
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
