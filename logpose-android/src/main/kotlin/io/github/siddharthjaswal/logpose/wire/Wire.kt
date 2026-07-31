package io.github.siddharthjaswal.logpose.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The transport envelope every **timeline** event travels in. The plugin only needs to
 * understand this much to place a row on the timeline; [payload] is opaque to it until a
 * renderer registered for [kind] decodes it.
 *
 * That split is what makes LogPose a framework rather than an HTTP tool: an app can emit a
 * [kind] the plugin has never heard of (see [GenericEvent]) and still get a first-class row.
 *
 * Timing follows a span convention:
 *  - `endedAt == null` — still open (an in-flight request).
 *  - `endedAt == at`   — a point-in-time event (a push, a log line).
 *  - `endedAt > at`    — a completed span; the difference is its duration.
 *
 * [traceId] / [parentId] group related events (a push and the calls it triggered). They are
 * always set explicitly by the caller — LogPose does not propagate them implicitly.
 *
 * Note the reverse-channel control messages ([Hello], [MockAck], [MockRuleSet]) are NOT
 * enveloped: they are a separate IDE ↔ device protocol, not timeline rows.
 *
 * MUST stay structurally in sync with the plugin's `model/Transaction.kt`.
 */
@Serializable
data class Envelope(
    /** Wire version. Bumped only for breaking changes to the envelope itself. */
    val v: Int = 1,
    val kind: String,
    /** Correlation id; re-emitting the same id updates that row in place. */
    val id: String,
    /** Device epoch millis the event started. */
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
// First-class app-runtime kinds. Unlike [GenericEvent], these carry **structure** rather than
// presentation: the plugin derives the title, badges and sections, which keeps the wire free of
// theme decisions and lets the IDE filter and analyse them (slow queries, worker retries, flag
// changes). Kept structurally in sync with the plugin's model/Transaction.kt.
// ---------------------------------------------------------------------------------------------

/**
 * One database access. Operation and table are deliberately **not** required: the plugin parses
 * them out of [sql], so that logic lives in one place instead of on both sides of the wire.
 * Set them explicitly only for stores that aren't SQL (DataStore, MMKV, a key-value cache).
 */
@Serializable
data class DbQuery(
    val sql: String,
    /** Bound arguments, already stringified — never raw values that could carry PII wholesale. */
    val args: List<String> = emptyList(),
    val database: String? = null,
    /** Rows returned or affected, when the caller knows. */
    val rows: Int? = null,
    val error: String? = null,
    /** Override for non-SQL stores; normally derived from [sql]. */
    val operation: String? = null,
    val table: String? = null,
)

/**
 * A background work request at one point in its life. Emitted with the envelope id set to
 * [workId], so a request occupies **one row that updates in place** as it moves
 * enqueued → running → succeeded, rather than spraying a row per transition.
 */
@Serializable
data class WorkerEvent(
    val worker: String,
    /** [STATE_ENQUEUED], [STATE_RUNNING], [STATE_SUCCEEDED], [STATE_FAILED], … */
    val state: String,
    val workId: String? = null,
    val uniqueName: String? = null,
    val runAttempt: Int = 0,
    val tags: List<String> = emptyList(),
    val inputData: Map<String, String> = emptyMap(),
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null,
    /**
     * True when this event was replayed from WorkManager's persisted store as the observer
     * attached — i.e. the work ran before capture was watching — rather than observed live this
     * session. Lets the UI mark it and keeps it out of "ran this session" counts.
     */
    val replayedAtAttach: Boolean = false,
) {
    companion object {
        const val STATE_ENQUEUED = "enqueued"
        const val STATE_RUNNING = "running"
        const val STATE_SUCCEEDED = "succeeded"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_BLOCKED = "blocked"

        /** States after which no further transition is expected, so the span can close. */
        val TERMINAL = setOf(STATE_SUCCEEDED, STATE_FAILED, STATE_CANCELLED)
    }
}

/**
 * A remote-config activation, as **one event listing what changed** rather than one event per
 * key: a fetch typically flips several flags at once and an app can carry hundreds of them, so
 * per-key rows would bury the timeline.
 */
@Serializable
data class ConfigUpdate(
    val source: String? = null,
    val fetchStatus: String? = null,
    /**
     * True for the first snapshot in a process: it establishes what "unchanged" means, so it's
     * recorded as a count instead of reporting every key as new.
     */
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
 * so any subsystem (Room, WorkManager, analytics, feature flags…) can appear on the timeline
 * without a plugin release.
 *
 * Presentation stays **semantic** — a [Badge] carries a tone, never a color, and a [Section]
 * carries a type, never a layout. The plugin maps those onto the active IDE theme; putting
 * raw colors on the wire would make every theme change a wire break.
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
 * The on-the-wire representation emitted by the LogPose interceptor and consumed
 * by the LogPose IDE plugin. This MUST stay structurally in sync with the
 * plugin's `model/Transaction.kt`.
 */
@Serializable
data class Transaction(
    val id: String,
    val startedAtMillis: Long = 0,
    val request: Request,
    val response: Response? = null,
    val durationMillis: Long? = null,
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

@Serializable
data class Body(
    val contentType: String? = null,
    val sizeBytes: Long = 0,
    val text: String? = null,
    val truncated: Boolean = false,
    /** True when [text] was produced by a [io.github.siddharthjaswal.logpose.BodyDecoder]
     *  (e.g. a decrypted payload) rather than read straight off the wire. */
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

@Serializable
data class Chunk(
    val id: String,
    val seq: Int,
    val total: Int,
    val payload: String,
)

/**
 * A Firebase Cloud Messaging event (an incoming push, or a registration-token refresh),
 * emitted on the same logcat tag as [Transaction] and told apart by [kind] = "fcm".
 *
 * FCM is push, not OkHttp traffic — the host app feeds these in explicitly via
 * `LogPose.logFcmMessage` / `LogPose.logFcmToken` (see `LogPoseFcm.kt`). Kept structurally
 * in sync with the plugin's `model/Transaction.kt`.
 */
@Serializable
data class FcmMessage(
    /** Discriminator: always "fcm", so the plugin can tell this from a [Transaction]. */
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
// Mock & replay (reverse channel). Rules travel IDE → device via an adb broadcast to
// MockCommandReceiver; Hello/MockAck travel device → IDE on the normal LogPose logcat tag
// (discriminated by "kind", like FcmMessage). Kept structurally in sync with the plugin's
// model/Transaction.kt.
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
     * "replace" — [body] is the whole response (default, back-compatible).
     * "patch"   — let the real network response come back, then deep-merge [body] (a JSON
     *             object) into it: object keys recurse, scalars/arrays are overwritten, new
     *             keys are added. Lets you tweak one field and leave the rest backend-generated.
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
 * Emitted on process start (init provider) and on the first intercept, so the IDE learns the
 * app's package (needed to target the mock broadcast) and current mock revision (0 after a
 * process death ⇒ the IDE knows to re-push).
 */
@Serializable
data class Hello(
    val kind: String = "hello",
    val pkg: String,
    val libVersion: String,
    val mockRevision: Int = 0,
    /**
     * Random per process. Two hellos carrying the same id are the same app run (the provider
     * emits one at startup and the interceptor re-emits on its first call); a different id means
     * the process restarted, which is what lets the IDE draw a session boundary instead of
     * running two app launches together into one timeline.
     */
    val processId: String = "",
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
