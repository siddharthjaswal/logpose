package io.github.siddharthjaswal.logpose

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
