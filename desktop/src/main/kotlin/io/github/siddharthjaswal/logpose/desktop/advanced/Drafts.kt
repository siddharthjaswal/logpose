package io.github.siddharthjaswal.logpose.desktop.advanced

import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.PushMessage
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.MockRuleForm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The mock editor's fields, as the strings they hold — a functional form over core's [MockRule]
 * and [MockRuleForm], with the plugin `MockRuleDialog`'s validation and `result()` mapping.
 *
 * Deliberately not a pixel copy of the plugin's dialog: the response body is plain JSON text in
 * both modes. The plugin's *field tree* (tick fields to override, fold to focus) and its diff view
 * are not ported — in merge mode you type the JSON object to deep-merge instead.
 */
data class MockDraft(
    val id: String,
    val enabled: Boolean = true,
    val method: String = "GET",
    val path: String = "",
    val mode: String = MockRule.MODE_REPLACE,
    val behavior: String = MockRule.BEHAVIOR_NORMAL,
    val status: String = "200",
    val contentType: String = "application/json",
    val headers: String = "",
    val body: String = "",
    val latency: String = "0",
    val serveLimit: String = "0",
    val matchQuery: String = "",
    val matchHeaders: String = "",
    val matchBody: String = "",
    val stepsEnabled: Boolean = false,
    val steps: List<MockRuleForm.StepDraft> = emptyList(),
) {
    val patch: Boolean get() = mode == MockRule.MODE_PATCH

    /** The plugin dialog's validation; null when the draft can be saved. */
    fun problem(): String? {
        if (path.isBlank()) return "Path is required (exact, or a glob with *)"
        if (stepsEnabled) {
            if (steps.isEmpty()) return "Add at least one step"
            steps.forEachIndexed { i, step ->
                val code = step.status.trim().toIntOrNull()
                if (code == null || code !in 100..599) return "Step ${i + 1}: status must be 100–599"
                if ((step.latency.trim().toLongOrNull() ?: -1L) < 0L) return "Step ${i + 1}: latency must be ≥ 0"
            }
            if ((serveLimit.trim().toIntOrNull() ?: -1) < 0) return "Serve limit must be ≥ 0"
            return null
        }
        if (patch) {
            val text = body.trim()
            if (text.isEmpty() || text == "{}") return "Write the fields to override, as a JSON object"
            if (runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() == null) {
                return "Merge mode needs a JSON object, e.g. {\"status\":\"ACCEPTED\"}"
            }
        } else if (behavior == MockRule.BEHAVIOR_NORMAL) {
            val code = status.trim().toIntOrNull()
            if (code == null || code !in 100..599) return "Status must be 100–599"
        }
        if ((latency.trim().toLongOrNull() ?: -1L) < 0L) return "Latency must be ≥ 0"
        if ((serveLimit.trim().toIntOrNull() ?: -1) < 0) return "Serve limit must be ≥ 0"
        return null
    }

    /** The rule this draft describes — the plugin dialog's `result()`. */
    fun toRule(): MockRule = MockRule(
        id = id,
        method = method.trim().ifBlank { "*" }.uppercase(),
        pathPattern = path.trim(),
        status = status.trim().toIntOrNull() ?: 200,
        headers = MockRuleForm.parsePairs(headers).filterValues { it != MockRule.MATCH_ANY },
        body = body.ifBlank { null },
        contentType = contentType.trim().ifBlank { "application/json" },
        latencyMillis = latency.trim().toLongOrNull() ?: 0,
        behavior = if (patch) MockRule.BEHAVIOR_NORMAL else behavior,
        serveLimit = serveLimit.trim().toIntOrNull() ?: 0,
        enabled = enabled,
        mode = mode,
        matchQuery = MockRuleForm.parsePairs(matchQuery),
        matchHeaders = MockRuleForm.parsePairs(matchHeaders),
        matchBodyContains = matchBody.trim().ifBlank { null },
        // The single-response fields stay populated with a sequence: the device ignores them while
        // `responses` is non-empty, and switching back must not lose the body that was there.
        responses = if (stepsEnabled) MockRuleForm.toSteps(steps) else emptyList(),
    )

    /** Turns the sequence on, seeding step 1 from the single response (core's `draftsOf`). */
    fun withSteps(on: Boolean): MockDraft =
        if (!on) copy(stepsEnabled = false)
        else copy(stepsEnabled = true, steps = steps.ifEmpty { MockRuleForm.draftsOf(toRule().copy(responses = emptyList())) })

    companion object {
        private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }
        private val pretty = Json { prettyPrint = true }

        fun of(rule: MockRule) = MockDraft(
            id = rule.id,
            enabled = rule.enabled,
            method = rule.method.uppercase(),
            path = rule.pathPattern,
            mode = rule.mode,
            behavior = rule.behavior,
            status = rule.status.toString(),
            contentType = rule.contentType,
            headers = MockRuleForm.formatPairs(rule.headers),
            body = prettyBody(rule.body),
            latency = rule.latencyMillis.toString(),
            serveLimit = rule.serveLimit.toString(),
            matchQuery = MockRuleForm.formatPairs(rule.matchQuery),
            matchHeaders = MockRuleForm.formatPairs(rule.matchHeaders),
            matchBody = rule.matchBodyContains.orEmpty(),
            stepsEnabled = rule.responses.isNotEmpty(),
            steps = if (rule.responses.isNotEmpty()) MockRuleForm.draftsOf(rule) else emptyList(),
        )

        /** *Mock this endpoint…*: the plugin's `MockRuleDialog.fromTransaction`, then into a draft. */
        fun fromTransaction(tx: Transaction, id: String = UUID.randomUUID().toString().substring(0, 8)) = of(
            MockRule(
                id = id,
                method = tx.request.method,
                pathPattern = tx.request.path.ifBlank { tx.request.url },
                status = tx.response?.code ?: 200,
                body = tx.response?.body?.text,
                contentType = tx.response?.body?.contentType ?: "application/json",
            ),
        )

        fun prettyBody(text: String?): String {
            if (text.isNullOrBlank()) return ""
            return runCatching { pretty.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), lenient.parseToJsonElement(text)) }
                .getOrDefault(text)
        }
    }
}

