package io.github.siddharthjaswal.logpose

/**
 * No-op replacement for the real event-building API, shipped in the `logpose-no-op` artifact
 * for release builds.
 *
 * It mirrors the real [EventBuilder]'s public surface exactly, so the same
 * `LogPose.event("…") { … }` call site compiles across variants and does nothing in release.
 * The builder lambda still runs — it has to, since it's ordinary Kotlin — so keep expensive
 * work (serializing a large object, stringifying a payload) out of it, or guard it with
 * `BuildConfig.DEBUG`.
 */
@Suppress("UNUSED_PARAMETER")
class EventBuilder internal constructor() {

    var subtitle: String? = null
    var id: String = ""
    var traceId: String? = null
    var parentId: String? = null

    fun took(millis: Long) = Unit
    fun open() = Unit
    fun badge(text: String, tone: String = Tone.MUTED) = Unit
    fun text(label: String, body: String) = Unit
    fun code(label: String, body: String) = Unit
    fun json(label: String, body: String) = Unit
    fun kv(label: String, values: Map<String, String>) = Unit
}

/** Semantic badge tones; mirrors the real API so call sites compile unchanged. */
object Tone {
    const val INFO = "info"
    const val WARN = "warn"
    const val ERROR = "error"
    const val MUTED = "muted"
}
