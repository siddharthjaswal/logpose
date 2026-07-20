package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The read side of LogPose's MCP server: turning a live capture into answers an agent can act
 * on.
 *
 * This layer is deliberately free of HTTP and IntelliJ plumbing — it takes a snapshot of
 * events and returns JSON — so the query behavior is unit-testable and the transport in
 * [LogPoseMcpHandler] stays a thin shell.
 *
 * Everything here is read-only. Writes (creating mocks) are separate, so a client can be given
 * read access without the ability to change what the app receives.
 */
object McpTools {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** The tool catalogue, in MCP's `tools/list` shape. */
    fun catalogue(): JsonArray = buildJsonArray {
        add(
            tool(
                "list_events",
                "List captured events from the running app, newest last. Use this first to see " +
                    "what the app actually did. Returns compact summaries; call get_event for " +
                    "full request/response bodies.",
            ) {
                put("limit", intProp("Max events to return (default 50)."))
                put("kind", stringProp("Filter by kind: 'http', 'fcm', or an app-defined kind."))
                put("method", stringProp("HTTP method filter, e.g. 'POST'."))
                put("status_class", intProp("HTTP status class: 2, 3, 4 or 5."))
                put("contains", stringProp("Substring match on URL, title, or subtitle."))
                put("failed_only", boolProp("Only failures: non-2xx responses and errors."))
                put("since_seconds", intProp("Only events captured in the last N seconds."))
            },
        )
        add(
            tool(
                "get_event",
                "Full detail for one event, including request and response bodies and headers. " +
                    "Use the id from list_events.",
            ) {
                put("id", stringProp("Event id.", required = true))
            },
        )
        add(
            tool(
                "get_trace",
                "All events sharing a trace id, in order — e.g. a push and the API calls it " +
                    "triggered. Use it to answer 'what led to this?'.",
            ) {
                put("trace_id", stringProp("Trace id.", required = true))
            },
        )
        add(
            tool(
                "find_failures",
                "The failing calls in the capture: non-2xx responses, transport errors, and " +
                    "timeouts, newest last.",
            ) {
                put("limit", intProp("Max failures to return (default 20)."))
            },
        )
        add(
            tool(
                "session_summary",
                "Overview of the current capture: how many events, which endpoints, failure " +
                    "count, duplicate calls, and the time span covered.",
            ) {}
        )
    }

    /**
     * Execute a tool call. [events] is a snapshot, [hostAgeMillis] maps an event id to how long
     * ago the plugin saw it (device clocks can't be diffed against the host's).
     *
     * Unknown tools return an error result rather than throwing: an agent handles a message far
     * better than a transport-level failure.
     */
    fun call(
        name: String,
        args: JsonObject,
        events: List<LogEvent>,
        hostAgeMillis: (String) -> Long,
        includeBodies: Boolean,
    ): JsonElement = when (name) {
        "list_events" -> listEvents(args, events, hostAgeMillis)
        "get_event" -> getEvent(args, events, includeBodies)
        "get_trace" -> getTrace(args, events)
        "find_failures" -> findFailures(args, events)
        "session_summary" -> summary(events)
        else -> buildJsonObject { put("error", "Unknown tool '$name'") }
    }

    // ---- tools ---------------------------------------------------------------------------

    private fun listEvents(
        args: JsonObject,
        events: List<LogEvent>,
        hostAgeMillis: (String) -> Long,
    ): JsonElement {
        val limit = args.int("limit") ?: 50
        val kind = args.str("kind")
        val method = args.str("method")?.uppercase()
        val statusClass = args.int("status_class")
        val contains = args.str("contains")
        val failedOnly = args.bool("failed_only") ?: false
        val sinceSeconds = args.int("since_seconds")

        val matched = events.filter { event ->
            if (kind != null && !event.kind.equals(kind, ignoreCase = true)) return@filter false
            if (sinceSeconds != null && hostAgeMillis(event.id) > sinceSeconds * 1000L) return@filter false
            if (failedOnly && !event.isFailure()) return@filter false
            val tx = (event as? LogEvent.Http)?.tx
            if (method != null && tx?.request?.method?.uppercase() != method) return@filter false
            if (statusClass != null && (tx?.response?.code ?: 0) / 100 != statusClass) return@filter false
            if (contains != null && !event.haystack().contains(contains, ignoreCase = true)) return@filter false
            true
        }

        // Newest last: an agent reads top-to-bottom, and the most recent event is usually the
        // one being asked about.
        val page = matched.takeLast(limit)
        return buildJsonObject {
            put("total_matched", matched.size)
            put("returned", page.size)
            if (matched.size > page.size) {
                put("note", "Showing the ${page.size} most recent of ${matched.size} matches.")
            }
            put("events", buildJsonArray { page.forEach { add(brief(it)) } })
        }
    }

    private fun getEvent(args: JsonObject, events: List<LogEvent>, includeBodies: Boolean): JsonElement {
        val id = args.str("id") ?: return buildJsonObject { put("error", "Missing 'id'") }
        val event = events.firstOrNull { it.id == id }
            ?: return buildJsonObject { put("error", "No event with id '$id'") }

        return buildJsonObject {
            put("id", event.id)
            put("kind", event.kind)
            put("at", event.timestampMillis)
            event.durationMillis?.let { put("duration_ms", it) }
            if (event.isOpen) put("in_flight", true)
            event.traceId?.let { put("trace_id", it) }
            event.envelope.parentId?.let { put("parent_id", it) }
            put("summary", summarize(event))
            if (includeBodies) {
                put("payload", event.envelope.payload)
            } else {
                // The setting exists because a capture can contain tokens and user data; the
                // shape is still useful to an agent even when the values are withheld.
                put("payload_withheld", "Response bodies are not exposed over MCP (LogPose setting).")
                (event as? LogEvent.Http)?.let { put("payload", json.encodeToJsonElement(it.tx.withoutBodies())) }
            }
        }
    }

