package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent

/**
 * Everything the filter bar *says* about its own state, as plain data.
 *
 * The redesign moves method, status, noise, dupes and correlation out of the permanent row and
 * behind one `Filters` button — which only works if the bar can state, in words, what it is
 * currently hiding: a badge count, one removable chip per active filter, an explainer for the
 * mode switches that used to be invisible ("a status filter shows HTTP only"), and — when the
 * filter has emptied the list — a sentence naming the *mechanism* that did it plus the one
 * loosening that would bring the most rows back.
 *
 * All of that is derivation, not rendering, so it lives here: pure functions over [FilterState]
 * and a few counts, with no Swing and no IntelliJ types. The bar and the panel decide where the
 * strings go; this decides what they say. It is also the only reason those strings are testable —
 * the alternative is asserting on a live tool window.
 */
object FilterPresentation {

    /**
     * A filter identified by what it narrows, not by which widget draws it.
     *
     * [DB_OPT_IN] is the odd one: no chip is on, and that *is* the narrowing — db events match
     * only once the DB chip is explicitly selected. It has no echo chip and no badge count (there
     * is no choice to echo), but the empty state has to be able to name it, or a db-only capture
     * gets blamed on whichever unrelated filter happens to be on — or, with none on at all, on
     * "several filters" when there are none.
     */
    enum class FilterId { STATUS, METHOD, CORRELATION, SEARCH, TYPES, HIDE_NOISE, DUPES, DB_OPT_IN }

    /**
     * The filters that live behind the `Filters` button — everything except the TYPE chips and the
     * search box, which are visible in the permanent row and so never need announcing.
     *
     * The order is the priority order: it decides which mechanism the empty state blames when two
     * filters would each bring rows back, and which explainer wins the one line the echo row has.
     */
    private val HIDDEN = listOf(
        FilterId.STATUS, FilterId.METHOD, FilterId.CORRELATION, FilterId.HIDE_NOISE, FilterId.DUPES,
    )

    /** A chip on the echo row: one active filter, removable as a whole. */
    data class EchoChip(val id: FilterId, val label: String, val tooltip: String)

    /**
     * The mode-switch line: plain text, then one link that switches the mode.
     *
     * These exist because two of LogPose's narrowing rules are otherwise invisible — a method or
     * status choice silently drops every non-HTTP kind, and db is hidden until asked for. Both used
     * to read as "LogPose lost my events".
     */
    data class Explainer(val text: String, val link: String, val action: Action) {
        enum class Action { SHOW_ALL_KINDS, SHOW_DB }
    }

    /** One filter that could be loosened, and how many rows dropping it alone would show. */
    data class Relaxation(val id: FilterId, val label: String, val wouldShow: Int)

    /** The "filtered to nothing" copy: the counts line, the mechanism sentence, and one loosener. */
    data class EmptyState(
        val total: Int,
        val headline: String,
        val explanation: String,
        val loosener: Relaxation?,
    )

    // ---- badge --------------------------------------------------------------------------------

    /**
     * How many hidden filters are active — the number on the `Filters` badge.
     *
     * A group counts once however many of it is selected: three status classes are one status
     * filter, because "3" next to a button that opens four controls would be counting the wrong
     * thing. TYPE chips and the search box are visible on the permanent row, so neither counts.
     */
    fun badgeCount(state: FilterState, correlationActive: Boolean): Int =
        HIDDEN.count { active(it, state, correlationActive) }

    private fun active(id: FilterId, state: FilterState, correlationActive: Boolean): Boolean = when (id) {
        FilterId.STATUS -> state.statusClasses.isNotEmpty()
        FilterId.METHOD -> state.methods.isNotEmpty()
        FilterId.CORRELATION -> correlationActive
        FilterId.HIDE_NOISE -> state.hideNoise
        FilterId.DUPES -> state.duplicatesOnly
        FilterId.SEARCH -> state.urlQuery.isNotBlank()
        FilterId.TYPES -> state.types.isNotEmpty()
        // Inverted on purpose: the db opt-in narrows while the chip is *off*.
        FilterId.DB_OPT_IN -> EventType.DB !in state.types
    }

