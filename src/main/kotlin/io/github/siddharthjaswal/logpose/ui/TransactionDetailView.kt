package io.github.siddharthjaswal.logpose.ui

import com.intellij.ui.OnePixelSplitter
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
import javax.swing.JPanel

/**
 * Detail pane laid out as three JSON-tree sections:
 *
 *   ┌──────────────── Overview ────────────────┐
 *   ├──────────────────┬───────────────────────┤
 *   │     Request      │       Response        │
 *   └──────────────────┴───────────────────────┘
 *
 * Each section renders its own JSON (request/response are real objects, with the
 * body parsed back into nested JSON) and offers Copy-as-JSON.
 */
class TransactionDetailView : JPanel(BorderLayout()) {

    private val overview = JsonTreePanel("Overview")
    private val request = JsonTreePanel("Request")
    private val response = JsonTreePanel("Response")

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        val bottom = OnePixelSplitter(false, 0.5f).apply {
            firstComponent = request
            secondComponent = response
        }
        val outer = OnePixelSplitter(true, 0.22f).apply {
            firstComponent = overview
            secondComponent = bottom
        }
        add(outer, BorderLayout.CENTER)
    }

    fun show(tx: Transaction?) {
        if (tx == null) {
            overview.setElement(null); request.setElement(null); response.setElement(null)
            return
        }
        overview.setElement(overviewJson(tx))
        request.setElement(requestJson(tx))
        response.setElement(responseJson(tx))
    }

    fun showError(message: String) {
        overview.showMessage(message)
        request.setElement(null)
        response.setElement(null)
    }

    private fun overviewJson(tx: Transaction): JsonElement = buildJsonObject {
        put("id", tx.id)
        put("method", tx.request.method)
        tx.response?.let { put("status", it.code) }
        tx.durationMillis?.let { put("durationMs", it) }
        if (tx.startedAtMillis > 0) put("startedAtMillis", tx.startedAtMillis)
        put("url", tx.request.url)
        tx.error?.let { put("error", it) }
    }

    private fun requestJson(tx: Transaction): JsonElement = buildJsonObject {
        put("method", tx.request.method)
        put("url", tx.request.url)
        if (tx.request.host.isNotBlank()) put("host", tx.request.host)
        if (tx.request.path.isNotBlank()) put("path", tx.request.path)
        put("headers", buildJsonObject { tx.request.headers.forEach { (k, v) -> put(k, v) } })
        bodyElement(tx.request.body)?.let { put("body", it) }
    }

    private fun responseJson(tx: Transaction): JsonElement {
        val r = tx.response ?: return JsonPrimitive(if (tx.error != null) "(failed)" else "(pending)")
        return buildJsonObject {
            put("code", r.code)
            if (r.message.isNotBlank()) put("message", r.message)
            put("headers", buildJsonObject { r.headers.forEach { (k, v) -> put(k, v) } })
            bodyElement(r.body)?.let { put("body", it) }
        }
    }

    /** Body as JSON: parsed object if it's JSON, raw string if not, or a parts array. */
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
