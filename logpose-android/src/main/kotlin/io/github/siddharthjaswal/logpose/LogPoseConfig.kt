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
 * @property redactHeaders exact header names whose values are replaced with "██" before
 *                       emission (case-insensitive). Extend rather than replace:
 *                       `redactHeaders = LogPoseConfig.DEFAULT_REDACT_HEADERS + "X-Tenant-Key"`.
 * @property redactHeaderPatterns substrings that redact any header whose *name* contains one.
 *                       Catches the vendor-specific names an exact list can't enumerate
 *                       (`X-Shopify-Access-Token`, `X-Goog-Api-Key`).
 */
data class LogPoseConfig(
    val tag: String = "LogPose",
    val enabled: Boolean = true,
    val maxBodyBytes: Long = 250_000L,
    val maxLineChars: Int = 3500,
    /** Emit a "pending" event when a request starts (lets the IDE show it live). */
    val emitPending: Boolean = true,
    val redactHeaders: Set<String> = DEFAULT_REDACT_HEADERS,
    val redactHeaderPatterns: Set<String> = DEFAULT_REDACT_PATTERNS,
    /**
     * Decoders that turn otherwise-unreadable bodies (encrypted, custom binary) into text for the
     * inspector. Consulted in order; the first non-null result wins, and an empty list keeps the
     * current raw-body behaviour. See [BodyDecoder].
     */
    val bodyDecoders: List<BodyDecoder> = emptyList(),
    /**
     * Allow the LogPose IDE plugin to serve mock responses for matching requests
     * (see `mock/MockRegistry`). Rules only ever arrive via adb from the developer's
     * machine; set to false to make this build ignore them entirely.
     */
    val mocksEnabled: Boolean = true,
    /**
     * Emit database events (see `LogPose.logDbQuery`). A Room query callback on a busy screen
     * can produce hundreds of events a minute, so this is the switch to turn that off without
     * unpicking the integration.
     */
    val dbEnabled: Boolean = true,
    /** Emit background-work events (see `LogPose.logWorker`). */
    val workersEnabled: Boolean = true,
    /** Emit analytics events (see `LogPose.logAnalytics`). Analytics can be chatty
     *  (screen views, impressions), so this is the per-kind off switch. */
    val analyticsEnabled: Boolean = true,
    /**
     * Analytics param **keys** whose values are masked before emission — the same idea as
     * [redactHeaderPatterns], matched as case-insensitive substrings. Analytics params routinely
     * carry PII (emails, phones, user ids), and a capture is pasted into tickets and read by
     * agents. Kept conservative by default so labels like `screen_name` survive; extend it:
     * `redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS + "user_id"`.
     */
    val redactAnalyticsParams: Set<String> = DEFAULT_REDACT_PARAMS,
) {
    companion object {
        /**
         * Credential-bearing headers in wide use. Deliberately broad: a capture gets pasted into
         * tickets and handed to coding agents, so a header wrongly redacted costs a re-run while
         * a header wrongly emitted costs a rotated secret.
         */
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

        /**
         * Name substrings that redact whatever header carries them. Every vendor invents its own
         * credential header, so an exact list will always trail reality; these patterns cover the
         * shapes those names actually take.
         */
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

        /**
         * Analytics param-key substrings redacted by default. Conservative on purpose — clear PII
         * and secrets only — so common non-sensitive keys (`screen_name`, `product_name`) come
         * through. Add your own (e.g. `"user_id"`) when your schema needs it.
         */
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
