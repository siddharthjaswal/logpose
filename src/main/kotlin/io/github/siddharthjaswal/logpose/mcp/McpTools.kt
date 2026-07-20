package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.SqlSummary
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.ui.KindPresenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The read side of LogPose's MCP server: turning a live capture into answers an agent can act
 * on.
 *
 * This layer is deliberately free of HTTP and IntelliJ plumbing — it takes a snapshot of
 * events and returns JSON — so the query behavior is unit-testable and the transport in
 * [LogPoseMcpHandler] stays a thin shell.
 *
 * Reads answer "what did the app do"; the mock tools close the loop by changing what it
 * receives next, which is what turns this from a viewer into a debugging loop: read the real
 * 404, serve a 200, watch the screen recover. Writes go through [Mocks], which is absent when
 * a project can't serve them.
 */
object McpTools {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * The write surface, kept as an interface so this file stays free of IntelliJ types and
     * unit-testable. Implemented over `MocksController` by the tool window.
     *
     * Writes change what the **running app** receives, so the tool descriptions say so plainly:
     * the MCP client's own per-call approval is the gate, and every rule created this way shows
     * up in the Mocks strip like any other.
     */
    interface Mocks {
        fun list(): List<MockRule>
        fun hits(): Map<String, Int>
        /** Human-readable device sync state, e.g. "synced" or "waiting for device". */
        fun deviceHint(): String
        fun create(rule: MockRule, baseBody: String?)
        fun setEnabled(id: String, enabled: Boolean)
        fun delete(id: String)
    }

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
        add(
            tool(
                "find_slow_queries",
                "Database queries that took longest, slowest first, with the SQL and the table. " +
                    "Use it to answer 'what is making this screen slow?'. Only queries the app " +
                    "measured have a duration; unmeasured ones are excluded rather than " +
                    "reported as instant.",
            ) {
                put("min_ms", intProp("Only queries at or above this duration (default 0)."))
                put("table", stringProp("Restrict to one table."))
                put("limit", intProp("Max queries to return (default 20)."))
            },
        )
        add(
            tool(
                "worker_history",
                "Background work requests and how they ended, with attempt counts — the answer " +
                    "to 'did SyncWorker run, and did it retry?'. Each request appears once, in " +
                    "its latest known state.",
            ) {
                put("worker", stringProp("Filter by worker name, e.g. 'SyncWorker'."))
                put("state", stringProp("Filter by state: enqueued, running, succeeded, failed, cancelled."))
                put("limit", intProp("Max requests to return (default 20)."))
            },
        )
        add(
            tool(
                "config_changes",
                "Remote-config flag changes during this session, newest last, with old and new " +
                    "values — the answer to 'what flag changed before this broke?'.",
            ) {
                put("key", stringProp("Filter to one flag key (substring match)."))
                put("limit", intProp("Max changes to return (default 50)."))
            },
        )
        add(
            tool(
                "list_mocks",
                "The mock rules currently defined, with how many times each has been served and " +
                    "whether the device has them.",
            ) {}
        )
        add(
            tool(
                "create_mock",
                "Serve a canned response to the running app instead of letting a request reach " +
                    "the network — to reproduce an error state, or to unblock on an endpoint " +
                    "that isn't ready. THIS CHANGES WHAT THE RUNNING APP RECEIVES until the rule " +
                    "is disabled or capture stops. Prefer from_event_id: it copies a real " +
                    "captured response so you only state what should differ.",
            ) {
                put("from_event_id", stringProp(
                    "Copy method, path and response body from this captured event, then apply " +
                        "the overrides below. Get the id from list_events.",
                ))
                put("method", stringProp("HTTP method to match, or '*' for any. Required unless from_event_id is given."))
                put("path_pattern", stringProp(
                    "Request path to match; '*' matches any run of characters, e.g. " +
                        "'/app/v1/orders/*'. Required unless from_event_id is given.",
                ))
                put("status", intProp("Response status to serve (default 200)."))
                put("body", stringProp(
                    "Response body. In mode 'patch' this is merged into the real response, so " +
                        "send only the fields to override.",
                ))
                put("content_type", stringProp("Response Content-Type (default application/json)."))
                put("mode", stringProp(
                    "'replace' (default) serves body as the whole response; 'patch' lets the " +
                        "real response come back and deep-merges body into it.",
                ))
                put("behavior", stringProp(
                    "'normal' (default), 'timeout', or 'connection_failure' to simulate a " +
                        "network fault instead of returning a response.",
                ))
                put("latency_ms", intProp("Delay before responding."))
                put("serve_limit", intProp("Serve this many times then deactivate; 0 (default) = always."))
            },
        )
        add(
            tool(
                "set_mock_enabled",
                "Turn a mock rule on or off. Disabling restores the real network response — do " +
                    "this when you're done reproducing a state.",
            ) {
                put("id", stringProp("Mock rule id, from list_mocks or create_mock.", required = true))
                put("enabled", boolProp("True to serve the mock, false to let requests through."))
            },
        )
        add(
            tool(
                "delete_mock",
                "Remove a mock rule entirely. Use set_mock_enabled if you might want it back.",
            ) {
                put("id", stringProp("Mock rule id.", required = true))
            },
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
        mocks: Mocks? = null,
    ): JsonElement = when (name) {
        "list_events" -> listEvents(args, events, hostAgeMillis)
        "get_event" -> getEvent(args, events, includeBodies)
        "get_trace" -> getTrace(args, events)
        "find_failures" -> findFailures(args, events)
        "session_summary" -> summary(events)
        "find_slow_queries" -> slowQueries(args, events)
        "worker_history" -> workerHistory(args, events)
        "config_changes" -> configChanges(args, events)
        "list_mocks" -> mocks.orError { listMocks(it) }
        "create_mock" -> mocks.orError { createMock(args, events, it) }
        "set_mock_enabled" -> mocks.orError { setMockEnabled(args, it) }
        "delete_mock" -> mocks.orError { deleteMock(args, it) }
        else -> buildJsonObject { put("error", "Unknown tool '$name'") }
    }

