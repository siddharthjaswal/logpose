package io.github.siddharthjaswal.logpose

/**
 * No-op replacement for the real `FcmMessageInfo` / `LogPose` FCM API, shipped in the
 * `logpose-no-op` artifact for release builds.
 *
 * It mirrors the real API's public surface exactly, so the same call site inside a
 * `FirebaseMessagingService` compiles across variants and does nothing in release: no
 * capture, no logcat output, no Firebase or Android dependency (this stays a pure-JVM jar).
 *
 * ```kotlin
 * debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:<tag>")
 * releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:<tag>")
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
