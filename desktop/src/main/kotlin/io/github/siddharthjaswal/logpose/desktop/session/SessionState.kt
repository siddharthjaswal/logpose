package io.github.siddharthjaswal.logpose.desktop.session

/**
 * The session lifecycle as a pure reducer, so the rules are tested rather than implied by
 * callbacks:
 *
 *  - Start is only honoured from [Idle] or [Ended] — a second click on a running session is a no-op.
 *  - A stream that ends on its own (cable pulled, app reinstalled, adb restarted) is [Ended] with a
 *    reason; one ended by Stop is [Idle]. The difference is what the toolbar tells the tester.
 *  - Events for a serial other than the running one are stale (a reader we already stopped
 *    reporting late) and ignored.
 */
sealed interface SessionState {
    data object Idle : SessionState
    data class Running(val serial: String, val startedAtMillis: Long) : SessionState
    data class Ended(val serial: String, val reason: String) : SessionState
}

sealed interface SessionEvent {
    data class Start(val serial: String, val atMillis: Long) : SessionEvent
    data object Stop : SessionEvent
    data class StreamEnded(val serial: String) : SessionEvent
    data class Failed(val serial: String, val message: String) : SessionEvent
}

object SessionReducer {

    const val DISCONNECTED = "The connection to the phone ended. Reconnect it and start a new session."

    fun reduce(state: SessionState, event: SessionEvent): SessionState = when (event) {
        is SessionEvent.Start -> when (state) {
            is SessionState.Running -> state
            else -> SessionState.Running(event.serial, event.atMillis)
        }
        SessionEvent.Stop -> when (state) {
            is SessionState.Running -> SessionState.Idle
            else -> state
        }
        is SessionEvent.StreamEnded ->
            if (state is SessionState.Running && state.serial == event.serial) SessionState.Ended(event.serial, DISCONNECTED)
            else state
        is SessionEvent.Failed ->
            if (state is SessionState.Running && state.serial == event.serial) SessionState.Ended(event.serial, event.message)
            else state
    }
}
