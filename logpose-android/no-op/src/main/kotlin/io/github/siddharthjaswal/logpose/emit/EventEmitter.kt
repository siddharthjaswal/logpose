package io.github.siddharthjaswal.logpose.emit

import io.github.siddharthjaswal.logpose.wire.Envelope

/**
 * No-op twin of the real [EventEmitter], so a call site that hands `LogPoseInterceptor` its own
 * sink — `LogPoseInterceptor(config, MySocketEmitter())` — compiles unchanged in release builds.
 *
 * The no-op interceptor never calls it: it captures nothing, so there is nothing to emit.
 */
fun interface EventEmitter {
    fun emit(event: Envelope)
}
