package io.github.siddharthjaswal.logpose.desktop.session

import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One immutable view of the store for the UI: rows in arrival order, plus a version to key on. */
data class TimelineSnapshot(val version: Long, val events: List<LogEvent>) {
    companion object {
        val EMPTY = TimelineSnapshot(0, emptyList())
    }
}

/**
 * How the UI observes the store: **coalesced, never per event.**
 *
 * The store's listener only flips a dirty flag (it runs on the reader thread, inside the store's
 * monitor, so it must do nothing else). A coroutine wakes every [periodMillis], and only when the
 * flag was set copies one snapshot out and publishes it. A burst of 500 events in 200 ms is one
 * recomposition; an idle capture is zero.
 */
class TimelineFeed(private val store: EventStore, private val periodMillis: Long = DEFAULT_PERIOD_MS) {

    private val dirty = AtomicBoolean(true)
    private var version = 0L
    private val _snapshots = MutableStateFlow(TimelineSnapshot.EMPTY)
    val snapshots: StateFlow<TimelineSnapshot> = _snapshots.asStateFlow()

    init {
        store.addListener { dirty.set(true) }
    }

    /** Publishes now if anything changed since the last publish. Returns whether it did. */
    fun publishIfDirty(): Boolean {
        if (!dirty.getAndSet(false)) return false
        _snapshots.value = TimelineSnapshot(++version, store.snapshot())
        return true
    }

    fun launchIn(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            publishIfDirty()
            delay(periodMillis)
        }
    }

    companion object {
        const val DEFAULT_PERIOD_MS = 200L
    }
}
