package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the per-kind detail cards *say* — the words, decided once, for every host.
 *
 * [RowContent] is the timeline row's half of this; this is the detail card's. The IDE plugin's
 * Swing cards and the Mac app's Compose cards both read these, so a duplicate banner, a status
 * pill or a push summary can't come to read differently in the two (PRD §11, "paint parity
 * drift"). Colours stay out, as in [RowContent]: severities are named, and the host maps them.
 */
object DetailContent {

    // ---- http hero -------------------------------------------------------------------------------

    /**
     * The hero status pill's label: `200 OK`, `404 Not Found`, `ERR`. The response's own message
     * wins; a standard reason phrase fills in when the server sent none (HTTP/2 never does).
     * A pending call's label is the host's (it carries a live spinner).
     */
    fun statusLabel(tx: Transaction, abandoned: String? = null): String {
        val code = tx.response?.code
        return when {
            tx.error != null -> "ERR"
            code != null -> "$code ${tx.response?.message?.ifBlank { reason(code) } ?: reason(code)}".trim()
            abandoned != null -> RowContent.abandonedLabel(abandoned)
            else -> "pending"
        }
    }

    /**
     * The strip above an abandoned span's hero: why no answer is coming. Neutral, like the row —
     * an abandoned call is not a failure the device reported, just one that can no longer finish.
     */
    fun abandonedBanner(reason: String): String = when (reason) {
        LogEvent.REASON_APP_RESTARTED ->
            "⊘  ABANDONED  ·  The app restarted before this finished — a newer run of the same app " +
                "announced itself, so the process that started it is gone and no answer is coming."
        LogEvent.REASON_DEVICE_DISCONNECTED ->
            "⊘  ABANDONED  ·  The device disconnected before this finished, so LogPose can't see " +
                "how it ended. It clears if the same app run reconnects and reports it."
        else -> "⊘  ABANDONED  ·  ${reason.replaceFirstChar { it.uppercase() }} — this can no longer finish."
    }

    /** The strip above a repeated analytics / app event: `⚠  DUPLICATE ×3  ·  Double-fired event — …`. */
    fun burstBanner(name: String, mark: io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Mark): String =
        "⚠  DUPLICATE ×${mark.ordinal}  ·  ${burstHeadline(mark)} — ${burstDetail(name, mark)}"

    fun burstHeadline(mark: io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Mark): String = when (mark.pattern) {
        io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Pattern.DOUBLE_FIRE -> "Double-fired event"
        io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Pattern.BURST -> "Event burst"
    }

    private fun burstDetail(name: String, mark: io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Mark): String =
        "${ordinalWord(mark.ordinal)} of ${mark.count} identical “$name” events (same screen and params) " +
            "fired in quick succession; each one counts in your analytics."

    /** A repeated event row's hover hint. */
    fun burstHint(name: String, mark: io.github.siddharthjaswal.logpose.analysis.EventBurstDetector.Mark): RowContent.Hint =
        RowContent.Hint(burstHeadline(mark), "", listOf(burstDetail(name, mark)))

