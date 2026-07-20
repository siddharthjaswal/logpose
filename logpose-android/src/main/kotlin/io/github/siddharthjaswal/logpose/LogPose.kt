package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.emit.emit
import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.wire.FcmNotification
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

        LogcatEmitter(config).emit(
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
            )
        )
    }

    /** Record an FCM registration-token refresh. Call from `onNewToken`. */
    fun logFcmToken(token: String, config: LogPoseConfig = LogPoseConfig()) {
        if (!config.enabled) return
        LogcatEmitter(config).emit(
            WireFcmMessage(
                id = newId(),
                event = "token",
                receivedAtMillis = System.currentTimeMillis(),
                token = token,
            )
        )
    }

    // ---- internals --------------------------------------------------------------------

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun emit(envelope: Envelope, config: LogPoseConfig) {
        // A logging call must never take down the app: a serialization or logcat failure is
        // swallowed rather than propagated into the caller's control flow.
        runCatching { LogcatEmitter(config).emit(envelope) }
    }
}
