package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.emit.emit
import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.wire.ConfigChange
import io.github.siddharthjaswal.logpose.wire.ConfigUpdate
import io.github.siddharthjaswal.logpose.wire.DbQuery
import io.github.siddharthjaswal.logpose.wire.FcmNotification
import io.github.siddharthjaswal.logpose.wire.WorkerEvent
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import io.github.siddharthjaswal.logpose.wire.FcmMessage as WireFcmMessage

/**
 * Entry point for everything the app tells LogPose about explicitly.
 *
 * HTTP traffic arrives on its own via [LogPoseInterceptor]; this object covers the rest:
 * push messages ([logFcmMessage] / [logFcmToken]) and arbitrary app events ([event] / [log]).
 *
 * The event API is the framework half of LogPose — HTTP and FCM are two kinds among many, so
 * an app can put its own subsystems (database queries, background jobs, analytics,
 * feature-flag evaluations, navigation) on the same time-ordered stream, and they render in
 * the IDE with no plugin support required.
 *
 * Every call no-ops when [LogPoseConfig.enabled] is false, so wiring that to
 * `BuildConfig.DEBUG` keeps all of it out of release builds.
 */
object LogPose {

    // ---- App events -------------------------------------------------------------------

    /**
     * Emit a self-describing event: it carries its own title, badges, and detail sections, so
     * the plugin renders it without knowing what it is.
     *
     * ```kotlin
     * LogPose.event("WorkManager") {
     *     subtitle = "SyncWorker succeeded"
     *     badge("JOB", Tone.INFO)
     *     took(1_240)
     *     kv("Tags", mapOf("unique" to "sync", "attempt" to "2"))
     * }
     * ```
     */
    fun event(
        title: String,
        config: LogPoseConfig = LogPoseConfig(),
        build: EventBuilder.() -> Unit = {},
    ) {
        if (!config.enabled) return
        val builder = EventBuilder(title).apply(build)
        emit(
            Envelope(
                kind = Envelope.KIND_EVENT,
                id = builder.id,
                at = builder.at,
                endedAt = builder.endedAt ?: builder.at,
                traceId = builder.traceId,
                parentId = builder.parentId,
                payload = json.encodeToJsonElement(builder.build()),
            ),
            config,
        )
    }

    /**
     * Escape hatch: emit a raw payload under your own [kind]. Use this when something on the
     * other side understands the shape (a future renderer, or an MCP consumer); otherwise
     * prefer [event], which renders everywhere with no extra support.
     *
     * [payloadJson] should be a JSON document; malformed input is emitted as a JSON string,
     * because a logging call must never throw into the caller.
     */
    fun log(
        kind: String,
        payloadJson: String,
        id: String = newId(),
        at: Long = System.currentTimeMillis(),
        endedAt: Long? = at,
        traceId: String? = null,
        parentId: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) {
        if (!config.enabled) return
        val payload: JsonElement = runCatching { Json.parseToJsonElement(payloadJson) }
            .getOrElse { JsonPrimitive(payloadJson) }
        emit(
            Envelope(
                kind = kind,
                id = id,
                at = at,
                endedAt = endedAt,
                traceId = traceId,
                parentId = parentId,
                payload = payload,
            ),
            config,
        )
    }

    /** A fresh trace id, to correlate a group of related events. */
    fun newTraceId(): String = newId()

    private val currentTrace = ThreadLocal<String?>()

    /** The trace id currently in scope on this thread (see [withTrace]), or null. */
    fun currentTraceId(): String? = currentTrace.get()

