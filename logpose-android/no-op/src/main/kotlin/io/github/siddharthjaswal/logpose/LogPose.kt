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

    fun currentTraceId(): String? = null

    fun <T> withTrace(traceId: String = "", block: () -> T): T = block()

    fun <T> continueTrace(block: () -> T): () -> T = block

    fun logAnalytics(info: AnalyticsEventInfo, config: LogPoseConfig = LogPoseConfig()) = Unit

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

    /**
     * No-op: the handler is dropped and never called. Push injection is a debug-only channel —
     * the receiver that would deliver one ships in the real artifact alone, so a release build
     * has nothing that could invoke this even if something tried.
     */
    fun onPushInject(handler: (FcmMessageInfo) -> Unit) = Unit
}
