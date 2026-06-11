package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
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

/**
 * Detail pane: a hero Overview card on top, with Request and Response cards
 * side-by-side below. Each request/response card renders real JSON (body parsed
 * back into nested JSON) as a tree or raw, with Copy.
 */
class TransactionDetailView(project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()) {

    private val overview = OverviewPanel()
    private val request = JsonTreePanel("Request", project) { Theme.methodColor(currentMethod) }
    private val response = JsonTreePanel("Response", project)

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }
    private var current: Transaction? = null
    private var currentMethod: String = "GET"

    // Request headers (auth/api-key) are usually useful → shown by default. Response headers
    // (CSP, security, caching) are mostly noise → hidden until the user clicks "Headers".
    private var showReqHeaders = true
    private var showRespHeaders = false

    init {
        isOpaque = true
        background = Theme.bg0
        border = JBUI.Borders.empty(6)

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

    fun show(tx: Transaction?, dup: io.github.siddharthjaswal.logpose.analysis.DuplicateDetector.Mark? = null) {
        current = tx
        currentMethod = tx?.request?.method ?: "GET"
        overview.show(tx, dup)
        if (tx == null) {
            request.setElement(null); request.setStatus(null)
            response.setElement(null); response.setStatus(null)
            return
        }
        renderRequest(tx)
        renderResponse(tx)
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
