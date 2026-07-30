package io.github.siddharthjaswal.logpose.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import io.github.siddharthjaswal.logpose.model.MockRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The native "original vs mock" diff. Shared so it opens the same way from the mock editor and
 * straight from a Mocks row (the quick-diff button) — eyeball what a rule changes without opening
 * the editor first.
 */
object MockDiff {
    private val prettyJson = Json { prettyPrint = true }
    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    /** The body the app actually receives for [rule]: the full body in replace mode, or the
     *  captured [baseBody] deep-merged with the patch in merge mode. */
    fun servedBody(rule: MockRule, baseBody: String?): String {
        val body = rule.body.orEmpty()
        if (rule.mode != MockRule.MODE_PATCH) return body
        val base = runCatching { lenientJson.parseToJsonElement(baseBody ?: "") }.getOrNull() ?: return body
        val patch = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() ?: return body
        return prettyJson.encodeToString(JsonElement.serializer(), mergeJson(base, patch))
    }

    /** Opens original (captured) vs what the app will receive, for a saved rule. */
    fun show(project: Project, rule: MockRule, baseBody: String?) {
        val f = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "${rule.method} ${rule.pathPattern}  —  original vs mock",
                f.create(project, pretty(baseBody), JsonFileType.INSTANCE),
                f.create(project, pretty(servedBody(rule, baseBody)), JsonFileType.INSTANCE),
                if (baseBody != null) "Original (captured)" else "Original (not captured)",
                "What the app will receive",
            ),
        )
    }

    fun pretty(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return runCatching {
            prettyJson.encodeToString(JsonElement.serializer(), lenientJson.parseToJsonElement(text))
        }.getOrDefault(text)
    }

    /** Local mirror of the device-side deep merge, for the "what the app will receive" preview. */
    fun mergeJson(base: JsonElement, patch: JsonElement): JsonElement {
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
}
