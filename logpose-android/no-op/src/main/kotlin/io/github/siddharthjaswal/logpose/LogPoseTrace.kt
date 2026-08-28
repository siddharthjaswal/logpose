package io.github.siddharthjaswal.logpose

import okhttp3.Call
import okhttp3.Request

/** No-op mirror of the real `LogPoseTrace` so release-build call sites compile unchanged. */
class LogPoseTrace(val traceId: String)

/** No-op: returns the builder untouched. */
fun Request.Builder.logPoseTrace(traceId: String? = null): Request.Builder = this

/** No-op: returns the delegate factory untouched. */
fun LogPose.traceCalls(delegate: Call.Factory): Call.Factory = delegate
