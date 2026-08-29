package io.github.siddharthjaswal.logpose.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The transport envelope every **timeline** event arrives in. This plugin only needs to
 * understand this much to place a row on the timeline; [payload] stays opaque until a
 * renderer for [kind] decodes it.
 *
 * That split is what makes LogPose a framework rather than an HTTP tool: a device can emit a
 * [kind] this plugin has never heard of (see [GenericEvent]) and still get a first-class row.
 *
 * Timing follows a span convention:
 *  - `endedAt == null` — still open (an in-flight request).
 *  - `endedAt == at`   — a point-in-time event (a push, a log line).
 *  - `endedAt > at`    — a completed span.
 *
 * Reverse-channel control messages ([Hello], [MockAck], [PushAck]) are NOT enveloped — they are
 * a separate IDE ↔ device protocol, not timeline rows.
 *
 * MUST stay structurally in sync with the library's `wire/Wire.kt`.
 */
@Serializable
data class Envelope(
    val v: Int = 1,
    val kind: String,
    val id: String,
    val at: Long,
    val endedAt: Long? = null,
    val traceId: String? = null,
    val parentId: String? = null,
    val payload: JsonElement,
) {
    companion object {
        const val KIND_HTTP = "http"
        const val KIND_FCM = "fcm"
        const val KIND_EVENT = "event"
        const val KIND_ANALYTICS = "analytics"
        const val KIND_DB = "db"
        const val KIND_WORKER = "worker"
        const val KIND_CONFIG = "config"
    }
}

// ---------------------------------------------------------------------------------------------
// First-class app-runtime kinds. Unlike [GenericEvent] these carry **structure** rather than
// presentation — the plugin derives title/badges/sections (see `ui/KindPresenter.kt`), which
// keeps theme decisions out of the wire and lets the IDE filter and analyse them. Kept
// structurally in sync with the library's wire/Wire.kt.
// ---------------------------------------------------------------------------------------------

/**
 * One database access. [operation] and [table] are normally absent — they're derived from [sql]
 * by `analysis/SqlSummary.kt`, so that parsing lives on one side of the wire only. They arrive
 * populated only for stores that aren't SQL.
 */
@Serializable
data class DbQuery(
    val sql: String,
    val args: List<String> = emptyList(),
    val database: String? = null,
    val rows: Int? = null,
    val error: String? = null,
    val operation: String? = null,
    val table: String? = null,
)

/**
 * A background work request at one point in its life. Emitted under the request's `workId`, so
 * enqueued → running → succeeded update **one** row rather than adding three.
 */
@Serializable
data class WorkerEvent(
    val worker: String,
    val state: String,
    val workId: String? = null,
    val uniqueName: String? = null,
    val runAttempt: Int = 0,
    val tags: List<String> = emptyList(),
    val inputData: Map<String, String> = emptyMap(),
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null,
    /**
     * Device epoch millis the library **observed** this request enter its current queue phase —
     * the enqueue before attempt 1, the backoff before a retry. Same clock as [Envelope.at], and
     * re-stamped onto every later emission for this `workId`, which is what makes it survive the
     * in-place row update that would otherwise overwrite it.
     *
     * Null means the transition was never observed (capture attached while the request was
     * already queued, the process restarted, the row was replayed) — and null must be rendered as
     * *nothing*, never approximated from arrival times. With [runStartedAtMillis] it gives the
     * queue wait; a library older than 1.7.2 never sends either, so both stay null there.
     */
    val enqueuedAtMillis: Long? = null,
    /**
     * Device epoch millis the current attempt was **observed** to start running. Null until it
     * runs, and null when the start was never observed — again, never to be guessed at.
     *
     * With the envelope's `endedAt` this gives the *run* duration, as opposed to the whole span
     * (which is queue + run). Both instants always describe the **current attempt**: a retry
     * re-enters the queue, resetting [enqueuedAtMillis] to the backoff and clearing this — so
     * report them alongside [runAttempt].
     */
    val runStartedAtMillis: Long? = null,
    /** True when replayed from WorkManager's persisted store on observer-attach rather than
     *  observed running this session — the UI marks it and it's kept out of run counts. Such a
     *  row carries neither instant above, by construction: no transition was observed for it. */
    val replayedAtAttach: Boolean = false,
) {
    companion object {
        const val STATE_ENQUEUED = "enqueued"
        const val STATE_RUNNING = "running"
        const val STATE_SUCCEEDED = "succeeded"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_BLOCKED = "blocked"
        val TERMINAL = setOf(STATE_SUCCEEDED, STATE_FAILED, STATE_CANCELLED)
    }
}

/** A config activation, as one event listing what changed rather than one event per key. */
@Serializable
data class ConfigUpdate(
    val source: String? = null,
    val fetchStatus: String? = null,
    /** First snapshot of a process: a count, not every key reported as new. */
    val baseline: Boolean = false,
    val totalKeys: Int = 0,
    val changes: List<ConfigChange> = emptyList(),
)

