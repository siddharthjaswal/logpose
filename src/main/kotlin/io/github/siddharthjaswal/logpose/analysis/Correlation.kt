package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A business key the user has told LogPose to correlate on — `order_id`, `trip_id`.
 *
 * The key is a *name*, and the name is only ever used to find a **value** (see [Correlation]).
 * Matching is then done by the value alone, which is what lets a group span events that never
 * mention the key: a bare path segment in `/order/21053953/` has no key attached to it.
 *
 * [minLength] is the safety floor that stops a short, common value ("1", "OK") from grouping
 * half the capture. A value below it is still extracted — the user can see it — but it is not
 * matchable unless this key sets [allowShortValues], which is the per-key opt-in from the PRD.
 *
 * Key vocabulary is per-app, so LogPose ships **no** built-in keys; these come from the user
 * (helped by [Correlation.suggest]) and are stored per project.
 */
data class CorrelationKey(
    val name: String,
    val enabled: Boolean = true,
    val minLength: Int = Correlation.DEFAULT_MIN_LENGTH,
    /** Opt in to matching on values shorter than [minLength], accepting the over-grouping risk. */
    val allowShortValues: Boolean = false,
)

/**
 * One configured key found on one event.
 *
 * [matchable] is false for a value that failed its key's length rule: extracted, so the UI can
 * show it, but excluded from grouping so it can't over-group. [Correlation.valuesFor] hands back
 * only the matchable ones; this type is how a caller sees the rest.
 */
data class KeyValue(
    /** The key's configured name, spelled as the user configured it — not as the payload spells it. */
    val key: String,
    val value: String,
    val matchable: Boolean,
)

/**
 * An id-ish key [Correlation.suggest] found in the capture, offered for the user to tick.
 *
 * This is **inert data**: a suggestion is not a key, nothing here enables anything, and no
 * grouping happens until the user configures a [CorrelationKey]. That is deliberate — silent
 * auto-grouping is the failure mode this design avoids.
 *
 * [eventsGrouped] is the ranking metric the PRD names ("how many distinct events they'd group"):
 * the number of events reached by *any* of this key's values. [largestGroup] is the size of the
 * biggest single-value group, i.e. what one waterfall opened from this key would actually show.
 */
data class Suggestion(
    /** Display name, spelled as first seen in the capture (`order_id`, not `orderid`). */
    val key: String,
    val eventsGrouped: Int,
    val largestGroup: Int,
    /** Events whose payload names this key — usually far fewer than [eventsGrouped]. */
    val eventsCarrying: Int,
    /** Distinct values seen, capped at [Correlation.MAX_TRACKED_VALUES]. */
    val distinctValues: Int,
    /** Most recently seen value, assuming [Correlation.suggest] was given arrival order. */
    val latestValue: String?,
)

/**
 * The outcome of reading what a human typed into "Find by value…" (PRD §4.2.1).
 *
 * Modelled as a result rather than a nullable string so every case has somewhere to render:
 * a too-short value must say *why* it found nothing instead of showing an empty screen.
 */
sealed interface FindQuery {

    /** Nothing usable typed yet. No message — the action is simply not ready. */
    data object Empty : FindQuery

    /** Below the safety floor: "too short to match safely", never a silent empty result. */
    data class TooShort(val key: String?, val value: String, val minLength: Int) : FindQuery

    /**
     * Ready to group. [key] is present only when the user typed `key=value`; a bare value has a
     * null key and is labelled later by [Correlation.keyLabelFor], or reads as `value <v>`.
     */
    data class Ready(val key: String?, val value: String) : FindQuery
}