    // ---- echo row -----------------------------------------------------------------------------

    /**
     * One chip per active hidden filter, in priority order.
     *
     * The search box is deliberately absent: it is visible, editable and self-describing three
     * inches to the left, and echoing it would be the bar repeating itself. Everything else here is
     * a choice made inside a popover that is now closed.
     */
    fun echoChips(
        state: FilterState,
        correlationLabel: String?,
        narrow: Boolean = false,
    ): List<EchoChip> = HIDDEN.filter { active(it, state, correlationLabel != null) }.map { id ->
        EchoChip(id, chipLabel(id, state, correlationLabel, narrow), chipTooltip(id, state, correlationLabel))
    }

    private fun chipLabel(
        id: FilterId,
        state: FilterState,
        correlationLabel: String?,
        narrow: Boolean,
    ): String = when (id) {
        // Narrow drops the noun, never the value: `4xx–5xx` still says what it filters, `status`
        // alone would not.
        FilterId.STATUS -> statusLabel(state.statusClasses).let { if (narrow) it else "status $it" }
        FilterId.METHOD -> methodLabel(state.methods).let { if (narrow) it else "method $it" }
        FilterId.CORRELATION -> ellipsize(correlationLabel.orEmpty(), if (narrow) 18 else 34)
        FilterId.HIDE_NOISE -> "hide noise"
        FilterId.DUPES -> "dupes"
        FilterId.SEARCH, FilterId.TYPES, FilterId.DB_OPT_IN -> ""
    }

    private fun chipTooltip(id: FilterId, state: FilterState, correlationLabel: String?): String {
        val what = when (id) {
            FilterId.STATUS -> "Status ${statusLabel(state.statusClasses)}"
            FilterId.METHOD -> "Method ${methodLabel(state.methods)}"
            FilterId.CORRELATION -> "Filtered by ${correlationLabel.orEmpty()}"
            FilterId.HIDE_NOISE -> "Hide noise"
            FilterId.DUPES -> "Duplicates only"
            FilterId.SEARCH, FilterId.TYPES, FilterId.DB_OPT_IN -> ""
        }
        return "$what — click to remove"
    }

    /**
     * The one line that explains a mode switch, or null when neither applies.
     *
     * Method/status wins over db because it *causes* the db case: an HTTP-only narrowing already
     * excludes every db event, so offering "show db" under it would be a link that changes nothing.
     */
    fun explainer(state: FilterState, wouldMatchHiddenDb: Boolean, narrow: Boolean = false): Explainer? = when {
        state.statusClasses.isNotEmpty() || state.methods.isNotEmpty() -> {
            val noun = if (state.statusClasses.isNotEmpty()) "status" else "method"
            Explainer(
                text = if (narrow) "HTTP only ·" else "$noun filter → showing HTTP only ·",
                link = if (narrow) "all kinds" else "show all kinds",
                action = Explainer.Action.SHOW_ALL_KINDS,
            )
        }
        wouldMatchHiddenDb -> Explainer("db hidden by default ·", "show", Explainer.Action.SHOW_DB)
        else -> null
    }

    /**
     * Whether the current search would have matched db events that the DB opt-in is hiding.
     *
     * This is the derived state behind the second explainer, and the reason it is a function of a
     * *list* rather than of a live store: it is computed once per filter change, never during a
     * paint. The probe simply asks the same [FilterState] again with DB allowed — one code path
     * decides what matches, so the explainer can't promise rows the chip wouldn't show.
     */
    fun wouldMatchHiddenDb(state: FilterState, events: List<LogEvent>): Boolean {
        if (EventType.DB in state.types || state.urlQuery.isBlank()) return false
        val probe = state.copy(types = state.types + EventType.DB)
        return events.any { it is LogEvent.Db && probe.matches(it) }
    }

    // ---- narrow panel -------------------------------------------------------------------------

    /** The width at or below which the bar goes icon-only. */
    const val NARROW_ENTER = 440

