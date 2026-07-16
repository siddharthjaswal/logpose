package io.github.siddharthjaswal.logpose.model

/**
 * A single row in the LogPose timeline. LogPose captures two structurally different kinds
 * of event on one logcat tag — HTTP exchanges ([Http]) and Firebase Cloud Messaging pushes
 * / token refreshes ([Fcm]) — and the tool window renders them in one unified, time-ordered
 * stream. Everything downstream of the parser (store, list, renderer, detail, filter)
 * switches on this type; the HTTP-specific analysis (duplicates, cURL, mute) only ever
 * touches the [Http] branch.
 */
sealed interface LogEvent {
    /** Correlation id, unique per event; used for in-place update and dedup in the store. */
    val id: String

    /** Device epoch millis the event carries, for display (0 if unknown). */
    val timestampMillis: Long

    data class Http(val tx: Transaction) : LogEvent {
        override val id: String get() = tx.id
        override val timestampMillis: Long get() = tx.startedAtMillis
    }

    data class Fcm(val msg: FcmMessage) : LogEvent {
        override val id: String get() = msg.id
        override val timestampMillis: Long get() = msg.receivedAtMillis
    }
}
