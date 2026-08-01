package io.github.siddharthjaswal.logpose

import okhttp3.Call
import okhttp3.Request

/**
 * An OkHttp request tag carrying a LogPose trace id.
 *
 * The ambient trace ([LogPose.withTrace]) is thread-local, but `LogPoseInterceptor` emits the
 * HTTP row on OkHttp's own dispatcher thread — where that thread-local is never set for an async
 * call. So the id has to ride on the **request object** instead, attached where the trace is in
 * scope (call-creation time) and read back by the interceptor on its own thread. That's the only
 * carrier that survives the hop, and it's what lands an async `getOrder()` row in the same
 * `get_trace` group as the push or screen that triggered it.
 *
 * Attach it with [logPoseTrace] per call, or [traceCalls] once around a whole client.
 */
class LogPoseTrace(val traceId: String)

/**
 * Tag this request builder with [traceId] (the ambient trace by default) so the row it produces
 * joins that trace. A null id leaves the request untouched.
 * ```kotlin
 * val request = Request.Builder().url(url).logPoseTrace().build()
 * ```
 */
fun Request.Builder.logPoseTrace(traceId: String? = LogPose.currentTraceId()): Request.Builder =
    if (traceId == null) this else tag(LogPoseTrace::class.java, LogPoseTrace(traceId))

/**
 * Wrap a [Call.Factory] (an `OkHttpClient` is one) so every request it creates inherits the
 * ambient trace **in scope at call-creation time** — which, for a Retrofit `suspend` call, is the
 * coroutine frame that made it, before the network hop. Requests that already carry a
 * [LogPoseTrace] are left as-is, so an explicit [logPoseTrace] always wins.
 *
 * ```kotlin
 * Retrofit.Builder().callFactory(LogPose.traceCalls(okHttpClient)) …
 * ```
 * For the ambient trace to be present here, the call must be created while a trace is live on the
 * thread (see [LogPose.withTrace]); if the call is created after a dispatcher hop, carry the trace
 * across it (e.g. a `ThreadContextElement`) so it's set when `newCall` runs.
 */
fun LogPose.traceCalls(delegate: Call.Factory): Call.Factory = object : Call.Factory {
    override fun newCall(request: Request): Call {
        val traceId = currentTraceId()
        val tagged = if (traceId == null || request.tag(LogPoseTrace::class.java) != null) request
        else request.newBuilder().tag(LogPoseTrace::class.java, LogPoseTrace(traceId)).build()
        return delegate.newCall(tagged)
    }
}