    /**
     * Run [block] with an ambient trace id: **every event emitted on this thread inside it** —
     * analytics, db, config, app events — is auto-stamped with [traceId] unless it already carries
     * one, so `get_trace` collapses a whole flow into one call without threading the id by hand.
     *
     * Mint one at the entry point (an FCM push, a screen open) and wrap the work:
     * ```kotlin
     * LogPose.withTrace { // fresh id
     *     handlePush(message)   // its analytics/db events all share the trace
     * }
     * ```
     * Thread-local, so it does **not** cross a coroutine dispatch or a thread hop — pass the id
     * explicitly (or re-enter `withTrace`) on the other side of an async boundary.
     */
    fun <T> withTrace(traceId: String = newTraceId(), block: () -> T): T {
        val previous = currentTrace.get()
        currentTrace.set(traceId)
        try {
            return block()
        } finally {
            currentTrace.set(previous)
        }
    }

    /**
     * Carry the current ambient trace across one async hop. [withTrace] is thread-local, so a
     * `viewModelScope.launch { … }` runs on another thread with no trace in scope. Capture at the
     * launch site and let the returned lambda re-enter the trace wherever it actually runs:
     * ```kotlin
     * val work = LogPose.continueTrace { repository.getOrder(id) } // grabs the id now
     * viewModelScope.launch { work() }                             // re-enters it there
     * ```
     * If no trace is in scope when captured, [block] runs unwrapped. This binds the trace once at
     * the hop; it is not a substitute for `withTrace` around a coroutine that suspends repeatedly.
     */
    fun <T> continueTrace(block: () -> T): () -> T {
        val captured = currentTrace.get()
        return { if (captured == null) block() else withTrace(captured, block) }
    }

    // ---- Analytics ----------------------------------------------------------------------