/**
 * Groups a flow by the id a human actually knows (`order_id 21053953`) instead of a trace hash
 * the app may never have propagated.
 *
 * **The key names the value; the value does the matching.** That sentence is the whole design:
 *
 *  1. **Extract** — scan an event's payload for the configured key *by name*, case- and
 *     snake/camel-insensitively (`order_id` == `orderId` == `ORDER_ID`), recursively, and —
 *     critically — **parsing JSON that arrives as a string value**. Real payloads nest the
 *     meaningful JSON inside a string (an FCM `data["body"]`, an HTTP body), so a shallow scan
 *     finds nothing at all.
 *  2. **Match** — an event joins the group when that *value* appears in its searchable text
 *     ([searchableText]): url and path, request/response bodies, FCM data, db sql and args,
 *     worker fields, generic section bodies. This is what catches `/app/v4/79096/order/21053953/`,
 *     where the id is a bare path segment with no key anywhere near it.
 *  3. **Guard** — a value must be at least [CorrelationKey.minLength] characters, and a match must
 *     be delimiter-bounded (not flanked by a letter or digit), so `2105` never matches `21053953`
 *     and `21053953` never matches `210539531`.
 *
 * Everything here is pure: no IntelliJ, no Swing, no I/O, no time. That is what makes the design
 * risk testable, and it is the same discipline as [DuplicateDetector] and [SqlSummary].
 *
 * ### Caching contract
 *
 * Both halves are pure functions of an immutable [LogEvent], so results can be cached on the store
 * entry when the event arrives and never invalidated:
 *
 *  - [searchableText] depends on the **event alone** — cache one string per event on arrival.
 *  - [extract] / [valuesFor] depend on the event **and** the configured key list — cache per
 *    event, and drop the cache when the key set changes.
 *  - [containsValue] takes *text*, not an event, and [group] takes a `textOf` lookup, so a caller
 *    holding cached haystacks groups with no rescan at all.
 *
 * None of this may run on a paint path without such a cache in front of it: the payload scan is
 * O(payload) and the haystack is O(bodies). That is the repaint-cost lesson from 1.8.0, stated as
 * an API property rather than a hope.
 */
object Correlation {

    /** Shortest value that may match without an explicit per-key opt-in. */
    const val DEFAULT_MIN_LENGTH = 4

    /** Distinct values [suggest] tracks per candidate key before it stops collecting. */
    const val MAX_TRACKED_VALUES = 64

    // Guards against pathological payloads. A capture is attacker-adjacent data — a device can
    // emit anything — so every scan is bounded in depth, node count and value size, and every
    // parse of an embedded string fails quietly rather than throwing into the caller.
    private const val MAX_DEPTH = 16
    private const val MAX_NODES = 50_000
    private const val MAX_EMBEDDED_JSON_CHARS = 1 shl 20
    private const val MAX_EMBEDDED_JSON_DEPTH = 64
    private const val MAX_VALUE_CHARS = 512
    private const val MAX_SEARCHABLE_CHARS = 1 shl 21

    /** Longest value [suggest] will treat as an identifier — a UUID is 36. */
    private const val MAX_SUGGESTED_VALUE_CHARS = 64

    private val json = Json { ignoreUnknownKeys = true }

    /** Last word of an id-ish key name. `valid` is not id-ish; `orderId` and `order_uuid` are. */
    private val ID_WORDS = setOf("id", "ids", "uuid", "guid")

    /** Values that are never identifiers, however id-ish the key that held them looked. */
    private val NON_VALUES = setOf("true", "false", "null", "none", "nil", "undefined")

    private val KEY_NAME = Regex("[A-Za-z_][A-Za-z0-9_.\\-]{0,63}")

    // ---- extract ---------------------------------------------------------------------------

    /**
     * Every configured key this event carries, matchable or not — the cacheable unit of the
     * extract half (see the caching contract above).
     *
     * Disabled keys are skipped entirely. When a payload names one key in several places the
     * first value found in document order wins, so a nested `order.order_id` beats a later
     * `refund.order_id`; ids repeat far more often than they conflict, and picking one
     * deterministically beats offering the user a choice they can't make.
     */
    fun extract(event: LogEvent, keys: List<CorrelationKey>): List<KeyValue> {
        val wanted = LinkedHashMap<String, CorrelationKey>()
        for (key in keys) {
            if (!key.enabled || key.name.isBlank()) continue
            val canonical = canonicalName(key.name)
            if (canonical.isNotEmpty()) wanted.putIfAbsent(canonical, key)
        }
        if (wanted.isEmpty()) return emptyList()

        val found = HashMap<String, String>(wanted.size)
        val budget = Budget()
        scan(event.envelope.payload, 0, budget) { name, value ->
            val canonical = canonicalName(name)
            if (canonical in wanted.keys && canonical !in found) {
                found[canonical] = value
                if (found.size == wanted.size) budget.stop = true
            }
        }

        return wanted.entries.mapNotNull { (canonical, key) ->
            val value = found[canonical] ?: return@mapNotNull null
            KeyValue(key.name, value, matchable = usable(value, key.minLength, key.allowShortValues))
        }
    }

