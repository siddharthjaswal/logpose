package io.github.siddharthjaswal.logpose.mock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.siddharthjaswal.logpose.export.ExportBuffer
import java.io.File

/**
 * Device end of the headless EXPORT channel — the read-back twin of [MockCommandReceiver]'s
 * push. A CI orchestrator dumps the retained capture (see
 * [io.github.siddharthjaswal.logpose.LogPoseConfig.exportEnabled]) to a file it can `adb pull`
 * and assert on, so wire-level verdicts ("exactly one PUT order/accept", "EXPIRY_DEFERRED fired,
 * REJECTED_WITH_TIME did not") no longer need an IDE/MCP session:
 *
 * ```
 * adb shell am broadcast \
 *   -n <pkg>/io.github.siddharthjaswal.logpose.mock.LogPoseExportReceiver \
 *   --es cmd dump [--es out capture.ndjson]
 * adb pull /sdcard/Android/data/<pkg>/files/logpose/capture.ndjson
 * ```
 *
 * `cmd clear` empties the buffer (for "start clean, run, read only my events"). The file is
 * NDJSON — one [io.github.siddharthjaswal.logpose.wire.Envelope] per line — written into the
 * app's external-files dir, which adb can read without any extra permission. No chunking is
 * needed: it's a file, not broadcast extras.
 *
 * Security: exported so adb can reach it, but gated on `android.permission.DUMP`
 * (signature|privileged|development) which the adb shell holds and third-party apps cannot
 * obtain. Ships only in the real artifact; the no-op jar has no manifest and no receiver.
 */
internal class LogPoseExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_CMD) ?: CMD_DUMP) {
            CMD_CLEAR -> {
                ExportBuffer.clear()
                Log.i(TAG, "$MARKER cleared")
            }

            else -> {
                val name = intent.getStringExtra(EXTRA_OUT)?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE
                val lines = ExportBuffer.snapshot()
                runCatching {
                    val dir = File(context.getExternalFilesDir(null), "logpose").apply { mkdirs() }
                    val file = File(dir, name)
                    // Trailing newline only when non-empty, so an empty buffer yields a 0-byte file.
                    file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
                    // A single, greppable confirmation line the orchestrator waits on before it pulls.
                    Log.i(TAG, "$MARKER wrote ${lines.size} events to ${file.absolutePath}")
                }.onFailure { Log.w(TAG, "$MARKER failed: $it") }
            }
        }
    }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_OUT = "out"
        const val CMD_DUMP = "dump"
        const val CMD_CLEAR = "clear"
        const val DEFAULT_FILE = "capture.ndjson"
        const val TAG = "LogPose"
        // Stable prefix the export script greps for (independent of the configurable capture tag).
        const val MARKER = "LogPose export:"
    }
}