    /** The width it has to reclaim to come back — the gap is what stops a drag from strobing. */
    const val NARROW_EXIT = 470

    /**
     * Whether the bar should be in its narrow layout at [width] px, given what it is now.
     *
     * Hysteresis rather than one threshold: the compact layout is *narrower* than the wide one, so
     * a single boundary makes a panel dragged to exactly 440px flip between two layouts that each
     * re-satisfy the other's condition.
     *
     * [width] is device px and the thresholds are logical, so the caller passes the same scaling
     * function the layout uses — `JBUI::scale` in production, identity in a test.
     */
    fun isNarrow(width: Int, currentlyNarrow: Boolean, scale: (Int) -> Int = { it }): Boolean =
        if (currentlyNarrow) width < scale(NARROW_EXIT) else width <= scale(NARROW_ENTER)

    // ---- filtered to nothing ------------------------------------------------------------------

    /** The counts line: `218 events captured · 0 match the current filter`. */
    fun headline(total: Int): String = "$total events captured · 0 match the current filter"

    /**
     * What to say when the capture is full and the list is empty.
     *
     * [relaxations] is the honest part: the caller re-runs the filter with each active narrowing
     * dropped in turn and reports what each would show, so the button offers the loosening that
     * actually brings rows back rather than the one that sounds likeliest. The sentence then names
     * that same mechanism — button and explanation always agree, because they're the same choice.
     *
     * Ties go to the first candidate in [HIDDEN] order, and a relaxation that would still show
     * nothing is not offered at all: two filters can be narrowing at once, and a button promising
     * rows that don't exist is worse than no button.
     */
    fun emptyState(
        total: Int,
        kinds: Map<String, Int>,
        state: FilterState,
        correlationLabel: String? = null,
        wouldMatchHiddenDb: Boolean = false,
        relaxations: List<Relaxation> = emptyList(),
    ): EmptyState {
        val loosener = relaxations.filter { it.wouldShow > 0 }.maxByOrNull { it.wouldShow }
        val mechanism = when {
            loosener != null -> loosener.id
            // Only one filter is on, and dropping it still shows nothing — it's still the one to
            // name. Several on and none of them enough: blaming one of them would be a guess.
            relaxations.size == 1 -> relaxations.first().id
            relaxations.isEmpty() -> dominant(state, correlationLabel != null)
            else -> null
        }
        return EmptyState(
            total = total,
            headline = headline(total),
            explanation = explain(mechanism, state, correlationLabel, kinds, wouldMatchHiddenDb),
            loosener = loosener,
        )
    }

    /** The label of the ghost button that drops one filter. */
    fun looseningLabel(id: FilterId, correlationLabel: String? = null): String = when (id) {
        FilterId.STATUS -> "Loosen status"
        FilterId.METHOD -> "Loosen method"
        FilterId.CORRELATION -> "Clear ${correlationLabel?.substringBefore(' ') ?: "correlation"}"
        FilterId.SEARCH -> "Clear search"
        FilterId.TYPES -> "Show all kinds"
        FilterId.HIDE_NOISE -> "Show noise"
        FilterId.DUPES -> "Show all requests"
        FilterId.DB_OPT_IN -> "Show db"
    }

    /** The first active filter in priority order — who to blame when no single loosening helps. */
    private fun dominant(state: FilterState, correlationActive: Boolean): FilterId? =
        (HIDDEN + listOf(FilterId.SEARCH, FilterId.TYPES))
            .firstOrNull { active(it, state, correlationActive) }

