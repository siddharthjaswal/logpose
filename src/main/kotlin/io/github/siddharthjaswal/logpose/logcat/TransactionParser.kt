package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.Chunk
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json

/**
 * Control messages the device emits alongside timeline events — the reverse-channel
 * handshake and mock acknowledgements. Unlike [LogEvent]s these don't become rows; the panel
 * routes them to the mocks controller.
 */
sealed interface ControlMessage {
    data class DeviceHello(val hello: Hello) : ControlMessage
    data class MockApplied(val ack: MockAck) : ControlMessage
}

/**
 * Turns raw logcat message payloads (one JSON object per line) into [LogEvent]s,
 * transparently reassembling multi-chunk payloads.
 *
 * A line is one of:
 *  - a full HTTP [Transaction] JSON object,
 *  - an [FcmMessage] JSON object (`"kind":"fcm"`),
 *  - a control message — `"kind":"hello"` / `"kind":"mock_ack"` — dispatched to [onControl]
 *    rather than returned (it isn't a timeline row), or
 *  - a [Chunk] envelope that must be joined with its siblings before parsing.
 */
class TransactionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Sink for reverse-channel control messages (hello / mock ack). Set by the panel. */
    var onControl: (ControlMessage) -> Unit = {}

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
        if (payload.contains("\"kind\"")) {
            when {
                payload.contains("\"fcm\"") ->
                    return runCatching { json.decodeFromString<FcmMessage>(payload) }.getOrNull()
                        ?.let { LogEvent.Fcm(it) }
                payload.contains("\"hello\"") -> {
                    runCatching { json.decodeFromString<Hello>(payload) }.getOrNull()
                        ?.let { onControl(ControlMessage.DeviceHello(it)) }
                    return null
                }
                payload.contains("\"mock_ack\"") -> {
                    runCatching { json.decodeFromString<MockAck>(payload) }.getOrNull()
                        ?.let { onControl(ControlMessage.MockApplied(it)) }
                    return null
                }
            }
        }
        return runCatching { json.decodeFromString<Transaction>(payload) }.getOrNull()
            ?.let { LogEvent.Http(it) }
    }

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
