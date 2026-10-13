package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.logcat.Adb

/**
 * What the timeline area shows when it has no rows (PRD §5.4). Three of these are the distinct
 * failure states a QA tester must be able to tell apart — no adb, no device, and a device whose app
 * has no LogPose in it — and none of them may read like another.
 */
sealed interface Placeholder {
    data object NoAdb : Placeholder
    data object NoDevice : Placeholder
    /** Devices are attached but none can be captured yet (unauthorized / offline). */
    data object DeviceNotReady : Placeholder
    data class NotStarted(val deviceName: String) : Placeholder
    data class Listening(val deviceName: String) : Placeholder
    data class NoLogPose(val deviceName: String) : Placeholder
    data class Ended(val reason: String) : Placeholder
    /** Rows exist: show them. */
    data object None : Placeholder

    companion object {
        fun resolve(
            adbReady: Boolean,
            devices: List<Adb.DeviceInfo>,
            selectedName: String?,
            session: SessionState,
            verdict: QuietDetector.Verdict?,
            eventCount: Int,
        ): Placeholder = when {
            eventCount > 0 -> None
            !adbReady -> NoAdb
            session is SessionState.Running ->
                if (verdict == QuietDetector.Verdict.NO_LOGPOSE) NoLogPose(selectedName ?: session.serial)
                else Listening(selectedName ?: session.serial)
            session is SessionState.Ended -> Ended(session.reason)
            devices.isEmpty() -> NoDevice
            devices.none { it.ready } -> DeviceNotReady
            else -> NotStarted(selectedName ?: devices.first { it.ready }.serial)
        }
    }
}
