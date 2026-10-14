package io.github.siddharthjaswal.logpose.model

/**
 * A single row in the LogPose timeline.
 *
 * Events arrive wrapped in an [Envelope]; this is the decoded form the UI works with. Some
 * kinds get first-class treatment because the plugin can do more with them than render —
 * [Http] (cURL, duplicate detection, mocking), [Fcm] (push detail), and the app-runtime trio
 * [Db] / [Worker] / [Config] (parsed SQL, worker spans, flag diffs, and MCP tools built on
 * them). Everything else lands as [Generic] and renders from the presentation the device
 * supplied, which is what lets an app put its own subsystems on the timeline without a plugin
 * release.
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

    /** True while the event's span is still open (an in-flight request — or an abandoned one). */
    val isOpen: Boolean get() = envelope.endedAt == null

    /**
     * Why this open span can never close, or null while it still can.
     *
     * Set by the store — never by the wire — when the app run that emitted the event is known to be
     * over: a newer run of the same package announced itself ([REASON_APP_RESTARTED]) or the
     * capture lost the device ([REASON_DEVICE_DISCONNECTED]). A request from a dead process has no
     * response coming, and reporting it as "in flight" for the rest of the session is a lie every
     * aggregate repeats. A later update that does close the span replaces the event, so the mark
     * goes with it.
     */
    val abandoned: String?

    /** Open and still able to finish — what "in flight" means everywhere LogPose says it. */
    val isInFlight: Boolean get() = isOpen && abandoned == null

    /** Span length in millis, or null while open or for a point-in-time event. */
    val durationMillis: Long?
        get() = envelope.endedAt?.minus(envelope.at)?.takeIf { it > 0 }

    data class Http(
        val tx: Transaction,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    data class Fcm(
        val msg: FcmMessage,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    data class Db(
        val query: DbQuery,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    data class Worker(
        val work: WorkerEvent,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    data class Config(
        val update: ConfigUpdate,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    /**
     * An event of a kind this plugin has no special support for. [event] is present when the
     * payload was self-describing (the built-in `"event"` kind); when it isn't, the row falls
     * back to the raw payload so nothing is ever silently dropped.
     */
    data class Generic(
        val event: GenericEvent?,
        override val envelope: Envelope,
        override val abandoned: String? = null,
    ) : LogEvent

    companion object {
        const val REASON_APP_RESTARTED = "app restarted"
        const val REASON_DEVICE_DISCONNECTED = "device disconnected"
    }
}

/** This event with [LogEvent.abandoned] set to [reason] (null clears it); everything else kept. */
fun LogEvent.withAbandoned(reason: String?): LogEvent = when (this) {
    is LogEvent.Http -> copy(abandoned = reason)
    is LogEvent.Fcm -> copy(abandoned = reason)
    is LogEvent.Db -> copy(abandoned = reason)
    is LogEvent.Worker -> copy(abandoned = reason)
    is LogEvent.Config -> copy(abandoned = reason)
    is LogEvent.Generic -> copy(abandoned = reason)
}