    private fun explain(
        mechanism: FilterId?,
        state: FilterState,
        correlationLabel: String?,
        kinds: Map<String, Int>,
        hiddenDb: Boolean,
    ): String = when (mechanism) {
        // The HTTP-only pair: which sentence is true depends on whether the capture has any HTTP at
        // all, and those are different problems — "you filtered away every kind you captured" vs
        // "no call came back like that".
        FilterId.STATUS, FilterId.METHOD -> {
            val noun = if (mechanism == FilterId.STATUS) "status" else "method"
            if ((kinds[Envelope.KIND_HTTP] ?: 0) == 0) {
                "The $noun filter limits results to HTTP; your capture is ${mostly(kinds)}."
            } else if (mechanism == FilterId.STATUS) {
                "No HTTP call in this capture came back ${statusLabel(state.statusClasses)}."
            } else {
                "No HTTP call in this capture used ${methodLabel(state.methods)}."
            }
        }
        FilterId.CORRELATION ->
            "Nothing in this capture carries ${correlationLabel ?: "that value"}."
        FilterId.SEARCH ->
            if (hiddenDb) "Only db events match “${state.urlQuery}”, and db stays hidden until you ask for it."
            else "Nothing in this capture matches “${state.urlQuery}”."
        FilterId.TYPES ->
            "The type filter shows only ${typeList(state.types)}; your capture is ${mostly(kinds)}."
        FilterId.HIDE_NOISE ->
            "Hide noise is muting every request that would otherwise match."
        FilterId.DUPES ->
            "Nothing in this capture repeats, so “dupes only” leaves nothing."
        FilterId.DB_OPT_IN ->
            "Db stays hidden until you ask for it, and your capture is ${mostly(kinds)}."
        null ->
            "Several filters are narrowing at once and no event satisfies all of them."
    }

    /** `mostly analytics and db` — the two kinds the capture actually consists of. */
    private fun mostly(kinds: Map<String, Int>): String {
        val top = kinds.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(2).map { kindWord(it.key) }
        return when (top.size) {
            0 -> "empty"
            1 -> "all ${top[0]}"
            else -> "mostly ${top[0]} and ${top[1]}"
        }
    }

    private fun kindWord(kind: String): String = when (kind) {
        Envelope.KIND_HTTP -> "HTTP"
        Envelope.KIND_FCM -> "FCM"
        Envelope.KIND_DB -> "db"
        Envelope.KIND_WORKER -> "worker"
        Envelope.KIND_CONFIG -> "config"
        Envelope.KIND_ANALYTICS -> "analytics"
        Envelope.KIND_EVENT -> "app events"
        else -> kind
    }

    // ---- shared vocabulary --------------------------------------------------------------------

    /** `NET`, `FCM`, … — the TYPE chip's own label, so the copy and the chip say the same word. */
    fun typeLabel(type: EventType): String = when (type) {
        EventType.NET -> "NET"
        EventType.FCM -> "FCM"
        EventType.DB -> "DB"
        EventType.WORK -> "WORK"
        EventType.CONF -> "CONF"
        EventType.ANALYTICS -> "ANLY"
        EventType.APP -> "APP"
    }

    private fun typeList(types: Set<EventType>): String =
        EventType.entries.filter { it in types }.joinToString(", ") { typeLabel(it) }

    /**
     * Status classes as a range where they are one: `4xx–5xx`, not `4xx, 5xx`.
     *
     * Contiguous runs collapse because that's how the choice was made — "everything that failed" —
     * and a chip narrow enough to survive a 400px panel has to say it in one token.
     */
    fun statusLabel(classes: Set<Int>): String {
        if (classes.isEmpty()) return ""
        val sorted = classes.sorted()
        val runs = ArrayList<Pair<Int, Int>>()
        var start = sorted.first()
        var prev = start
        for (c in sorted.drop(1)) {
            if (c == prev + 1) { prev = c; continue }
            runs.add(start to prev); start = c; prev = c
        }
        runs.add(start to prev)
        return runs.joinToString(", ") { (a, b) -> if (a == b) "${a}xx" else "${a}xx–${b}xx" }
    }

    private val METHOD_ORDER = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    /** Selected verbs in request-lifecycle order, so `GET/POST` never renders as `POST/GET`. */
    fun methodLabel(methods: Set<String>): String =
        methods.sortedWith(compareBy({ METHOD_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: 99 }, { it }))
            .joinToString("/")

    /** Trims [text] to [max] characters, keeping the ellipsis inside the budget. */
    fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}
