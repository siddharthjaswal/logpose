package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.CorrelationKeys
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.FindQuery
import io.github.siddharthjaswal.logpose.analysis.Groupings
import io.github.siddharthjaswal.logpose.analysis.KeyValue
import io.github.siddharthjaswal.logpose.analysis.SqlSummary
import io.github.siddharthjaswal.logpose.analysis.Suggestion
import io.github.siddharthjaswal.logpose.mock.DeviceCapability
import io.github.siddharthjaswal.logpose.mock.DeviceFeature
import io.github.siddharthjaswal.logpose.mock.PushReplay
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockStep
import io.github.siddharthjaswal.logpose.model.PushAck
import io.github.siddharthjaswal.logpose.model.PushInject
import io.github.siddharthjaswal.logpose.model.PushMessage
import io.github.siddharthjaswal.logpose.model.Section
import io.github.siddharthjaswal.logpose.store.EventStore
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.presentation.RowContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

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
     * Every kind LogPose ships support for, reported by `session_summary` even at zero. App-defined
     * kinds are appended after these when present.
     */
    private val KNOWN_KINDS = listOf(
        Envelope.KIND_HTTP,
        Envelope.KIND_FCM,
        Envelope.KIND_DB,
        Envelope.KIND_WORKER,
        Envelope.KIND_CONFIG,
        Envelope.KIND_ANALYTICS,
        Envelope.KIND_EVENT,
    )

    private val BEHAVIORS = setOf(
        MockRule.BEHAVIOR_NORMAL,
        MockRule.BEHAVIOR_TIMEOUT,
        MockRule.BEHAVIOR_CONNECTION_FAILURE,
    )

    /** Every key a `responses` step accepts. Anything else is a typo, and is refused as one. */
    private val STEP_KEYS = setOf("status", "body", "headers", "content_type", "latency_ms", "behavior")

    private const val FROM_RULES = "rules"
    private const val FROM_SESSION = "session"

    /** The oldest device library that understands the narrowing matchers and response steps. */
    private val MIN_RICH_MATCHER_LIB = DeviceFeature.RICH_MATCHERS.since

    /**
     * How long `await_event` waits. Bounded at both ends: a sub-second wait almost always means a
     * caller that meant to poll, and two minutes is already longer than any flow worth waiting on
     * synchronously.
     */
    private const val DEFAULT_AWAIT_MILLIS = 30_000L
    private const val MIN_AWAIT_MILLIS = 1_000L
    private const val MAX_AWAIT_MILLIS = 120_000L

    private const val CAPTURE_STOPPED_NOTE =
        "Capture is NOT running — nothing can arrive because logcat isn't being tailed (it can " +
            "stop on an app reinstall or adb disconnect). Press ▶ in the LogPose window to " +
            "reattach, then wait again."

    /**
     * What a caller is told when a surface can't serve them, and — crucially — **what to do about
     * it**, which is the one part of an MCP result that is host-specific: the plugin's fix is to
     * open a tool window, a headless daemon's is to restart with `--mocks`.
     *
     * The wire shape stays identical either way (an `{"error": …}` payload); only the sentence
     * changes. [Ide] is what the plugin sends and must not drift — every host that isn't the IDE
     * passes its own, so no host has to speak another's vocabulary.
     */
    data class Unavailable(
        val mocks: String,
        val waits: String,
        val push: String,
        val scenarios: String,
        val correlations: String,
    ) {
        companion object {
            val Ide = Unavailable(
                mocks = "Mocking isn't available — open the LogPose tool window for this project.",
                waits = "Waiting isn't available — open the LogPose tool window for this project.",
                push = "Push injection isn't available — open the LogPose tool window for this project.",
                scenarios = "Scenarios aren't available — open the LogPose tool window for a project with a " +
                    "directory on disk (scenarios live in .logpose/scenarios).",
                correlations = "Correlation isn't available — open the LogPose tool window for this project.",
            )
        }
    }

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
        /** True when a device is attached and synced, so a new rule takes effect immediately. */
        fun deviceReady(): Boolean
        /**
         * The library version the device announced, or null before the handshake. A rule using
         * fields that version predates is **withheld** from the push rather than downgraded (see
         * [io.github.siddharthjaswal.logpose.mock.DeviceCapability]), so a caller has to be told
         * when it just wrote one.
         */
        fun deviceLibVersion(): String? = null

        /**
         * False when this host may **read** mock state but must not change it — the headless
         * daemon without `--mocks`, which still answers `list_mocks` from the device handshake it
         * ingests, but declines the three writes because only one process may own the device's
         * rule set (see the daemon's `--mocks` help). Defaults true: a host that offers this
         * surface at all normally owns it.
         */
        fun writable(): Boolean = true
        fun create(rule: MockRule, baseBody: String?)
        fun setEnabled(id: String, enabled: Boolean)
        fun delete(id: String)
    }

    /**
     * Push injection: the other half of the loop mocking opened. A mock changes what the app gets
     * when it asks; an injected push starts a flow the app never asked for — which is how most
     * mobile flows actually begin (order assigned, payment confirmed).
     *
     * Callback-shaped because delivery is answered by the device, not by the call: implementations
     * MUST invoke the callback exactly once, with the device's answer or with null when nothing
     * came back at all.
     */
    interface Push {
        /** Device state as one phrase, e.g. "com.acme · lib 1.7.0". */
        fun deviceHint(): String
        /** Null when a push can be delivered right now; otherwise why it can't, in plain words. */
        fun notReady(): String?
        fun inject(inject: PushInject, onAck: (Ack?) -> Unit)

        /** What the device reported about an injected push. */
        data class Ack(val delivered: String, val error: String? = null)
    }

    /**
     * Named, committable rule sets (`.logpose/scenarios/<name>.json`) — "make this whole app work
     * offline" as one action.
     *
     * Callback-shaped for a different reason than [Push]: the implementation touches the
     * filesystem, and this layer is called from the MCP transport's IO thread, so the work belongs
     * on a pooled one.
     */
    interface Scenarios {
        data class Info(val name: String, val rules: Int, val createdAt: Long, val note: String?)

        /** What a load did. [found] false means there's no such scenario on disk. */
        data class LoadReport(
            val name: String,
            val found: Boolean,
            val rules: Int = 0,
            val replaced: Boolean = false,
            /** Rules active after the load (a merge keeps the ones it didn't replace). */
            val activeRules: Int = 0,
            val deviceHint: String = "",
            /** True when capture is live, so the load was actually pushed to a device. */
            val live: Boolean = false,
            /** Loaded rules the device's library is too old to receive (they were withheld). */
            val withheld: Int = 0,
        )

        data class SaveReport(
            val name: String,
            val rules: Int = 0,
            /** Project-relative path written, when it was. */
            val path: String? = null,
            /** What the snapshot refused to guess at, e.g. "skipped 3 in-flight/bodyless". */
            val detail: String? = null,
            val error: String? = null,
        ) {
            val saved: Boolean get() = error == null
        }

        /**
         * False when scenarios may be listed and saved but not **loaded**: loading pushes a rule
         * set to the device, which is the single-writer channel a read-only daemon must not touch.
         * Listing and saving are a directory read and a file write under `.logpose/scenarios`,
         * shared with the IDE by design and safe from either process.
         */
        fun writable(): Boolean = true

        fun list(onResult: (List<Info>) -> Unit)
        fun load(name: String, replace: Boolean, onResult: (LoadReport) -> Unit)
        /** [fromSession] false = bottle the active rules; true = snapshot the capture. */
        fun save(
            name: String,
            note: String?,
            fromSession: Boolean,
            successOnly: Boolean,
            onResult: (SaveReport) -> Unit,
        )
    }

    /**
     * The project's correlation vocabulary and the cache in front of it — how an agent groups a
     * flow by the id a human knows (`order_id 21053953`) instead of a trace the app may never
     * have propagated.
     *
     * Everything here is a **read**: what the user configured, what one event carries, and one
     * event's searchable haystack. The decisions — which key wins for a row, what a too-short
     * value means, how a bare paste is labelled — deliberately stay in this file, expressed
     * against [Correlation], so an agent and the tool window apply the very same rules.
     *
     * [offThread] puts the threading contract in the type. Every read below scans a payload on a
     * cache miss, and these tools are called from the MCP transport's IO thread, so the tools do
     * all of their work inside it. Implementations MUST run it exactly once.
     */
    interface Correlations {
        /** The configured keys, in the order the user configured them. */
        fun keys(): List<CorrelationKey>

        /** One event's searchable text — fed to [Correlation.group] so grouping never rescans. */
        fun textOf(event: LogEvent): String

        /** Every configured key this event carries, matchable or not (see [KeyValue]). */
        fun valuesOf(event: LogEvent): List<KeyValue>

        /** The configured key holding [value], for labelling a bare paste; null when none does. */
        fun keyLabelFor(events: List<LogEvent>, value: String): String?

        /** Runs [work] where a payload scan is allowed — a pooled thread, never the IO thread. */
        fun offThread(work: () -> Unit)
    }

    /**
     * The capture's "tell me when" side, injected so this file stays testable without a store or a
     * clock. Implemented over [EventStore.addWaiter].
     */
    fun interface Waits {
        /**
         * Parks [predicate] for up to [timeoutMillis]. The future completes with the matching
         * event or with null on timeout; a null **return** means too many waits are already
         * outstanding on this capture.
         */
        fun await(timeoutMillis: Long, predicate: (LogEvent) -> Boolean): CompletableFuture<LogEvent?>?
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
                put("kind", stringProp(
                    "Filter by kind: 'http', 'fcm', 'db', 'worker', 'config', 'event', or an " +
                        "app-defined kind. session_summary's by_kind lists what this capture holds.",
                ))
                put("session", intProp(
                    "Restrict to one app run (see session_summary). Omit for all runs — which " +
                        "mixes them when the app restarted mid-capture.",
                ))
                put("method", stringProp("HTTP method filter, e.g. 'POST'."))
                put("status_class", intProp("HTTP status class: 2, 3, 4 or 5."))
                put("contains", stringProp(
                    "Substring match (case-insensitive) over an event's full searchable text. For " +
                        "HTTP that includes the request and response BODIES, not just the URL — the " +
                        "same body search the plugin's filter bar does; for other kinds it covers " +
                        "the fields the row is built from (db sql/args, FCM data, worker input/" +
                        "output, config values, event sections). Headers are not searched.",
                ))
                put("exclude", stringProp("Drop events whose text matches this — e.g. 'SFX_GEOFENCE' to hide a chatty location feed."))
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
                "get_related",
                "Everything carrying one business id — 'order_id 21053953' — as a timeline. This " +
                    "is usually a better get_trace: a trace groups only what the app itself " +
                    "propagated, while this matches the VALUE anywhere in an event (url and path, " +
                    "request/response body, FCM data, db args, event sections), so it reaches the " +
                    "GET /order/21053953/ that carries no trace at all, and spans the several " +
                    "traces one flow usually fragments into. Pass key + value, or value alone (an " +
                    "id pasted from a ticket or a backend log), or event_id to group by what that " +
                    "row itself carries.",
            ) {
                put("key", stringProp(
                    "A configured correlation key, e.g. 'order_id' — list_correlation_keys shows " +
                        "them. Omit to match the value alone; the key is a label and a safety " +
                        "rule, never part of the matching.",
                ))
                put("value", stringProp(
                    "The id to group by, e.g. '21053953'. Accepts a pasted 'order_id=21053953' " +
                        "too. Matching is delimiter-bounded, so '2105' never matches '21053953'.",
                ))
                put("event_id", stringProp(
                    "Group by the key values this event carries (id from list_events). With " +
                        "'key', picks which of its keys to group by.",
                ))
                put("limit", intProp(
                    "Max events to return, newest last (default 200). 'count' always reports the " +
                        "whole group.",
                ))
            },
        )
        add(
            tool(
                "list_correlation_keys",
                "This project's correlation vocabulary: the keys a human configured (order_id, " +
                    "trip_id) with the values seen most recently, plus id-ish keys found in the " +
                    "capture that nobody has configured. The two lists are separate on purpose — " +
                    "suggestions are INERT: this tool enables nothing, nothing groups by a " +
                    "suggestion, and only a person ticks one. get_related needs no configured key " +
                    "anyway; one only supplies the label and the length rule.",
            ) {
                put("limit", intProp("Max suggestions to return (default 12)."))
            },
        )
        add(
            tool(
                "await_event",
                "Wait for the next event matching a filter, instead of polling list_events. This " +
                    "is what closes the loop: create_mock / inject_fcm to trigger something, " +
                    "await_event to catch what it caused, then assert. Only matches events that " +
                    "arrive AFTER this call starts — if the thing you're waiting for may already " +
                    "have happened, use list_events. A timeout is a normal result " +
                    "(matched: false), not an error.",
            ) {
                put("kind", stringProp("Only this kind: 'http', 'fcm', 'db', 'worker', 'config', 'event', or an app-defined kind."))
                put("method", stringProp("HTTP method, e.g. 'POST'."))
                put("status_class", intProp("HTTP status class: 2, 3, 4 or 5."))
                put("contains", stringProp("Substring of the event's full searchable text, bodies included for HTTP — same matching as list_events."))
                put("trace_id", stringProp(
                    "Only events carrying this trace id. Pair it with inject_fcm's returned " +
                        "trace_id to wait for what that push set off.",
                ))
                put("failed_only", boolProp("Only failures: non-2xx responses and errors."))
                put("timeout_ms", intProp("How long to wait (default 30000, clamped to 1000–120000)."))
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
                "Overview of the current capture: the app runs it spans, how many events of each " +
                    "kind, which endpoints, and the ids of every failure and duplicate burst so " +
                    "you can jump straight to them with get_event.",
            ) {}
        )
        add(
            tool(
                "query_hotspots",
                "Database statements that ran repeatedly, most-repeated first — the answer to " +
                    "'what is making this screen slow?'. Repetition (an N+1 from a list adapter, " +
                    "a query re-run per row) is the failure mode that shows up without " +
                    "instrumenting execution time, which Room's callback does not provide.",
            ) {
                put("min_count", intProp("Only statements run at least this many times (default 2)."))
                put("table", stringProp("Restrict to one table."))
                put("limit", intProp("Max statements to return (default 20)."))
            },
        )
        add(
            tool(
                "worker_history",
                "Background work requests and how they ended, with attempt counts — the answer " +
                    "to 'did SyncWorker run, and did it retry?'. Each request appears once, in " +
                    "its latest known state. When the device reported the transitions, each entry " +
                    "also splits how long the request waited in the queue (queued_ms) from how " +
                    "long it actually ran (run_ms), so 'slow' can be told apart from 'waiting'.",
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
                "analytics_events",
                "Analytics events the app logged, with their params — answer 'did purchase_complete " +
                    "fire once with the right value after checkout?'. Also returns screen_flow: the " +
                    "screen-to-screen transitions observed, i.e. the shape of the user flow.",
            ) {
                put("name", stringProp("Filter by event name (substring match)."))
                put("screen", stringProp("Filter by the screen the event fired on."))
                put("limit", intProp("Max events to return (default 50)."))
            },
        )
        add(
            tool(
                "clear_capture",
                "Discard every captured event, so the next run starts clean — for test " +
                    "orchestration ('reset, run the flow, read only my events'). Does not touch " +
                    "mock rules; the app keeps emitting into the now-empty buffer.",
            ) {}
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
                put("latency_ms", intProp("Delay before responding — and before a timeout/" +
                    "connection_failure throws. Leave a failure at 0 to test the failure path; raise " +
                    "it above your race window to hold the request in flight and reproduce a race."))
                put("serve_limit", intProp("Serve this many times then deactivate; 0 (default) = always."))
                put("match_query", objectProp(
                    "Narrow the match to requests carrying these query parameters, e.g. " +
                        "{\"debug\":\"1\"}. Every entry must match; '*' as a value means 'present, " +
                        "any value'. Needs device library ≥ 1.7.0.",
                ))
                put("match_headers", objectProp(
                    "Narrow the match to requests carrying these headers (name case-insensitive), " +
                        "e.g. {\"X-Tenant\":\"acme\"}. '*' as a value means 'present, any value'. " +
                        "Needs device library ≥ 1.7.0.",
                ))
                put("match_body_contains", stringProp(
                    "Only match when the request body contains this text (case-insensitive). " +
                        "Fails closed on the device: a body it can't buffer never matches, so the " +
                        "call goes to the network rather than being mocked on a guess. Needs " +
                        "device library ≥ 1.7.0.",
                ))
                put("responses", arrayProp(
                    "Serve a different response per hit, e.g. [{\"status\":500},{\"status\":200," +
                        "\"body\":\"…\"}] to test retry logic in one rule. The last step sticks " +
                        "once the list runs out. Each step takes status (required), body, " +
                        "headers, content_type, latency_ms, behavior — the rule-level response " +
                        "fields are ignored when this is present. Needs device library ≥ 1.7.0.",
                ))
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
        add(
            tool(
                "inject_fcm",
                "Deliver a synthetic push to the running app — no Play services, no network. " +
                    "THIS STARTS A FLOW IN THE RUNNING APP: most mobile journeys begin with a " +
                    "push (order assigned, payment confirmed), and this is how you begin one on " +
                    "demand. The push appears on the timeline like any other, marked INJ, so the " +
                    "capture never passes it off as real. Pair it with await_event on the " +
                    "returned trace_id to see what it triggered. Needs device library ≥ 1.7.0 " +
                    "and an app that registered LogPose.onPushInject { } (or has a " +
                    "FirebaseMessagingService in its manifest).",
            ) {
                put("data", objectProp(
                    "The push's data map — the payload the app routes on, e.g. " +
                        "{\"channel\":\"order_assigned\",\"orderId\":\"91\"}. Required unless " +
                        "from_event_id is given. Values must be strings.",
                ))
                put("from_event_id", stringProp(
                    "Re-send a captured push: copies every field of that FCM event (id from " +
                        "list_events), with a fresh message id and send time. Anything you also " +
                        "state below overrides the captured value.",
                ))
                put("notification_title", stringProp("Notification title, for a push that carries one."))
                put("notification_body", stringProp("Notification body."))
                put("from", stringProp("Sender, e.g. a '/topics/...' or a sender id."))
                put("collapse_key", stringProp("Collapse key."))
                put("trace_id", stringProp(
                    "Deliver inside this trace, so everything the push triggers groups under it. " +
                        "One is generated and returned when you don't supply one.",
                ))
                put("await", boolProp(
                    "Wait (≤ 10s) for the device to report what consumed the push, and return " +
                        "delivered = handler | service | none. Default false, which returns as " +
                        "soon as the command is sent with delivered = 'pending'.",
                ))
            },
        )
        add(
            tool(
                "list_scenarios",
                "Saved mock scenarios in this project (.logpose/scenarios/*.json) — named rule " +
                    "sets a teammate can commit and you can load to put the whole app in a known " +
                    "state offline.",
            ) {}
        )
        add(
            tool(
                "load_scenario",
                "Apply a saved scenario's rules and push them to the device — one action, one " +
                    "revision. THIS CHANGES WHAT THE RUNNING APP RECEIVES for every endpoint the " +
                    "scenario covers. The result reports the device sync state: loaded is not the " +
                    "same as live until the device acknowledges it.",
            ) {
                put("name", stringProp("Scenario name, from list_scenarios.", required = true))
                put("replace", boolProp(
                    "True swaps the whole active rule set for this scenario; false (default) " +
                        "merges it in, replacing only rules with the same id.",
                ))
            },
        )
        add(
            tool(
                "save_scenario",
                "Bottle mock rules into a committable file under .logpose/scenarios. from='rules' " +
                    "saves the rule set as it stands; from='session' walks the capture and builds " +
                    "one replace-rule per endpoint from the LATEST real response, so a recorded " +
                    "session becomes an offline demo. A snapshot never invents data: endpoints " +
                    "with no completed response are skipped and counted, and rows LogPose itself " +
                    "mocked are always skipped rather than laundered back in as 'what the backend " +
                    "said'. Scenario files contain captured response bodies — say so before " +
                    "anyone commits one.",
            ) {
                put("name", stringProp(
                    "File name, lowercase letters/digits/'-'/'_', ≤ 64 chars.", required = true,
                ))
                put("from", stringProp("'rules' (default) or 'session'.", required = true))
                put("note", stringProp("Optional one-line note stored with the scenario."))
                put("success_only", boolProp(
                    "from='session' only: keep just 2xx responses. Default false — an error state " +
                        "is very often the thing worth bottling.",
                ))
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
        sessions: List<EventStore.Session> = emptyList(),
        sessionOf: (String) -> Int = { 0 },
        captureRunning: () -> Boolean = { true },
        clearCapture: () -> Unit = {},
        unavailable: Unavailable = Unavailable.Ide,
    ): JsonElement = when (name) {
        "list_events" -> listEvents(args, events, hostAgeMillis, sessionOf, captureRunning)
        "get_event" -> getEvent(args, events, includeBodies)
        "get_trace" -> getTrace(args, events)
        "find_failures" -> findFailures(args, events)
        "session_summary" -> summary(events, sessions, sessionOf, hostAgeMillis, captureRunning)
        "query_hotspots" -> queryHotspots(args, events)
        "worker_history" -> workerHistory(args, events)
        "config_changes" -> configChanges(args, events)
        "analytics_events" -> analyticsEvents(args, events, sessionOf)
        "clear_capture" -> clearCaptureTool(clearCapture, events.size)
        // Reading the rule set is a read: it reports what the device was told and how often each
        // rule hit, and a host that can only read still knows both.
        "list_mocks" -> mocks.orError(unavailable.mocks, write = false) { listMocks(it, events) }
        "create_mock" -> mocks.orError(unavailable.mocks, write = true) { createMock(args, events, it) }
        "set_mock_enabled" -> mocks.orError(unavailable.mocks, write = true) { setMockEnabled(args, it) }
        "delete_mock" -> mocks.orError(unavailable.mocks, write = true) { deleteMock(args, it) }
        else -> buildJsonObject { put("error", "Unknown tool '$name'") }
    }

    private inline fun Mocks?.orError(
        message: String,
        write: Boolean,
        block: (Mocks) -> JsonElement,
    ): JsonElement =
        this?.takeIf { !write || it.writable() }?.let(block)
            ?: buildJsonObject { put("error", message) }

    // ---- deferred tools ---------------------------------------------------------------------

    /**
     * Tools whose answer can arrive later than the call: a wait that only ends when the app does
     * something, a push whose outcome the device reports, a scenario read off disk, a correlation
     * scan that must not happen on the transport's IO thread.
     *
     * They are a separate entry point rather than a mode of [call] so the fourteen synchronous
     * tools keep returning a value on the calling thread exactly as before — the transport picks
     * the path, and only these tools ever leave a request open.
     */
    private val ASYNC_TOOLS = setOf(
        "await_event", "inject_fcm", "list_scenarios", "load_scenario", "save_scenario",
        "get_related", "list_correlation_keys",
    )

    fun isAsync(name: String): Boolean = name in ASYNC_TOOLS

    /**
     * Execute a deferred tool, handing the result to [onResult] — possibly on another thread, and
     * possibly much later (an `await_event` can hold for two minutes).
     *
     * [onResult] is called **exactly once** on every path, including argument errors: an agent's
     * request must never be left hanging. [now] is injectable so elapsed times are testable.
     */
    fun callAsync(
        name: String,
        args: JsonObject,
        events: List<LogEvent>,
        push: Push? = null,
        waits: Waits? = null,
        scenarios: Scenarios? = null,
        correlations: Correlations? = null,
        captureRunning: () -> Boolean = { true },
        now: () -> Long = { System.currentTimeMillis() },
        unavailable: Unavailable = Unavailable.Ide,
        onResult: (JsonElement) -> Unit,
    ) {
        // One answer per call, whichever path gets there first: a push that acks after its own
        // deadline fired must not write a second response onto the same request.
        val answered = AtomicBoolean(false)
        val answer: (JsonElement) -> Unit = { if (answered.compareAndSet(false, true)) onResult(it) }
        val fail: (String) -> Unit = { answer(buildJsonObject { put("error", it) }) }

        when (name) {
            "await_event" ->
                if (waits == null) fail(unavailable.waits)
                else awaitEvent(args, waits, captureRunning, now, answer)
            "inject_fcm" ->
                if (push == null) fail(unavailable.push) else injectFcm(args, events, push, now, answer)
            // Listing reads the scenarios directory and saving writes a file into it — both are
            // shared with the IDE by design and safe from a read-only host. Loading is the one
            // that pushes rules to the device, so it is the one the writable() gate covers.
            "list_scenarios" ->
                if (scenarios == null) fail(unavailable.scenarios) else listScenarios(scenarios, answer)
            "load_scenario" ->
                if (scenarios == null || !scenarios.writable()) fail(unavailable.scenarios)
                else loadScenario(args, scenarios, answer)
            "save_scenario" ->
                if (scenarios == null) fail(unavailable.scenarios) else saveScenario(args, scenarios, answer)
            "get_related" ->
                if (correlations == null) fail(unavailable.correlations)
                else getRelated(args, events, correlations, answer)
            "list_correlation_keys" ->
                if (correlations == null) fail(unavailable.correlations)
                else listCorrelationKeys(args, events, correlations, answer)
            else -> fail("Unknown tool '$name'")
        }
    }

    // ---- tools ---------------------------------------------------------------------------

    private fun listEvents(
        args: JsonObject,
        events: List<LogEvent>,
        hostAgeMillis: (String) -> Long,
        sessionOf: (String) -> Int,
        captureRunning: () -> Boolean,
    ): JsonElement {
        val limit = args.int("limit") ?: 50
        val kind = args.str("kind")
        val method = args.str("method")?.uppercase()
        val statusClass = args.int("status_class")
        val contains = args.str("contains")
        val failedOnly = args.bool("failed_only") ?: false
        val sinceSeconds = args.int("since_seconds")
        val session = args.int("session")
        val exclude = args.str("exclude")

        val matched = events.filter { event ->
            if (session != null && sessionOf(event.id) != session) return@filter false
            if (kind != null && !event.kind.equals(kind, ignoreCase = true)) return@filter false
            if (sinceSeconds != null && hostAgeMillis(event.id) > sinceSeconds * 1000L) return@filter false
            if (failedOnly && !event.isFailure()) return@filter false
            val tx = (event as? LogEvent.Http)?.tx
            if (method != null && tx?.request?.method?.uppercase() != method) return@filter false
            if (statusClass != null && (tx?.response?.code ?: 0) / 100 != statusClass) return@filter false
            if (contains != null && !event.haystack().contains(contains, ignoreCase = true)) return@filter false
            // Server-side noise exclusion: drop e.g. every SFX_GEOFENCE_* event in one pass.
            if (exclude != null && event.haystack().contains(exclude, ignoreCase = true)) return@filter false
            true
        }

        // Newest last: an agent reads top-to-bottom, and the most recent event is usually the
        // one being asked about.
        val page = matched.takeLast(limit)
        return buildJsonObject {
            put("total_matched", matched.size)
            put("returned", page.size)
            // Empty is ambiguous — no such events, or capture isn't running (it dies silently on an
            // app reinstall). Say which, so an agent doesn't read "0" as "nothing happened".
            if (events.isEmpty() && !captureRunning()) {
                put("capture_stopped", true)
                put("note", "Capture is NOT running — the buffer is empty because logcat isn't " +
                    "being tailed (it can stop on an app reinstall or adb disconnect). Press ▶ in " +
                    "the LogPose window to reattach.")
            } else if (matched.size > page.size) {
                put("note", "Showing the ${page.size} most recent of ${matched.size} matches.")
            }
            put("events", buildJsonArray { page.forEach { add(brief(it)) } })
        }
    }

    private fun clearCaptureTool(clear: () -> Unit, before: Int): JsonElement {
        clear()
        return buildJsonObject {
            put("cleared", before)
            put("note", "Event buffer reset. Mock rules are untouched. New events accrue from now.")
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
            // A trace that holds nothing, or holds only the row it was read off, is the normal
            // shape of a flow the app never propagated a trace through — not a rare edge. Saying
            // "0 events" and stopping sends an agent looking for a bug in the capture instead of
            // at the grouping that does work on this app.
            if (inTrace.isEmpty() || inTrace.size == 1) {
                put("note", traceGapNote(inTrace.firstOrNull()))
            }
            put("events", buildJsonArray { inTrace.forEach { add(brief(it)) } })
        }
    }

    /**
     * What to do when a trace groups nothing. This is the failure mode the correlation work exists
     * for: the app mints its own trace when it handles a push, and HTTP rows join a trace only via
     * an OkHttp request tag, so a flow routinely spans several traces plus rows carrying none —
     * while the `order_id` in all of them correlates the lot.
     */
    private fun traceGapNote(only: LogEvent?): String {
        val lead = if (only == null) {
            "No event carries this trace id."
        } else {
            "Only this one event carries this trace id, so the trace is not the flow."
        }
        val handle = only?.let { "event_id='${it.id}'" } ?: "value='<the id you know>'"
        return "$lead A trace groups only what the app itself propagated: an app that starts its " +
            "own trace when handling a push never joins the IDE's, and HTTP rows join a trace " +
            "only when the client is built with LogPose.traceCalls(...). The business key is " +
            "usually the better grouping — get_related($handle) matches an id by VALUE anywhere " +
            "in an event, so it spans traces and reaches rows with no trace at all. " +
            "list_correlation_keys shows which keys this capture supports."
    }

    /**
     * Wait for the next matching event.
     *
     * The whole point is that it only sees what arrives *after* the wait is parked: an agent that
     * triggers something and then asks "did it happen?" must not be answered by a stale row from
     * before the trigger. That also means an in-place update counts — a response landing on a
     * request that was already in flight is new information arriving, and awaiting a 5xx on it is
     * exactly the intended use.
     *
     * A timeout is a result, not an error: "nothing happened" is a legitimate answer to assert on,
     * and an error would make an agent retry the transport instead of reading the finding.
     */
    private fun awaitEvent(
        args: JsonObject,
        waits: Waits,
        captureRunning: () -> Boolean,
        now: () -> Long,
        answer: (JsonElement) -> Unit,
    ) {
        val statusClass = args.int("status_class")
        if (statusClass != null && statusClass !in 1..5) {
            answer(errorObj("status_class must be 1, 2, 3, 4 or 5.")); return
        }
        val requested = args.int("timeout_ms")?.toLong() ?: DEFAULT_AWAIT_MILLIS
        val timeout = requested.coerceIn(MIN_AWAIT_MILLIS, MAX_AWAIT_MILLIS)

        // Nothing can arrive while logcat isn't being tailed, so waiting the full timeout would
        // just be a slow way of saying "capture is stopped".
        if (!captureRunning()) {
            answer(buildJsonObject {
                put("matched", false)
                put("waited_ms", 0)
                put("capture_stopped", true)
                put("note", CAPTURE_STOPPED_NOTE)
            })
            return
        }

        val kind = args.str("kind")
        val method = args.str("method")?.uppercase()
        val contains = args.str("contains")
        val traceId = args.str("trace_id")
        val failedOnly = args.bool("failed_only") ?: false

        val predicate: (LogEvent) -> Boolean = { event ->
            when {
                kind != null && !event.kind.equals(kind, ignoreCase = true) -> false
                traceId != null && event.traceId != traceId -> false
                failedOnly && !event.isFailure() -> false
                method != null && (event as? LogEvent.Http)?.tx?.request?.method?.uppercase() != method -> false
                statusClass != null &&
                    ((event as? LogEvent.Http)?.tx?.response?.code ?: 0) / 100 != statusClass -> false
                contains != null && !event.haystack().contains(contains, ignoreCase = true) -> false
                else -> true
            }
        }

        val started = now()
        val future = waits.await(timeout, predicate)
        if (future == null) {
            answer(errorObj(
                "Too many waits are already outstanding on this capture (limit " +
                    "${EventStore.MAX_WAITERS}). Let one finish or time out before starting " +
                    "another — a wait holds a request open until its event arrives.",
            ))
            return
        }
        future.whenComplete { event, error ->
            val waited = now() - started
            answer(
                when {
                    error != null -> errorObj("await_event failed: ${error.message ?: error::class.java.simpleName}")
                    event == null -> buildJsonObject {
                        put("matched", false)
                        put("waited_ms", waited)
                        put("timeout_ms", timeout)
                        put("note", "Nothing matching arrived in ${timeout}ms. This only sees " +
                            "events that arrive AFTER the wait starts — if it may have happened " +
                            "already, check list_events. If the app should have been triggered, " +
                            "check that capture is running and the trigger actually fired.")
                    }
                    else -> buildJsonObject {
                        put("matched", true)
                        put("waited_ms", waited)
                        put("id", event.id)
                        put("event", brief(event))
                    }
                }
            )
        }
    }

    private fun findFailures(args: JsonObject, events: List<LogEvent>): JsonElement {
        val limit = args.int("limit") ?: 20
        val failures = events.filter { it.isFailure() }
        // The same request failing four times is one problem, not four. Collapsing keeps the
        // distinct failures visible instead of burying them under repeats of the loudest one.
        val groups = failures.groupBy { summarize(it) }
        return buildJsonObject {
            put("count", failures.size)
            put("distinct", groups.size)
            put("failures", buildJsonArray {
                groups.entries.toList().takeLast(limit).forEach { entry ->
                    val group: List<LogEvent> = entry.value
                    add(buildJsonObject {
                        put("summary", entry.key)
                        put("count", group.size)
                        put("kind", group.first().kind)
                        put("first_at", group.minOf { e -> e.timestampMillis })
                        put("last_at", group.maxOf { e -> e.timestampMillis })
                        put("event_ids", buildJsonArray { group.forEach { e -> add(e.id) } })
                    })
                }
            })
        }
    }

    private fun summary(
        events: List<LogEvent>,
        sessions: List<EventStore.Session>,
        sessionOf: (String) -> Int,
        hostAgeMillis: (String) -> Long,
        captureRunning: () -> Boolean,
    ): JsonElement {
        val http = events.filterIsInstance<LogEvent.Http>()
        val dupes = DuplicateDetector.analyze(http.map { it.tx })
        return buildJsonObject {
            // Capture health first: an agent needs to know whether "0 events" means quiet or dead.
            // Capture stops silently on an app reinstall / adb disconnect.
            val running = captureRunning()
            put("capture", buildJsonObject {
                put("running", running)
                val lastAge = events.map { hostAgeMillis(it.id) }.filter { it >= 0 }.minOrNull()
                if (lastAge != null) put("last_event_age_ms", lastAge)
                if (!running) put("note", "Capture is stopped — press ▶ in the LogPose window to reattach.")
                else if (events.isEmpty()) put("note", "Capture is running but nothing has arrived yet.")
            })
            put("total_events", events.size)

            // A capture that spans an app restart holds several runs. Reporting one span over all
            // of them turns two short bursts into "6 hours of activity" and makes every aggregate
            // below misleading, so the runs are broken out explicitly.
            if (sessions.size > 1) {
                put("note", "This capture spans ${sessions.size} app runs. Totals below cover all " +
                    "of them — pass session=<index> to list_events to scope to one.")
            }
            put("sessions", buildJsonArray {
                sessions.forEach { s ->
                    val own = events.filter { sessionOf(it.id) == s.index }
                    val stamps = own.map { it.timestampMillis }.filter { it > 0 }
                    add(buildJsonObject {
                        put("session", s.index)
                        put("events", own.size)
                        if (s.pkg.isNotBlank()) put("package", s.pkg)
                        if (s.libVersion.isNotBlank()) put("lib_version", s.libVersion)
                        if (stamps.isNotEmpty()) {
                            put("first_at", stamps.min())
                            put("last_at", stamps.max())
                            put("duration_ms", stamps.max() - stamps.min())
                        }
                    })
                }
                // Events that arrived before any handshake can't be attributed to a run — most
                // often because capture started mid-session and the hello was already gone.
                val orphans = events.count { sessionOf(it.id) == 0 }
                if (orphans > 0) {
                    add(buildJsonObject {
                        put("session", 0)
                        put("events", orphans)
                        put("note", "Captured before the app announced itself — the launch " +
                            "handshake predates this capture, so these can't be tied to a run.")
                    })
                }
            })
            val counts = events.groupingBy { it.kind }.eachCount()
            put("by_kind", buildJsonObject {
                // Enumerate every kind LogPose knows, including zeros: an absent key reads as
                // "not counted" and leaves a caller unable to tell that apart from "never fired".
                KNOWN_KINDS.forEach { put(it, counts[it] ?: 0) }
                counts.filterKeys { it !in KNOWN_KINDS }.forEach { (k, v) -> put(k, v) }
            })

            val failures = events.filter { it.isFailure() }
            put("failures", failures.size)
            if (failures.isNotEmpty()) {
                // Ids, not just a tally — otherwise finding the failure means paging list_events
                // by hand. Grouped so repeats of one problem don't crowd out the others.
                put("failure_groups", buildJsonArray {
                    failures.groupBy { summarize(it) }.entries.take(25).forEach { entry ->
                        val group: List<LogEvent> = entry.value
                        add(buildJsonObject {
                            put("summary", entry.key)
                            put("count", group.size)
                            put("event_ids", buildJsonArray { group.forEach { e -> add(e.id) } })
                        })
                    }
                })
            }

            val open = events.filter { it.isOpen }
            put("in_flight", open.size)
            if (open.isNotEmpty()) {
                put("in_flight_ids", buildJsonArray { open.take(25).forEach { add(it.id) } })
            }

            put("duplicate_calls", dupes.size)
            if (dupes.isNotEmpty()) {
                put("duplicate_groups", buildJsonArray {
                    dupes.entries.groupBy { it.value.originalId }.entries.take(25)
                        .forEach { entry ->
                            val originalId: String = entry.key
                            val marks = entry.value
                            val original = events.firstOrNull { it.id == originalId }
                            add(buildJsonObject {
                                put("endpoint", original?.let { summarize(it) } ?: "unknown")
                                put("severity", marks.maxOf { m -> m.value.severity }.name.lowercase())
                                // The original plus each repeat, so the whole burst is one hop away.
                                put("count", marks.size + 1)
                                put("event_ids", buildJsonArray {
                                    add(originalId)
                                    marks.forEach { m -> add(m.key) }
                                })
                            })
                        }
                })
            }
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
            val traces = events.mapNotNull { it.traceId }.distinct()
            put("traces", buildJsonArray { traces.take(25).forEach { add(it) } })
            if (traces.isEmpty()) {
                // A bare [] reads like "nothing to worry about" when it actually means the app
                // never set a trace id — LogPose does no implicit propagation.
                put("traces_note", "No event carries a trace id. LogPose never infers causality: " +
                    "the app opts in by wrapping a flow (LogPose.withTrace { } / traceContext()), " +
                    "which stamps the events it emits — including HTTP rows, when the client is set " +
                    "up with LogPose.traceCalls(...) — so get_trace has nothing to group until then.")
            }
        }
    }

    // ---- app-runtime kinds ------------------------------------------------------------------

    /**
     * Repeated queries, most-repeated first.
     *
     * This replaced a slow-query tool that could never work: Room's `setQueryCallback` fires
     * *before* execution and carries no duration, so on every real capture it reported nothing
     * and taught callers to stop asking. Repetition needs no timing, and is the failure mode that
     * actually hurts here — forty identical selects from a list adapter cost far more than one
     * 30ms query, and only the former is visible without instrumenting execution.
     *
     * Queries are grouped by shape, not by text: bound arguments are already out of the SQL, so
     * the same statement with different parameters lands in one group, which is precisely the
     * N+1 signature.
     */
    private fun queryHotspots(args: JsonObject, events: List<LogEvent>): JsonElement {
        val table = args.str("table")
        val minCount = args.int("min_count") ?: 2
        val limit = args.int("limit") ?: 20

        val queries = events.filterIsInstance<LogEvent.Db>()
        val matched = queries.filter { event ->
            if (table == null) return@filter true
            val eventTable = event.query.table ?: SqlSummary.of(event.query.sql).table
            eventTable.equals(table, ignoreCase = true)
        }

        val groups = matched.groupBy { it.query.sql }
            .filterValues { it.size >= minCount }
            .entries.sortedByDescending { it.value.size }

        return buildJsonObject {
            put("total_queries", queries.size)
            put("distinct_statements", matched.groupBy { it.query.sql }.size)
            put("repeated_statements", groups.size)
            if (groups.isEmpty()) {
                put("note", "No statement ran ${minCount}+ times" +
                    (table?.let { " against '$it'" } ?: "") +
                    ". Repetition is what this reports — LogPose has no query timings unless the " +
                    "app passes durationMillis itself, since Room's callback carries none.")
            }
            put("hotspots", buildJsonArray {
                groups.take(limit).forEach { entry ->
                    val group: List<LogEvent.Db> = entry.value
                    val first = group.first()
                    val summary = SqlSummary.of(first.query.sql)
                    add(buildJsonObject {
                        put("count", group.size)
                        put("operation", first.query.operation ?: summary.operation)
                        (first.query.table ?: summary.table)?.let { put("table", it) }
                        first.query.database?.let { put("database", it) }
                        put("sql", first.query.sql)
                        put("first_at", group.minOf { e -> e.timestampMillis })
                        put("last_at", group.maxOf { e -> e.timestampMillis })
                        // Enough ids to inspect the burst without pasting back hundreds.
                        put("event_ids", buildJsonArray { group.take(10).forEach { e -> add(e.id) } })
                        val measured = group.mapNotNull { e -> e.durationMillis }
                        if (measured.isNotEmpty()) {
                            put("measured", measured.size)
                            put("total_ms", measured.sum())
                            put("max_ms", measured.max())
                        }
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

        val replayed = matched.count { it.work.replayedAtAttach }
        return buildJsonObject {
            put("count", matched.size)
            put("ran_this_session", matched.size - replayed)
            // Work WorkManager replayed from its store when the observer attached — it ran before
            // capture was watching. Split out so "SyncWorker ran 20 times" isn't really 19 replays.
            put("replayed_at_attach", replayed)
            put("retried", matched.count { it.work.runAttempt > 1 })
            put("failed", matched.count { it.work.state == WorkerEvent.STATE_FAILED })
            // "Is my background work slow, or just waiting?" in one number. Counted only over
            // requests where the device reported both legs — a request that reported neither is
            // not evidence either way, so it is absent from the count rather than assumed fast.
            put(
                "queue_bound",
                matched.count { event ->
                    val queued = RowContent.workerQueueMillis(event.work)
                    val ran = RowContent.workerRunMillis(event)
                    queued != null && ran != null && queued > ran
                },
            )
            put("workers", buildJsonArray {
                matched.takeLast(limit).forEach { event ->
                    add(buildJsonObject {
                        put("id", event.id)
                        put("worker", event.work.worker)
                        put("state", event.work.state)
                        put("attempt", event.work.runAttempt)
                        if (event.work.replayedAtAttach) put("replayed_at_attach", true)
                        event.work.uniqueName?.let { put("unique_name", it) }
                        val queued = RowContent.workerQueueMillis(event.work)
                        val ran = RowContent.workerRunMillis(event)
                        // Both are absent unless the device observed the transitions bounding them
                        // (an old library, or capture attaching mid-flight, reports neither) — an
                        // agent must read their absence as "not measured", never as zero.
                        queued?.let { put("queued_ms", it) }
                        ran?.let { put("run_ms", it) }
                        // Unchanged meaning on purpose: the whole span, queue included. Saved
                        // scenarios and agents already read it, and silently redefining a key is
                        // worse than adding two beside it.
                        event.durationMillis?.let {
                            put("duration_ms", it)
                            put(
                                "duration_note",
                                if (ran != null) "duration_ms is queue + run; see queued_ms and run_ms"
                                else "includes queue time; WorkInfo reports state, not execution",
                            )
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

    /** One decoded analytics event: the library emits them as a self-describing Generic payload
     *  under the `analytics` kind (title = name, subtitle = screen, a "kv" section of params). */
    private class Analytic(
        val id: String, val at: Long, val session: Int,
        val name: String, val screen: String?, val params: Map<String, String>,
    )

    private fun decodeAnalytics(events: List<LogEvent>, sessionOf: (String) -> Int): List<Analytic> =
        events.filter { it.kind == Envelope.KIND_ANALYTICS }.mapNotNull { event ->
            val g = (event as? LogEvent.Generic)?.event ?: return@mapNotNull null
            val params = g.sections.firstOrNull { it.type == Section.TYPE_KV }?.body
                ?.let { it as? JsonObject }
                ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
                ?: emptyMap()
            Analytic(event.id, event.timestampMillis, sessionOf(event.id), g.title, g.subtitle, params)
        }

    private fun analyticsEvents(args: JsonObject, events: List<LogEvent>, sessionOf: (String) -> Int): JsonElement {
        val name = args.str("name")
        val screen = args.str("screen")
        val limit = args.int("limit") ?: 50

        val all = decodeAnalytics(events, sessionOf)
        val matched = all.filter { a ->
            (name == null || a.name.contains(name, ignoreCase = true)) &&
                (screen == null || a.screen?.contains(screen, ignoreCase = true) == true)
        }

        // Screen-to-screen transitions in arrival order — the shape of the user flow, and the seed
        // for a flow graph. Reset at session boundaries so a restart doesn't invent an edge, and
        // only count when the screen actually changes (repeated events on one screen aren't a move).
        val transitions = LinkedHashMap<Pair<String, String>, Int>()
        var prevScreen: String? = null
        var prevSession = Int.MIN_VALUE
        for (a in all) {
            val to = a.screen ?: continue
            if (a.session != prevSession) { prevScreen = null; prevSession = a.session }
            val from = prevScreen
            if (from != null && from != to) transitions[from to to] = (transitions[from to to] ?: 0) + 1
            prevScreen = to
        }

        return buildJsonObject {
            put("count", matched.size)
            // How often each event fired — the double-fire / wrong-screen check.
            put("by_name", buildJsonObject {
                matched.groupingBy { it.name }.eachCount().forEach { (n, c) -> put(n, c) }
            })
            put("events", buildJsonArray {
                matched.takeLast(limit).forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        put("at", a.at)
                        put("name", a.name)
                        a.screen?.let { put("screen", it) }
                        if (a.params.isNotEmpty()) put("params", buildJsonObject { a.params.forEach { (k, v) -> put(k, v) } })
                    })
                }
            })
            put("screen_flow", buildJsonArray {
                transitions.entries.sortedByDescending { it.value }.take(50).forEach { (edge, count) ->
                    add(buildJsonObject { put("from", edge.first); put("to", edge.second); put("count", count) })
                }
            })
        }
    }

    // ---- mocks (write) ---------------------------------------------------------------------

    private fun listMocks(mocks: Mocks, events: List<LogEvent>): JsonElement {
        // `served` is counted from the captured `mocked:true` responses, not the device's hit
        // counter — the counter only rides back when a rule set is (re)applied, so it reads 0
        // while a rule is demonstrably serving. The event flag is the trustworthy signal.
        val deviceHits = mocks.hits()
        return buildJsonObject {
            put("device", mocks.deviceHint())
            put("count", mocks.list().size)
            put("served_note", "'served' is counted from captured mocked responses in this buffer " +
                "(it resets with clear_capture); 'device_hits' is the app's own counter, which can lag.")
            put("mocks", buildJsonArray {
                mocks.list().forEach { add(briefMock(it, ruleServed(it, events), deviceHits[it.id] ?: 0)) }
            })
        }
    }

    /** How many captured responses this rule actually served — matched the way the device does. */
    private fun ruleServed(rule: MockRule, events: List<LogEvent>): Int {
        val pathRegex = runCatching {
            Regex(rule.pathPattern.split("*").joinToString(".*") { Regex.escape(it) })
        }.getOrNull()
        return events.count { ev ->
            val tx = (ev as? LogEvent.Http)?.tx ?: return@count false
            tx.mocked &&
                (rule.method == "*" || tx.request.method.equals(rule.method, ignoreCase = true)) &&
                (pathRegex?.matches(tx.request.path.ifBlank { tx.request.url }) ?: false)
        }
    }

    private fun createMock(args: JsonObject, events: List<LogEvent>, mocks: Mocks): JsonElement =
        // Bad arguments come back as a readable message rather than a transport failure — and
        // never as a coerced value, since a mock that quietly matches something other than what
        // was asked for is worse than no mock at all.
        try {
            createMockChecked(args, events, mocks)
        } catch (e: BadArgs) {
            errorObj(e.message ?: "Invalid arguments.")
        }

    private fun createMockChecked(args: JsonObject, events: List<LogEvent>, mocks: Mocks): JsonElement {
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
        if (behavior !in BEHAVIORS) {
            return buildJsonObject {
                put("error", "behavior must be 'normal', 'timeout' or 'connection_failure'.")
            }
        }

        // The narrowing matchers and the per-hit response sequence. Parsed before anything is
        // created, so a malformed step can't leave a half-specified rule on the device.
        val matchQuery = args.stringMap("match_query").orEmpty()
        val matchHeaders = args.stringMap("match_headers").orEmpty()
        val steps = args.steps("responses")

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
            matchQuery = matchQuery,
            matchHeaders = matchHeaders,
            // An empty string would be a constraint that constrains nothing; drop it rather than
            // store a matcher that reads like one and isn't.
            matchBodyContains = args.str("match_body_contains")?.takeIf { it.isNotEmpty() },
            responses = steps,
        )
        mocks.create(rule, seed?.response?.body?.text)

        // A rule using fields the device's library predates is withheld from the push, not
        // downgraded — so it is emphatically not active, however healthy the handshake is.
        val libVersion = mocks.deviceLibVersion()
        val withheld = !DeviceCapability.canPush(rule, libVersion)
        val active = mocks.deviceReady() && !withheld
        return buildJsonObject {
            // Lead with whether the rule is actually serving. "created" alone read as success even
            // when no device was attached, and an agent built a flow on a mock that never fired.
            put("active", active)
            put("created", briefMock(rule, 0, 0))
            put("device", mocks.deviceHint())
            if (withheld) {
                put("warning", withheldText(1, libVersion))
            } else if (!active) {
                put(
                    "warning",
                    "NOT SERVING YET — the app hasn't announced itself to this capture, so the " +
                        "running app is still getting the real response. The gate is the app→IDE " +
                        "handshake, not just capture: if the app was already running when capture " +
                        "started, RESTART IT (or start capture before launching). The rule activates " +
                        "when the device shows 'synced rev N'. Do not rely on it until active=true.",
                )
            } else {
                put(
                    "note",
                    "The running app will now receive this instead of the real response for matching " +
                        "requests. Disable it with set_mock_enabled when you're done; all rules also " +
                        "clear from the device when capture stops.",
                )
            }
            // Both a sequence and a single response were given. The device serves the sequence, so
            // saying nothing would leave a caller believing in a body that never gets served.
            if (steps.isNotEmpty() && (args.str("body") != null || args.int("status") != null)) {
                put(
                    "ignored",
                    "'responses' is present, so the rule-level status/body are not served — each " +
                        "step carries its own. Remove them, or drop 'responses' to serve one " +
                        "response every time.",
                )
            }
            // A timeout/failure with no latency throws almost instantly — it exercises the failure
            // path but never holds the request in flight, so it can't reproduce a race *during* a
            // slow call. Flag it so an agent doesn't verify against the wrong thing.
            if (behavior != MockRule.BEHAVIOR_NORMAL && rule.latencyMillis <= 0) {
                put(
                    "latency_warning",
                    "This '$behavior' fires almost instantly (latency_ms=0), testing the failure path, " +
                        "not an in-flight window. To reproduce a race during a slow call, set latency_ms " +
                        "above the window you're testing.",
                )
            }
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

    private fun briefMock(rule: MockRule, served: Int, deviceHits: Int): JsonObject = buildJsonObject {
        put("id", rule.id)
        put("match", "${rule.method} ${rule.pathPattern}")
        put("enabled", rule.enabled)
        put("mode", rule.mode)
        if (rule.responses.isNotEmpty()) {
            // A sequence overrides the rule-level response fields, so reporting those would
            // describe something the device will never serve.
            put("steps", buildJsonArray {
                rule.responses.forEach { step ->
                    add(buildJsonObject {
                        put("status", step.status)
                        if (step.behavior != MockRule.BEHAVIOR_NORMAL) put("behavior", step.behavior)
                        if (step.latencyMillis > 0) put("latency_ms", step.latencyMillis)
                        if (step.body != null) put("has_body", true)
                    })
                }
            })
            put("steps_note", "Hit N serves step N; the last step sticks once the list runs out.")
        } else if (rule.behavior != MockRule.BEHAVIOR_NORMAL) {
            put("behavior", rule.behavior)
        } else {
            put("status", rule.status)
        }
        if (rule.latencyMillis > 0 && rule.responses.isEmpty()) put("latency_ms", rule.latencyMillis)
        if (rule.serveLimit > 0) put("serve_limit", rule.serveLimit)
        // The narrowing constraints, so a rule that reads "mock /orders" but only fires on
        // ?debug=1 can't be mistaken for one that fires on everything.
        if (rule.matchQuery.isNotEmpty()) {
            put("match_query", buildJsonObject { rule.matchQuery.forEach { (k, v) -> put(k, v) } })
        }
        if (rule.matchHeaders.isNotEmpty()) {
            put("match_headers", buildJsonObject { rule.matchHeaders.forEach { (k, v) -> put(k, v) } })
        }
        rule.matchBodyContains?.takeIf { it.isNotEmpty() }?.let { put("match_body_contains", it) }
        DeviceCapability.requiredVersion(rule)?.let { put("needs_device_lib", it) }
        put("served", served)
        put("device_hits", deviceHits)
    }

    /** The message the tool window shows for rules a device's library is too old to receive. */
    private fun withheldText(count: Int, libVersion: String?): String =
        "WITHHELD FROM THE DEVICE — $count rule(s) use query/header/body matching or sequential " +
            "responses, which need logpose-android ≥ ${MIN_RICH_MATCHER_LIB} on the device" +
            (libVersion?.takeIf { it.isNotBlank() }?.let { " (it reports $it)" } ?: "") +
            ". LogPose withholds them rather than send them to a library that would ignore the " +
            "constraint and match too broadly — an old device matching every call to that path " +
            "is exactly the trust failure this tool exists to prevent. Update the app's " +
            "logpose-android dependency, or write the rule without the new fields."

    // ---- push injection (write) --------------------------------------------------------------

    /**
     * Deliver a synthetic push, optionally rebuilt from a captured one.
     *
     * Replay copies the captured message through [PushReplay] rather than re-deriving the fields
     * here, so an agent's replay and the tool window's "Re-send this push" produce the same
     * message — two implementations of "what was in that push" is exactly how a replay starts
     * quietly differing from the thing it replays.
     */
    private fun injectFcm(
        args: JsonObject,
        events: List<LogEvent>,
        push: Push,
        now: () -> Long,
        answer: (JsonElement) -> Unit,
    ) {
        val seed = args.str("from_event_id")?.let { id ->
            val captured = (events.firstOrNull { it.id == id } as? LogEvent.Fcm)?.msg
                ?: return answer(errorObj("No captured FCM event with id '$id' to replay."))
            if (!PushReplay.canReplay(captured)) {
                return answer(errorObj(
                    "Event '$id' is a token refresh, not a message — there is nothing to deliver.",
                ))
            }
            captured
        }

        val data = try {
            args.stringMap("data")
        } catch (e: BadArgs) {
            return answer(errorObj(e.message ?: "Invalid 'data'."))
        }
        if (seed == null && data == null) {
            return answer(errorObj(
                "Provide 'data' (the push payload the app routes on), or 'from_event_id' to " +
                    "replay a captured push.",
            ))
        }

        val traceId = args.str("trace_id")?.takeIf { it.isNotBlank() } ?: PushReplay.newTraceId()
        val base = seed?.let { PushReplay.toMessage(it, PushReplay.newId(), now()) } ?: PushMessage()
        val message = base.copy(
            sentTimeMillis = now(),
            from = args.str("from") ?: base.from,
            collapseKey = args.str("collapse_key") ?: base.collapseKey,
            notificationTitle = args.str("notification_title") ?: base.notificationTitle,
            notificationBody = args.str("notification_body") ?: base.notificationBody,
            data = data ?: base.data,
        )
        // One id for the injection, the message and the FCM row's envelope — minted by
        // [PushReplay.inject] rather than here, so this path can't drift from the tool window's
        // "Re-send this push". A separately generated message id was exactly the bug: the id
        // reported back named the injection while the timeline row carried the message id, and
        // the app's own re-log of the push then landed on a second, unmarked row.
        val injection = PushReplay.inject(message, traceId = traceId)
        val id = injection.id

        // Nothing can be delivered to a device that hasn't announced itself or whose library
        // predates injection — say so as loudly as create_mock does, rather than reporting a send
        // that never happened.
        push.notReady()?.let { reason ->
            return answer(buildJsonObject {
                put("sent", false)
                put("id", id)
                put("delivered", "none")
                put("device", push.deviceHint())
                put("warning", "NOT DELIVERED — $reason Nothing was sent, so the app has not " +
                    "received this push and no FCM row will appear on the timeline.")
            })
        }

        val await = args.bool("await") ?: false
        push.inject(injection) { ack ->
            if (!await) return@inject
            answer(buildJsonObject {
                put("sent", true)
                put("id", id)
                put("trace_id", traceId)
                put("device", push.deviceHint())
                if (ack == null) {
                    put("delivered", "unknown")
                    put("warning", "The device did not report an outcome in time. The ack rides " +
                        "back on logcat, so check capture is still running and the app is alive — " +
                        "the push may well have been delivered. Look for the injected FCM row " +
                        "(list_events kind='fcm') to see whether it landed.")
                } else {
                    put("delivered", ack.delivered)
                    ack.error?.let { put("error", it) }
                    if (ack.delivered == PushAck.DELIVERED_NONE) {
                        put("warning", "The app received the injection but NOTHING CONSUMED IT. " +
                            "Register a handler in the app's init — " +
                            "LogPose.onPushInject { info -> MyPushRouter.handle(info.data) } — or " +
                            "keep a FirebaseMessagingService in the manifest, which LogPose calls " +
                            "as a fallback. The push still appears on the timeline with an INJ " +
                            "pill, because it really was injected.")
                    } else {
                        put("note", "Delivered to the app's ${ack.delivered} tier, inside trace " +
                            "$traceId. Use await_event(trace_id='$traceId') or get_trace to see " +
                            "what it set off.")
                    }
                }
            })
        }
        if (!await) {
            answer(buildJsonObject {
                put("sent", true)
                put("id", id)
                put("trace_id", traceId)
                put("delivered", "pending")
                put("device", push.deviceHint())
                put("note", "Sent to the device; the delivery outcome was not awaited. Call with " +
                    "await=true to have the device report handler | service | none, or " +
                    "await_event(trace_id='$traceId') to catch what the push triggered. The push " +
                    "appears on the timeline with an INJ pill, as one row under this same id — " +
                    "get_event(id='$id') reads it.")
            })
        }
    }

    // ---- scenarios ----------------------------------------------------------------------------

    private fun listScenarios(scenarios: Scenarios, answer: (JsonElement) -> Unit) =
        scenarios.list { infos ->
            answer(buildJsonObject {
                put("count", infos.size)
                put("dir", ScenarioStore.REL_DIR)
                if (infos.isEmpty()) {
                    put("note", "No scenarios saved yet. save_scenario(from='session') bottles the " +
                        "endpoints in the current capture into one, which is how an app gets an " +
                        "offline demo mode.")
                }
                put("scenarios", buildJsonArray {
                    infos.forEach { info ->
                        add(buildJsonObject {
                            put("name", info.name)
                            put("rules", info.rules)
                            if (info.createdAt > 0) put("created_at", info.createdAt)
                            info.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                        })
                    }
                })
            })
        }

    private fun loadScenario(args: JsonObject, scenarios: Scenarios, answer: (JsonElement) -> Unit) {
        val name = args.str("name") ?: return answer(errorObj("Missing 'name'"))
        if (!ScenarioStore.isValidName(name)) return answer(errorObj(nameRule(name)))
        val replace = args.bool("replace") ?: false

        scenarios.load(name, replace) { report ->
            answer(
                if (!report.found) {
                    errorObj("No scenario named '$name' in ${ScenarioStore.REL_DIR}. " +
                        "Use list_scenarios to see what's saved.")
                } else buildJsonObject {
                    put("loaded", true)
                    put("name", report.name)
                    put("rules", report.rules)
                    put("mode", if (report.replaced) "replace" else "merge")
                    put("active_rules", report.activeRules)
                    put("device", report.deviceHint)
                    put("live", report.live)
                    if (!report.live) {
                        put("warning", "NOT SERVING YET — the rules are loaded in the IDE but " +
                            "capture isn't running, so nothing was pushed to a device and the app " +
                            "is still getting real responses. Press ▶ in the LogPose window.")
                    } else if (report.withheld > 0) {
                        put("warning", withheldText(report.withheld, null))
                    } else {
                        put("note", "Pushed to the device. 'Loaded' is not 'live' until the device " +
                            "acknowledges it — the device field above reports which it is; " +
                            "list_mocks re-reads it.")
                    }
                }
            )
        }
    }

    private fun saveScenario(args: JsonObject, scenarios: Scenarios, answer: (JsonElement) -> Unit) {
        val name = args.str("name") ?: return answer(errorObj("Missing 'name'"))
        if (!ScenarioStore.isValidName(name)) return answer(errorObj(nameRule(name)))
        val from = args.str("from")
            ?: return answer(errorObj("Missing 'from': 'rules' saves the active mock rules, " +
                "'session' snapshots the capture into rules."))
        if (from !in setOf(FROM_RULES, FROM_SESSION)) {
            return answer(errorObj("from must be '$FROM_RULES' or '$FROM_SESSION'."))
        }
        val successOnly = args.bool("success_only") ?: false

        scenarios.save(name, args.str("note"), from == FROM_SESSION, successOnly) { report ->
            answer(
                if (!report.saved) errorObj(report.error ?: "Could not save scenario '$name'.")
                else buildJsonObject {
                    put("saved", true)
                    put("name", report.name)
                    put("rules", report.rules)
                    report.path?.let { put("path", it) }
                    report.detail?.let { put("detail", it) }
                    put("note", "Scenario files hold captured response bodies verbatim — review " +
                        "before committing one. load_scenario puts them back on the device.")
                }
            )
        }
    }

    private fun nameRule(name: String): String =
        "'$name' isn't a usable scenario name. Use lowercase letters, digits, '-' and '_', at " +
            "most ${ScenarioStore.MAX_NAME} characters — the name is the filename, so nothing " +
            "that could address another directory is accepted."

    // ---- correlation ---------------------------------------------------------------------------

    /** Suggestions returned when a caller states no limit — the keys dialog's own default. */
    private const val DEFAULT_SUGGESTIONS = 12

    /** Events one `get_related` returns. `count` always reports the whole group regardless. */
    private const val DEFAULT_RELATED_LIMIT = 200

    /**
     * The configured vocabulary and what the capture suggests adding to it.
     *
     * The two lists are separate keys in the response, and never merged, because they mean
     * opposite things: a configured key groups, a suggestion is inert data a human has not looked
     * at yet. Nothing here writes — this tool cannot enable a key, and an agent reading it should
     * not pretend otherwise (PRD §4.1: suggestions are a discovery aid the human confirms).
     */
    private fun listCorrelationKeys(
        args: JsonObject,
        events: List<LogEvent>,
        correlations: Correlations,
        answer: (JsonElement) -> Unit,
    ) {
        val limit = (args.int("limit") ?: DEFAULT_SUGGESTIONS).coerceIn(1, 50)
        correlations.offThread {
            answer(
                runCatching { vocabulary(limit, events, correlations) }
                    .getOrElse { failed("list_correlation_keys", it) },
            )
        }
    }

    private fun vocabulary(
        limit: Int,
        events: List<LogEvent>,
        correlations: Correlations,
    ): JsonElement {
        val keys = correlations.keys()

        // Values come off the cached extractions, in arrival order, so "seen most recently"
        // costs a map lookup per event rather than a payload scan per key.
        val evidence = LinkedHashMap<String, KeyEvidence>()
        keys.forEach { evidence[CorrelationKeys.canonical(it.name)] = KeyEvidence() }
        if (keys.isNotEmpty()) {
            for (event in events) {
                for (value in correlations.valuesOf(event)) {
                    evidence[CorrelationKeys.canonical(value.key)]?.record(value)
                }
            }
        }

        // Ask for enough headroom that dropping the already-configured ones can't silently
        // shorten the list a caller asked for.
        val suggestions = Correlation.suggest(events, limit + keys.size)
            .filterNot { CorrelationKeys.contains(keys, it.key) }
            .take(limit)

        return buildJsonObject {
            put("configured_count", keys.size)
            put("configured", buildJsonArray {
                keys.forEach { key ->
                    add(configuredKey(key, evidence[CorrelationKeys.canonical(key.name)]))
                }
            })
            put("suggested_count", suggestions.size)
            put("suggested", buildJsonArray { suggestions.forEach { add(suggested(it)) } })
            put("note", vocabularyNote(keys, suggestions))
        }
    }

    private fun configuredKey(key: CorrelationKey, seen: KeyEvidence?): JsonObject = buildJsonObject {
        put("key", key.name)
        put("enabled", key.enabled)
        put("min_length", key.minLength)
        put("allow_short_values", key.allowShortValues)
        put("events_carrying", seen?.carrying ?: 0)
        put("distinct_values", seen?.distinct?.size ?: 0)
        seen?.latest?.let { put("latest_value", it) }
        // Three ways a configured key can be present and still group nothing. Each is invisible
        // from the numbers alone, and each sends an agent hunting for a bug that isn't there.
        val note = when {
            !key.enabled ->
                "Disabled, so no row offers it and nothing is extracted for it. get_related(key=" +
                    "'${key.name}', value=…) still groups — matching is by value, not by key."
            seen == null || seen.carrying == 0 ->
                "No event in this capture names this key. Either the app doesn't emit it, or it " +
                    "is spelled differently — key matching is case- and snake/camel-insensitive, " +
                    "but '${key.name}' and 'orderRef' are different names."
            seen.latestMatchable == false ->
                "The latest value ('${seen.latest}') is shorter than min_length ${key.minLength}, " +
                    "so it is extracted but cannot group — a short, common value would group half " +
                    "the capture. Tick \"short values\" for this key in the LogPose window to " +
                    "accept that risk."
            else -> null
        }
        note?.let { put("note", it) }
    }

    private fun suggested(suggestion: Suggestion): JsonObject = buildJsonObject {
        put("key", suggestion.key)
        // The evidence, so ranking is inspectable rather than a black box: how many events this
        // key would reach in total, and how big the single biggest group is — which is what one
        // get_related on its latest value would actually return.
        put("events_grouped", suggestion.eventsGrouped)
        put("largest_group", suggestion.largestGroup)
        put("events_carrying", suggestion.eventsCarrying)
        put("distinct_values", suggestion.distinctValues)
        suggestion.latestValue?.let { put("latest_value", it) }
    }

    private fun vocabularyNote(keys: List<CorrelationKey>, suggestions: List<Suggestion>): String {
        val inert = if (suggestions.isEmpty()) {
            "Nothing in this capture looks like an unconfigured id key."
        } else {
            "'suggested' is INERT: those keys group nothing and this tool enabled none of them — " +
                "a person ticks one in the LogPose window (filter bar → Filters → Correlation " +
                "keys…). You " +
                "can group by one right now without configuring it: get_related(value=…) matches " +
                "the value alone."
        }
        val configured = if (keys.isEmpty()) {
            "No correlation key is configured for this project — LogPose ships none, because the " +
                "vocabulary (order_id, trip_id) belongs to the app. "
        } else {
            "'configured' is the vocabulary a human set for this project; only those names label " +
                "a grouping and carry a length rule. "
        }
        return configured + inert
    }

    /** One configured key's footprint in the capture, accumulated over the cached extractions. */
    private class KeyEvidence {
        val distinct = LinkedHashSet<String>()
        var carrying = 0
            private set
        var latest: String? = null
            private set
        /** Whether the most recent value can group, or failed its key's length rule. */
        var latestMatchable: Boolean? = null
            private set

        fun record(value: KeyValue) {
            carrying++
            latest = value.value
            latestMatchable = value.matchable
            if (distinct.size < Correlation.MAX_TRACKED_VALUES) distinct.add(value.value)
        }
    }

    /**
     * The grouped timeline for one business id — `get_trace`'s shape, grouped the way that
     * actually works on an app that doesn't propagate traces.
     *
     * Three ways in, one answer: `key` + `value`, a bare `value` (the id pasted from a ticket),
     * or an `event_id` whose own key values name the grouping. Every guard is [Correlation]'s,
     * applied through [Correlation.parseFindQuery] and the configured key's own `minLength` /
     * `allowShortValues`, so an agent and the "Find by value…" dialog refuse exactly the same
     * inputs — a second implementation of the rule is how the two would drift apart.
     */
    private fun getRelated(
        args: JsonObject,
        events: List<LogEvent>,
        correlations: Correlations,
        answer: (JsonElement) -> Unit,
    ) {
        val keyArg = args.str("key")?.trim()?.takeIf { it.isNotEmpty() }
        val valueArg = args.str("value")
        val eventId = args.str("event_id")?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (args.int("limit") ?: DEFAULT_RELATED_LIMIT).coerceIn(1, 1_000)

        if (valueArg == null && eventId == null) {
            answer(errorObj(
                "Provide 'value' (the id to group by, e.g. '21053953'), optionally with 'key' to " +
                    "name it, or 'event_id' to group by the keys that row carries. " +
                    "list_correlation_keys shows the configured keys and the values seen most " +
                    "recently.",
            ))
            return
        }

        // The work happens on someone else's thread, so a failure there has to be turned back into
        // this call's answer: an exception escaping the pool would leave an agent's request open
        // until its client gave up, which is the one outcome this layer never allows.
        correlations.offThread {
            answer(
                runCatching { relatedResult(keyArg, valueArg, eventId, limit, events, correlations) }
                    .getOrElse { failed("get_related", it) },
            )
        }
    }

    private fun relatedResult(
        keyArg: String?,
        valueArg: String?,
        eventId: String?,
        limit: Int,
        events: List<LogEvent>,
        correlations: Correlations,
    ): JsonElement {
        val keys = correlations.keys()
        val configured = keyArg?.let { name -> keys.firstOrNull { sameKey(it.name, name) } }
        if (keyArg != null && configured == null) return unknownKey(keyArg, keys)

        // event_id names a row and therefore its own keys, so it wins over a value that would only
        // have been a guess at the same thing.
        val resolved =
            if (eventId != null) fromEvent(eventId, configured, keys, events, correlations)
            else fromValue(valueArg!!, configured, keys, events, correlations)

        return when (resolved) {
            is Resolution.Failed -> resolved.result
            is Resolution.Grouped -> groupResult(resolved, events, limit, correlations)
        }
    }

    private fun failed(tool: String, error: Throwable): JsonElement =
        errorObj("$tool failed: ${error.message ?: error::class.java.simpleName}")

    /** What resolving `get_related`'s arguments produced: something to group, or the answer why not. */
    private sealed interface Resolution {
        data class Grouped(
            val key: CorrelationKey?,
            /** The label — a configured key's own spelling, a key that merely holds the value, or null. */
            val label: String?,
            val value: String,
            /** Other matchable keys the originating event carried, when it came from one. */
            val others: List<KeyValue> = emptyList(),
        ) : Resolution

        data class Failed(val result: JsonElement) : Resolution
    }

    /**
     * `event_id` → the key to group by.
     *
     * When a row carries several configured keys the **first configured one wins** — the same
     * precedence [Groupings.best] gives the row menu and the hover glyph, so an agent and a click
     * open the same group. The others are not dropped: they come back in `other_keys` with the
     * call that would group by each, because "which id is this flow about" is the caller's
     * question, not ours.
     */
    private fun fromEvent(
        eventId: String,
        configured: CorrelationKey?,
        keys: List<CorrelationKey>,
        events: List<LogEvent>,
        correlations: Correlations,
    ): Resolution {
        val event = events.firstOrNull { it.id == eventId }
            ?: return Resolution.Failed(errorObj("No event with id '$eventId'."))
        val carried = correlations.valuesOf(event)

        if (configured != null) {
            val held = carried.firstOrNull { sameKey(it.key, configured.name) }
                ?: return Resolution.Failed(errorObj(
                    "Event '$eventId' carries no '${configured.name}'. " + carries(carried) +
                        " Omit 'key' to group by the first key it does carry.",
                ))
            if (!held.matchable) return Resolution.Failed(tooShort(configured.name, held.value, configured.minLength))
            return Resolution.Grouped(
                key = configured,
                label = configured.name,
                value = held.value,
                others = carried.filter { it.matchable && !sameKey(it.key, configured.name) },
            )
        }

        val matchable = carried.filter { it.matchable }
        val best = matchable.firstOrNull()
            ?: return Resolution.Failed(noKeyOnEvent(event, carried, keys))
        val key = keys.firstOrNull { sameKey(it.name, best.key) }
        return Resolution.Grouped(key, best.key, best.value, matchable.drop(1))
    }

    /**
     * A pasted `value` → the key that labels it.
     *
     * Parsed twice on purpose. The first pass is permissive, only to read the shape of what was
     * pasted (`21053953` vs `order_id=21053953`, quotes and whitespace stripped); that tells us
     * *which* key's rule applies, and the second pass applies it. Deciding the length guard here
     * instead would be the second implementation this exists to avoid.
     */
    private fun fromValue(
        value: String,
        configured: CorrelationKey?,
        keys: List<CorrelationKey>,
        events: List<LogEvent>,
        correlations: Correlations,
    ): Resolution {
        val probe = Correlation.parseFindQuery(value, allowShortValues = true)
        if (probe !is FindQuery.Ready) {
            return Resolution.Failed(errorObj("Provide a non-empty 'value' to group by."))
        }
        // A configured 'key' argument wins over one typed into the value: it is the more explicit
        // statement of the two, and it has already been checked against the vocabulary.
        val rule = configured ?: probe.key?.let { name -> keys.firstOrNull { sameKey(it.name, name) } }
        val query = Correlation.parseFindQuery(
            value,
            rule?.minLength ?: Correlation.DEFAULT_MIN_LENGTH,
            rule?.allowShortValues ?: false,
        )
        return when (query) {
            is FindQuery.Empty -> Resolution.Failed(errorObj("Provide a non-empty 'value' to group by."))
            is FindQuery.TooShort -> Resolution.Failed(
                tooShort(rule?.name ?: query.key, query.value, query.minLength),
            )
            is FindQuery.Ready -> Resolution.Grouped(
                key = rule,
                // A bare value is labelled by whichever configured key holds it somewhere in the
                // capture, exactly as the dialog labels a paste; unlabelled it reads 'value <v>'.
                label = rule?.name ?: query.key ?: correlations.keyLabelFor(events, query.value),
                value = query.value,
            )
        }
    }

    /** Runs the grouping over the **cached** haystacks and shapes it like `get_trace`. */
    private fun groupResult(
        resolved: Resolution.Grouped,
        events: List<LogEvent>,
        limit: Int,
        correlations: Correlations,
    ): JsonElement {
        val key = resolved.key
        val group = if (key != null) {
            Correlation.group(events, key, resolved.value, correlations::textOf)
        } else {
            Correlation.group(events, resolved.label, resolved.value, textOf = correlations::textOf)
        }
        val page = group.takeLast(limit)
        val grouping = Groupings.forValue(resolved.value, resolved.label)

        val notes = ArrayList<String>(3)
        if (page.size < group.size) {
            notes.add("Showing the ${page.size} most recent of ${group.size} events; raise 'limit' for more.")
        }
        when {
            group.isEmpty() -> notes.add(
                "No event carries this value. Matching is delimiter-bounded, so a value that only " +
                    "appears as part of a longer id (2105 inside 21053953) is not a match, and " +
                    "headers are deliberately outside the haystack. Check list_events(contains=…) " +
                    "for the raw text, or clear_capture and re-run the flow if the id predates " +
                    "this capture.",
            )
            group.size == 1 -> notes.add(
                "Only one event carries this value, so there is no flow around it yet — the rest " +
                    "may not have happened, or may name the id differently.",
            )
        }
        if (resolved.label == null) {
            notes.add(
                "No configured key holds this value, so it is grouped by the value alone. That is " +
                    "a label difference only: matching never used the key. list_correlation_keys " +
                    "shows what this project has configured.",
            )
        }
        if (resolved.others.isNotEmpty()) {
            notes.add(
                "That event also carries " + resolved.others.joinToString(", ") { "${it.key} ${it.value}" } +
                    " — grouped by ${grouping.shortLabel} because configured keys take the order " +
                    "the user set. get_related(key='${resolved.others.first().key}', value=" +
                    "'${resolved.others.first().value}') groups by the other instead.",
            )
        }

        return buildJsonObject {
            put("grouped_by", grouping.shortLabel)
            grouping.key?.let { put("key", it) }
            put("value", resolved.value)
            put("count", group.size)
            if (page.size < group.size) put("returned", page.size)
            if (resolved.others.isNotEmpty()) {
                put("other_keys", buildJsonArray {
                    resolved.others.forEach {
                        add(buildJsonObject { put("key", it.key); put("value", it.value) })
                    }
                })
            }
            if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
            put("events", buildJsonArray { page.forEach { add(brief(it)) } })
        }
    }

    /**
     * The short-value refusal, as a result rather than an empty group.
     *
     * PRD §4.2.1 is explicit that a value below the floor must say so: the usual cause is a paste
     * that clipped, and an empty timeline reads as "this never happened" — the most expensive
     * wrong answer this tool could give.
     */
    private fun tooShort(key: String?, value: String, minLength: Int): JsonElement = buildJsonObject {
        val grouping = Groupings.forValue(value, key)
        put("grouped_by", grouping.shortLabel)
        grouping.key?.let { put("key", it) }
        put("value", value)
        put("too_short", true)
        put("min_length", minLength)
        put("count", 0)
        put("note", "TOO SHORT TO MATCH SAFELY — '$value' is under $minLength characters, so it " +
            "was not matched at all rather than matched loosely: a short value appears inside " +
            "unrelated ids and timestamps and would group half the capture. This is not an empty " +
            "result. Use the full id, or " +
            (key?.let { "tick \"short values\" for '$it'" } ?: "tick \"short values\" for the key that holds it") +
            " in the LogPose window (filter bar → Filters → Correlation keys…) to accept the risk.")
        put("events", buildJsonArray {})
    }

    private fun unknownKey(name: String, keys: List<CorrelationKey>): JsonElement = errorObj(
        "'$name' is not a configured correlation key. " +
            (if (keys.isEmpty()) {
                "This project has none configured — LogPose ships no defaults, since the " +
                    "vocabulary belongs to the app."
            } else {
                "Configured: " + keys.joinToString(", ") { it.name } + "."
            }) +
            " Omit 'key' to group by the value alone (matching never uses the key), or add the " +
            "key in the LogPose window (filter bar → Filters → Correlation keys…); " +
            "list_correlation_keys " +
            "also reports what the capture suggests.",
    )

    private fun noKeyOnEvent(
        event: LogEvent,
        carried: List<KeyValue>,
        keys: List<CorrelationKey>,
    ): JsonElement = errorObj(
        "Event '${event.id}' carries no groupable correlation key. " +
            (if (carried.isEmpty()) "" else carries(carried) + " ") +
            (if (keys.isEmpty()) {
                "No key is configured for this project — list_correlation_keys reports what the " +
                    "capture suggests, and a person ticks one in the LogPose window. "
            } else {
                "Configured: " + keys.joinToString(", ") { it.name } + ". "
            }) +
            "Pass 'value' with an id you know from the payload instead" +
            (event.traceId?.let { ", or get_trace(trace_id='$it') for this row's trace" } ?: "") +
            ".",
    )

    private fun carries(carried: List<KeyValue>): String =
        if (carried.isEmpty()) "It carries no configured key at all."
        else "It carries: " + carried.joinToString(", ") {
            "${it.key} ${it.value}" + if (it.matchable) "" else " (too short to match)"
        } + "."

    /** `order_id`, `orderId` and `ORDER_ID` are one key — [CorrelationKeys]' rule, not a new one. */
    private fun sameKey(a: String, b: String): Boolean =
        CorrelationKeys.canonical(a) == CorrelationKeys.canonical(b)

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

    /**
     * The text a `contains:` / `exclude:` query matches against — the **broad** searchable
     * haystack, request/response bodies included.
     *
     * This is the MCP side catching up to the plugin's filter bar, which since 1.9.2 searches HTTP
     * request/response bodies (see `presentation.FilterState`, whose body search reads the very
     * same [Correlation.searchableText] through a [io.github.siddharthjaswal.logpose.analysis.CorrelationIndex]
     * cache). Routing both through [Correlation.searchableText] means an agent's `contains` and a
     * developer's filter box now agree on what "contains" means. Headers stay out of it, exactly as
     * the plugin's body search does — [Correlation.searchableText] excludes them on purpose.
     *
     * The old per-kind haystack (HTTP = url only; DB = sql/database/table; …) is a **subset** of
     * this for every kind, so every query that matched before still matches; the gain is that
     * bodies, db args, worker input/output data, config values and generic section bodies are now
     * reachable too. The one field [Correlation.searchableText] omits that the old row-search had
     * is a Generic event's kind, restored below so nothing an agent could match before is lost.
     *
     * **No cache, on purpose.** [CorrelationIndex] exists to keep this off the EDT's 150ms repaint
     * tick; MCP is request-scoped — an agent calls list_events occasionally, never per keystroke —
     * so a direct [Correlation.searchableText] per matched event is the right cost here, and it
     * keeps this file free of the plugin's cache plumbing.
     */
    private fun LogEvent.haystack(): String = when (this) {
        // searchableText omits a Generic event's kind; the old contains/exclude matched it, so keep it.
        is LogEvent.Generic -> Correlation.searchableText(this) + " " + kind
        else -> Correlation.searchableText(this)
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

    private fun objectProp(description: String) = buildJsonObject {
        put("type", "object"); put("description", description)
    }

    private fun arrayProp(description: String) = buildJsonObject {
        put("type", "array"); put("description", description)
    }

    // ---- argument parsing ------------------------------------------------------------------

    /**
     * A rejected argument. Thrown by the parsers below and turned into a plain `{error: …}`
     * result: an agent can read a message and correct itself, where a silently coerced value
     * produces a rule that matches something other than what was asked for.
     */
    private class BadArgs(message: String) : IllegalArgumentException(message)

    private fun errorObj(message: String): JsonElement = buildJsonObject { put("error", message) }

    /**
     * A JSON object of string values — a query/header matcher, or a push's data map. Numbers and
     * booleans are taken by their literal text (a query value is text on the wire anyway); an
     * object, array or null value is refused rather than stringified into something unmatchable.
     */
    private fun JsonObject.stringMap(key: String): Map<String, String>? {
        val value = this[key] ?: return null
        if (value is kotlinx.serialization.json.JsonNull) return null
        val obj = value as? JsonObject
            ?: throw BadArgs("'$key' must be an object of string values, e.g. {\"debug\":\"1\"}.")
        return obj.mapValues { (name, element) ->
            (element as? JsonPrimitive)?.contentOrNull()
                ?: throw BadArgs(
                    "'$key.$name' must be a string. Nested objects, arrays and nulls have no " +
                        "meaning here — send the value you want matched, as text.",
                )
        }
    }

    /** The per-hit response sequence. Every key is checked, so a typo can't vanish into a default. */
    private fun JsonObject.steps(key: String): List<MockStep> {
        val value = this[key] ?: return emptyList()
        if (value is kotlinx.serialization.json.JsonNull) return emptyList()
        val array = value as? JsonArray ?: throw BadArgs(
            "'$key' must be an array of step objects, e.g. [{\"status\":500},{\"status\":200}].",
        )
        return array.mapIndexed { index, element ->
            val step = element as? JsonObject
                ?: throw BadArgs("$key[$index] must be an object with at least a 'status'.")
            val unknown = step.keys - STEP_KEYS
            if (unknown.isNotEmpty()) {
                throw BadArgs(
                    "$key[$index] has unknown key(s) ${unknown.joinToString()}. A step takes " +
                        "${STEP_KEYS.joinToString()} — nothing else is applied, so an unknown key " +
                        "is refused rather than dropped.",
                )
            }
            val status = step.int("status")
                ?: throw BadArgs("$key[$index] needs a 'status' — a step with no status serves nothing.")
            if (status !in 100..599) throw BadArgs("$key[$index].status must be an HTTP status (100–599).")
            val behavior = step.str("behavior") ?: MockRule.BEHAVIOR_NORMAL
            if (behavior !in BEHAVIORS) {
                throw BadArgs("$key[$index].behavior must be 'normal', 'timeout' or 'connection_failure'.")
            }
            MockStep(
                status = status,
                body = step.str("body"),
                headers = step.stringMap("headers").orEmpty(),
                contentType = step.str("content_type") ?: "application/json",
                latencyMillis = (step.int("latency_ms") ?: 0).toLong(),
                behavior = behavior,
            )
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