    /**
     * Record an analytics event (see [AnalyticsEventInfo]) — one line in your analytics facade
     * puts every event on the timeline, PII in the params masked per
     * [LogPoseConfig.redactAnalyticsParams].
     *
     * Emitted under its own `analytics` kind carrying a self-describing payload, so it renders in
     * any plugin build with no update; [LogPoseConfig.analyticsEnabled] switches it off.
     */
    fun logAnalytics(info: AnalyticsEventInfo, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled || !config.analyticsEnabled) return
        val redacted = maskParams(info.params, config)
        val builder = EventBuilder(info.name).apply {
            info.screen?.let { subtitle = it }
            badge("ANALYTICS", Tone.INFO)
            info.provider?.let { badge(it.uppercase(), Tone.MUTED) }
            if (redacted.isNotEmpty()) kv("Params", redacted)
            info.traceId?.let { traceId = it }
        }
        emit(
            Envelope(
                kind = Envelope.KIND_ANALYTICS,
                id = builder.id,
                at = builder.at,
                endedAt = builder.at,
                traceId = builder.traceId,
                payload = json.encodeToJsonElement(builder.build()),
            ),
            config,
        )
    }

    /** Masks analytics param values whose key matches [LogPoseConfig.redactAnalyticsParams]
     *  (case-insensitive substring), leaving everything else intact. Pure, for tests. */
    internal fun maskParams(params: Map<String, String>, config: LogPoseConfig): Map<String, String> {
        if (params.isEmpty()) return params
        val patterns = config.redactAnalyticsParams.map { it.lowercase() }
        if (patterns.isEmpty()) return params
        return params.mapValues { (key, value) ->
            if (patterns.any { it in key.lowercase() }) "██" else value
        }
    }

    // ---- Database -----------------------------------------------------------------------

    /**
     * Record a database access. See [DbQueryInfo] for the one-call Room integration.
     *
     * Gated by [LogPoseConfig.dbEnabled] as well as `enabled`: a query callback on a busy screen
     * can emit hundreds of events a minute, and that switch is how a build opts out without
     * unpicking the integration.
     */
    fun logDbQuery(info: DbQueryInfo, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled || !config.dbEnabled) return
        val at = System.currentTimeMillis()
        emit(
            Envelope(
                kind = Envelope.KIND_DB,
                id = newId(),
                at = at,
                // A measured query is a span; an unmeasured one (Room's callback gives no
                // timing) is a point in time rather than a span of unknown length.
                endedAt = at + (info.durationMillis ?: 0L),
                payload = json.encodeToJsonElement(
                    DbQuery(
                        sql = info.sql,
                        args = info.args,
                        database = info.database,
                        rows = info.rows,
                        error = info.error,
                        operation = info.operation,
                        table = info.table,
                    )
                ),
            ),
            config,
        )
    }

    // ---- Background work ----------------------------------------------------------------

    /**
     * Record a background work request's state.
     *
     * Emitted under the request's own [WorkerEventInfo.workId], so enqueued → running →
     * succeeded collapse into **one row that updates in place** instead of three. The span opens
     * when the request is first seen and closes on a terminal state.
     */
    fun logWorker(info: WorkerEventInfo, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled || !config.workersEnabled) return
        val id = info.workId ?: newId()
        val now = System.currentTimeMillis()
        val terminal = info.state.lowercase() in WorkerEvent.TERMINAL

        // First sighting starts the span; a terminal state closes it and forgets the request so
        // a long session can't leak entries.
        var replayedAtAttach = false
        val startedAt = synchronized(workerStarts) {
            val firstSighting = id !in workerStarts
            val started = workerStarts.getOrPut(id) { now }
            if (terminal) workerStarts.remove(id)
            // A workId we've never seen that's already terminal ran before we attached: WorkManager
            // replays its persisted store to a fresh observer. Live work passes through
            // enqueued/running first, so we'd have seen it. (A worker that finishes between two
            // emissions is the rare false positive — acceptable versus counting replays as runs.)
            replayedAtAttach = firstSighting && terminal
            started
        }

        emit(
            Envelope(
                kind = Envelope.KIND_WORKER,
                id = id,
                at = startedAt,
                endedAt = if (terminal) now else null,
                payload = json.encodeToJsonElement(
                    WorkerEvent(
                        worker = info.worker,
                        state = info.state.lowercase(),
                        workId = info.workId,
                        uniqueName = info.uniqueName,
                        runAttempt = info.runAttempt,
                        tags = info.tags,
                        inputData = info.inputData,
                        outputData = info.outputData,
                        error = info.error,
                        replayedAtAttach = replayedAtAttach,
                    )
                ),
            ),
            config,
        )
    }

    // ---- Remote config --------------------------------------------------------------------

    /**
     * Record a config activation by handing LogPose the **whole** current snapshot; it diffs
     * against what it last saw and reports only what changed.
     *
     * That's the right split because Firebase Remote Config won't tell you what changed — you
     * get a map and a boolean. Call it once, wherever activation completes:
     *
     * ```kotlin
     * firebaseRemoteConfig.fetchAndActivate().addOnCompleteListener {
     *     LogPose.logConfigSnapshot(
     *         firebaseRemoteConfig.all.mapValues { it.value.asString() },
     *         source = "remote",
     *         config = LogPoseConfig(enabled = BuildConfig.DEBUG),
     *     )
     * }
     * ```
     *
     * The first snapshot in a process is recorded as a baseline count rather than reporting
     * every key as new.
     */
    fun logConfigSnapshot(
        values: Map<String, String>,
        source: String? = null,
        fetchStatus: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) {
        if (!config.enabled) return

        val update = synchronized(configSnapshot) {
            val previous = configSnapshot.toMap()
            configSnapshot.clear()
            configSnapshot.putAll(values)

            if (previous.isEmpty()) {
                ConfigUpdate(
                    source = source, fetchStatus = fetchStatus,
                    baseline = true, totalKeys = values.size,
                )
            } else {
                val changes = values.mapNotNull { (key, value) ->
                    val before = previous[key]
                    if (before == value) null
                    else ConfigChange(key, value, before, isNew = !previous.containsKey(key))
                }
                ConfigUpdate(
                    source = source, fetchStatus = fetchStatus,
                    totalKeys = values.size, changes = changes,
                )
            }
        }

        // A fetch that changed nothing is the common case; don't spend a row on it.
        if (!update.baseline && update.changes.isEmpty()) return

        val at = System.currentTimeMillis()
        emit(
            Envelope(
                kind = Envelope.KIND_CONFIG, id = newId(), at = at, endedAt = at,
                payload = json.encodeToJsonElement(update),
            ),
            config,
        )
    }

    /** Record a single known config change, for apps that already track their own flags. */
    fun logConfigChange(
        key: String,
        value: String,
        previous: String? = null,
        source: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) {
        if (!config.enabled) return
        synchronized(configSnapshot) { configSnapshot[key] = value }
        val at = System.currentTimeMillis()
        emit(
            Envelope(
                kind = Envelope.KIND_CONFIG, id = newId(), at = at, endedAt = at,
                payload = json.encodeToJsonElement(
                    ConfigUpdate(
                        source = source,
                        totalKeys = 1,
                        changes = listOf(
                            ConfigChange(key, value, previous, isNew = previous == null),
                        ),
                    )
                ),
            ),
            config,
        )
    }

    // ---- Push messaging ---------------------------------------------------------------

    /** Record an incoming FCM push. Call from `FirebaseMessagingService.onMessageReceived`. */
    fun logFcmMessage(info: FcmMessageInfo, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled) return
        val notification = if (
            info.notificationTitle != null || info.notificationBody != null ||
            info.notificationChannelId != null || info.notificationClickAction != null ||
            info.notificationImageUrl != null
        ) {
            FcmNotification(
                title = info.notificationTitle,
                body = info.notificationBody,
                channelId = info.notificationChannelId,
                clickAction = info.notificationClickAction,
                imageUrl = info.notificationImageUrl,
            )
        } else null

        emitFcm(
            WireFcmMessage(
                id = info.messageId ?: newId(),
                event = "message",
                receivedAtMillis = System.currentTimeMillis(),
                messageId = info.messageId,
                from = info.from,
                to = info.to,
                collapseKey = info.collapseKey,
                messageType = info.messageType,
                sentTimeMillis = info.sentTimeMillis,
                ttlSeconds = info.ttlSeconds,
                priority = info.priority,
                notification = notification,
                data = info.data,
            ),
            config,
        )
    }

    /** Record an FCM registration-token refresh. Call from `onNewToken`. */
    fun logFcmToken(token: String, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled) return
        emitFcm(
            WireFcmMessage(
                id = newId(),
                event = "token",
                receivedAtMillis = System.currentTimeMillis(),
                token = token,
            ),
            config,
        )
    }

    // ---- internals --------------------------------------------------------------------

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** workId → when the request was first seen, so a worker row spans its whole life. */
    private val workerStarts = mutableMapOf<String, Long>()

    /** Last config snapshot seen, so an activation can be reported as a diff. */
    private val configSnapshot = mutableMapOf<String, String>()

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    /** Wrap an FCM message in its envelope and emit through [emit], so it inherits the ambient
     *  trace like every other kind — the FCM row is the anchor of a push trace, so it must be in it. */
    private fun emitFcm(fcm: WireFcmMessage, config: LogPoseConfig) {
        emit(
            Envelope(
                kind = Envelope.KIND_FCM,
                id = fcm.id,
                at = fcm.receivedAtMillis,
                endedAt = fcm.receivedAtMillis,
                payload = json.encodeToJsonElement(fcm),
            ),
            config,
        )
    }

    private fun emit(envelope: Envelope, config: LogPoseConfig) {
        // Auto-attach the ambient trace (see withTrace) when the event didn't set one itself, so a
        // whole flow shares a trace without every call site passing it.
        val traced = if (envelope.traceId == null) envelope.copy(traceId = currentTrace.get()) else envelope
        // A logging call must never take down the app: a serialization or logcat failure is
        // swallowed rather than propagated into the caller's control flow.
        runCatching { LogcatEmitter(config).emit(traced) }
    }
}