    /**
     * The PRD's `key -> value` view: the values on this event that can actually group something.
     *
     * Low-confidence (too-short, not opted in) values are deliberately absent — this map answers
     * "what can this row group by", which is what every call site wants. Use [extract] when you
     * need to *show* a value you can't group by.
     */
    fun valuesFor(event: LogEvent, keys: List<CorrelationKey>): Map<String, String> =
        extract(event, keys).filter { it.matchable }.associate { it.key to it.value }

    // ---- match -----------------------------------------------------------------------------

    /**
     * Every event whose searchable text carries [value], in the order given (arrival order, as
     * the store keeps it).
     *
     * [key] is a **label only** — it names the grouping for the user and takes no part in the
     * matching, because an event that carries the value without naming the key is exactly the
     * case this exists to catch. It is in the signature so call sites read the way the user
     * thinks, and so a future key-scoped refinement has a place to live.
     *
     * A value that fails the length rule groups nothing (returns empty) rather than matching
     * loosely. Pass [textOf] to group off cached haystacks instead of rescanning.
     */
    @JvmOverloads
    fun group(
        events: List<LogEvent>,
        key: String?,
        value: String,
        minLength: Int = DEFAULT_MIN_LENGTH,
        allowShortValues: Boolean = false,
        textOf: (LogEvent) -> String = ::searchableText,
    ): List<LogEvent> {
        val needle = value.trim()
        if (!usable(needle, minLength, allowShortValues)) return emptyList()
        return events.filter { containsValue(textOf(it), needle) }
    }

    /** [group] with a configured key supplying its own name, length floor and short-value opt-in. */
    @JvmOverloads
    fun group(
        events: List<LogEvent>,
        key: CorrelationKey,
        value: String,
        textOf: (LogEvent) -> String = ::searchableText,
    ): List<LogEvent> =
        group(events, key.name, value, key.minLength, key.allowShortValues, textOf)

    /** Whether this event belongs to the group for [value]. Rescans; prefer the cached form. */
    fun matches(event: LogEvent, value: String): Boolean =
        containsValue(searchableText(event), value)

    /**
     * Delimiter-bounded, case-insensitive containment: [value] must occur in [text] without a
     * letter or digit on either side.
     *
     * This is the whole over-grouping guard. `2105` does not match `21053953`, `21053953` does not
     * match `210539531`, and `/order/21053953/` does match because `/` is not a word character.
     * The PRD writes the rule as `[A-Za-z0-9]`; this generalizes it to any Unicode letter or
     * digit, which can only ever reject more, never match more.
     *
     * Takes text rather than an event on purpose — this is the entry point a cached haystack uses.
     */
    fun containsValue(text: String, value: String): Boolean {
        if (value.isEmpty() || text.isEmpty()) return false
        var from = 0
        while (from <= text.length - value.length) {
            val at = text.indexOf(value, from, ignoreCase = true)
            if (at < 0) return false
            val before = at - 1
            val after = at + value.length
            val boundedLeft = before < 0 || !text[before].isLetterOrDigit()
            val boundedRight = after >= text.length || !text[after].isLetterOrDigit()
            if (boundedLeft && boundedRight) return true
            from = at + 1
        }
        return false
    }

