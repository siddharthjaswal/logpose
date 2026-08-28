package io.github.siddharthjaswal.logpose.mock

import java.util.concurrent.ConcurrentHashMap

/**
 * Reassembles the base64 slices a broadcast command arrives in (`seq` / `total`), keeping a
 * **separate pending map per command** so two commands in flight can't corrupt each other.
 *
 * That separation is the whole point: a rule-set push and a push injection travel over the same
 * receiver, and an injected push landing between two slices of a rule set used to overwrite them
 * (one shared map, one shared key). Keyed by command, each stream assembles on its own.
 *
 * Pure Kotlin (no Android imports), so the reassembly logic — the part that actually goes wrong —
 * is unit-testable without a device. A manifest receiver is a fresh instance per broadcast, so
 * the state has to live here rather than on it.
 */
internal object ChunkAssembly {

    private val streams = ConcurrentHashMap<String, Stream>()

    /**
     * Adds one slice of [cmd]'s payload and returns the joined payload once every slice has
     * arrived, or null while it's still incomplete.
     *
     * [key] identifies the message being assembled — the rule-set revision for `rules`. A slice
     * carrying a new key supersedes whatever was half-assembled under the old one; so does a
     * *second* `seq 0` under the same key, which is how back-to-back messages are told apart for
     * a command that has no revision to count (a push). Slices may arrive in any order.
     */
    fun add(cmd: String, key: Int, seq: Int, total: Int, payload: String): String? =
        streams.getOrPut(cmd) { Stream() }.add(key, seq, total, payload)

    /** Test hook: forget every partially assembled command. */
    fun reset() = streams.clear()

    private class Stream {
        private val pending = HashMap<Int, String>()
        private var key = Int.MIN_VALUE

        @Synchronized
        fun add(key: Int, seq: Int, total: Int, payload: String): String? {
            if (key != this.key || (seq == 0 && pending.containsKey(0))) {
                pending.clear()
                this.key = key
            }
            pending[seq] = payload
            if (pending.size < total) return null
            val joined = buildString { for (i in 0 until total) append(pending[i] ?: return null) }
            pending.clear()
            this.key = Int.MIN_VALUE
            return joined
        }
    }
}
