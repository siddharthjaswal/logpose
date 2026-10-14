package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * The staged progress list the sheet shows while pairing — "Waiting for your phone to scan…" →
 * "Phone found (via macOS Bonjour)" → "Pairing with 192.168.1.10" → "Connecting" → "Connected" —
 * each with how long it took (or has been running). Pure: built from the state, the trace and now.
 */
object PairingProgress {

    enum class Status { DONE, ACTIVE, PENDING, FAILED }

    data class Row(val stage: Stage, val label: String, val status: Status, val elapsedMillis: Long?)

    /** After this long without a new stage, the sheet runs the connection doctor and shows what it found. */
    const val STALL_MILLIS = 15_000L

    fun rows(state: PairingState?, trace: PairingTrace?, nowMillis: Long): List<Row> {
        if (state == null || trace == null) return emptyList()
        val stages = when {
            trace.serviceName != null -> Stage.entries
            Stage.PAIRING in trace.stageAt || state is PairingState.Pairing -> listOf(Stage.PAIRING, Stage.CONNECTING, Stage.CONNECTED)
            else -> listOf(Stage.CONNECTING, Stage.CONNECTED)
        }
        val failed = state is PairingState.TimedOut || state is PairingState.Failed
        val current = stages.lastOrNull { it in trace.stageAt }
        val end = trace.endedAt ?: nowMillis
        return stages.mapIndexed { i, s ->
            val at = trace.stageAt[s]
            val nextAt = stages.drop(i + 1).firstNotNullOfOrNull { trace.stageAt[it] }
            val status = when {
                at == null -> Status.PENDING
                s == Stage.CONNECTED -> Status.DONE
                s == current && failed -> Status.FAILED
                s == current -> Status.ACTIVE
                else -> Status.DONE
            }
            val elapsed = when {
                // Found and Connected are moments, not spans.
                at == null || s == Stage.CONNECTED || s == Stage.FOUND -> null
                nextAt != null -> nextAt - at
                else -> end - at
            }
            Row(s, label(s, status, trace), status, elapsed)
        }
    }

    fun label(stage: Stage, status: Status, t: PairingTrace): String = when (stage) {
        Stage.WAITING -> if (status == Status.ACTIVE) "Waiting for your phone to scan…" else "Waiting for your phone to scan"
        Stage.FOUND -> t.foundVia?.let { "Phone found (via ${it.label})" } ?: "Find the phone on the network"
        Stage.PAIRING -> t.pairAddress?.let { "Pairing with ${AdbOutput.splitHostPort(it)?.first ?: it}" } ?: "Pair"
        Stage.CONNECTING -> if (status != Status.DONE) "Connecting…" else when (t.connectVia) {
            Via.BONJOUR -> "Connect address found via macOS Bonjour"
            Via.ADB -> "Connect address found via adb's discovery"
            Via.ADB_AUTO -> "adb connected the phone on its own"
            Via.TYPED -> "Connected to the address you typed"
            null -> "Connecting"
        }
        Stage.CONNECTED -> "Connected"
    }

    /** True once the current stage has run [STALL_MILLIS] with nothing new, while the attempt is still going. */
    fun stalled(state: PairingState?, trace: PairingTrace?, nowMillis: Long): Boolean =
        state != null && !state.done && trace != null && nowMillis - trace.lastProgressAt >= STALL_MILLIS

    /** `12s`, `1m 05s`. */
    fun formatElapsed(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
    }
}