    private fun getTrace(args: JsonObject, events: List<LogEvent>): JsonElement {
        val traceId = args.str("trace_id") ?: return buildJsonObject { put("error", "Missing 'trace_id'") }
        val inTrace = events.filter { it.traceId == traceId }
        return buildJsonObject {
            put("trace_id", traceId)
            put("count", inTrace.size)
            if (inTrace.isEmpty()) put("note", "No events carry this trace id.")
            put("events", buildJsonArray { inTrace.forEach { add(brief(it)) } })
        }
    }

    private fun findFailures(args: JsonObject, events: List<LogEvent>): JsonElement {
        val limit = args.int("limit") ?: 20
        val failures = events.filter { it.isFailure() }
        return buildJsonObject {
            put("count", failures.size)
            put("events", buildJsonArray { failures.takeLast(limit).forEach { add(brief(it)) } })
        }
    }

    private fun summary(events: List<LogEvent>): JsonElement {
        val http = events.filterIsInstance<LogEvent.Http>()
        val dupes = DuplicateDetector.analyze(http.map { it.tx })
        return buildJsonObject {
            put("total_events", events.size)
            put("by_kind", buildJsonObject {
                events.groupingBy { it.kind }.eachCount().forEach { (k, v) -> put(k, v) }
            })
            put("failures", events.count { it.isFailure() })
            put("in_flight", events.count { it.isOpen })
            put("duplicate_calls", dupes.size)
            put("endpoints", buildJsonArray {
                http.map { "${it.tx.request.method} ${it.tx.request.path.ifBlank { it.tx.request.url }}" }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(25)
                    .forEach { (endpoint, count) ->
                        add(buildJsonObject { put("endpoint", endpoint); put("calls", count) })
                    }
            })
            val stamps = events.map { it.timestampMillis }.filter { it > 0 }
            if (stamps.isNotEmpty()) {
                put("first_at", stamps.min())
                put("last_at", stamps.max())
            }
            put("traces", buildJsonArray {
                events.mapNotNull { it.traceId }.distinct().take(25).forEach { add(it) }
            })
        }
    }

    // ---- shared shaping -------------------------------------------------------------------

    /** One compact line per event — enough to decide which one to fetch in full. */
    private fun brief(event: LogEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("kind", event.kind)
        put("at", event.timestampMillis)
        event.durationMillis?.let { put("duration_ms", it) }
        if (event.isOpen) put("in_flight", true)
        event.traceId?.let { put("trace_id", it) }
        put("summary", summarize(event))
        if (event.isFailure()) put("failed", true)
        (event as? LogEvent.Http)?.tx?.let { if (it.mocked) put("mocked", true) }
    }

    private fun summarize(event: LogEvent): String = when (event) {
        is LogEvent.Http -> {
            val tx = event.tx
            val where = "${tx.request.method} ${tx.request.path.ifBlank { tx.request.url }}"
            val outcome = tx.response?.code?.toString() ?: tx.error?.let { "error: $it" } ?: "pending"
            "$where → $outcome"
        }
        is LogEvent.Fcm -> {
            val m = event.msg
            val what = m.notification?.title
                ?: m.data.entries.firstOrNull { it.key.equals("channel", true) }?.value
                ?: m.collapseKey
                ?: if (m.event == "token") "token refresh" else "data message"
            "FCM $what"
        }
        is LogEvent.Generic -> listOfNotNull(
            event.event?.title ?: event.kind,
            event.event?.subtitle,
        ).joinToString(" · ")
    }

    private fun LogEvent.isFailure(): Boolean = when (this) {
        is LogEvent.Http -> tx.error != null || (tx.response?.code ?: 0) >= 400
        is LogEvent.Generic -> event?.badges?.any { it.tone == "error" } == true
        is LogEvent.Fcm -> false
    }

    private fun LogEvent.haystack(): String = when (this) {
        is LogEvent.Http -> tx.request.url
        is LogEvent.Fcm -> listOfNotNull(msg.notification?.title, msg.notification?.body, msg.from).joinToString(" ")
        is LogEvent.Generic -> listOfNotNull(event?.title, event?.subtitle, kind).joinToString(" ")
    }

    /** Strip bodies but keep the shape, for when body exposure is turned off. */
    private fun Transaction.withoutBodies(): Transaction = copy(
        request = request.copy(body = request.body?.copy(text = null)),
        response = response?.copy(body = response.body?.copy(text = null)),
    )

    // ---- tiny JSON-schema helpers ----------------------------------------------------------

    private fun tool(
        name: String,
        description: String,
        properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val props = buildJsonObject(properties)
        val required = props.entries
            .filter { (it.value as? JsonObject)?.get("_required")?.toString() == "true" }
            .map { it.key }
        return buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    props.forEach { (k, v) ->
                        put(k, JsonObject((v as JsonObject).filterKeys { key -> key != "_required" }))
                    }
                })
                put("required", buildJsonArray { required.forEach { add(it) } })
            })
        }
    }

    private fun stringProp(description: String, required: Boolean = false) = buildJsonObject {
        put("type", "string"); put("description", description)
        if (required) put("_required", true)
    }

    private fun intProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }

    private fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean"); put("description", description)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
