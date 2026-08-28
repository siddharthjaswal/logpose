package io.github.siddharthjaswal.logpose.wire

/**
 * No-op twin of the real transport [Envelope], present only so a custom
 * [io.github.siddharthjaswal.logpose.emit.EventEmitter] — and therefore the two-argument
 * `LogPoseInterceptor(config, emitter)` constructor — compiles against `logpose-no-op` too.
 *
 * Nothing here is ever constructed: the no-op interceptor captures nothing, so it emits nothing.
 *
 * **The one place the mirror can't be exact:** the real `payload` is a kotlinx-serialization
 * `JsonElement`, and the no-op is a pure-JVM jar with no dependencies at all — so it carries the
 * payload as the raw JSON string instead. A release call site that only *forwards* an envelope
 * (the point of a custom sink) is unaffected; one that picks the payload apart needs to guard
 * itself with `BuildConfig.DEBUG`.
 */
data class Envelope(
    val v: Int = 1,
    val kind: String,
    val id: String,
    val at: Long,
    val endedAt: Long? = null,
    val traceId: String? = null,
    val parentId: String? = null,
    val payload: String,
) {
    /** Mirrors the real kind constants, so a `when (event.kind)` in a custom sink compiles. */
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
