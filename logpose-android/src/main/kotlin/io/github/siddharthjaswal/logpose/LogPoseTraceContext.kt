package io.github.siddharthjaswal.logpose

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine context element that keeps [traceId] as the ambient LogPose trace
 * ([LogPose.withTrace] / [LogPose.currentTraceId]) on whatever thread the coroutine runs on — set
 * on each resume, restored on each suspend. That's what makes the ambient trace survive a
 * `launch`/`withContext` hop, so every event the flow emits — an OkHttp call created through
 * [LogPose.traceCalls], plus any analytics/db/app events on the way — lands in one `get_trace`
 * group.
 *
 * ```kotlin
 * viewModelScope.launch(LogPose.traceContext()) { repository.getOrder(id) }
 * ```
 */
fun LogPose.traceContext(traceId: String = newTraceId()): CoroutineContext =
    LogPoseTraceElement(traceId)

internal class LogPoseTraceElement(private val traceId: String) : ThreadContextElement<String?> {
    companion object Key : CoroutineContext.Key<LogPoseTraceElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): String? = LogPose.installTrace(traceId)

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) =
        LogPose.restoreTrace(oldState)
}