    fun reason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"; else -> ""
    }

    /**
     * A server-side trace/request id from the response (then request) headers, as
     * `"trace id" | "req id"` to the value — preferred on the hero over LogPose's internal id,
     * because it is what a backend log lookup needs.
     */
    fun serverTraceId(tx: Transaction): Pair<String, String>? {
        fun find(headers: Map<String, String>): Pair<String, String>? {
            for (key in TRACE_HEADERS) {
                val match = headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                if (match != null && match.value.isNotBlank()) {
                    val label = if (key == "traceparent" || key.contains("trace")) "trace id" else "req id"
                    return label to match.value
                }
            }
            return null
        }
        return tx.response?.let { find(it.headers) } ?: find(tx.request.headers)
    }

    private val TRACE_HEADERS = listOf(
        "x-request-id", "x-correlation-id", "x-amzn-requestid", "x-amzn-trace-id",
        "x-trace-id", "x-span-id", "request-id", "traceparent", "x-b3-traceid",
    )

    /** The duplicate strip above the hero: `⚠  DUPLICATE ×2  ·  Possible double-submit — …`. */
    data class DuplicateBanner(val severity: DuplicateDetector.Severity, val text: String)

    fun duplicateBanner(tx: Transaction, dup: DuplicateDetector.Mark): DuplicateBanner {
        val detail = if (dup.severity == DuplicateDetector.Severity.STRONG)
            " — fired before the previous ${tx.request.method} responded; check for a missing debounce or disabled-button guard."
        else
            " — ${ordinalWord(dup.ordinal)} identical ${tx.request.method} within a short burst."
        return DuplicateBanner(dup.severity, "⚠  DUPLICATE ×${dup.ordinal}  ·  ${duplicateHeadline(dup.severity)}$detail")
    }

    fun duplicateHeadline(severity: DuplicateDetector.Severity): String = when (severity) {
        DuplicateDetector.Severity.STRONG -> "Possible double-submit"
        DuplicateDetector.Severity.MEDIUM -> "Redundant duplicate request"
        DuplicateDetector.Severity.INFO -> "Repeated request"
    }

    /** A duplicate row's hover hint. */
    fun duplicateHint(tx: Transaction, mark: DuplicateDetector.Mark): RowContent.Hint = RowContent.Hint(
        bold = duplicateHeadline(mark.severity),
        rest = "",
        lines = buildList {
            add("${ordinalWord(mark.ordinal)} identical ${tx.request.method} call in a quick burst.")
            if (mark.severity == DuplicateDetector.Severity.STRONG) {
                add("It fired before the previous one responded — likely a missing debounce or disabled-button guard.")
            }
        },
    )

    fun ordinalWord(n: Int): String = when (n) {
        2 -> "2nd"; 3 -> "3rd"; else -> "${n}th"
    }

    // ---- http panes ------------------------------------------------------------------------------

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The Request pane's tree: method, url, host, path, headers (when shown — request headers
     * carry the auth/api-key a reader usually wants, so hosts default them **on**), and the body
     * parsed back into nested JSON.
     */
    fun requestJson(tx: Transaction, showHeaders: Boolean): JsonElement = buildJsonObject {
        put("method", tx.request.method)
        put("url", tx.request.url)
        if (tx.request.host.isNotBlank()) put("host", tx.request.host)
        if (tx.request.path.isNotBlank()) put("path", tx.request.path)
        if (showHeaders) put("headers", buildJsonObject { tx.request.headers.forEach { (k, v) -> put(k, v) } })
        bodyElement(tx.request.body)?.let { put("body", it) }
    }

    /**
     * The Response pane's tree. Response headers (CSP, security, caching) are mostly noise, so
     * hosts default them **off**. A call that never got a response shows the exception the wire
     * carries in `error` — the most useful thing there is to show.
     */
    fun responseJson(tx: Transaction, showHeaders: Boolean): JsonElement {
        val r = tx.response ?: return buildJsonObject {
            if (tx.error != null) put("error", tx.error) else put("status", "(pending)")
        }
        return buildJsonObject {
            put("code", r.code)
            if (r.message.isNotBlank()) put("message", r.message)
            if (showHeaders) put("headers", buildJsonObject { r.headers.forEach { (k, v) -> put(k, v) } })
            bodyElement(r.body)?.let { put("body", it) }
        }
    }

    /** The Response pane's status caption: `200 OK`, `Failed`, `…` while in flight. */
    fun responseStatus(tx: Transaction, abandoned: String? = null): String = when {
        RowContent.isPending(tx) && abandoned != null -> RowContent.ABANDONED_STATUS
        RowContent.isPending(tx) -> "…"
        tx.error != null -> "Failed"
        else -> tx.response?.let { "${it.code} ${it.message}".trim() } ?: "—"
    }

    /** A body as JSON when it parses, multipart parts as their metadata, else the text itself. */
    fun bodyElement(body: Body?): JsonElement? {
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

    // ---- fcm hero --------------------------------------------------------------------------------

    /** A composed view of a push for its payload tree: notification, data map, and token. */
    fun fcmPayloadJson(msg: FcmMessage): JsonElement = buildJsonObject {
        put("event", msg.event)
        msg.notification?.let { n ->
            put("notification", buildJsonObject {
                n.title?.let { put("title", it) }
                n.body?.let { put("body", it) }
                n.channelId?.let { put("channelId", it) }
                n.clickAction?.let { put("clickAction", it) }
                n.imageUrl?.let { put("imageUrl", it) }
            })
        }
        if (msg.data.isNotEmpty()) put("data", buildJsonObject { msg.data.forEach { (k, v) -> put(k, v) } })
        msg.token?.let { put("token", it) }
    }


    /** The hero's headline beside the TOKEN/NOTIF/DATA pill. */
    fun fcmEventLabel(msg: FcmMessage): String = if (msg.event == "token") "Token refresh" else "Push message"

    /**
     * The hero summary — fuller than the row's: a token refresh shows the token itself, and a
     * notification shows title and body on two lines.
     */
    fun fcmSummary(msg: FcmMessage): String = when {
        msg.event == "token" -> msg.token ?: "(token)"
        msg.notification != null -> listOfNotNull(
            msg.notification?.title?.takeIf { it.isNotBlank() },
            msg.notification?.body?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { "(notification)" }
        else -> RowContent.fcmChannel(msg)
            ?: msg.from?.takeIf { it.isNotBlank() }?.let { "from $it" }
            ?: "(data message)"
    }

    fun fcmPriority(p: Int): String = when (p) {
        1 -> "high"; 2 -> "normal"; else -> p.toString()
    }

    /** Says out loud that LogPose delivered this push — the detail half of the row's INJ pill. */
    const val INJECTED_BANNER = "⚡  Injected by LogPose — delivered into the app on your behalf, not by Firebase"

    // ---- stat chips ------------------------------------------------------------------------------

    /**
     * One stat chip on a hero card: an uppercase caption over a mono value, with an optional hover
     * [tip]. [traceLink] is set on the LogPose trace chip — the full trace id — because that chip
     * is also the way into the flow's waterfall; what a click opens is the host's business.
     */
    data class Chip(val caption: String, val value: String, val tip: String? = null, val traceLink: String? = null)

    private val chipClock = ThreadLocal.withInitial { java.text.SimpleDateFormat("HH:mm:ss.SSS") }
    private fun at(millis: Long): String = chipClock.get().format(java.util.Date(millis))

    /**
     * The HTTP hero's chips: duration (a pending call's is a live placeholder the host updates),
     * size, started, host, the **server's** trace/request id when a header carries one — what a
     * backend log lookup needs — else LogPose's own id, then the LogPose trace when the app set one.
     */
    fun httpChips(
        tx: Transaction,
        envelope: io.github.siddharthjaswal.logpose.model.Envelope?,
        abandoned: String? = null,
    ): List<Chip> = buildList {
        // An abandoned call has no duration to report — not 0, not a live count: none.
        if (RowContent.isPending(tx) && abandoned != null) {
            Unit
        } else if (RowContent.isPending(tx)) {
            add(Chip(CAPTION_DURATION, "0 ms"))
        } else {
            tx.durationMillis?.let { add(Chip(CAPTION_DURATION, "$it ms")) }
            tx.response?.body?.sizeBytes?.takeIf { it >= 0 }?.let { add(Chip("size", RowContent.humanSize(it))) }
        }
        if (tx.startedAtMillis > 0) add(Chip("started", at(tx.startedAtMillis)))
        if (tx.request.host.isNotBlank()) add(Chip("host", tx.request.host.substringBefore('.')))
        val trace = serverTraceId(tx)
        if (trace != null) add(Chip(trace.first, ellipsize(trace.second, 14), tip = trace.second))
        else add(Chip("id", tx.id, tip = "Internal LogPose correlation id"))
        envelope?.traceId?.takeIf { it.isNotBlank() }?.let { add(Chip("trace", ellipsize(it, 14), traceLink = it)) }
    }

    /** The caption of the HTTP hero's duration chip — the one a pending call updates live. */
    const val CAPTION_DURATION = "duration"

    /** A pending call's live duration chip value. */
    fun liveDuration(elapsedMillis: Long): String = "$elapsedMillis ms"

    /**
     * The FCM hero's chips. [envelope] carries the trace, parent and — for an injected push, which
     * has no device receive-time of its own — the timestamp.
     */
    fun fcmChips(msg: FcmMessage, envelope: io.github.siddharthjaswal.logpose.model.Envelope?): List<Chip> = buildList {
        fun e(s: String) = ellipsize(s, 16)
        val receivedAt = msg.receivedAtMillis.takeIf { it > 0 } ?: envelope?.at ?: 0
        RowContent.fcmChannel(msg)?.let { add(Chip("channel", e(it), tip = it)) }
        if (receivedAt > 0) add(Chip("at", at(receivedAt)))
        msg.sentTimeMillis?.takeIf { it > 0 }?.let { add(Chip("sent", at(it))) }
        msg.from?.takeIf { it.isNotBlank() }?.let { add(Chip("from", e(it), tip = it)) }
        msg.priority?.let { add(Chip("priority", fcmPriority(it))) }
        msg.ttlSeconds?.let { add(Chip("ttl", "${it}s")) }
        msg.collapseKey?.takeIf { it.isNotBlank() }?.let { add(Chip("collapse", e(it), tip = it)) }
        if (msg.data.isNotEmpty()) add(Chip("data", "${msg.data.size}"))
        envelope?.traceId?.takeIf { it.isNotBlank() }?.let { add(Chip("trace", e(it), traceLink = it)) }
        envelope?.parentId?.takeIf { it.isNotBlank() }?.let { add(Chip("parent", e(it), tip = it)) }
        val idShown = msg.messageId?.takeIf { it.isNotBlank() } ?: msg.id
        add(Chip("id", e(idShown), tip = idShown))
    }

    /**
     * The generic card's chips (db / worker / config / app-defined). `took` is still the whole span
     * (a worker's queue + run); the worker's own Timing section is what breaks that down.
     */
    fun genericChips(event: LogEvent): List<Chip> = buildList {
        if (event.timestampMillis > 0) add(Chip("at", at(event.timestampMillis)))
        event.durationMillis?.let { add(Chip("took", RowContent.shortDuration(it))) }
        event.abandoned?.takeIf { event.isOpen }?.let { add(Chip("state", RowContent.abandonedLabel(it))) }
            ?: run { if (event.isOpen) add(Chip("state", "running")) }
        event.traceId?.takeIf { it.isNotBlank() }?.let { add(Chip("trace", it, traceLink = it)) }
        event.envelope.parentId?.let { add(Chip("parent", it, tip = it)) }
        add(Chip("id", event.id, tip = event.id))
    }

    // ---- worker ----------------------------------------------------------------------------------

    /**
     * A worker's **observed** transitions as `(time, "state  ·  attempt N")` rows — empty unless
     * there is a sequence ([io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle.hasTransitions]);
     * one sighting is not a transition, and a view that opens empty is worse than none.
     */
    fun workerTransitions(transitions: List<io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle.Transition>): List<Pair<String, String>> =
        if (transitions.size < 2) emptyList() else transitions.map { t ->
            val stamp = t.atMillis.takeIf { it > 0 } ?: t.hostMillis
            at(stamp) to (t.state + if (t.runAttempt > 1) "  ·  attempt ${t.runAttempt}" else "")
        }

    /** What that list is — sightings, not WorkManager's own history. Wherever it renders, this does. */
    const val TRANSITIONS_NOTE =
        "These are the transitions LogPose observed. WorkManager can move between two states in the gap " +
            "between observer emissions, so this is a record of sightings, not its full history."

    // ---- shared ----------------------------------------------------------------------------------

    /**
     * A row as one line of a copied timeline. The name comes from [KindPresenter.rowLabel] — the
     * same one the waterfall's lanes use — with the kind prefixed, since a pasted timeline has no
     * glyph to carry it.
     */
    fun timelineLine(event: LogEvent): String = when (event) {
        is LogEvent.Http -> KindPresenter.rowLabel(event)   // already leads with the method
        is LogEvent.Fcm -> "FCM ${KindPresenter.rowLabel(event)}"
        else -> "${KindPresenter.kindLabel(event)} ${KindPresenter.rowLabel(event)}"
    }

    /** Trims [s] to [max] characters, keeping the ellipsis inside the budget. */
    fun ellipsize(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"
}
