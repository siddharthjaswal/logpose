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
    /**
     * Retain emitted events in a bounded in-memory ring so the whole capture buffer can be dumped
     * to a file for headless/CI verification (see the EXPORT broadcast in `mock/`), with no IDE or
     * MCP session in the loop. **Off by default** — turn it on in the staging/debug build a CI gate
     * reads, so ordinary runs pay no retention cost.
     */
    val exportEnabled: Boolean = false,
    /** Max events kept for [exportEnabled]; the oldest fall off. */
    val exportBufferSize: Int = 2000,
    val redactHeaders: Set<String> = DEFAULT_REDACT_HEADERS,
    val redactHeaderPatterns: Set<String> = DEFAULT_REDACT_PATTERNS,
    /**
     * Exact query-parameter names whose **values** are replaced with "██" in the emitted URL
     * (case-insensitive; the name stays visible). The query-string twin of [redactHeaders]:
     * `?api_key=…` in logcat is the same leak as an `API-Key` header. Extend rather than replace:
     * `redactQueryParams = LogPoseConfig.DEFAULT_REDACT_QUERY_PARAMS + "tenant_key"`.
     * Emission-only — mock rules still match on the real values.
     */
    val redactQueryParams: Set<String> = DEFAULT_REDACT_QUERY_PARAMS,
    /**
     * Substrings that redact any query parameter whose *name* contains one — the twin of
     * [redactHeaderPatterns], for the `?shop_token=`/`?gcs_signature=` shapes an exact list
     * can't enumerate.
     */
    val redactQueryParamPatterns: Set<String> = DEFAULT_REDACT_QUERY_PATTERNS,
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
     * Analytics param **keys** whose values are masked before emission — case-insensitive
     * substrings, same idea as [redactHeaderPatterns]. **Empty by default**: analytics is a
     * debug/staging tool and its params are usually test data, so nothing is masked unless you ask.
     * If a build does carry real PII in params, opt in with the ready-made set:
     * `redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS` (or `+ "user_id"`).
     */
    val redactAnalyticsParams: Set<String> = emptySet(),
    /**
     * Truncate the FCM registration token emitted by `LogPose.logFcmToken` to its first 12
     * characters plus "…". The token's display use is "token refreshed", which the prefix serves;
     * a full token pasted out of a capture can address pushes at the device. Set false when you
     * genuinely need to copy the whole token out of the IDE.
     */
    val redactFcmToken: Boolean = true,
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
         * Credential-bearing query-parameter names in wide use. Same philosophy as
         * [DEFAULT_REDACT_HEADERS]: a capture gets pasted into tickets and handed to coding
         * agents, so a value wrongly redacted costs a re-run while a value wrongly emitted costs
         * a rotated secret.
         */
        val DEFAULT_REDACT_QUERY_PARAMS: Set<String> = setOf(
            "api_key",
            "apikey",
            "api-key",
            "key",
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "auth",
            "auth_token",
            "authorization",
            "secret",
            "client_secret",
            "password",
            "passwd",
            "signature",
            "sig",
        )

        /**
         * Name substrings that redact whatever query parameter carries them — the shapes
         * credential params actually take (`?shop_token=`, `?x-goog-signature=`). Deliberately
         * broad, per the same tradeoff as [DEFAULT_REDACT_PATTERNS]; note "key" also masks
         * benign names like `sort_key` — accepted over-redaction, since `X-Signing-Key`-style
         * params are exactly the ones an exact list misses.
         */
        val DEFAULT_REDACT_QUERY_PATTERNS: Set<String> = setOf(
            "token",
            "secret",
            "password",
            "passwd",
            "credential",
            "auth",
            "key",
            "signature",
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
