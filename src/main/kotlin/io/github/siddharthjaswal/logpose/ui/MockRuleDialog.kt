package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
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
import java.awt.Dimension
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
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
        // Subclass rather than SimpleListCellRenderer.create(Customizer) — that overload is
        // scheduled for removal from the platform.
        renderer = object : SimpleListCellRenderer<String>() {
            override fun customize(
                list: JList<out String>, value: String?, index: Int,
                selected: Boolean, hasFocus: Boolean,
            ) {
                text = value
                // Method carries no hue any more — one axis owns hue, and it isn't this one.
                // The foreground is left to the renderer so the highlighted row in the popup
                // keeps the list's own selection contrast instead of a hard-coded neutral.
                font = JBUI.Fonts.label(12f).asBold()
            }
        }
    }
    private val path = JBTextField(initial.pathPattern)

    // ---- Only when… (extra match constraints, device library 1.7.0+) -----------------------
    // Collapsed unless the rule already narrows on something: most rules match on path alone,
    // and an empty box for every possible constraint would bury the two fields that matter.
    private val matchQuery = JBTextArea(MockRuleForm.formatPairs(initial.matchQuery), 2, 40).apply {
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        emptyText.text = "debug=1"
    }
    private val matchHeaders = JBTextArea(MockRuleForm.formatPairs(initial.matchHeaders), 2, 40).apply {
        font = JBUI.Fonts.create("JetBrains Mono", 12)
        emptyText.text = "X-Env: staging"
    }
    private val matchBody = JBTextField(initial.matchBodyContains.orEmpty())
    private var matchersExpanded = initial.matchQuery.isNotEmpty() ||
        initial.matchHeaders.isNotEmpty() || !initial.matchBodyContains.isNullOrBlank()
    private val matchersToggle = LinkLabel { matchersExpanded = !matchersExpanded; updateMatchers() }
    private lateinit var matchersFields: JComponent

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
    private val headersToggle = LinkLabel { headersExpanded = !headersExpanded; updateHeaders() }
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

    // ---- Then respond… (sequential steps, device library 1.7.0+) ---------------------------
    // One rule, several responses: hit N serves step N and the last one sticks — "fail, then
    // succeed" is a retry test you can write in one rule instead of racing two.
    private val stepDrafts = MockRuleForm.draftsOf(initial).toMutableList()
    private var stepsEnabled = initial.responses.isNotEmpty()
    private val stepCards = mutableListOf<StepCard>()
    private val stepsHost = JPanel(VerticalLayout(JBUI.scale(4))).apply { isOpaque = false }
    private val stepsToggle = link("") { toggleSteps() }
    private lateinit var stepsToggleRow: JComponent
    private lateinit var stepsPanel: JComponent

    // rows/sections we show & hide
    private lateinit var behaviorRow: JComponent
    private lateinit var limitRow: JComponent
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
        // all align left/right cleanly (a plain vertical BoxLayout centres narrow rows) — but
        // only if the column itself fills the viewport it now scrolls inside, hence Scrollable.
        val column = object : JPanel(VerticalLayout(JBUI.scale(3))), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(120)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { isOpaque = false }

        column.add(TitledSeparator("When a request matches"))
        column.add(row("Method / path", hbox(method, hgap(8), path)))
        column.add(hint("Path may use * as a wildcard, e.g. /app/v4/*/order/*"))

        column.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 106, 0, 0)
            add(matchersToggle, BorderLayout.WEST)
        })
        matchersFields = JPanel(VerticalLayout(JBUI.scale(2))).apply {
            isOpaque = false
            add(row("Query", boxed(matchQuery, 46)))
            add(hint("One per line: debug=1, or cursor=* for \"present, any value\""))
            add(row("Headers", boxed(matchHeaders, 46)))
            add(hint("Name matched case-insensitively; value * means \"present, any value\""))
            add(row("Body contains", hbox(matchBody, glue())))
            add(hint("Case-insensitive. A body the device can't buffer never matches — narrowing " +
                "fails closed, so the call goes to the network rather than being mocked on a guess."))
        }
        column.add(matchersFields)
        updateMatchers()

        column.add(TitledSeparator("Then"))
        column.add(row("Mode", hbox(mode, glue())))
        behaviorRow = row("On match", hbox(behavior, hgap(12), muted("Latency"), hgap(6), latency, small("ms"), glue()))
        column.add(behaviorRow)
        limitRow = row("Serve limit", hbox(serveLimit, small("0 = always"), glue()))
        column.add(limitRow)

        sendSeparator = TitledSeparator("Response")
        column.add(sendSeparator)
        stepsToggleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
            add(stepsToggle, BorderLayout.WEST)
        }
        column.add(stepsToggleRow)
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

        stepsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            // No scroll pane of its own: the whole form already scrolls, and a list nested in a
            // second scroller is the kind of thing that eats a mouse-wheel gesture.
            add(stepsHost, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 0, 0, 0)
                add(link("+  Add step") { addStep() }, BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        }
        column.add(stepsPanel)
        rebuildSteps()
        updateStepsToggle()

        // The form grew past a fixed frame once "Only when…" and the step list joined it; scroll
        // rather than clip, so no field can end up unreachable on a short screen.
        return JBScrollPane(column).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(660))
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    // ---- disclosure + view switching ------------------------------------------------------

    private fun onModeOrBehaviorChanged() {
        val patch = modeKey() == MockRule.MODE_PATCH
        val serves = patch || behaviorKey() == MockRule.BEHAVIOR_NORMAL
        if (::behaviorRow.isInitialized) {
            // Rule-level behavior/latency belong to a single response; a sequence sets them per
            // step. Serve limit and mode stay rule-level either way.
            behaviorRow.isVisible = !patch && !stepsEnabled
            limitRow.isVisible = !patch
            sendSeparator.isVisible = serves || stepsEnabled
            stepsToggleRow.isVisible = serves || stepsEnabled
            replaceFields.isVisible = !patch && !stepsEnabled && behaviorKey() == MockRule.BEHAVIOR_NORMAL
            sendBody.isVisible = serves && !stepsEnabled
            stepsPanel.isVisible = stepsEnabled
        }
        diffLink.isVisible = baseBody != null
        if (fieldsActive) seedTree()
    }

    private fun updateMatchers() {
        val count = MockRuleForm.parsePairs(matchQuery.text).size +
            MockRuleForm.parsePairs(matchHeaders.text).size +
            (if (matchBody.text.isBlank()) 0 else 1)
        matchersToggle.text = (if (matchersExpanded) "▾  " else "▸  ") +
            "Only when…" + if (count > 0) "  ($count)" else ""
        if (::matchersFields.isInitialized) {
            matchersFields.isVisible = matchersExpanded
            matchersFields.parent?.let { it.revalidate(); it.repaint() }
        }
    }

    // ---- sequential steps ------------------------------------------------------------------

    private fun toggleSteps() {
        if (!stepsEnabled) {
            // Seed step 1 from what the form currently holds, so turning a captured mock into a
            // sequence doesn't mean retyping the response that was already there.
            syncDrafts()
            if (stepDrafts.isEmpty()) stepDrafts.add(currentAsDraft())
            else stepDrafts[0] = currentAsDraft()
            stepsEnabled = true
            if (stepDrafts.size == 1) stepDrafts.add(MockRuleForm.StepDraft(status = "200"))
        } else {
            stepsEnabled = false
        }
        rebuildSteps()
        updateStepsToggle()
        onModeOrBehaviorChanged()
    }

    private fun updateStepsToggle() {
        stepsToggle.text = if (stepsEnabled) "◂  Back to a single response"
        else "▸  Respond in sequence (a different response per hit)…"
    }

    private fun addStep() {
        syncDrafts()
        stepDrafts.add(stepDrafts.lastOrNull()?.copy(body = "") ?: MockRuleForm.StepDraft())
        rebuildSteps()
    }

    private fun removeStep(index: Int) {
        syncDrafts()
        if (index in stepDrafts.indices) stepDrafts.removeAt(index)
        if (stepDrafts.isEmpty()) stepDrafts.add(MockRuleForm.StepDraft())
        rebuildSteps()
    }

    private fun moveStep(index: Int, delta: Int) {
        syncDrafts()
        val to = index + delta
        if (index !in stepDrafts.indices || to !in stepDrafts.indices) return
        val moved = stepDrafts.removeAt(index)
        stepDrafts.add(to, moved)
        rebuildSteps()
    }

    /** Pulls what the user typed back into the drafts before any structural change. */
    private fun syncDrafts() {
        if (stepCards.isEmpty()) return
        val edited = stepCards.map { it.toDraft() }
        stepDrafts.clear()
        stepDrafts.addAll(edited)
    }

    private fun rebuildSteps() {
        stepsHost.removeAll()
        stepCards.clear()
        stepDrafts.forEachIndexed { i, draft ->
            val card = StepCard(i, draft, last = i == stepDrafts.lastIndex)
            stepCards.add(card)
            stepsHost.add(card)
        }
        stepsHost.revalidate(); stepsHost.repaint()
    }

    /** The single-response fields as a step — how step 1 gets pre-filled from the capture. */
    private fun currentAsDraft() = MockRuleForm.StepDraft(
        status = status.text,
        body = currentBody(),
        headers = MockRuleForm.formatPairs(parseHeaders(headers.text)),
        contentType = contentType.text,
        latency = latency.text,
        behavior = behaviorKey(),
    )

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

    /**
     * One response in the sequence. Compact on purpose — a sequence is usually three variations
     * of one response, so the card shows the fields that differ between hits and keeps the body
     * a plain mono area rather than a second IDE editor per step.
     */
    private inner class StepCard(
        index: Int,
        draft: MockRuleForm.StepDraft,
        last: Boolean,
    ) : CardPanel(VerticalLayout(JBUI.scale(2))) {

        private val status = JBTextField(draft.status).fixedWidth(70)
        private val behaviorBox = ComboBox(behaviorItems.values.toTypedArray()).apply {
            selectedItem = behaviorItems[draft.behavior] ?: behaviorItems.values.first()
        }
        private val latency = JBTextField(draft.latency).fixedWidth(80)
        private val contentType = JBTextField(draft.contentType).fixedWidth(180)
        private val headers = JBTextField(draft.headers.replace("\n", "; ")).apply {
            emptyText.text = "X-Header: value; X-Other: value"
        }
        private val body = JBTextArea(draft.body, 4, 40).apply {
            font = JBUI.Fonts.create("JetBrains Mono", 12)
            lineWrap = false
        }

        init {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 10)

            val header = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel("Step ${index + 1}" + if (last) "   (repeats from here on)" else "").apply {
                    foreground = Theme.textDim
                    font = JBUI.Fonts.label(11f).asBold()
                }, BorderLayout.WEST)
                add(hbox(
                    iconLabel(AllIcons.Actions.PreviousOccurence, "Move up") { moveStep(index, -1) },
                    hgap(4),
                    iconLabel(AllIcons.Actions.NextOccurence, "Move down") { moveStep(index, +1) },
                    hgap(8),
                    iconLabel(AllIcons.General.Remove, "Remove step") { removeStep(index) },
                ), BorderLayout.EAST)
            }
            add(header)
            add(row2("Status", hbox(status, hgap(12), muted("On hit"), hgap(6), behaviorBox,
                hgap(12), muted("Latency"), hgap(6), latency, small("ms"), glue())))
            add(row2("Content-Type", hbox(contentType, hgap(12), muted("Headers"), hgap(6), headers)))
            add(row2("Body", JBScrollPane(body).apply {
                border = JBUI.Borders.customLine(Theme.borderStrong, 1)
                preferredSize = Dimension(JBUI.scale(500), JBUI.scale(84))
            }))
        }

        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

        fun toDraft() = MockRuleForm.StepDraft(
            status = status.text,
            body = body.text,
            headers = headers.text,
            contentType = contentType.text,
            latency = latency.text,
            behavior = behaviorItems.entries.firstOrNull { it.value == behaviorBox.selectedItem }?.key
                ?: MockRule.BEHAVIOR_NORMAL,
        )

        private fun row2(labelText: String, field: Component): JComponent =
            object : JPanel(BorderLayout(JBUI.scale(8), 0)) {
                override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
                add(JBLabel(labelText).apply {
                    foreground = Theme.textMuted
                    font = JBUI.Fonts.label(11f)
                    preferredSize = Dimension(JBUI.scale(84), preferredSize.height)
                }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            }
    }

    override fun doValidate(): ValidationInfo? {
        if (path.text.isBlank()) return ValidationInfo("Path pattern is required", path)
        if (stepsEnabled) {
            syncDrafts()
            if (stepDrafts.isEmpty()) {
                return ValidationInfo("Add at least one step, or go back to a single response", stepsHost)
            }
            for ((i, draft) in stepDrafts.withIndex()) {
                val code = draft.status.trim().toIntOrNull()
                if (code == null || code !in 100..599) {
                    return ValidationInfo("Step ${i + 1}: status must be 100–599", stepsHost)
                }
                if ((draft.latency.trim().toLongOrNull() ?: -1L) < 0L) {
                    return ValidationInfo("Step ${i + 1}: latency must be ≥ 0", stepsHost)
                }
            }
            if (serveLimit.text.toIntOrNull()?.let { it < 0 } != false) {
                return ValidationInfo("Serve limit must be ≥ 0", serveLimit)
            }
            return null
        }
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
        if (stepsEnabled) syncDrafts()
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
            matchQuery = MockRuleForm.parsePairs(matchQuery.text),
            matchHeaders = MockRuleForm.parsePairs(matchHeaders.text),
            matchBodyContains = matchBody.text.trim().ifBlank { null },
            // The single-response fields above stay populated even with a sequence: the device
            // ignores them while `responses` is non-empty, and keeping them means switching back
            // to a single response doesn't lose the body that was there.
            responses = if (stepsEnabled) MockRuleForm.toSteps(stepDrafts) else emptyList(),
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

    private fun link(text: String, onClick: () -> Unit) = LinkLabel(text, onClick)

    private fun iconLabel(icon: javax.swing.Icon, tip: String, onClick: () -> Unit) =
        IconButton(icon, tip, onClick)

    /** A short text area in a bordered box — the matcher inputs, which are 1–3 lines each. */
    private fun boxed(area: JBTextArea, height: Int): JComponent = JBScrollPane(area).apply {
        border = JBUI.Borders.customLine(Theme.borderStrong, 1)
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(height))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height))
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
