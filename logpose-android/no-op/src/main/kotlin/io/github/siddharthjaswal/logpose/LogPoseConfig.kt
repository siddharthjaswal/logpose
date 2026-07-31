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
 * @property redactHeaderPatterns unused.
 * @property mocksEnabled unused — the no-op never serves mocks regardless.
 */
data class LogPoseConfig(
    val tag: String = "LogPose",
    val enabled: Boolean = true,
    val maxBodyBytes: Long = 250_000L,
    val maxLineChars: Int = 3500,
    val emitPending: Boolean = true,
    val redactHeaders: Set<String> = DEFAULT_REDACT_HEADERS,
    val redactHeaderPatterns: Set<String> = DEFAULT_REDACT_PATTERNS,
    val bodyDecoders: List<BodyDecoder> = emptyList(),
    val mocksEnabled: Boolean = true,
    val dbEnabled: Boolean = true,
    val workersEnabled: Boolean = true,
    val analyticsEnabled: Boolean = true,
    val redactAnalyticsParams: Set<String> = DEFAULT_REDACT_PARAMS,
) {
    /**
     * Mirrors the real config's constants so that
     * `redactHeaders = LogPoseConfig.DEFAULT_REDACT_HEADERS + "X-Tenant-Key"` compiles in release
     * builds too. Nothing here is ever consulted — the no-op captures nothing to redact.
     */
    companion object {
        val DEFAULT_REDACT_HEADERS: Set<String> = setOf(
            "Authorization",
            "Proxy-Authorization",
            "Authentication",
            "Cookie",
            "Cookie2",
            "Set-Cookie",
            "Set-Cookie2",
            "API-Key",
            "X-API-Key",
            "X-API-Token",
            "X-Auth-Token",
            "X-Auth",
            "X-Access-Token",
            "X-Refresh-Token",
            "X-CSRF-Token",
            "X-XSRF-Token",
            "X-Amz-Security-Token",
            "X-Goog-Api-Key",
            "Private-Token",
            "Token",
            "Access-Token",
            "Refresh-Token",
            "Id-Token",
        )

        val DEFAULT_REDACT_PATTERNS: Set<String> = setOf(
            "token",
            "secret",
            "password",
            "passwd",
            "credential",
            "apikey",
            "api-key",
            "api_key",
            "auth",
        )

        val DEFAULT_REDACT_PARAMS: Set<String> = setOf(
            "email",
            "phone",
            "password",
            "passwd",
            "token",
            "secret",
            "ssn",
            "card_number",
            "cvv",
        )
    }
}