    private inline fun Mocks?.orError(block: (Mocks) -> JsonElement): JsonElement =
        this?.let(block) ?: buildJsonObject {
            put("error", "Mocking isn't available — open the LogPose tool window for this project.")
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

    // ---- app-runtime kinds ------------------------------------------------------------------

    private fun slowQueries(args: JsonObject, events: List<LogEvent>): JsonElement {
        val minMs = args.int("min_ms") ?: 0
        val table = args.str("table")
        val limit = args.int("limit") ?: 20

        val queries = events.filterIsInstance<LogEvent.Db>()
        // A query with no duration wasn't measured (Room's callback gives no timing). Reporting
        // it as 0ms would put unmeasured queries at the *fast* end of a slowness ranking, which
        // is exactly backwards, so they're excluded and counted separately instead.
        val measured = queries.filter { it.durationMillis != null }
        val matched = measured.filter { event ->
            val summary = SqlSummary.of(event.query.sql)
            val eventTable = event.query.table ?: summary.table
            if (table != null && !eventTable.equals(table, ignoreCase = true)) return@filter false
            (event.durationMillis ?: 0) >= minMs
        }

        return buildJsonObject {
            put("total_queries", queries.size)
            put("measured", measured.size)
            if (measured.size < queries.size) {
                put(
                    "note",
                    "${queries.size - measured.size} queries carried no duration and are " +
                        "excluded — the app didn't measure them (Room's query callback has no timing).",
                )
            }
            put("queries", buildJsonArray {
                matched.sortedByDescending { it.durationMillis ?: 0 }.take(limit).forEach { event ->
                    val summary = SqlSummary.of(event.query.sql)
                    add(buildJsonObject {
                        put("id", event.id)
                        put("duration_ms", event.durationMillis ?: 0)
                        put("operation", event.query.operation ?: summary.operation)
                        (event.query.table ?: summary.table)?.let { put("table", it) }
                        event.query.database?.let { put("database", it) }
                        put("sql", event.query.sql)
                        event.query.rows?.let { put("rows", it) }
                    })
                }
            })
        }
    }

    private fun workerHistory(args: JsonObject, events: List<LogEvent>): JsonElement {
        val worker = args.str("worker")
        val state = args.str("state")?.lowercase()
        val limit = args.int("limit") ?: 20

        // Each request already occupies one row (the device reuses workId as the envelope id),
        // so the store has already collapsed the state transitions for us.
        val matched = events.filterIsInstance<LogEvent.Worker>().filter { event ->
            if (worker != null && !event.work.worker.contains(worker, ignoreCase = true)) return@filter false
            if (state != null && event.work.state != state) return@filter false
            true
        }

        return buildJsonObject {
            put("count", matched.size)
            put("retried", matched.count { it.work.runAttempt > 1 })
            put("failed", matched.count { it.work.state == WorkerEvent.STATE_FAILED })
            put("workers", buildJsonArray {
                matched.takeLast(limit).forEach { event ->
                    add(buildJsonObject {
                        put("id", event.id)
                        put("worker", event.work.worker)
                        put("state", event.work.state)
                        put("attempt", event.work.runAttempt)
                        event.work.uniqueName?.let { put("unique_name", it) }
                        event.durationMillis?.let {
                            put("duration_ms", it)
                            put("duration_note", "includes queue time; WorkInfo reports state, not execution")
                        }
                        event.work.error?.let { put("error", it) }
                        if (event.work.outputData.isNotEmpty()) {
                            put("output", buildJsonObject {
                                event.work.outputData.forEach { (k, v) -> put(k, v) }
                            })
                        }
                    })
                }
            })
        }
    }

    private fun configChanges(args: JsonObject, events: List<LogEvent>): JsonElement {
        val key = args.str("key")
        val limit = args.int("limit") ?: 50

        val updates = events.filterIsInstance<LogEvent.Config>()
        // Flatten to individual changes: an agent asking "what changed" wants flags, not
        // activations, and one activation can carry many.
        val changes = updates.flatMap { event ->
            event.update.changes
                .filter { key == null || it.key.contains(key, ignoreCase = true) }
                .map { event to it }
        }

        return buildJsonObject {
            put("activations", updates.size)
            put("count", changes.size)
            if (updates.any { it.update.baseline }) {
                put(
                    "note",
                    "A baseline snapshot was recorded at process start; flags already set then " +
                        "aren't reported as changes.",
                )
            }
            put("changes", buildJsonArray {
                changes.takeLast(limit).forEach { (event, change) ->
                    add(buildJsonObject {
                        put("at", event.timestampMillis)
                        put("key", change.key)
                        put("value", change.value)
                        change.previous?.let { put("previous", it) }
                        // A producer may signal "new" either way; no previous value means the
                        // key didn't exist, whether or not it bothered to set the flag.
                        if (change.isNew || change.previous == null) put("new_key", true)
                        event.update.source?.let { put("source", it) }
                    })
                }
            })
        }
    }

    // ---- mocks (write) ---------------------------------------------------------------------

    private fun listMocks(mocks: Mocks): JsonElement {
        val hits = mocks.hits()
        return buildJsonObject {
            put("device", mocks.deviceHint())
            put("count", mocks.list().size)
            put("mocks", buildJsonArray { mocks.list().forEach { add(briefMock(it, hits[it.id] ?: 0)) } })
        }
    }

    private fun createMock(args: JsonObject, events: List<LogEvent>, mocks: Mocks): JsonElement {
        // Seeding from a captured event is the path worth encouraging: the agent copies a real
        // response and states only the difference, instead of inventing a payload the app has
        // never seen.
        val seed = args.str("from_event_id")?.let { id ->
            (events.firstOrNull { it.id == id } as? LogEvent.Http)?.tx
                ?: return buildJsonObject {
                    put("error", "No captured HTTP event with id '$id' to copy from.")
                }
        }

        val method = args.str("method") ?: seed?.request?.method ?: "*"
        val path = args.str("path_pattern")
            ?: seed?.request?.path?.takeIf { it.isNotBlank() }
            ?: return buildJsonObject {
                put("error", "Provide path_pattern, or from_event_id to copy it from a captured call.")
            }

        val mode = args.str("mode") ?: MockRule.MODE_REPLACE
        if (mode !in setOf(MockRule.MODE_REPLACE, MockRule.MODE_PATCH)) {
            return buildJsonObject { put("error", "mode must be 'replace' or 'patch'.") }
        }
        val behavior = args.str("behavior") ?: MockRule.BEHAVIOR_NORMAL
        if (behavior !in setOf(
                MockRule.BEHAVIOR_NORMAL,
                MockRule.BEHAVIOR_TIMEOUT,
                MockRule.BEHAVIOR_CONNECTION_FAILURE,
            )
        ) {
            return buildJsonObject {
                put("error", "behavior must be 'normal', 'timeout' or 'connection_failure'.")
            }
        }

        // In replace mode with no body given, fall back to the captured response so the rule is
        // immediately meaningful (e.g. "same response, but status 500").
        val body = args.str("body")
            ?: if (mode == MockRule.MODE_REPLACE) seed?.response?.body?.text else null

        val rule = MockRule(
            id = "mcp-" + UUID.randomUUID().toString().take(8),
            method = method.uppercase(),
            pathPattern = path,
            status = args.int("status") ?: seed?.response?.code ?: 200,
            body = body,
            contentType = args.str("content_type") ?: "application/json",
            latencyMillis = (args.int("latency_ms") ?: 0).toLong(),
            behavior = behavior,
            serveLimit = args.int("serve_limit") ?: 0,
            enabled = true,
            mode = mode,
        )
        mocks.create(rule, seed?.response?.body?.text)

        return buildJsonObject {
            put("created", briefMock(rule, 0))
            put("device", mocks.deviceHint())
            put(
                "note",
                "The running app will now receive this instead of the real response for matching " +
                    "requests. Disable it with set_mock_enabled when you're done; all rules also " +
                    "clear from the device when capture stops.",
            )
        }
    }

    private fun setMockEnabled(args: JsonObject, mocks: Mocks): JsonElement {
        val id = args.str("id") ?: return buildJsonObject { put("error", "Missing 'id'") }
        if (mocks.list().none { it.id == id }) {
            return buildJsonObject { put("error", "No mock rule with id '$id'") }
        }
        val enabled = args.bool("enabled") ?: true
        mocks.setEnabled(id, enabled)
        return buildJsonObject {
            put("id", id)
            put("enabled", enabled)
            put("device", mocks.deviceHint())
        }
    }

    private fun deleteMock(args: JsonObject, mocks: Mocks): JsonElement {
        val id = args.str("id") ?: return buildJsonObject { put("error", "Missing 'id'") }
        if (mocks.list().none { it.id == id }) {
            return buildJsonObject { put("error", "No mock rule with id '$id'") }
        }
        mocks.delete(id)
        return buildJsonObject { put("deleted", id); put("device", mocks.deviceHint()) }
    }

    private fun briefMock(rule: MockRule, hits: Int): JsonObject = buildJsonObject {
        put("id", rule.id)
        put("match", "${rule.method} ${rule.pathPattern}")
        put("enabled", rule.enabled)
        put("mode", rule.mode)
        if (rule.behavior != MockRule.BEHAVIOR_NORMAL) put("behavior", rule.behavior)
        else put("status", rule.status)
        if (rule.latencyMillis > 0) put("latency_ms", rule.latencyMillis)
        if (rule.serveLimit > 0) put("serve_limit", rule.serveLimit)
        put("hits", hits)
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
        // db / worker / config / app-defined: the presenter already reduces each to the words
        // that describe it, and reusing it keeps an agent's view identical to the developer's.
        else -> KindPresenter.present(event)
            ?.let { listOfNotNull(it.title, it.subtitle).joinToString(" · ") }
            ?: event.kind
    }

    private fun LogEvent.isFailure(): Boolean = when (this) {
        is LogEvent.Http -> tx.error != null || (tx.response?.code ?: 0) >= 400
        is LogEvent.Db -> query.error != null
        is LogEvent.Worker -> work.state == WorkerEvent.STATE_FAILED
        is LogEvent.Fcm, is LogEvent.Config -> false
        is LogEvent.Generic -> event?.badges?.any { it.tone == "error" } == true
    }

    private fun LogEvent.haystack(): String = when (this) {
        is LogEvent.Http -> tx.request.url
        is LogEvent.Fcm -> listOfNotNull(msg.notification?.title, msg.notification?.body, msg.from).joinToString(" ")
        // Searching the SQL itself matters here — "contains: orders" should find the query that
        // touches that table even when the row shows only the table name.
        is LogEvent.Db -> listOfNotNull(query.sql, query.database, query.table).joinToString(" ")
        is LogEvent.Worker -> listOfNotNull(work.worker, work.uniqueName, work.state).joinToString(" ") +
            " " + work.tags.joinToString(" ")
        is LogEvent.Config -> update.changes.joinToString(" ") { it.key }
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
