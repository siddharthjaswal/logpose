package io.github.siddharthjaswal.logpose.emit

import android.util.Log
import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.wire.Chunk
import io.github.siddharthjaswal.logpose.wire.FcmMessage
import io.github.siddharthjaswal.logpose.wire.Transaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Default emitter: writes each transaction as a single logcat line under the
 * configured tag. Payloads longer than [LogPoseConfig.maxLineChars] are split into
 * ordered [Chunk]s that the plugin reassembles — this is what makes large bodies
 * (and S3/GCS multipart metadata) survive logcat's per-line truncation.
 */
class LogcatEmitter(private val config: LogPoseConfig) : TransactionEmitter {

    // explicitNulls=false keeps lines compact by omitting null fields; the plugin
    // side fills them back in via schema defaults.
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    override fun emit(tx: Transaction) = emitLine(tx.id, json.encodeToString(tx))

    /** Emit an FCM event (incoming push or token refresh) on the same tag; see `LogPoseFcm.kt`. */
    fun emit(fcm: FcmMessage) = emitLine(fcm.id, json.encodeToString(fcm))

    /**
     * Write [line] under the configured tag, splitting into ordered [Chunk]s (sharing [id])
     * when it exceeds [LogPoseConfig.maxLineChars] so large payloads survive logcat's
     * per-line truncation. Shared by every event type.
     */
    private fun emitLine(id: String, line: String) {
        if (line.length <= config.maxLineChars) {
            Log.println(Log.INFO, config.tag, line)
            return
        }

        // Reserve headroom for the chunk envelope's own JSON overhead.
        val chunkSize = (config.maxLineChars - 160).coerceAtLeast(256)
        val total = (line.length + chunkSize - 1) / chunkSize
        for (seq in 0 until total) {
            val from = seq * chunkSize
            val to = minOf(from + chunkSize, line.length)
            val chunk = Chunk(id = id, seq = seq, total = total, payload = line.substring(from, to))
            Log.println(Log.INFO, config.tag, json.encodeToString(chunk))
        }
    }
}
