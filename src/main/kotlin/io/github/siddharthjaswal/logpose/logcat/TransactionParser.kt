package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.Chunk
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json

/**
 * Turns raw logcat message payloads (one JSON object per line) into [Transaction]s,
 * transparently reassembling multi-chunk payloads.
 *
 * A line is either:
 *  - a full [Transaction] JSON object, or
 *  - a [Chunk] envelope (has "seq"/"total"/"payload" fields) that must be joined
 *    with its siblings before parsing.
 */
class TransactionParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // id -> received chunks, keyed by seq
    private val pending = HashMap<String, MutableMap<Int, Chunk>>()

    /** Returns a [Transaction] once a full payload is available, otherwise null. */
    fun accept(line: String): Transaction? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return null

        // Cheap discriminator: chunk envelopes carry a "seq" field.
        if (trimmed.contains("\"seq\"") && trimmed.contains("\"total\"")) {
            val chunk = runCatching { json.decodeFromString<Chunk>(trimmed) }.getOrNull()
                ?: return null
            return acceptChunk(chunk)
        }

        return runCatching { json.decodeFromString<Transaction>(trimmed) }.getOrNull()
    }

    private fun acceptChunk(chunk: Chunk): Transaction? {
        val parts = pending.getOrPut(chunk.id) { HashMap() }
        parts[chunk.seq] = chunk
        if (parts.size < chunk.total) return null

        val payload = buildString {
            for (i in 0 until chunk.total) {
                append(parts[i]?.payload ?: return null)
            }
        }
        pending.remove(chunk.id)
        return runCatching { json.decodeFromString<Transaction>(payload) }.getOrNull()
    }

    fun reset() = pending.clear()
}
