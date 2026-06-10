package io.github.siddharthjaswal.logpose

/**
 * No-op twin of the real [LogPoseConfig], shipped in the `logpose-no-op` artifact.
 *
 * It is byte-for-byte API-compatible with the real config so that call sites compile
 * unchanged when you swap `debugImplementation(logpose)` for
 * `releaseImplementation(logpose-no-op)`. None of these values do anything here.
 *
 * @property tag          unused (kept for API parity).
 * @property enabled      unused — the no-op interceptor never captures regardless.
 * @property maxBodyBytes unused.
 * @property maxLineChars unused.
 * @property emitPending  unused.
 * @property redactHeaders unused.
 */
data class LogPoseConfig(
    val tag: String = "LogPose",
    val enabled: Boolean = true,
    val maxBodyBytes: Long = 250_000L,
    val maxLineChars: Int = 3500,
    val emitPending: Boolean = true,
    val redactHeaders: Set<String> = setOf(
        "Authorization",
        "Cookie",
        "Set-Cookie",
        "Proxy-Authorization",
    ),
)
