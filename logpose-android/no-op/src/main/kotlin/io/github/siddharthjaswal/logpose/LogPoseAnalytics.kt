package io.github.siddharthjaswal.logpose

/** No-op twin of the real [AnalyticsEventInfo], for API parity in release builds. */
data class AnalyticsEventInfo(
    val name: String,
    val params: Map<String, String> = emptyMap(),
    val screen: String? = null,
    val provider: String? = null,
    val traceId: String? = null,
)