@Serializable
data class ConfigChange(
    val key: String,
    val value: String,
    val previous: String? = null,
    val isNew: Boolean = false,
)

/**
 * The payload of the built-in `"event"` kind: an event that describes its own presentation,
 * so any subsystem (Room, WorkManager, analytics, feature flags…) appears on the timeline
 * without a plugin release.
 *
 * Presentation is **semantic** — a [Badge] carries a tone, never a color, and a [Section]
 * carries a type, never a layout — so the plugin can map it onto the active IDE theme.
 */
@Serializable
data class GenericEvent(
    val title: String,
    val subtitle: String? = null,
    val badges: List<Badge> = emptyList(),
    val sections: List<Section> = emptyList(),
)

/** A short pill on the row — a category ("DB"), a count, a duration. */
@Serializable
data class Badge(
    val text: String,
    /** One of [TONE_INFO], [TONE_WARN], [TONE_ERROR], [TONE_MUTED]. */
    val tone: String = TONE_MUTED,
) {
    companion object {
        const val TONE_INFO = "info"
        const val TONE_WARN = "warn"
        const val TONE_ERROR = "error"
        const val TONE_MUTED = "muted"
    }
}

/** One labelled block in the detail pane. */
@Serializable
data class Section(
    val label: String,
    /** One of [TYPE_TEXT], [TYPE_JSON], [TYPE_KV], [TYPE_CODE]. */
    val type: String = TYPE_TEXT,
    /** Shape depends on [type]: a string for text/code, any JSON for json, an object for kv. */
    val body: JsonElement,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_JSON = "json"
        const val TYPE_KV = "kv"
        const val TYPE_CODE = "code"
    }
}

/**
 * The wire contract between the on-device LogPose interceptor and this plugin.
 *
 * The interceptor builds ONE [Transaction] per HTTP exchange (request + response
 * together) and emits it as a single line of JSON. Emitting the whole exchange
 * atomically is what fixes the classic logcat problem where concurrent requests
 * interleave their lines and bodies get mismatched.
 */
@Serializable
data class Transaction(
    /** Stable id correlating request and response. Generated on the device. */
    val id: String,
    /** Epoch millis when the request left the client. */
    val startedAtMillis: Long = 0,
    val request: Request,
    /** Null until the response arrives (or if the call failed before responding). */
    val response: Response? = null,
    /** Total round-trip time in millis, if known. */
    val durationMillis: Long? = null,
    /** Populated when the call threw (timeout, connection reset, etc.). */
    val error: String? = null,
    /** True when the response was served by an active LogPose mock rule, not the network. */
    val mocked: Boolean = false,
)

@Serializable
data class Request(
    val method: String,
    val url: String,
    val host: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: Body? = null,
)

@Serializable
data class Response(
    val code: Int,
    val message: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: Body? = null,
)

/**
 * A request or response payload. For textual payloads [text] holds the content.
 * For multipart uploads (S3 / GCS media) we deliberately do NOT ship raw bytes —
 * [parts] carries per-part metadata instead, so large binary uploads stay cheap
 * and readable.
 */
@Serializable
data class Body(
    val contentType: String? = null,
    val sizeBytes: Long = 0,
    val text: String? = null,
    val truncated: Boolean = false,
    /** True when [text] was decoded on-device (e.g. a decrypted payload) rather than read
     *  straight off the wire — the UI can mark it so it isn't mistaken for cleartext. */
    val decoded: Boolean = false,
    val parts: List<MultipartPart>? = null,
)

@Serializable
data class MultipartPart(
    val name: String? = null,
    val filename: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
)

/**
 * Envelope used when a [Transaction] is too large for a single logcat line
 * (logcat truncates entries at ~4 KB). The interceptor splits the JSON payload
 * into ordered chunks sharing one [id]; the plugin reassembles them.
 */
@Serializable
data class Chunk(
    val id: String,
    val seq: Int,
    val total: Int,
    val payload: String,
)

/**
 * A Firebase Cloud Messaging event (an incoming push, or a registration-token refresh),
 * emitted by the on-device library on the same logcat tag as [Transaction] and told apart
 * by [kind] = "fcm". Mirrors the library's `wire/Wire.kt`.
 */
