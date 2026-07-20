package io.github.siddharthjaswal.logpose

/**
 * No-op replacement for the real `LogPose` entry point, shipped in the `logpose-no-op`
 * artifact for release builds.
 *
 * It mirrors the real API's public surface exactly, so every call site — a push handler inside
 * a `FirebaseMessagingService`, or a `LogPose.event { }` in a DAO — compiles across variants
 * and does nothing in release: no capture, no logcat output, no Firebase or Android
 * dependency (this stays a pure-JVM jar).
 *
 * ```kotlin
 * debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:<tag>")
 * releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:<tag>")
 * ```
 */
@Suppress("UNUSED_PARAMETER")
object LogPose {

    fun event(
        title: String,
        config: LogPoseConfig = LogPoseConfig(),
        build: EventBuilder.() -> Unit = {},
    ) = Unit

    fun log(
        kind: String,
        payloadJson: String,
        id: String = "",
        at: Long = 0L,
        endedAt: Long? = null,
        traceId: String? = null,
        parentId: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) = Unit

    fun newTraceId(): String = ""

    fun logDbQuery(info: DbQueryInfo, config: LogPoseConfig = LogPoseConfig()) = Unit

    fun logWorker(info: WorkerEventInfo, config: LogPoseConfig = LogPoseConfig()) = Unit

    fun logConfigSnapshot(
        values: Map<String, String>,
        source: String? = null,
        fetchStatus: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) = Unit

    fun logConfigChange(
        key: String,
        value: String,
        previous: String? = null,
        source: String? = null,
        config: LogPoseConfig = LogPoseConfig(),
    ) = Unit

    fun logFcmMessage(info: FcmMessageInfo, config: LogPoseConfig = LogPoseConfig()) = Unit

    fun logFcmToken(token: String, config: LogPoseConfig = LogPoseConfig()) = Unit
}