    /**
     * Everything about an event a correlation value may hide in — the *broad* haystack, bodies
     * included, which is what separates this from the deliberately narrow search haystacks in
     * `McpTools` (row-level `contains:`) and `FilterBar` (what the row shows).
     *
     * Request and response **headers are excluded on purpose**: §3 names urls, bodies, FCM data,
     * db sql/args and section bodies, and folding in auth tokens, cookies and trace headers would
     * add false matches from values a user never sees on the row.
     *
     * Depends on the event alone — cache one string per event on arrival (see the caching
     * contract). Truncated at [MAX_SEARCHABLE_CHARS] so one pathological body can't stall a scan.
     */
    fun searchableText(event: LogEvent): String {
        val sb = StringBuilder()

        fun add(text: String?) {
            if (text.isNullOrEmpty() || sb.length >= MAX_SEARCHABLE_CHARS) return
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(text)
        }

        fun addAll(values: Iterable<String?>) = values.forEach(::add)

        fun addBody(body: Body?) {
            body ?: return
            add(body.text)
            body.parts?.forEach { add(it.name); add(it.filename) }
        }

        // Ids first: a value pasted from elsewhere is as likely to be an event or trace id as a
        // business key, and both are unique enough that including them adds no false matches.
        add(event.id)
        add(event.traceId)

        when (event) {
            is LogEvent.Http -> {
                val tx = event.tx
                // Method, host and status message are labels, not identifier carriers — a value
                // never hides in "GET" or "Not Found", and "POST" is four characters long.
                add(tx.request.url)
                add(tx.request.path)
                addBody(tx.request.body)
                addBody(tx.response?.body)
                add(tx.error)
            }
            is LogEvent.Fcm -> {
                val msg = event.msg
                addAll(
                    listOf(
                        msg.messageId, msg.from, msg.to, msg.collapseKey, msg.messageType, msg.token,
                        msg.notification?.title, msg.notification?.body, msg.notification?.channelId,
                        msg.notification?.clickAction, msg.notification?.imageUrl,
                    )
                )
                // A data-message carries its meaning in the data map — keys and values both, and
                // the values are routinely JSON strings holding the ids this feature is about.
                msg.data.forEach { (k, v) -> add(k); add(v) }
            }
            is LogEvent.Db -> {
                add(event.query.sql)
                addAll(event.query.args)
                add(event.query.database)
                add(event.query.table)
                add(event.query.error)
            }
            is LogEvent.Worker -> {
                val work = event.work
                addAll(listOf(work.worker, work.state, work.workId, work.uniqueName, work.error))
                addAll(work.tags)
                work.inputData.forEach { (k, v) -> add(k); add(v) }
                work.outputData.forEach { (k, v) -> add(k); add(v) }
            }
            is LogEvent.Config -> {
                add(event.update.source)
                add(event.update.fetchStatus)
                event.update.changes.forEach { add(it.key); add(it.value); add(it.previous) }
            }
            is LogEvent.Generic -> {
                val generic = event.event
                if (generic == null) {
                    // Nothing decoded this payload, so the raw JSON is all there is — and dropping
                    // it would make unknown kinds silently ungroupable.
                    add(event.envelope.payload.toString())
                } else {
                    add(generic.title)
                    add(generic.subtitle)
                    generic.badges.forEach { add(it.text) }
                    generic.sections.forEach { add(it.label); add(jsonText(it.body)) }
                }
            }
        }
        return sb.toString()
    }

    // ---- suggest ---------------------------------------------------------------------------