/**
 * The compose-push form — the plugin `ComposePushDialog`'s fields and its data-payload rule: a JSON
 * object of simple values, stringified as a real push would; anything nested is refused rather than
 * silently encoded into something the backend never sends.
 */
data class PushDraft(
    val from: String = "",
    val collapseKey: String = "",
    val title: String = "",
    val body: String = "",
    val data: String = "{\n  \n}",
    /** The seed's fields the form doesn't show (priority, ttl, channel…), carried through untouched. */
    val seed: PushMessage = PushMessage(),
) {
    fun problem(): String? =
        if (data.isNotBlank() && parseData(data) == null) "Data must be a JSON object of simple values, e.g. {\"orderId\":\"42\"}" else null

    /** The composed push; `messageId` / `sentTimeMillis` are stamped at send time. */
    fun toMessage(): PushMessage = seed.copy(
        from = from.trim().ifBlank { null },
        collapseKey = collapseKey.trim().ifBlank { null },
        notificationTitle = title.trim().ifBlank { null },
        notificationBody = body.trim().ifBlank { null },
        data = parseData(data) ?: emptyMap(),
    )

    companion object {
        private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }
        private val pretty = Json { prettyPrint = true }

        fun of(seed: PushMessage) = PushDraft(
            from = seed.from.orEmpty(),
            collapseKey = seed.collapseKey.orEmpty(),
            title = seed.notificationTitle.orEmpty(),
            body = seed.notificationBody.orEmpty(),
            data = if (seed.data.isEmpty()) "{\n  \n}"
            else pretty.encodeToString(JsonObject.serializer(), JsonObject(seed.data.mapValues { JsonPrimitive(it.value) })),
            seed = seed,
        )

        fun parseData(text: String): Map<String, String>? {
            if (text.isBlank()) return emptyMap()
            val obj = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val out = LinkedHashMap<String, String>(obj.size)
            for ((key, value) in obj) out[key] = (value as? JsonPrimitive ?: return null).content
            return out
        }
    }
}
