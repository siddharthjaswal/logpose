package io.github.siddharthjaswal.logpose.emit

import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.wire.FcmMessage
import io.github.siddharthjaswal.logpose.wire.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Sink for timeline events. Swap in a custom one (e.g. a socket) if desired.
 *
 * Every timeline event — HTTP, FCM, or an app-defined kind — arrives here as an [Envelope],
 * so a transport never needs to know which kinds exist.
 */
fun interface EventEmitter {
    fun emit(event: Envelope)
}

private val wireJson = Json { encodeDefaults = true; explicitNulls = false }

/**
 * Wrap and emit an HTTP exchange. A transaction with no duration yet is still in flight, so
 * its span stays open (`endedAt = null`) and the IDE renders it as pending.
 */
fun EventEmitter.emit(tx: Transaction) = emit(
    Envelope(
        kind = Envelope.KIND_HTTP,
        id = tx.id,
        at = tx.startedAtMillis,
        endedAt = tx.durationMillis?.let { tx.startedAtMillis + it },
        payload = wireJson.encodeToJsonElement(tx),
    )
)

/** Wrap and emit an FCM event — a point in time, so the span opens and closes at once. */
fun EventEmitter.emit(fcm: FcmMessage) = emit(
    Envelope(
        kind = Envelope.KIND_FCM,
        id = fcm.id,
        at = fcm.receivedAtMillis,
        endedAt = fcm.receivedAtMillis,
        payload = wireJson.encodeToJsonElement(fcm),
    )
)
