package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.mock.DeviceCapability
import io.github.siddharthjaswal.logpose.mock.DeviceFeature
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.MockRule
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Collapsible strip under the filter bar listing active mock rules — styled to match the
 * timeline rows (weighted method label, status pill, latency/hit chips) so mocks read as
 * first-class. Enable toggle, edit / delete, "Disable all", and the device sync state.
 * Rebuilt wholesale on each [refresh]; the rule count is small, so simplicity beats diffing.
 */
class MocksBar(
    private val onEdit: (MockRule) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onToggle: (String, Boolean) -> Unit,
    private val onDisableAll: () -> Unit,
    private val onDiff: (MockRule) -> Unit,
    /** Opens the scenarios menu under the clicked label (the panel owns the actions + file I/O). */
    private val onScenarios: (Component) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val rowsHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyTop(4)
    }
    private val syncDot = JBLabel("●").apply { font = JBUI.Fonts.label(9f) }
    private val syncText = JBLabel().apply { font = JBUI.Fonts.label(11f) }
    private val disableAll = LinkLabel("Disable all") { onDisableAll() }
    private val scenarios = LinkLabel("Scenarios ▾").apply {
        toolTipText = "Save the current rules, snapshot the session, or load a saved scenario " +
            "from .logpose/scenarios"
        onClick = { onScenarios(this) }
    }

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(Theme.borderStrong, 0, 0, 1, 0),
            JBUI.Borders.empty(7, 10, 8, 10),
        )

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(7), 0)).apply {
                isOpaque = false
                // A caption, not a state: it names the strip rather than saying anything about it,
                // so it sits in textDim and leaves the colour budget to the rules underneath.
                add(JBLabel("MOCKS").apply {
                    foreground = Theme.textDim; font = JBUI.Fonts.label(10.5f).asBold()
                })
                add(syncDot)
                add(syncText)
            }
            add(left, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(scenarios)
                add(disableAll)
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(rowsHost, BorderLayout.CENTER)
        isVisible = false
    }

    /**
     * Rebuilds the strip from the current rules + device state. Hides itself when there is
     * nothing to show — but *not* when the project has saved scenarios, since the strip is where
     * you load one, and a hidden strip would make a saved scenario unreachable.
     */
    fun refresh(rules: List<MockRule>, device: MocksController.DeviceState, scenarioCount: Int = 0) {
        rowsHost.removeAll()
        rules.forEach { rowsHost.add(ruleRow(it, device.hits[it.id] ?: 0, device)) }
        applySync(device)
        disableAll.isVisible = rules.any { it.enabled }
        scenarios.text = if (scenarioCount > 0) "Scenarios ($scenarioCount) ▾" else "Scenarios ▾"
        isVisible = rules.isNotEmpty() || scenarioCount > 0
        revalidate(); repaint()
    }

    /**
     * Three states, not two: a green dot used to appear the moment a device said hello, whether
     * or not it had actually taken the rules. Now green means the device acknowledged *this*
     * revision with the rule count we sent, amber means we're still waiting on it, and red means
     * the push failed or went unanswered — with the reason in the tooltip.
     */
    private fun applySync(device: MocksController.DeviceState) {
        val sync = device.sync
        val name = device.pkg ?: "device"
        var tip: String? = sync.message
        when {
            !device.helloSeen -> {
                syncDot.foreground = Theme.warn
                syncText.foreground = Theme.textMuted
                syncText.text = "waiting for device — needs logpose-android ≥ 1.1.0 + capture running"
                tip = "No device has announced itself yet. Start capture and run the app."
            }
            sync.phase == SyncState.Phase.FAILED -> {
                syncDot.foreground = Theme.danger
                syncText.foreground = Theme.danger
                syncText.text = "$name  ·  not synced — rules may not be live"
            }
            !device.capturing -> {
                syncDot.foreground = Theme.textMuted
                syncText.foreground = Theme.textMuted
                syncText.text = "$name  ·  capture stopped — device rules cleared"
                tip = "Rules stay here; they're pushed again when capture restarts."
            }
            sync.phase == SyncState.Phase.PENDING -> {
                syncDot.foreground = Theme.warn
                syncText.foreground = Theme.textDim
                syncText.text = "$name  ·  syncing rev ${sync.revision}…"
                tip = "Waiting for the device to acknowledge revision ${sync.revision}."
            }
            else -> {
                syncDot.foreground = Theme.ok
                syncText.foreground = Theme.textDim
                syncText.text = "$name  ·  synced rev ${device.syncedRevision}"
                tip = buildString {
                    append("Device acknowledged ${sync.expectedRules} rule(s) at revision ${sync.syncedRevision}")
                    device.libVersion?.let { append(" · logpose-android $it") }
                    append('.')
                }
            }
        }
        if (device.withheldRules > 0) {
            tip = (tip?.plus("\n") ?: "") +
                "${device.withheldRules} rule(s) withheld — they need logpose-android ≥ " +
                "${DeviceFeature.RICH_MATCHERS.since} on the device."
        }
        val html = tip?.let { "<html>${it.replace("\n", "<br/>")}</html>" }
        syncDot.toolTipText = html
        syncText.toolTipText = html
    }

    private fun ruleRow(rule: MockRule, hits: Int, device: MocksController.DeviceState): Component {
        val on = rule.enabled
        val toggle = ToggleSwitch(initialOn = on) { onToggle(rule.id, !on) }
        // The controller withholds rules the device's library can't honour; the row has to say so,
        // or a rule would sit there looking active while nothing on the device knows about it.
        val withheld = device.helloSeen && !DeviceCapability.canPush(rule, device.libVersion)

        val method = JLabel(rule.method, SwingConstants.LEFT).apply {
            // Same rule as the timeline row: no hue, weight does the work.
            font = if (Theme.isRead(rule.method)) JBUI.Fonts.label(11f) else JBUI.Fonts.label(11f).asBold()
            foreground = fade(Theme.methodTextColor(rule.method), on)
            val d = Dimension(JBUI.scale(46), JBUI.scale(20))
            preferredSize = d; minimumSize = d; maximumSize = d
        }
        val path = JLabel(rule.pathPattern).apply {
            font = JBUI.Fonts.label(12f)
            foreground = if (on) Theme.text else Theme.textMuted
            border = JBUI.Borders.emptyLeft(10)
        }

        val outcome = outcomePill(rule, on)
        val meta = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            if (withheld) {
                add(chip("lib ≥ ${DeviceFeature.RICH_MATCHERS.since}", Theme.warn, on).apply {
                    toolTipText = "Not sent to this device — it runs logpose-android " +
                        "${device.libVersion ?: "an older version"}, which would ignore this " +
                        "rule's constraints and match more broadly than it reads."
                })
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            // "only when …" is the difference between a rule that reads right and one that
            // matches right, so it gets a visible mark, not just a tooltip.
            if (MockRuleForm.matchSummary(rule) != null) {
                add(chip("only when", Theme.accent, on))
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            // "×3 steps" states a shape, not a severity — neutral, so the outcome pill beside it
            // stays the only coloured thing in the row's right half.
            MockRuleForm.stepsLabel(rule)?.let {
                add(chip(it, Theme.textDim, on))
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            add(outcome)
            val latency = rule.responses.firstOrNull()?.latencyMillis ?: rule.latencyMillis
            if (latency > 0) {
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(chip("${latency}ms", Theme.textMuted, on))
            }
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(JBLabel(if (hits > 0) "${hits}×" else "").apply {
                foreground = Theme.textMuted
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                val d = Dimension(JBUI.scale(34), JBUI.scale(20)); preferredSize = d; minimumSize = d
                horizontalAlignment = SwingConstants.RIGHT
            })
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            // Quick diff: original captured response vs what this rule serves, without opening the
            // editor. Only meaningful when the rule actually has a body to compare.
            if (!rule.body.isNullOrBlank()) {
                add(IconButton(AllIcons.Actions.Diff, "Diff vs original") { onDiff(rule) })
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            add(IconButton(AllIcons.Actions.Edit, "Edit") { onEdit(rule) })
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(IconButton(AllIcons.General.Remove, "Delete") { onDelete(rule.id) })
        }

        val west = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(toggle); add(method)
        }

        val tip = rowTooltip(rule, withheld, device)
        return CardRow().apply {
            border = JBUI.Borders.empty(3, 10)
            add(west, BorderLayout.WEST)
            add(path, BorderLayout.CENTER)
            add(meta, BorderLayout.EAST)
            toolTipText = tip
            path.toolTipText = tip
        }
    }

    /** Everything about the rule the row can't fit: what narrows it, and how a sequence plays. */
    private fun rowTooltip(rule: MockRule, withheld: Boolean, device: MocksController.DeviceState): String? {
        val lines = buildList {
            add("${rule.method} ${rule.pathPattern}")
            MockRuleForm.matchSummary(rule)?.let { add(it) }
            MockRuleForm.stepsSummary(rule)?.let { add(it) }
            if (rule.serveLimit > 0) add("serves ${rule.serveLimit} time(s), then deactivates")
            if (withheld) {
                add(
                    "NOT on the device — needs logpose-android ≥ " +
                        "${DeviceCapability.requiredVersion(rule) ?: DeviceFeature.RICH_MATCHERS.since}" +
                        (device.libVersion?.let { ", which reports $it" } ?: "")
                )
            }
        }
        return if (lines.size == 1 && !withheld) null
        else "<html>" + lines.joinToString("<br/>") + "</html>"
    }

    private fun outcomePill(rule: MockRule, on: Boolean): TagLabel = TagLabel().apply {
        font = JBUI.Fonts.label(11f).asBold()
        border = JBUI.Borders.empty(2, 8)
        // With a sequence, the pill shows the *first* response — what the next hit gets — and
        // the "×N steps" chip beside it says the rest is coming.
        val status = MockRuleForm.effectiveStatus(rule)
        val behavior = MockRuleForm.effectiveBehavior(rule)
        when {
            // Patch rules keep the backend's status — the response is merged, not replaced. A
            // merge is LogPose reaching into the response, so it wears the solid intervention
            // fill the MOCK pill wears in the row it will produce.
            rule.mode == MockRule.MODE_PATCH ->
                set("MERGE", fade(Theme.onAccent, on), fade(Theme.intervention, on))
            behavior == MockRule.BEHAVIOR_TIMEOUT ->
                set("TIMEOUT", fade(Theme.warn, on), tintFor(Theme.warn, on))
            behavior == MockRule.BEHAVIOR_CONNECTION_FAILURE ->
                set("FAILED", fade(Theme.danger, on), tintFor(Theme.danger, on))
            else -> {
                val c = Theme.statusColor(status, null)
                set(status.toString(), fade(c, on), if (on) Theme.statusTint(status, null) else Theme.tint(c, 14))
            }
        }
    }

    private fun chip(text: String, color: Color, on: Boolean) = TagLabel().apply {
        font = JBUI.Fonts.create("JetBrains Mono", 10)
        border = JBUI.Borders.empty(2, 7)
        set(text, fade(color, on), tintFor(color, on))
    }

    /** Dims a color when the rule is disabled, so the whole row reads as "off". */
    private fun fade(c: Color, on: Boolean): Color = if (on) c else Theme.fade(c, 0.5f)
    private fun tintFor(c: Color, on: Boolean): Color = Theme.tint(c, if (on) 26 else 12)

    /** A rounded card surface for one rule, with a little vertical breathing room below it. */
    private class CardRow : JPanel(BorderLayout()) {
        init { isOpaque = false }

        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width, d.height + JBUI.scale(4))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height - JBUI.scale(4)
            g2.color = Theme.bg1
            g2.fillRoundRect(0, 0, width - 1, h, JBUI.scale(8), JBUI.scale(8))
            g2.color = Theme.borderSubtle
            g2.drawRoundRect(0, 0, width - 1, h, JBUI.scale(8), JBUI.scale(8))
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
