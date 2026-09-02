package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints

/**
 * Filterable event families in the unified stream.
 *
 * The kinds LogPose understands get their own chip; [APP] covers *every* app-defined kind
 * rather than getting one each, because that set is open (the point of the framework) and
 * rebuilding the segmented control whenever a new kind appears would make the bar jump around
 * mid-capture. Narrowing to one specific app kind is what the search box is for.
 */
enum class EventType { NET, FCM, DB, WORK, CONF, ANALYTICS, APP }

/** Structured filter state — replaces the free-text grammar with one-click toggles. */
data class FilterState(
    val urlQuery: String = "",
    val methods: Set<String> = emptySet(),
    val statusClasses: Set<Int> = emptySet(), // 2,3,4,5 -> 2xx..5xx
    val hideNoise: Boolean = false,
    /** Restrict to these event kinds; empty = show all. */
    val types: Set<EventType> = emptySet(),
    /**
     * "Duplicates only" — applied by the panel (not [matches]), since duplicate membership
     * is a property of the whole capture, not of a single event in isolation.
     */
    val duplicatesOnly: Boolean = false,
    /**
     * The cached searchable haystack for an event — bodies included — injected by the panel from
     * its [io.github.siddharthjaswal.logpose.analysis.CorrelationIndex], exactly the way the
     * correlation chip matches values that hide in bodies no row shows.
     *
     * Null (the pure default, and what every existing test constructs) keeps HTTP search
     * URL-only. With the hook set, an HTTP row whose URL misses is given a second chance against
     * status-code text and — for queries of [MIN_BODY_QUERY] chars or more — the cached body
     * text. Never a scan: the lookup must be the panel's cache, which is warmed off the EDT.
     */
    val textOf: ((LogEvent) -> String)? = null,
) {
    fun matches(event: LogEvent): Boolean = when (event) {
        is LogEvent.Http -> types.allowsHttp() && matchesHttp(event)
        is LogEvent.Fcm -> types.allowsFcm() && matchesFcm(event)
        is LogEvent.Db -> types.allowsDb() && matchesStructured(event)
        is LogEvent.Worker -> types.allows(EventType.WORK) && matchesStructured(event)
        is LogEvent.Config -> types.allows(EventType.CONF) && matchesStructured(event)
        is LogEvent.Generic ->
            // Analytics gets its own chip; every other app-defined kind falls under APP.
            if (event.kind == Envelope.KIND_ANALYTICS) types.allows(EventType.ANALYTICS) && matchesStructured(event)
            else types.allowsApp() && matchesStructured(event)
    }

    private fun Set<EventType>.allowsHttp() = isEmpty() || EventType.NET in this
    private fun Set<EventType>.allowsFcm() = isEmpty() || EventType.FCM in this
    private fun Set<EventType>.allowsApp() = allows(EventType.APP)
    private fun Set<EventType>.allows(type: EventType) = isEmpty() || type in this

    /**
     * DB is the one kind that must be asked for. A Room query callback outproduces every other
     * source by an order of magnitude — a real capture ran 75 queries against 12 requests — so
     * defaulting it visible buries the traffic people opened LogPose to see. Capture is
     * unaffected: the events are stored and stay available to the DB chip and to MCP.
     */
    private fun Set<EventType>.allowsDb() = EventType.DB in this

    private fun matchesHttp(event: LogEvent.Http): Boolean {
        val tx = event.tx
        if (urlQuery.isNotBlank() && !httpTextMatches(event)) return false
        if (methods.isNotEmpty() && tx.request.method.uppercase() !in methods) return false
        if (statusClasses.isNotEmpty()) {
            val cls = (tx.response?.code ?: 0) / 100
            if (cls !in statusClasses) return false
        }
        if (hideNoise && MutedEndpoints.isMuted(tx)) return false
        return true
    }

    /**
     * Whether the query hits an HTTP row anywhere it can. The URL stays the fast path — one
     * string, no lookup — and is what a hook-less state (tests, or a panel that hasn't wired the
     * cache) still searches. Status-code text is next, so `404` finds the failures. Bodies come
     * last and only for queries of [MIN_BODY_QUERY]+ chars, through the injected cache: a one- or
     * two-char query would match nearly every body and turn typing's first keystrokes into noise.
     */
    private fun httpTextMatches(event: LogEvent.Http): Boolean {
        val tx = event.tx
        if (tx.request.url.contains(urlQuery, ignoreCase = true)) return true
        val code = tx.response?.code
        if (code != null && code.toString().contains(urlQuery)) return true
        val lookup = textOf ?: return false
        if (urlQuery.length < MIN_BODY_QUERY) return false
        return lookup(event).contains(urlQuery, ignoreCase = true)
    }

    companion object {
        /** Shortest query that searches request/response bodies, not just URL and status. */
        const val MIN_BODY_QUERY = 3
    }

    private fun matchesFcm(event: LogEvent.Fcm): Boolean {
        // HTTP-only chips (method / status) narrow to HTTP, so an active selection hides FCM.
        if (methods.isNotEmpty() || statusClasses.isNotEmpty()) return false
        if (urlQuery.isNotBlank()) {
            val m = event.msg
            val haystack = listOfNotNull(
                m.notification?.title, m.notification?.body, m.from, m.messageId,
                m.collapseKey, m.token,
            ) + m.data.flatMap { listOf(it.key, it.value) }
            if (haystack.none { it.contains(urlQuery, ignoreCase = true) }) return false
        }
        return true
    }

    /**
     * Search over whatever the row actually shows, for every non-HTTP/FCM kind. Going through
     * [KindPresenter] means a query matches the SQL table, the worker name or the changed flag
     * keys — the same words on screen — rather than raw payload JSON.
     */
    private fun matchesStructured(event: LogEvent): Boolean {
        // HTTP-only chips (method / status) narrow to HTTP, so an active selection hides these.
        if (methods.isNotEmpty() || statusClasses.isNotEmpty()) return false
        if (urlQuery.isNotBlank()) {
            val presentation = KindPresenter.present(event)
            val haystack = listOfNotNull(
                presentation?.title, presentation?.subtitle, event.kind, event.traceId, event.id,
            ) + presentation?.badges?.map { it.text }.orEmpty() +
                presentation?.sections?.map { it.label }.orEmpty()
            if (haystack.none { it.contains(urlQuery, ignoreCase = true) }) return false
        }
        return true
    }
}
