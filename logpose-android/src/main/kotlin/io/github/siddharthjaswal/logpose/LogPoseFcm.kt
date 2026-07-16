package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.wire.FcmNotification
import java.util.UUID
import io.github.siddharthjaswal.logpose.wire.FcmMessage as WireFcmMessage

/**
 * A Firebase-free snapshot of an incoming FCM push, filled by the host app from a
 * `RemoteMessage` and handed to [LogPose.logFcmMessage].
 *
 * LogPose deliberately knows nothing about Firebase types — you copy the handful of fields
 * you care about across. This keeps the release [`no-op`][LogPoseInterceptor] artifact a pure
 * JVM jar (no Firebase, no Android AAR) while the exact same call site compiles in every
 * build variant.
 *
 * ```kotlin
 * class MyMessagingService : FirebaseMessagingService() {
 *     override fun onMessageReceived(m: RemoteMessage) {
 *         LogPose.logFcmMessage(
 *             FcmMessageInfo(
 *                 messageId = m.messageId,
 *                 from = m.from,
 *                 collapseKey = m.collapseKey,
 *                 messageType = m.messageType,
 *                 sentTimeMillis = m.sentTime,
 *                 ttlSeconds = m.ttl,
 *                 priority = m.priority,
 *                 notificationTitle = m.notification?.title,
 *                 notificationBody = m.notification?.body,
 *                 notificationChannelId = m.notification?.channelId,
 *                 notificationClickAction = m.notification?.clickAction,
 *                 notificationImageUrl = m.notification?.imageUrl?.toString(),
 *                 data = m.data,
 *             ),
 *             LogPoseConfig(enabled = BuildConfig.DEBUG),
 *         )
 *         super.onMessageReceived(m)
 *     }
 *
 *     override fun onNewToken(token: String) {
 *         LogPose.logFcmToken(token, LogPoseConfig(enabled = BuildConfig.DEBUG))
 *     }
 * }
 * ```
 */
data class FcmMessageInfo(
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
 * Entry point for feeding push-messaging events to the LogPose IDE plugin. These are emitted
 * on the same logcat tag as HTTP transactions and show up inline in the unified timeline.
 *
 * Both calls no-op when [LogPoseConfig.enabled] is false, so wiring them to `BuildConfig.DEBUG`
 * keeps them out of release traffic.
 */
object LogPose {

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

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
}
