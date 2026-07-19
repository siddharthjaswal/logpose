package io.github.siddharthjaswal.logpose.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
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
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Create/edit dialog for a [MockRule], reading top-to-bottom as a sentence: **when** a request
 * matches → **then** do this → **send** that. Fields that don't apply to the chosen mode /
 * behavior are hidden (not just disabled) so the form stays focused.
 *
 * The response is edited as a [JsonPatchTree] by default ("Fields" — tick to override, fold to
 * focus), with a raw-text escape hatch and a native "Compare with original" diff.
 */
class MockRuleDialog(
    private val project: Project,
    private val initial: MockRule,
    private val baseBody: String?, // the captured response, for the tree + diff
    private val titleText: String,
) : DialogWrapper(project) {

    // ---- When a request matches -----------------------------------------------------------
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

    // ---- Then -----------------------------------------------------------------------------
    private val modeItems = linkedMapOf(
        MockRule.MODE_REPLACE to "Replace the response",
        MockRule.MODE_PATCH to "Merge into the real response (keep backend data)",
    )
    private val mode = ComboBox(modeItems.values.toTypedArray()).apply {
        selectedItem = modeItems[initial.mode] ?: modeItems.values.first()
        addActionListener { onModeOrBehaviorChanged() }
    }
    private val behaviorItems = linkedMapOf(
        MockRule.BEHAVIOR_NORMAL to "Serve a response",
        MockRule.BEHAVIOR_TIMEOUT to "Time out (SocketTimeoutException)",
        MockRule.BEHAVIOR_CONNECTION_FAILURE to "Fail to connect (ConnectException)",
    )
    private val behavior = ComboBox(behaviorItems.values.toTypedArray()).apply {
        selectedItem = behaviorItems[initial.behavior] ?: behaviorItems.values.first()
        addActionListener { onModeOrBehaviorChanged() }
    }
    private val latency = JBTextField(initial.latencyMillis.toString()).fixedWidth(80)
    private val serveLimit = JBTextField(initial.serveLimit.toString()).fixedWidth(60)

    // ---- Send -----------------------------------------------------------------------------
    private val status = JBTextField(initial.status.toString()).fixedWidth(70)
    private val contentType = JBTextField(initial.contentType).fixedWidth(220)
    private val headers = JBTextArea(initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }, 3, 40).apply {
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        emptyText.text = "X-Header: value"
    }
    // Response headers are rarely mocked, so keep them collapsed unless the rule already has some.
    private var headersExpanded = initial.headers.isNotEmpty()
    private val headersScroll = JBScrollPane(headers).apply {
        border = JBUI.Borders.customLine(Theme.borderStrong, 1)
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(58))
    }
    private val headersToggle = JBLabel().apply {
        foreground = Theme.accent
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { headersExpanded = !headersExpanded; updateHeaders() }
        })
    }
    private val body = EditorTextField(
        EditorFactory.getInstance().createDocument(prettyBody(initial.body)),
        project, JsonFileType.INSTANCE, false, false,
    ).apply {
        setOneLineMode(false)
        addSettingsProvider { (it as EditorEx).setVerticalScrollbarVisible(true); it.settings.isLineNumbersShown = true }
    }
    private val patchTree = JsonPatchTree()

    // body view: FIELDS (tree) or TEXT (raw)
    private val bodyCards = CardLayout()
    private val bodyPanel = JPanel(bodyCards).apply {
        add(patchTree, "fields")
        add(JBScrollPane(body).apply { border = JBUI.Borders.customLine(Theme.borderStrong, 1) }, "text")
    }
    private var fieldsActive = false
    private val viewToggle = link("Edit as text") { toggleView() }
    private val diffLink = link("Compare with original") { showDiff() }

    // rows/sections we show & hide
    private lateinit var behaviorRow: JComponent
    private lateinit var sendSeparator: JComponent
    private lateinit var replaceFields: JComponent
    private lateinit var sendBody: JComponent

    init {
        title = titleText
        init()
        seedTree()
        // Default to the fields view when the body is JSON; otherwise raw text.
        setView(fields = !patchTree.isNonJson())
        onModeOrBehaviorChanged()
    }

    override fun createCenterPanel(): JComponent {
        // VerticalLayout stretches every child to the full width, so labels/separators/links
        // all align left/right cleanly (a plain vertical BoxLayout centres narrow rows).
        val column = JPanel(VerticalLayout(JBUI.scale(3))).apply {
            preferredSize = Dimension(JBUI.scale(700), JBUI.scale(640))
        }

        column.add(TitledSeparator("When a request matches"))
        column.add(row("Method / path", hbox(method, hgap(8), path)))
        column.add(hint("Path may use * as a wildcard, e.g. /app/v4/*/order/*"))

        column.add(TitledSeparator("Then"))
        column.add(row("Mode", hbox(mode, glue())))
        behaviorRow = row("On match", hbox(behavior, hgap(12), muted("Latency"), hgap(6), latency, small("ms"),
            hgap(12), muted("Serve limit"), hgap(6), serveLimit, small("0 = always"), glue()))
        column.add(behaviorRow)

        sendSeparator = TitledSeparator("Response")
        column.add(sendSeparator)
        replaceFields = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(row("Status", hbox(status, hgap(12), muted("Content-Type"), hgap(6), contentType, glue())))
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(headersToggle, BorderLayout.WEST) })
            add(JPanel(BorderLayout()).apply {
                isOpaque = false; border = JBUI.Borders.empty(2, 106, 2, 0)
                add(headersScroll, BorderLayout.CENTER)
            })
        }
        column.add(replaceFields)
        updateHeaders()

        val bodyHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 4, 0)
            add(JBLabel("Body").apply { foreground = Theme.textDim; font = JBUI.Fonts.label(12f).asBold() }, BorderLayout.WEST)
            add(hbox(viewToggle, hgap(16), diffLink), BorderLayout.EAST)
        }
        bodyPanel.preferredSize = Dimension(JBUI.scale(660), JBUI.scale(348))
        sendBody = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(bodyHeader); add(bodyPanel)
        }
        column.add(sendBody)
        return column
    }

    // ---- disclosure + view switching ------------------------------------------------------

    private fun onModeOrBehaviorChanged() {
        val patch = modeKey() == MockRule.MODE_PATCH
        val serves = patch || behaviorKey() == MockRule.BEHAVIOR_NORMAL
        if (::behaviorRow.isInitialized) {
            behaviorRow.isVisible = !patch          // merge always serves the real response
            sendSeparator.isVisible = serves
            replaceFields.isVisible = !patch && behaviorKey() == MockRule.BEHAVIOR_NORMAL
            sendBody.isVisible = serves
        }
        diffLink.isVisible = baseBody != null
        if (fieldsActive) seedTree()
    }

    private fun updateHeaders() {
        val count = parseHeaders(headers.text).size
        headersToggle.text = (if (headersExpanded) "▾  " else "▸  ") + "Response headers" + if (count > 0) "  ($count)" else ""
        (headersScroll.parent as? JComponent)?.isVisible = headersExpanded
        (headersScroll.parent?.parent as? JComponent)?.let { it.revalidate(); it.repaint() }
    }

    private fun toggleView() = setView(!fieldsActive)

    private fun setView(fields: Boolean) {
        if (fields) {
            // Re-seed the tree from whatever the text view currently holds, so switching keeps edits.
            patchTree.setBase(baseBody, body.text.ifBlank { initial.body }, modeKey() == MockRule.MODE_PATCH)
            if (patchTree.isNonJson()) { setView(false); return }
        } else {
            body.text = if (fieldsActive) patchTree.resultJson() else body.text.ifBlank { prettyBody(initial.body) }
        }
        fieldsActive = fields
        bodyCards.show(bodyPanel, if (fields) "fields" else "text")
        viewToggle.text = if (fields) "Edit as text" else "Edit as fields"
    }

    private fun seedTree() {
        val merge = modeKey() == MockRule.MODE_PATCH
        // In merge mode, pre-tick only if this rule was ALREADY a patch (its body is a real
        // patch). A fresh rule's body is the whole response, so merge should start with nothing
        // ticked rather than overriding every field.
        val current = when {
            merge && initial.mode == MockRule.MODE_PATCH -> initial.body
            merge -> null
            else -> initial.body
        }
        patchTree.setBase(baseBody, current, merge)
    }

    private fun showDiff() {
        val original = baseBody ?: return
        val served = effectiveServedBody()
        val f = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Original response  vs  your mock",
                f.create(project, prettyBody(original), JsonFileType.INSTANCE),
                f.create(project, prettyBody(served), JsonFileType.INSTANCE),
                "Original (captured)", "What the app will receive",
            ),
        )
    }

    /** What the app actually ends up with: the merged body (patch mode) or the full body (replace). */
    private fun effectiveServedBody(): String {
        val bodyText = currentBody()
        if (modeKey() != MockRule.MODE_PATCH) return bodyText
        val base = runCatching { lenientJson.parseToJsonElement(baseBody ?: "") }.getOrNull() ?: return bodyText
        val patch = runCatching { lenientJson.parseToJsonElement(bodyText) }.getOrNull() ?: return bodyText
        return prettyJson.encodeToString(JsonElement.serializer(), mergeJson(base, patch))
    }

    private fun currentBody(): String =
        if (fieldsActive && !patchTree.isNonJson()) patchTree.resultJson() else body.text

    // ---- result -----------------------------------------------------------------------------

    override fun doValidate(): ValidationInfo? {
        if (path.text.isBlank()) return ValidationInfo("Path pattern is required", path)
        if (modeKey() == MockRule.MODE_PATCH) {
            val text = currentBody().trim()
            if (text.isEmpty() || text == "{}") return ValidationInfo("Tick at least one field to override", bodyPanel)
        } else if (behaviorKey() == MockRule.BEHAVIOR_NORMAL) {
            val code = status.text.toIntOrNull()
            if (code == null || code !in 100..599) return ValidationInfo("Status must be 100–599", status)
        }
        if (latency.text.toLongOrNull()?.let { it < 0 } != false) return ValidationInfo("Latency must be ≥ 0", latency)
        if (serveLimit.text.toIntOrNull()?.let { it < 0 } != false) return ValidationInfo("Serve limit must be ≥ 0", serveLimit)
        return null
    }

    fun result(): MockRule {
        val patch = modeKey() == MockRule.MODE_PATCH
        return MockRule(
            id = initial.id,
            method = (method.selectedItem as? String)?.trim()?.ifBlank { "*" }?.uppercase() ?: "*",
            pathPattern = path.text.trim(),
            status = status.text.trim().toIntOrNull() ?: 200,
            headers = parseHeaders(headers.text),
            body = currentBody().ifBlank { null },
            contentType = contentType.text.trim().ifBlank { "application/json" },
            latencyMillis = latency.text.trim().toLongOrNull() ?: 0,
            behavior = if (patch) MockRule.BEHAVIOR_NORMAL else behaviorKey(),
            serveLimit = serveLimit.text.trim().toIntOrNull() ?: 0,
            enabled = initial.enabled,
            mode = modeKey(),
        )
    }

    /** The original captured body, so the caller can persist it for re-editing. */
    fun capturedBaseBody(): String? = baseBody

    private fun modeKey() = modeItems.entries.firstOrNull { it.value == mode.selectedItem }?.key ?: MockRule.MODE_REPLACE
    private fun behaviorKey() = behaviorItems.entries.firstOrNull { it.value == behavior.selectedItem }?.key ?: MockRule.BEHAVIOR_NORMAL

    private fun parseHeaders(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() }.toMap()

    // ---- small UI helpers -----------------------------------------------------------------

    private fun row(labelText: String, field: Component): JComponent =
        object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
            add(JBLabel(labelText).apply {
                foreground = Theme.textDim
                preferredSize = Dimension(JBUI.scale(96), preferredSize.height)
            }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

    private fun hint(text: String): JComponent = JBLabel(text).apply {
        foreground = Theme.textMuted; font = JBUI.Fonts.label(11f)
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, 106, 4, 0)
    }

    private fun muted(text: String) = JBLabel(text).apply { foreground = Theme.textDim; font = JBUI.Fonts.label(12f) }
    private fun small(text: String) = JBLabel(text).apply {
        foreground = Theme.textMuted; font = JBUI.Fonts.label(11f); border = JBUI.Borders.emptyLeft(6)
    }

    private fun hbox(vararg comps: Component) = JPanel().apply {
        isOpaque = false; layout = BoxLayout(this, BoxLayout.X_AXIS)
        comps.forEach { add(it) }
    }

    private fun hgap(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))
    private fun glue() = Box.createHorizontalGlue()

    private fun link(text: String, onClick: () -> Unit) = JBLabel(text).apply {
        foreground = Theme.accent; font = JBUI.Fonts.label(11f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onClick() })
    }

    private fun JBTextField.fixedWidth(px: Int): JBTextField = apply {
        val d = Dimension(JBUI.scale(px), preferredSize.height); preferredSize = d; minimumSize = d; maximumSize = d
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }
        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

        private fun prettyBody(text: String?): String {
            if (text.isNullOrBlank()) return ""
            return runCatching {
                prettyJson.encodeToString(JsonElement.serializer(), lenientJson.parseToJsonElement(text))
            }.getOrDefault(text)
        }

        /** Local mirror of the device merge, for the "what the app will receive" diff preview. */
        private fun mergeJson(base: JsonElement, patch: JsonElement): JsonElement {
            if (base is JsonObject && patch is JsonObject) {
                val out = LinkedHashMap(base)
                for ((k, v) in patch) out[k] = out[k]?.let { mergeJson(it, v) } ?: v
                return JsonObject(out)
            }
            if (base is JsonArray && patch is JsonArray) {
                val out = base.toMutableList()
                patch.forEachIndexed { i, v -> if (i < out.size) out[i] = mergeJson(out[i], v) else out.add(v) }
                return JsonArray(out)
            }
            return patch
        }

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
