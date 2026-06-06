package io.github.siddharthjaswal.logpose

/**
 * Tunables for [LogPoseInterceptor].
 *
 * @property tag         logcat tag the plugin filters on. Keep it as the default
 *                       unless you also change the plugin's tag.
 * @property enabled     master switch. Wire this to `BuildConfig.DEBUG` so LogPose
 *                       never runs in release builds.
 * @property maxBodyBytes max bytes of a textual body to capture; larger bodies are
 *                       truncated (and flagged `truncated = true`).
 * @property maxLineChars max characters per logcat line before the payload is split
 *                       into chunks (~3500 stays safely under logcat's limit).
 * @property redactHeaders header names whose values are replaced with "██" before
 *                       emission (case-insensitive).
 */
data class LogPoseConfig(
    val tag: String = "LogPose",
    val enabled: Boolean = true,
    val maxBodyBytes: Long = 250_000L,
    val maxLineChars: Int = 3500,
    val redactHeaders: Set<String> = setOf(
        "Authorization",
        "Cookie",
        "Set-Cookie",
        "Proxy-Authorization",
    ),
)
