package io.github.siddharthjaswal.logpose.model

/**
 * A single row in the LogPose timeline.
 *
 * Events arrive wrapped in an [Envelope]; this is the decoded form the UI works with. Two
 * kinds get first-class treatment because the plugin can do more with them than render —
 * [Http] (cURL, duplicate detection, mocking) and [Fcm] (push-specific detail). Everything
 * else lands as [Generic] and renders from the presentation the device supplied, which is
 * what lets an app put its own subsystems on the timeline without a plugin release.
 *
 * Downstream code should prefer switching on [envelope]`.kind` or handling [Generic] as the
 * fallback rather than assuming the set is closed — that assumption is exactly what this type
 * stopped making.
 */
sealed interface LogEvent {

    /** The transport envelope this event arrived in. */
    val envelope: Envelope

    /** Correlation id, unique per event; used for in-place update and dedup in the store. */
    val id: String get() = envelope.id

    /** Device epoch millis the event started, for display and ordering (0 if unknown). */
    val timestampMillis: Long get() = envelope.at

    /** Wire kind — `"http"`, `"fcm"`, or anything an app defined. */
    val kind: String get() = envelope.kind

    /** Groups this event with others in the same flow, when the device set one. */
    val traceId: String? get() = envelope.traceId

    /** True while the event's span is still open (an in-flight request). */
    val isOpen: Boolean get() = envelope.endedAt == null

    /** Span length in millis, or null while open or for a point-in-time event. */
    val durationMillis: Long?
        get() = envelope.endedAt?.minus(envelope.at)?.takeIf { it > 0 }

    data class Http(val tx: Transaction, override val envelope: Envelope) : LogEvent

    data class Fcm(val msg: FcmMessage, override val envelope: Envelope) : LogEvent

    /**
     * An event of a kind this plugin has no special support for. [event] is present when the
     * payload was self-describing (the built-in `"event"` kind); when it isn't, the row falls
     * back to the raw payload so nothing is ever silently dropped.
     */
    data class Generic(val event: GenericEvent?, override val envelope: Envelope) : LogEvent
}