@Serializable
data class FcmMessage(
    /** Discriminator: always "fcm", so the parser can tell this from a [Transaction]. */
    val kind: String = "fcm",
    /** Correlation id — the FCM messageId when present, otherwise a generated short id. */
    val id: String,
    /** "message" (an incoming push) or "token" (onNewToken). */
    val event: String = "message",
    /** Host-device epoch millis when the app handed the event to LogPose. */
    val receivedAtMillis: Long = 0,
    // message events:
    val messageId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val collapseKey: String? = null,
    val messageType: String? = null,
    val sentTimeMillis: Long? = null,
    val ttlSeconds: Int? = null,
    val priority: Int? = null,
    val notification: FcmNotification? = null,
    val data: Map<String, String> = emptyMap(),
    // token events:
    val token: String? = null,
    /**
     * True when LogPose itself delivered this push on the IDE's behalf (see [PushInject]) rather
     * than the app reporting one Firebase actually delivered. The timeline never passes an
     * injected push off as a real one — including when the app's own messaging service re-logs
     * the injected push through `logFcmMessage`: library 1.7.1+ recognises that re-log by its
     * message id, keeps the flag, and emits it under the same envelope id so the two land on one
     * row. Library 1.7.0+; older payloads simply omit it.
     */
    val injected: Boolean = false,
)

@Serializable
data class FcmNotification(
    val title: String? = null,
    val body: String? = null,
    val channelId: String? = null,
    val clickAction: String? = null,
    val imageUrl: String? = null,
)

// ---------------------------------------------------------------------------------------------
// Mock & replay + push injection (reverse channel). Commands travel IDE → device via an adb
// broadcast to the library's MockCommandReceiver, told apart by the `cmd` extra
// (`rules` | `push`); Hello/MockAck/PushAck travel device → IDE on the normal LogPose logcat tag
// (discriminated by "kind", like FcmMessage). Mirrors the library's wire/Wire.kt.
// ---------------------------------------------------------------------------------------------

/** One mock rule: match by method + path pattern, serve the described response. */
@Serializable
data class MockRule(
    /** Stable id assigned by the plugin; hit counts are keyed by it. */
    val id: String,
    /** HTTP method to match, or "*" for any. */
    val method: String,
    /** Exact request path, or a glob where '*' matches any run of characters. */
    val pathPattern: String,
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json",
    /** Delay before responding — and before a timeout/failure throws. A failure with latency 0
     *  throws almost instantly (failure path, not an in-flight window); raise it to hold the
     *  request in flight and reproduce a race during a slow call. */
    val latencyMillis: Long = 0,
    /** "normal" | "timeout" | "connection_failure" */
    val behavior: String = BEHAVIOR_NORMAL,
    /** 0 = serve forever; N = serve N times, then the rule deactivates. */
    val serveLimit: Int = 0,
    val enabled: Boolean = true,
    /**
     * "replace" — [body] is the whole response (default). "patch" — deep-merge [body] into the
     * real backend response (override keys, add keys, keep the rest).
     */
    val mode: String = MODE_REPLACE,
    /**
     * Extra match constraints, all narrowing on top of method + [pathPattern]. Every entry must
     * hold for the rule to match, and an empty map is no constraint at all — so a rule written
     * before these existed behaves exactly as it did. Needs device library 1.7.0+ (see
     * [io.github.siddharthjaswal.logpose.mock.DeviceFeature.RICH_MATCHERS]).
     *
     * Query pairs the request must carry: exact value, or [MATCH_ANY] for "key present, any value".
     */
    val matchQuery: Map<String, String> = emptyMap(),
    /** Request headers that must be present. Name matched case-insensitively; value exactly, or
     *  [MATCH_ANY] for "header present, any value". Needs device library 1.7.0+. */
    val matchHeaders: Map<String, String> = emptyMap(),
    /**
     * Case-insensitive substring the request body must contain. Matching **fails closed** on the
     * device: a body it could not buffer never matches, so the call goes to the network rather
     * than being mocked on a guess. Needs device library 1.7.0+.
     */
    val matchBodyContains: String? = null,
    /**
     * Sequential responses. When non-empty these replace the single [status]/[body]/[headers]/
     * [contentType]/[latencyMillis]/[behavior] above: hit *N* (0-based, from this rule's serve
     * count) serves step `min(N, responses.lastIndex)`, so the last step sticks once the list
     * runs out. `[{status:500}, {status:200, body:…}]` is the canonical retry test.
     *
     * [serveLimit] and [mode] still apply at rule level. Needs device library 1.7.0+.
     */
    val responses: List<MockStep> = emptyList(),
) {
    companion object {
        const val BEHAVIOR_NORMAL = "normal"
        const val BEHAVIOR_TIMEOUT = "timeout"
        const val BEHAVIOR_CONNECTION_FAILURE = "connection_failure"
        const val MODE_REPLACE = "replace"
        const val MODE_PATCH = "patch"
        /** Matcher value meaning "present, whatever the value". */
        const val MATCH_ANY = "*"
    }
}

/**
 * One response in a [MockRule.responses] sequence. Same shape as the rule's own response fields,
 * so a single-response rule and a one-step sequence serve identically — including the defaults,
 * which are the rule's defaults (a step that omits `contentType` serves `application/json`
 * rather than inheriting the now-ignored rule-level value).
 */
