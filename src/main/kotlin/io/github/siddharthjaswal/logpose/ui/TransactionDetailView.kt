package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder

/**
 * Detail pane: a hero Overview card on top, with Request and Response cards
 * side-by-side below. Each request/response card renders real JSON (body parsed
 * back into nested JSON) as a tree or raw, with Copy.
 */
class TransactionDetailView(project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()) {

    /** One call of a collapsed polling run: the transaction and the envelope it arrived in. */
    data class Occurrence(
        val tx: Transaction,
        val envelope: io.github.siddharthjaswal.logpose.model.Envelope?,
    )

    private val overview = OverviewPanel()
    private val request = JsonTreePanel("Request", project)
    private val response = JsonTreePanel("Response", project)

    /** Opens the trace waterfall for the flow this call belongs to (see [OverviewPanel]). */
    var onOpenTrace: (String) -> Unit
        get() = overview.onOpenTrace
        set(value) { overview.onOpenTrace = value }

    /**
     * Reports the transaction id the stepper moved to, so the owner can pin it: once a user has
     * walked back to occurrence 3 of a live poll, a new call landing must not yank the card
     * forward to 31 under them.
     */
    var onOccurrenceChanged: (String) -> Unit = {}

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }
    private var current: Transaction? = null

    // The calls a collapsed row stands for, in arrival order, and which one is on screen. Empty for
    // every ordinary selection — [show] clears it, so a stepper can never outlive its run.
    private var occurrences: List<Occurrence> = emptyList()
    private var occurrenceIndex = 0
    private var duplicateOf: (Transaction) -> io.github.siddharthjaswal.logpose.analysis.DuplicateDetector.Mark? = { null }

    // Request headers (auth/api-key) are usually useful → shown by default. Response headers
    // (CSP, security, caching) are mostly noise → hidden until the user clicks "Headers".
    private var showReqHeaders = true
    private var showRespHeaders = false

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty(8)

        // The arrows re-render the whole card, not just the hero: the Request and Response JSON
        // belong to the occurrence too, and a stepper that moved only the status pill would be
        // showing one call's headline over another call's body.
        overview.onStep = { delta -> step(delta) }

        overview.onCopyCurl = { current?.let { copy(CurlBuilder.build(it), "cURL copied") } }
        overview.onCopyJson = { current?.let { copy(pretty.encodeToString(Transaction.serializer(), it), "Transaction JSON copied") } }

        request.setHeadersToggle(showReqHeaders) { on -> showReqHeaders = on; current?.let { renderRequest(it) } }
        response.setHeadersToggle(showRespHeaders) { on -> showRespHeaders = on; current?.let { renderResponse(it) } }

        val bottom = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = pad(request, 160, 60)
            secondComponent = pad(response, 160, 60)
            setHonorComponentsMinimumSize(true)
            minimumSize = Dimension(0, JBUI.scale(140))
        }
        val outer = OnePixelSplitter(true, 0.30f).apply {
            firstComponent = pad(overview, 0, 96)
            secondComponent = bottom
            setHonorComponentsMinimumSize(true)
        }
        add(outer, BorderLayout.CENTER)
    }

    fun show(
        tx: Transaction?,
        dup: io.github.siddharthjaswal.logpose.analysis.DuplicateDetector.Mark? = null,
        envelope: io.github.siddharthjaswal.logpose.model.Envelope? = null,
    ) {
        occurrences = emptyList()
        occurrenceIndex = 0
        current = tx
        overview.show(tx, dup, envelope)
        if (tx == null) {
            request.setElement(null); request.setStatus(null)
            response.setElement(null); response.setStatus(null)
            return
        }
        renderRequest(tx)
        renderResponse(tx)
    }

    /**
     * Shows one call out of the run a collapsed row stands for, with the `occurrence n / N` stepper.
     *
     * [index] is 0-based over [items] in arrival order, so `items.lastIndex` is the latest call —
     * which is what a freshly selected run opens on (§6: the row shows the latest timestamp, and
     * the card should agree with the row).
     */
    fun showOccurrences(
        items: List<Occurrence>,
        index: Int,
        dupOf: (Transaction) -> io.github.siddharthjaswal.logpose.analysis.DuplicateDetector.Mark?,
    ) {
        if (items.isEmpty()) {
            show(null)
            return
        }
        occurrences = items
        duplicateOf = dupOf
        occurrenceIndex = index.coerceIn(0, items.lastIndex)
        renderOccurrence()
    }

    private fun step(delta: Int) {
        if (occurrences.isEmpty()) return
        val next = (occurrenceIndex + delta).coerceIn(0, occurrences.lastIndex)
        if (next == occurrenceIndex) return
        occurrenceIndex = next
        renderOccurrence()
        onOccurrenceChanged(occurrences[next].tx.id)
    }

    private fun renderOccurrence() {
        val item = occurrences[occurrenceIndex]
        current = item.tx
        // The duplicate banner is per-occurrence and *should* appear when you step onto a marked
        // call: the row's aggregate pill is what invited the user to come and look.
        overview.show(
            item.tx,
            duplicateOf(item.tx),
            item.envelope,
            OverviewPanel.Occurrence(occurrenceIndex + 1, occurrences.size),
        )
        renderRequest(item.tx)
        renderResponse(item.tx)
    }

    private fun renderRequest(tx: Transaction) {
        request.setStatus(tx.request.method)
        request.setElement(requestJson(tx))
    }

    private fun renderResponse(tx: Transaction) {
        when {
            tx.isPending() -> {
                response.setStatus("…")
                response.showMessage("Waiting for response…")
            }
            // The call never returned a response — OkHttp (or a downstream interceptor) threw.
            // Show the captured exception instead of a bare "(failed)" so the failure is diagnosable.
            tx.error != null -> {
                response.setStatus("Failed")
                response.setElement(responseJson(tx))
            }
            else -> {
                response.setStatus(tx.response?.let { "${it.code} ${it.message}".trim() } ?: "—")
                response.setElement(responseJson(tx))
            }
        }
    }

    /** Live update for an in-flight request — ticking duration + spinning loader. */
    fun tick(elapsedMs: Long, frame: Int) {
        if (current?.isPending() != true) return
        overview.tick(elapsedMs, frame)
        response.setLoadingText("${spinnerChar(frame)}  Waiting for response…   ${elapsedMs}ms")
    }

    fun showError(message: String) {
        current = null
        occurrences = emptyList()
        occurrenceIndex = 0
        overview.show(null)
        request.showMessage(message); request.setStatus(null)
        response.setElement(null); response.setStatus(null)
    }

    private fun pad(c: Component, minW: Int, minH: Int): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        minimumSize = Dimension(JBUI.scale(minW), JBUI.scale(minH))
        add(c, BorderLayout.CENTER)
    }

    private fun copy(text: String, label: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Toast.show(overview, label)
    }

    private fun requestJson(tx: Transaction): JsonElement = buildJsonObject {
        put("method", tx.request.method)
        put("url", tx.request.url)
        if (tx.request.host.isNotBlank()) put("host", tx.request.host)
        if (tx.request.path.isNotBlank()) put("path", tx.request.path)
        if (showReqHeaders) put("headers", buildJsonObject { tx.request.headers.forEach { (k, v) -> put(k, v) } })
        bodyElement(tx.request.body)?.let { put("body", it) }
    }

    private fun responseJson(tx: Transaction): JsonElement {
        val r = tx.response ?: return buildJsonObject {
            // No response object reached the interceptor. Surface the exception text (connection
            // reset, timeout, cleartext-not-permitted, a downstream interceptor's throw, …) which
            // the wire transaction carries in `error` — this is the most useful thing we can show.
            if (tx.error != null) put("error", tx.error) else put("status", "(pending)")
        }
        return buildJsonObject {
            put("code", r.code)
            if (r.message.isNotBlank()) put("message", r.message)
            if (showRespHeaders) put("headers", buildJsonObject { r.headers.forEach { (k, v) -> put(k, v) } })
            bodyElement(r.body)?.let { put("body", it) }
        }
    }

    private fun bodyElement(body: Body?): JsonElement? {
        if (body == null) return null
        body.parts?.let { parts ->
            return buildJsonArray {
                parts.forEach { p ->
                    add(buildJsonObject {
                        p.name?.let { put("name", it) }
                        p.filename?.let { put("filename", it) }
                        p.contentType?.let { put("contentType", it) }
                        put("sizeBytes", p.sizeBytes)
                    })
                }
            }
        }
        val text = body.text ?: return JsonPrimitive("(empty)")
        return runCatching { lenient.parseToJsonElement(text) }.getOrNull() ?: JsonPrimitive(text)
    }
}
