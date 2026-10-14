package io.github.siddharthjaswal.logpose.desktop.session

/**
 * PRD §5.4: the most common QA failure is a build without LogPose in it. A capturing session that
 * has heard nothing on the LogPose tag for [thresholdMillis] says so, distinctly from "no device".
 *
 * "Heard" means any LogPose line — a timeline event, a chunk, or a reverse-channel hello — because
 * a hello alone already proves the library is in the build, even before the app does anything.
 */
object QuietDetector {

    const val DEFAULT_THRESHOLD_MS = 20_000L

    enum class Verdict { LISTENING, NO_LOGPOSE, RECEIVING }

    fun verdict(
        startedAtMillis: Long,
        nowMillis: Long,
        firstLogPoseAtMillis: Long?,
        thresholdMillis: Long = DEFAULT_THRESHOLD_MS,
    ): Verdict = when {
        firstLogPoseAtMillis != null -> Verdict.RECEIVING
        nowMillis - startedAtMillis >= thresholdMillis -> Verdict.NO_LOGPOSE
        else -> Verdict.LISTENING
    }

    /** Whether a raw line on the LogPose tag is LogPose's own output (logcat adds `--------- beginning of main` banners). */
    fun isLogPoseLine(line: String): Boolean =
        // The reader keeps logcat's `-v brief` prefix (for the pid); the payload is what counts.
        io.github.siddharthjaswal.logpose.logcat.LogcatLine.parse(line).message.trimStart().startsWith("{")
}