    /**
     * Id-ish key names actually present in [events], ranked by how many distinct events each one
     * would group — the seed for the keys dialog (PRD §4.1).
     *
     * A candidate must be named like an id (last word `id` / `ids` / `uuid` / `guid`, so `valid`
     * and `paid` are not candidates) and hold values that look like identifiers. Candidates whose
     * best value groups only a single event are dropped: a key that cannot group anything is not
     * a suggestion.
     *
     * This is **discovery, not grouping**. The result auto-enables nothing; a human ticks a
     * suggestion and it becomes a [CorrelationKey]. Note that an id constant across the whole
     * capture (a device or install id) ranks high by this metric — the human tick is the filter,
     * which is precisely the role the PRD gives suggestions.
     *
     * Expects [events] in arrival order (as the store keeps them) so [Suggestion.latestValue] is
     * the most recent. One-shot work sized for opening a dialog, not for a paint path.
     */
    @JvmOverloads
    fun suggest(
        events: List<LogEvent>,
        limit: Int = 12,
        minLength: Int = DEFAULT_MIN_LENGTH,
    ): List<Suggestion> {
        if (events.isEmpty()) return emptyList()

        val candidates = LinkedHashMap<String, Candidate>()
        for (event in events) {
            val seen = HashSet<String>()
            scan(event.envelope.payload, 0, Budget()) { name, value ->
                if (!isIdishName(name) || !looksLikeIdentifier(value, minLength)) return@scan
                val canonical = canonicalName(name)
                if (!seen.add(canonical)) return@scan
                candidates.getOrPut(canonical) { Candidate(name) }.record(value)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val ranked = candidates.values.onEach { it.prepare() }
        for (event in events) {
            val text = searchableText(event)
            val tokens = tokenize(text)
            for (candidate in ranked) candidate.count(text, tokens)
        }

        return ranked
            .filter { it.largestGroup >= 2 }
            .map {
                Suggestion(
                    key = it.display,
                    eventsGrouped = it.union,
                    largestGroup = it.largestGroup,
                    eventsCarrying = it.carriers,
                    distinctValues = it.values.size,
                    latestValue = it.latest,
                )
            }
            .sortedWith(
                compareByDescending<Suggestion> { it.eventsGrouped }
                    .thenByDescending { it.largestGroup }
                    .thenBy { it.key },
            )
            .take(limit)
    }

    /**
     * The configured key whose value on some event equals [value], or null when no key claims it —
     * how a bare pasted value gets labelled `order_id 21053953` instead of `value 21053953`
     * (PRD §4.2.1).
     *
     * Searches newest-first, so a value reused across sessions is labelled by its most recent use.
     */
    fun keyLabelFor(events: List<LogEvent>, keys: List<CorrelationKey>, value: String): String? {
        val needle = value.trim()
        if (needle.isEmpty() || keys.isEmpty()) return null
        for (index in events.indices.reversed()) {
            extract(events[index], keys)
                .firstOrNull { it.value.equals(needle, ignoreCase = true) }
                ?.let { return it.key }
        }
        return null
    }

    // ---- find by value ---------------------------------------------------------------------

    /**
     * Reads what a human typed or pasted into "Find by value…" (PRD §4.2.1).
     *
     * Accepts a bare value (`21053953`) or a `key=value` pair (`order_id=21053953`), trims
     * whitespace, and strips surrounding quotes on both the whole input and the value — ids arrive
     * copied out of JSON as often as not.
     *
     * `key=value` is only read as a pair when the left side is a plausible key name **and** the
     * right side has an alphanumeric in it; otherwise the whole string is the value. That is what
     * keeps a base64 id (`MjEwNTM5NTM=`) a value rather than an empty-valued key. A `:` separator
     * is deliberately *not* accepted: too many bare values (urls, timestamps) contain one.
     *
     * Returns a [FindQuery] rather than a nullable string so a too-short value can say so out
     * loud — the PRD is explicit that a silent empty result is the wrong answer for a typo.
     */
    @JvmOverloads
    fun parseFindQuery(
        input: String,
        minLength: Int = DEFAULT_MIN_LENGTH,
        allowShortValues: Boolean = false,
    ): FindQuery {
        val trimmed = unquote(input.trim())
        if (trimmed.isEmpty()) return FindQuery.Empty

        var key: String? = null
        var raw = trimmed
        val separator = trimmed.indexOf('=')
        if (separator > 0) {
            val left = trimmed.substring(0, separator).trim()
            val right = unquote(trimmed.substring(separator + 1).trim())
            if (KEY_NAME.matches(left) && right.any { it.isLetterOrDigit() }) {
                key = left
                raw = right
            }
        }

        val value = unquote(raw.trim())
        if (value.isEmpty()) return FindQuery.Empty
        if (!usable(value, minLength, allowShortValues)) return FindQuery.TooShort(key, value, minLength)
        return FindQuery.Ready(key, value)
    }

    // ---- internals -------------------------------------------------------------------------

    /**
     * `order_id`, `orderId`, `ORDER_ID` and `order-id` are one key: lowercase, and drop every
     * character that isn't a letter or digit.
     */
    private fun canonicalName(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        return sb.toString()
    }

    private fun usable(value: String, minLength: Int, allowShortValues: Boolean): Boolean =
        value.isNotBlank() && (allowShortValues || value.length >= minLength.coerceAtLeast(1))

    /** Bounds one payload scan: a device can emit anything, so nothing here is unbounded. */
    private class Budget {
        var nodes = 0
        var stop = false
        fun spend(): Boolean {
            nodes++
            return !stop && nodes <= MAX_NODES
        }
    }

    /**
     * Depth-first walk of a payload, reporting every `name -> primitive` pair it passes.
     *
     * The part that matters: a **string value that holds JSON is parsed and walked too**. gandalf
     * nests the real payload as a JSON string inside an FCM `data["body"]`, and HTTP bodies arrive
     * the same way, so without this the scan finds nothing on exactly the payloads it exists for.
     * A string that isn't JSON, is too big, or is nested absurdly deep is skipped in silence.
     */
    private fun scan(
        element: JsonElement,
        depth: Int,
        budget: Budget,
        onPair: (name: String, value: String) -> Unit,
    ) {
        if (depth > MAX_DEPTH || !budget.spend()) return
        when (element) {
            is JsonObject -> for ((name, child) in element) {
                if (budget.stop) return
                if (child is JsonPrimitive && child !is JsonNull) {
                    val value = child.content
                    if (value.isNotBlank() && value.length <= MAX_VALUE_CHARS) onPair(name, value)
                }
                scan(child, depth + 1, budget, onPair)
            }
            is JsonArray -> for (child in element) {
                if (budget.stop) return
                scan(child, depth + 1, budget, onPair)
            }
            is JsonPrimitive ->
                if (element !is JsonNull && element.isString) {
                    embedded(element.content)?.let { scan(it, depth + 1, budget, onPair) }
                }
        }
    }

    /** Parses a string value that holds JSON, or returns null — never throws at the caller. */
    private fun embedded(text: String): JsonElement? {
        if (text.length > MAX_EMBEDDED_JSON_CHARS) return null
        val first = text.firstOrNull { !it.isWhitespace() } ?: return null
        if (first != '{' && first != '[') return null
        // Depth is checked before parsing rather than caught after: a few thousand nested brackets
        // would blow the parser's own stack, and a cheap pre-scan makes the guard deterministic.
        if (nestingTooDeep(text)) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun nestingTooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_EMBEDDED_JSON_DEPTH) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    private fun jsonText(element: JsonElement): String =
        if (element is JsonPrimitive) element.content else element.toString()

    /** Maximal runs of letters and digits, lowercased — the delimiter-bounded units of [text]. */
    private fun tokenize(text: String): Set<String> {
        val tokens = HashSet<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                sb.append(c.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                tokens.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /** `orderId` / `order_id` / `ORDER_ID` are id-ish; `valid` and `paid` are not. */
    private fun isIdishName(name: String): Boolean = wordsOf(name).lastOrNull() in ID_WORDS

    /** Splits a name on separators *and* camel humps: `orderID` -> `[order, id]`. */
    private fun wordsOf(name: String): List<String> {
        val words = ArrayList<String>(4)
        val sb = StringBuilder()
        for ((index, c) in name.withIndex()) {
            if (!c.isLetterOrDigit()) {
                if (sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) }
                continue
            }
            val previous = if (index == 0) null else name[index - 1]
            val hump = c.isUpperCase() && previous != null && (previous.isLowerCase() || previous.isDigit())
            if (hump && sb.isNotEmpty()) { words.add(sb.toString()); sb.setLength(0) }
            sb.append(c.lowercaseChar())
        }
        if (sb.isNotEmpty()) words.add(sb.toString())
        return words
    }

    private fun looksLikeIdentifier(value: String, minLength: Int): Boolean {
        if (value.length < minLength.coerceAtLeast(1) || value.length > MAX_SUGGESTED_VALUE_CHARS) return false
        if (value.lowercase() in NON_VALUES) return false
        var alphanumeric = false
        for (c in value) {
            if (c.isWhitespace() || c == '{' || c == '}' || c == '"') return false
            if (c.isLetterOrDigit()) alphanumeric = true
        }
        return alphanumeric
    }

    /** Strips one or more matched pairs of surrounding quotes, straight or curly. */
    private fun unquote(text: String): String {
        var result = text
        repeat(3) {
            if (result.length < 2) return result
            val open = result.first()
            val close = result.last()
            val quoted = (open == '"' && close == '"') ||
                (open == '\'' && close == '\'') ||
                (open == '`' && close == '`') ||
                (open == '“' && close == '”') ||
                (open == '‘' && close == '’')
            if (!quoted) return result
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    /** One id-ish key name accumulating across the capture, for [suggest]. */
    private class Candidate(val display: String) {
        val values = LinkedHashSet<String>()
        var carriers = 0
        var latest: String? = null

        private var ordered: List<String> = emptyList()
        private var lowered: List<String> = emptyList()
        private var single: BooleanArray = BooleanArray(0)
        private var counts: IntArray = IntArray(0)
        var union = 0
            private set

        fun record(value: String) {
            carriers++
            latest = value
            // Beyond the cap a key is per-event-unique (a request id, say) and groups nothing;
            // under-counting it pushes it down the ranking, which is where it belongs.
            if (values.size < MAX_TRACKED_VALUES) values.add(value)
        }

        fun prepare() {
            ordered = values.toList()
            lowered = ordered.map { it.lowercase() }
            single = BooleanArray(ordered.size) { i -> ordered[i].all { c -> c.isLetterOrDigit() } }
            counts = IntArray(ordered.size)
        }

        fun count(text: String, tokens: Set<String>) {
            var hit = false
            for (i in ordered.indices) {
                // A value made only of letters and digits IS a token, so set membership decides it
                // exactly — and far faster than a substring search over a whole response body.
                val matched = if (single[i]) lowered[i] in tokens else containsValue(text, ordered[i])
                if (matched) { counts[i]++; hit = true }
            }
            if (hit) union++
        }

        val largestGroup: Int get() = counts.maxOrNull() ?: 0
    }
}
