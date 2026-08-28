package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.model.PushAck
import io.github.siddharthjaswal.logpose.model.PushInject
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The IDE end of push injection: ships a synthetic push to the device over the same broadcast
 * channel [MocksController] uses for rules (`cmd=push`), then waits for the device to say what
 * happened to it.
 *
 * A sibling of [MocksController] rather than more of it: rules are a *persistent, revisioned*
 * set whose truth is "does the device hold what we hold"; a push is a one-shot command whose
 * truth is "did anything consume it". Sharing [BroadcastCommand] and [AdbCommand] keeps the
 * transport identical without conflating the two state machines.
 *
 * Every outcome is a report, never a throw:
 *  - the device library predates injection → one notification naming the version needed;
 *  - the broadcast never left the machine → the adb failure text;
 *  - nothing acknowledged within [ACK_TIMEOUT_MILLIS] → "the device never answered";
 *  - `delivered = none` → the register-a-handler guidance (PRD A1).
 *
 * Threading: [injectPush] may be called from the EDT and does all adb work on a short-lived
 * daemon thread; [onAck] arrives on the logcat reader thread; deadlines fire on a shared
 * scheduler. Callbacks run on whichever of those completed the injection — a UI caller must
 * marshal.
 */
class PushController(
    private val packageName: () -> String?,
    private val deviceSerial: () -> String?,
    private val libVersion: () -> String?,
) {

    /** How an injected push ended up, as reported by the device. */
    data class Outcome(val id: String, val delivered: String, val error: String?) {
        val reachedApp: Boolean get() = delivered != PushAck.DELIVERED_NONE
    }

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Sink for user-visible problems — an IDE notification the panel owns (never the detail pane). */
    @Volatile var onProblem: (String, String) -> Unit = { _, _ -> }

    private class Pending(val onResult: (Outcome?) -> Unit) {
        @Volatile var deadline: ScheduledFuture<*>? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** True when the device has announced a library new enough to deliver an injected push. */
    fun deviceSupportsPush(): Boolean =
        DeviceCapability.supports(libVersion(), DeviceFeature.PUSH_INJECTION)

    /**
     * The app to push to when injection is possible right now — otherwise null, having already
     * reported *why* through [onProblem]. Entry points call this before opening a dialog, so an
     * unsupported device is answered before the composing rather than after it.
     */
    fun readyToInject(): String? {
        val target = packageName()
        if (target.isNullOrBlank()) {
            onProblem(
                "LogPose: no app to push to yet",
                "No device has announced itself to this capture. Start capture, then launch the " +
                    "app (it announces itself on its first LogPose event) and try again.",
            )
            return null
        }
        if (!deviceSupportsPush()) {
            val reported = libVersion()?.takeIf { it.isNotBlank() }
            onProblem(
                "LogPose: device library ≥ ${DeviceFeature.PUSH_INJECTION.since} required",
                "Push injection needs logpose-android ≥ ${DeviceFeature.PUSH_INJECTION.since} on " +
                    "the device" + (reported?.let { " (it reports $it)" } ?: "") +
                    ". Nothing was sent — an older library has no receiver for the command, so " +
                    "the push would have been silently dropped.",
            )
            return null
        }
        return target
    }

    /**
     * Sends [inject] to the device and calls [onResult] with the device's answer — or with null
     * when the push could not be sent at all, or went unanswered. Every null path has already
     * been reported through [onProblem]; [onResult] is for the caller's own UI (a toast), not
     * for error surfacing.
     */
    fun injectPush(inject: PushInject, onResult: (Outcome?) -> Unit = {}) {
        val target = readyToInject()
        if (target == null) {
            onResult(null)
            return
        }
        val adb = Adb.resolve()
        if (adb == null) {
            onProblem(
                "LogPose: push not sent",
                "adb not found (set ANDROID_HOME or put adb on PATH).",
            )
            onResult(null)
            return
        }

        val slices = BroadcastCommand.slices(json.encodeToString(PushInject.serializer(), inject))
        val serial = deviceSerial()
        val entry = Pending(onResult)
        pending[inject.id] = entry

        Thread({
            var failure: String? = null
            for ((seq, slice) in slices.withIndex()) {
                val cmd = Adb.baseCmd(adb, serial) + BroadcastCommand.args(
                    target = target,
                    cmd = BroadcastCommand.CMD_PUSH,
                    // A push carries no revision: the device keys the stream on the extra's
                    // default and tells consecutive pushes apart by their second `seq 0`.
                    revision = null,
                    seq = seq,
                    total = slices.size,
                    payload = slice,
                )
                failure = AdbCommand.run(cmd)
                if (failure != null) break
            }
            if (failure != null) {
                pending.remove(inject.id)
                onProblem("LogPose: push not sent", "$failure.\n\nNothing was delivered to the app.")
                entry.onResult(null)
                return@Thread
            }
            entry.deadline = scheduler.schedule(
                { onDeadline(inject.id) }, ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS,
            )
        }, "logpose-mock-push").apply { isDaemon = true }.start()
    }

    /**
     * Consumes a `push_ack` from the reverse channel. Acks for ids this controller didn't send
     * (a script, another IDE window) are ignored rather than reported — they aren't ours to
     * judge.
     */
    fun onAck(ack: PushAck) {
        val entry = pending.remove(ack.id) ?: return
        entry.deadline?.cancel(false)
        if (ack.delivered == PushAck.DELIVERED_NONE) {
            onProblem(
                "LogPose: push reached no handler",
                "The app received the injection but nothing consumed it" +
                    (ack.error?.let { " ($it)" } ?: "") + ".\n\n" +
                    "Register a handler in your app's init:\n" +
                    "    LogPose.onPushInject { info -> MyPushRouter.handle(info.data) }\n\n" +
                    "or keep a FirebaseMessagingService declared in the manifest — LogPose calls " +
                    "its onMessageReceived as a fallback. The push still shows on the timeline " +
                    "with an INJ pill, because it really was injected.",
            )
        }
        entry.onResult(Outcome(ack.id, ack.delivered, ack.error))
    }

    /** Nothing came back in time — say so rather than leaving the developer guessing. */
    private fun onDeadline(id: String) {
        val entry = pending.remove(id) ?: return
        onProblem(
            "LogPose: push not acknowledged",
            "The device didn't report what happened to the injected push within " +
                "${ACK_TIMEOUT_MILLIS / 1000}s. The ack rides back on logcat, so check that " +
                "capture is still running and the app is alive; the push may still have been " +
                "delivered.",
        )
        entry.onResult(null)
    }

    /** Capture is going away: stop waiting on acks that can no longer arrive. */
    fun reset() {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach { it.deadline?.cancel(false) }
    }

    private companion object {
        /** How long the device gets to report a delivery outcome (PRD A5). */
        const val ACK_TIMEOUT_MILLIS = 10_000L

        val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "logpose-push-ack").apply { isDaemon = true }
        }
    }
}