@Serializable
data class MockStep(
    val status: Int = 200,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "application/json",
    val latencyMillis: Long = 0,
    /** "normal" | "timeout" | "connection_failure" — see [MockRule.behavior]. */
    val behavior: String = MockRule.BEHAVIOR_NORMAL,
)

/** The full replacement rule set pushed by the IDE; applied atomically by revision. */
@Serializable
data class MockRuleSet(
    val kind: String = "mock_rules",
    /** Monotonic revision from the plugin; stale revisions are ignored. */
    val revision: Int,
    val rules: List<MockRule> = emptyList(),
)

/**
 * Emitted by the library on process start and on the first intercept, so the IDE learns the
 * app's package (needed to target the mock broadcast) and current mock revision (0 after a
 * process death ⇒ the IDE knows to re-push).
 */
@Serializable
data class Hello(
    val kind: String = "hello",
    val pkg: String,
    val libVersion: String,
    val mockRevision: Int = 0,
    /** Active mock rules right now (library 1.6.0+); 0 after a restart wiped the registry. */
    val ruleCount: Int = 0,
    /**
     * Random per app run (library 1.5.0+; empty from older libraries). Same id across two hellos
     * = same process re-announcing itself; a different id = the app restarted, which is the
     * boundary [io.github.siddharthjaswal.logpose.store.EventStore] splits sessions on.
     */
    val processId: String = "",
)

/** Emitted after a rule set applies; confirms sync and carries per-rule serve counts. */
@Serializable
data class MockAck(
    val kind: String = "mock_ack",
    val pkg: String,
    val revision: Int,
    /** Active rules after this set applied (library 1.6.0+). */
    val ruleCount: Int = 0,
    /** rule id → times served so far in this process. */
    val hits: Map<String, Int> = emptyMap(),
)

/**
 * A synthetic push the IDE asks the device to deliver in-process — no Play services, no network.
 * Pushed over the same broadcast channel as [MockRuleSet], told apart by the `cmd` extra
 * (`push`); the revision extra is meaningless here and ignored.
 *
 * The device emits the resulting FCM row with [FcmMessage.injected] set **before** it attempts
 * delivery, so an injection that fails to reach a handler still shows on the timeline, and then
 * reports the outcome in a [PushAck] carrying the same [id]. Needs device library 1.7.0+.
 */
@Serializable
data class PushInject(
    val kind: String = "push_inject",
    /**
     * Correlation id: the FCM row's envelope id, the id the [PushAck] comes back under, and —
     * since 1.9.0 / library 1.7.1 — [PushMessage.messageId] itself. One value for all three is
     * what makes the app's own re-log of the injected push land on the row LogPose already
     * emitted, instead of appearing beside it as an unmarked twin (see `mock/PushReplay`).
     */
    val id: String,
    /** Trace to deliver inside, so everything the push triggers lands in one `get_trace` group. */
    val traceId: String? = null,
    val message: PushMessage = PushMessage(),
)

/**
 * The push itself, field-for-field the app-facing `FcmMessageInfo` (flat, not the nested
 * [FcmNotification] shape [FcmMessage] uses) — so a captured push can be replayed by copying
 * the row's fields straight across.
 */
@Serializable
data class PushMessage(
    val messageId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val collapseKey: String? = null,
    val messageType: String? = null,
    val sentTimeMillis: Long? = null,
    val ttlSeconds: Int? = null,
    val priority: Int? = null,
    val notificationTitle: String? = null,
    val notificationBody: String? = null,
    val notificationChannelId: String? = null,
    val notificationClickAction: String? = null,
    val notificationImageUrl: String? = null,
    val data: Map<String, String> = emptyMap(),
)

/**
 * Emitted after an injected push has been offered to the app; [delivered] says which tier took
 * it. [DELIVERED_NONE] is the "nothing is listening" answer the IDE turns into the
 * register-a-handler hint — an outcome, never a crash: delivery failures are reported, not thrown.
 */
@Serializable
data class PushAck(
    val kind: String = "push_ack",
    val pkg: String,
    /** The [PushInject.id] this answers. */
    val id: String,
    /** [DELIVERED_HANDLER] | [DELIVERED_SERVICE] | [DELIVERED_NONE]. */
    val delivered: String = DELIVERED_NONE,
    val error: String? = null,
) {
    companion object {
        /** Tier 1: the app's own `LogPose.onPushInject { }` handler ran. */
        const val DELIVERED_HANDLER = "handler"
        /** Tier 2: the manifest's FirebaseMessagingService got `onMessageReceived` reflectively. */
        const val DELIVERED_SERVICE = "service"
        /** Nothing consumed it — no handler registered and no service reachable. */
        const val DELIVERED_NONE = "none"
    }
}
