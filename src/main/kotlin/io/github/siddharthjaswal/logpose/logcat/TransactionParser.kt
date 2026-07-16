package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.Chunk
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json

/**
 * Turns raw logcat message payloads (one JSON object per line) into [LogEvent]s,
 * transparently reassembling multi-chunk payloads.
 *
 * A line is one of:
 *  - a full HTTP [Transaction] JSON object,
 *  - an [FcmMessage] JSON object (discriminated by `"kind":"fcm"`), or
 *  - a [Chunk] envelope (has "seq"/"total"/"payload" fields) that must be joined with its
 *    siblings before parsing — the joined payload is then dispatched like any full line.
 */
class TransactionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // id -> received chunks, keyed by seq
    private val pending = HashMap<String, MutableMap<Int, Chunk>>()

    /** Returns a [LogEvent] once a full payload is available, otherwise null. */
    fun accept(line: String): LogEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return null

        // Cheap discriminator: chunk envelopes carry a "seq" field.
        if (trimmed.contains("\"seq\"") && trimmed.contains("\"total\"")) {
            val chunk = runCatching { json.decodeFromString<Chunk>(trimmed) }.getOrNull()
                ?: return null
            return acceptChunk(chunk)
        }

        return decodeEvent(trimmed)
    }

    /** Decode a full (already reassembled) JSON payload into the right [LogEvent] variant. */
    private fun decodeEvent(payload: String): LogEvent? {
        // FCM events carry a "kind":"fcm" discriminator; everything else is an HTTP transaction.
        if (payload.contains("\"kind\"") && payload.contains("\"fcm\"")) {
            return runCatching { json.decodeFromString<FcmMessage>(payload) }.getOrNull()
                ?.let { LogEvent.Fcm(it) }
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
        return decodeEvent(payload)
    }

    fun reset() = pending.clear()
}
