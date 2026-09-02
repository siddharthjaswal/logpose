package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.Chunk
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.PushAck
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Control messages the device emits alongside timeline events — the reverse-channel
 * handshake, mock acknowledgements and push-injection outcomes. Unlike [LogEvent]s these don't
 * become rows; the panel routes them to the mocks controller.
 */
sealed interface ControlMessage {
    data class DeviceHello(val hello: Hello) : ControlMessage
    data class MockApplied(val ack: MockAck) : ControlMessage
    /** The device reporting how an injected push was (or wasn't) delivered. Library 1.7.0+. */
    data class PushDelivered(val ack: PushAck) : ControlMessage
}

/**
 * Turns raw logcat message payloads (one JSON object per line) into [LogEvent]s,
 * transparently reassembling multi-chunk payloads.
 *
 * A line is one of:
 *  - an [Envelope] wrapping a timeline event of any [Envelope.kind],
 *  - a control message — `"kind":"hello"` / `"kind":"mock_ack"` / `"kind":"push_ack"` —
 *    dispatched to [onControl] rather than returned (it isn't a timeline row),
 *  - a [Chunk] envelope that must be joined with its siblings before parsing, or
 *  - a **legacy** bare [Transaction] / [FcmMessage], emitted by `logpose-android` before
 *    1.3.0. Those are wrapped into an [Envelope] here so everything downstream sees one shape.
 *
 * An envelope whose `kind` has no first-class support becomes [LogEvent.Generic] rather than
 * being dropped — that is what lets an app log its own kinds without a plugin release.
 */
class TransactionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Sink for reverse-channel control messages (hello / mock ack / push ack). Set by the panel. */
    var onControl: (ControlMessage) -> Unit = {}

    /**
     * True once a line has been seen that only a pre-1.3.0 library emits. The panel uses this
     * to prompt for a library upgrade rather than silently losing the new capabilities.
     */
    var sawLegacyPayload: Boolean = false
        private set

    // id -> received chunks, keyed by seq
    private val pending = HashMap<String, MutableMap<Int, Chunk>>()

    /** Returns a [LogEvent] once a full timeline payload is available, otherwise null. */
    fun accept(line: String): LogEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return null

        // Cheap discriminator: chunk envelopes carry a "seq" field.
        if (trimmed.contains("\"seq\"") && trimmed.contains("\"total\"")) {
            val chunk = runCatching { json.decodeFromString<Chunk>(trimmed) }.getOrNull()
                ?: return null
            return acceptChunk(chunk)
        }

        return decode(trimmed)
    }

    /**
     * Decode a full (already reassembled) JSON payload. Timeline events are returned; control
     * messages are dispatched to [onControl] and return null.
     */
    private fun decode(payload: String): LogEvent? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return null

        // Control messages are not enveloped — they're a separate IDE ↔ device protocol.
        when (obj.string("kind")) {
            "hello" -> {
                runCatching { json.decodeFromString<Hello>(payload) }.getOrNull()
                    ?.let { onControl(ControlMessage.DeviceHello(it)) }
                return null
            }
            "mock_ack" -> {
                runCatching { json.decodeFromString<MockAck>(payload) }.getOrNull()
                    ?.let { onControl(ControlMessage.MockApplied(it)) }
                return null
            }
            "push_ack" -> {
                runCatching { json.decodeFromString<PushAck>(payload) }.getOrNull()
                    ?.let { onControl(ControlMessage.PushDelivered(it)) }
                return null
            }
        }

        // An envelope is the only shape carrying a "payload" object alongside "kind".
        if (obj.containsKey("payload") && obj.containsKey("kind")) {
            val envelope = runCatching { json.decodeFromString<Envelope>(payload) }.getOrNull()
            if (envelope != null) return fromEnvelope(envelope)
        }

        return legacy(obj, payload)
    }

    /** Decode an envelope's payload into the richest [LogEvent] its kind supports. */
    private fun fromEnvelope(envelope: Envelope): LogEvent = when (envelope.kind) {
        Envelope.KIND_HTTP ->
            envelope.decode(Transaction.serializer())?.let { LogEvent.Http(it, envelope) }
                ?: LogEvent.Generic(null, envelope)
        Envelope.KIND_FCM ->
            envelope.decode(FcmMessage.serializer())?.let { LogEvent.Fcm(it, envelope) }
                ?: LogEvent.Generic(null, envelope)
        Envelope.KIND_DB ->
            envelope.decode(DbQuery.serializer())?.let { LogEvent.Db(it, envelope) }
                ?: LogEvent.Generic(null, envelope)
        Envelope.KIND_WORKER ->
            envelope.decode(WorkerEvent.serializer())?.let { LogEvent.Worker(it, envelope) }
                ?: LogEvent.Generic(null, envelope)
        Envelope.KIND_CONFIG ->
            envelope.decode(ConfigUpdate.serializer())?.let { LogEvent.Config(it, envelope) }
                ?: LogEvent.Generic(null, envelope)
        // Unknown kinds still get a row: self-describing payloads render fully, anything else
        // falls back to the raw payload rather than being dropped.
        else -> LogEvent.Generic(envelope.decode(GenericEvent.serializer()), envelope)
    }

    private fun <T> Envelope.decode(serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { json.decodeFromJsonElement(serializer, payload) }.getOrNull()

    /**
     * Pre-1.3.0 lines: a bare [FcmMessage] (discriminated by `kind`) or a bare [Transaction].
     * Both are wrapped so the rest of the plugin only ever deals with envelopes.
     */
    private fun legacy(obj: JsonObject, payload: String): LogEvent? {
        if (obj.string("kind") == "fcm") {
            val msg = runCatching { json.decodeFromString<FcmMessage>(payload) }.getOrNull()
                ?: return null
            sawLegacyPayload = true
            return LogEvent.Fcm(
                msg,
                Envelope(
                    kind = Envelope.KIND_FCM,
                    id = msg.id,
                    at = msg.receivedAtMillis,
                    endedAt = msg.receivedAtMillis,
                    payload = json.parseToJsonElement(payload),
                ),
            )
        }

        val tx = runCatching { json.decodeFromString<Transaction>(payload) }.getOrNull()
            ?: return null
        sawLegacyPayload = true
        return LogEvent.Http(
            tx,
            Envelope(
                kind = Envelope.KIND_HTTP,
                id = tx.id,
                at = tx.startedAtMillis,
                endedAt = tx.durationMillis?.let { tx.startedAtMillis + it },
                payload = json.parseToJsonElement(payload),
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun acceptChunk(chunk: Chunk): LogEvent? {
        // Ignore malformed envelopes; an out-of-range seq would otherwise inflate the
        // part count and permanently wedge (and leak) this id's pending entry.
        if (chunk.total <= 0 || chunk.seq !in 0 until chunk.total) return null

        val parts = pending.getOrPut(chunk.id) { HashMap() }
        parts[chunk.seq] = chunk
        if (parts.size < chunk.total) return null

        val payload = buildString {
            for (i in 0 until chunk.total) append(parts[i]?.payload ?: "")
        }
        pending.remove(chunk.id)
        return decode(payload)
    }

    fun reset() = pending.clear()
}
