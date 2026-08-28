package io.github.siddharthjaswal.logpose

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * No-op: an empty context, so the coroutine runs with no trace installed.
 *
 * It lives in its own file for a reason — Kotlin names the facade class after the file, so
 * `traceContext` has to be declared in `LogPoseTraceContext.kt` here exactly as it is in the real
 * library, or the two artifacts would expose the same function on differently named JVM classes.
 */
@Suppress("UNUSED_PARAMETER")
fun LogPose.traceContext(traceId: String = ""): CoroutineContext = EmptyCoroutineContext
