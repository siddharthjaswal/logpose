package io.github.siddharthjaswal.logpose

/**
 * A single analytics event, dependency-free like [FcmMessageInfo] / [DbQueryInfo] so the app maps
 * its own analytics facade (Firebase, Amplitude, Segment, …) across and the no-op stays pure-JVM.
 *
 * Fed to [LogPose.logAnalytics] from wherever you already fan events out — most apps have one
 * `Analytics.log(name, params)` chokepoint, and one line there puts every event on the timeline
 * next to the API call and screen that triggered it.
 *
 * @param name     the event name, e.g. `purchase_complete`.
 * @param params   event parameters. PII values are masked per `LogPoseConfig.redactAnalyticsParams`.
 * @param screen   the screen the event fired on, if known — shown as the subtitle and (later) the
 *                 node key for the flow map.
 * @param provider which sink it went to (`firebase`, `amplitude`, …), if you want to tell them apart.
 * @param traceId  correlate this event with the API/DB activity around it (see [LogPose.newTraceId]).
 */
data class AnalyticsEventInfo(
    val name: String,
    val params: Map<String, String> = emptyMap(),
    val screen: String? = null,
    val provider: String? = null,
    val traceId: String? = null,
)
