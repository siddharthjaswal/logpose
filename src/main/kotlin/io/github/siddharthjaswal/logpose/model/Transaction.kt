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
 * Reverse-channel control messages ([Hello], [MockAck]) are NOT enveloped — they are a
 * separate IDE ↔ device protocol, not timeline rows.
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
// Mock & replay (reverse channel). Rules travel IDE → device via an adb broadcast to the
// library's MockCommandReceiver; Hello/MockAck travel device → IDE on the normal LogPose
// logcat tag (discriminated by "kind", like FcmMessage). Mirrors the library's wire/Wire.kt.
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
    /** Delay before responding (also the delay before a timeout/failure fires). */
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
) {
    companion object {
        const val BEHAVIOR_NORMAL = "normal"
        const val BEHAVIOR_TIMEOUT = "timeout"
        const val BEHAVIOR_CONNECTION_FAILURE = "connection_failure"
        const val MODE_REPLACE = "replace"
        const val MODE_PATCH = "patch"
    }
}

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
)

/** Emitted after a rule set applies; confirms sync and carries per-rule serve counts. */
@Serializable
data class MockAck(
    val kind: String = "mock_ack",
    val pkg: String,
    val revision: Int,
    /** rule id → times served so far in this process. */
    val hits: Map<String, Int> = emptyMap(),
)
