package io.github.siddharthjaswal.logpose.capture

import io.github.siddharthjaswal.logpose.model.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Removes customer PII from captured events before they leave the machine (PRD §5.3).
 *
 * The device already redacted headers and secret query parameters (library 1.7.3) — those are left
 * alone. Bodies are the risk: a delivery app's responses carry customer names, phone numbers and
 * addresses. Three rules, applied to the free-form parts of each event's payload:
 *
 *  1. **By key.** Any value under a key named like a PII field is replaced with [MARK] — the same
 *     marker the library uses for redaction. Keys match by *word*, case- and separator-blind:
 *     `customerName`, `customer_name` and `CUSTOMER-NAME` all contain the word `name`; `filename`
 *     does not. A matched object or array is scrubbed leaf by leaf (each leaf counts once).
 *     A few qualifiers make `name`/`address` structural rather than personal (`file_name`,
 *     `channel_name`, `ip_address`, …) and are left alone.
 *  2. **By pattern**, anywhere in a string value: emails, international numbers written with `+`,
 *     and Indian mobiles (10 digits starting 6–9, with or without `+91`/`0`). Only *strings* are
 *     pattern-scanned — a bare JSON number (a status, a duration, an id) never is, and a digit run
 *     inside a longer number or word never matches.
 *  3. **JSON inside strings** — HTTP bodies are JSON *strings* — is parsed, scrubbed by 1 and 2,
 *     and re-serialized; a string that isn't JSON gets the pattern pass only. A string is only
 *     rewritten when something in it was scrubbed, so an untouched body stays byte-identical.
 *
 * Idempotent: [MARK] is never re-counted, so scrubbing a scrubbed capture reports zero.
 *
 * What it deliberately does **not** scrub: headers (device-redacted already), the envelope's own
 * fields (ids, times, trace ids), request methods/hosts, multipart part metadata, SQL table and
 * column names, worker and config *keys*, FCM registration tokens, and bare numbers outside a PII
 * key. Order ids, amounts and statuses are what make a capture useful to the developer.
 */
class Scrubber(val options: Options = Options()) {

    data class Options(
        /** The PII scrubber proper — keys + patterns. Off still honours [includeBodies]. */
        val enabled: Boolean = true,
        val keys: List<String> = DEFAULT_KEYS,
        val patterns: Boolean = true,
        /** False drops HTTP request/response body text entirely (sizes and content types stay). */
        val includeBodies: Boolean = true,
    )

    enum class Category(val one: String, val many: String) {
        PHONE("phone number", "phone numbers"),
        EMAIL("email", "emails"),
        NAME("name", "names"),
        ADDRESS("address", "addresses"),
        LOCATION("location", "locations"),
        OTHER("other field", "other fields"),
    }

    data class Result(val envelope: Envelope, val counts: Map<Category, Int>, val bodiesDropped: Int) {
        val scrubbed: Int get() = counts.values.sum()
    }

    private val keyTable: Map<String, Category> = options.keys
        .map { normalize(it) }.filter { it.isNotEmpty() }
        .associateWith { categoryOfKey(it) }

    fun scrub(envelope: Envelope): Result {
        val obj = envelope.payload as? JsonObject
        val ctx = Ctx()
        val payload = when {
            obj == null -> envelope.payload
            else -> when (envelope.kind) {
                Envelope.KIND_HTTP -> http(obj, ctx)
                Envelope.KIND_FCM -> fcm(obj, ctx)
                Envelope.KIND_DB -> db(obj, ctx)
                Envelope.KIND_WORKER -> worker(obj, ctx)
                Envelope.KIND_CONFIG -> config(obj, ctx)
                else -> if (options.enabled) walk(obj, ctx, null, 0) else obj
            }
        }
        val changed = ctx.counts.isNotEmpty() || ctx.bodiesDropped > 0
        return Result(if (changed) envelope.copy(payload = payload) else envelope, ctx.counts.toMap(), ctx.bodiesDropped)
    }

    /** Scrubs every envelope and totals what was done, for the share sheet's preview and the manifest. */
    fun scrubAll(envelopes: List<Envelope>): Pair<List<Envelope>, ScrubReport> {
        var report = ScrubReport()
        val out = envelopes.map { e ->
            val r = scrub(e)
            report += ScrubReport.of(e.kind, r)
            r.envelope
        }
        return out to report
    }

    // ---- per-kind regions -------------------------------------------------------------------

    private fun http(tx: JsonObject, ctx: Ctx): JsonObject = tx.edit {
        update("request") { req -> (req as? JsonObject)?.let { message(it, ctx, "url", "path") } ?: req }
        update("response") { res -> (res as? JsonObject)?.let { message(it, ctx, "message") } ?: res }
        update("error") { text(it, ctx) }
    }

    /** A request or response: the named free-text fields, then the body. Headers are never touched. */
    private fun message(m: JsonObject, ctx: Ctx, vararg textFields: String): JsonObject = m.edit {
        for (f in textFields) update(f) { text(it, ctx) }
        update("body") { b ->
            val body = b as? JsonObject ?: return@update b
            if (!options.includeBodies) {
                if (body["text"] != null && body["text"] !is JsonNull) ctx.bodiesDropped++
                JsonObject(body - "text")
            } else {
                body.edit { update("text") { text(it, ctx) } }
            }
        }
    }

    private fun fcm(msg: JsonObject, ctx: Ctx): JsonObject = msg.edit {
        update("data") { d -> (d as? JsonObject)?.let { if (options.enabled) walk(it, ctx, null, 0) else it } ?: d }
        update("notification") { n -> (n as? JsonObject)?.edit { update("title") { text(it, ctx) }; update("body") { text(it, ctx) } } ?: n }
    }

    private fun db(q: JsonObject, ctx: Ctx): JsonObject = q.edit {
        update("sql") { text(it, ctx) }
        update("args") { a -> (a as? JsonArray)?.let { arr -> JsonArray(arr.map { text(it, ctx) }) } ?: a }
        update("error") { text(it, ctx) }
    }

    private fun worker(w: JsonObject, ctx: Ctx): JsonObject = w.edit {
        for (k in listOf("inputData", "outputData")) {
            update(k) { d -> (d as? JsonObject)?.let { if (options.enabled) walk(it, ctx, null, 0) else it } ?: d }
        }
        update("error") { text(it, ctx) }
    }

    private fun config(c: JsonObject, ctx: Ctx): JsonObject = c.edit {
        update("changes") { arr ->
            (arr as? JsonArray)?.let { list ->
                JsonArray(list.map { ch -> (ch as? JsonObject)?.edit { update("value") { text(it, ctx) }; update("previous") { text(it, ctx) } } ?: ch })
            } ?: arr
        }
    }

    // ---- the walk ---------------------------------------------------------------------------

    /**
     * Scrubs a JSON tree. [inherited] is the category of a matched ancestor key: everything under
     * it is PII (a `customer` object), unless a nearer key names a more specific category.
     */
    private fun walk(e: JsonElement, ctx: Ctx, inherited: Category?, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return e
        return when (e) {
            is JsonObject -> {
                var changed = false
                val out = LinkedHashMap<String, JsonElement>(e.size)
                for ((k, v) in e) {
                    // Inside a matched `customer` object its `id` / `type` stay: metadata, not PII.
                    val cat = matchKey(k) ?: inherited?.takeUnless { isMetaKey(k) }
                    val nv = walk(v, ctx, cat, depth + 1)
                    if (nv !== v) changed = true
                    out[k] = nv
                }
                if (changed) JsonObject(out) else e
            }
            is JsonArray -> {
                var changed = false
                val out = e.map { v -> walk(v, ctx, inherited, depth + 1).also { if (it !== v) changed = true } }
                if (changed) JsonArray(out) else e
            }
            is JsonPrimitive -> when {
                e is JsonNull -> e
                inherited != null && (e.isString || e.content.toBooleanStrictOrNull() == null) -> {
                    if (e.isString && (e.content == MARK || e.content.isBlank())) e
                    else { ctx.add(inherited); JsonPrimitive(MARK) }
                }
                e.isString -> scrubString(e, ctx, depth)
                else -> e // a bare number or boolean outside a PII key: statuses, durations, ids
            }
        }
    }

    /** A free-form text field: JSON-aware when it holds JSON, pattern-scanned otherwise. */
    private fun text(e: JsonElement, ctx: Ctx): JsonElement {
        if (!options.enabled) return e
        val p = e as? JsonPrimitive ?: return e
        if (p is JsonNull || !p.isString) return e
        return scrubString(p, ctx, 0)
    }

    private fun scrubString(p: JsonPrimitive, ctx: Ctx, depth: Int): JsonPrimitive {
        val s = p.content
        val t = s.trimStart()
        if ((t.startsWith("{") || t.startsWith("[")) && depth < MAX_STRING_NESTING * 16) {
            val parsed = runCatching { STRICT.parseToJsonElement(s) }.getOrNull()
            if (parsed is JsonObject || parsed is JsonArray) {
                val before = ctx.total
                val scrubbed = walk(parsed, ctx, null, depth + 16)
                return if (ctx.total == before) p else JsonPrimitive(STRICT.encodeToString(JsonElement.serializer(), scrubbed))
            }
        }
        if (!options.patterns) return p
        val out = scrubPatterns(s, ctx)
        return if (out == s) p else JsonPrimitive(out)
    }

    private fun scrubPatterns(s: String, ctx: Ctx): String {
        if (s.length < 7) return s
        var out = EMAIL.replace(s) { ctx.add(Category.EMAIL); MARK }
        out = INTL_PHONE.replace(out) { m ->
            val digits = m.value.count(Char::isDigit)
            if (digits in 8..15) { ctx.add(Category.PHONE); MARK } else m.value
        }
        out = IN_PHONE.replace(out) { ctx.add(Category.PHONE); MARK }
        return out
    }

    // ---- keys -------------------------------------------------------------------------------

    /** The category a JSON key names, or null when it names nothing personal. */
    fun matchKey(key: String): Category? {
        if (!options.enabled || keyTable.isEmpty()) return null
        val tokens = tokenize(key)
        if (tokens.isEmpty()) return null
        // `customer_id`, `phone_verified`, `address_type`: metadata *about* the PII, not the PII.
        if (tokens.size > 1 && tokens.last() in META_SUFFIX) return null
        // Contiguous word windows, longest first, so `contact_number` wins over `contact`.
        for (len in tokens.size downTo 1) {
            for (start in 0..tokens.size - len) {
                val cat = keyTable[tokens.subList(start, start + len).joinToString("")] ?: continue
                if (len == 1 && qualifiedAway(tokens, start)) continue
                return cat
            }
        }
        return null
    }

    private fun isMetaKey(key: String): Boolean = tokenize(key).lastOrNull() in META_SUFFIX

    private fun qualifiedAway(tokens: List<String>, index: Int): Boolean {
        val word = tokens[index]
        // An entity word names the person only as the whole key's subject: `customer` (an object or
        // a name) and `delivery_contact` are PII; `customer_rating` and `contact_preference` are not.
        if (word in ENTITY_WORDS && index != tokens.lastIndex) return true
        val prev = tokens.getOrNull(index - 1) ?: return false
        return when (word) {
            "name" -> prev in STRUCTURAL_NAME
            "address" -> prev in STRUCTURAL_ADDRESS
            else -> false
        }
    }

    private class Ctx {
        val counts = LinkedHashMap<Category, Int>()
        var bodiesDropped = 0
        var total = 0
        fun add(c: Category) { counts[c] = (counts[c] ?: 0) + 1; total++ }
    }

    private class Editor(private val src: JsonObject) {
        val map = LinkedHashMap(src)
        var changed = false
        fun update(key: String, f: (JsonElement) -> JsonElement) {
            val v = map[key] ?: return
            val nv = f(v)
            if (nv !== v) { map[key] = nv; changed = true }
        }
        fun result(): JsonObject = if (changed) JsonObject(map) else src
    }

    private inline fun JsonObject.edit(block: Editor.() -> Unit): JsonObject = Editor(this).apply(block).result()

    companion object {
        /** The library's redaction marker, so a scrubbed value reads like a redacted header. */
        const val MARK = "██"

        /**
         * Defaults for a delivery app: who the customer is, how to reach them, where they are.
         * Editable per share; matched by word (see the class doc).
         */
        val DEFAULT_KEYS: List<String> = listOf(
            "name", "customer", "recipient", "receiver", "consignee",
            "phone", "mobile", "contact", "contact_number", "msisdn",
            "email",
            "address", "landmark", "pincode", "street",
            "lat", "lng", "lon", "latitude", "longitude",
            "otp",
        )

        private val STRUCTURAL_NAME = setOf(
            "file", "channel", "class", "method", "event", "screen", "app", "package", "host", "device",
            "model", "brand", "table", "worker", "tag", "param", "key", "field", "column", "unique",
            "service", "queue", "topic", "city", "state", "country", "zone", "hub", "store", "shop",
            "product", "item", "sku", "category", "plan", "bank", "db", "database", "process", "thread",
            "node", "cluster", "bucket", "image", "font", "color", "icon", "feature", "flag", "config",
            "layout", "view", "fragment", "activity", "action", "type", "status", "role", "variant",
        )
        private val ENTITY_WORDS = setOf("customer", "recipient", "receiver", "consignee", "contact")
        private val META_SUFFIX = setOf(
            "id", "ids", "uuid", "guid", "type", "types", "count", "verified", "enabled", "status", "flag",
            "required", "masked", "mask", "length", "format", "visible", "editable", "available", "source",
        )
        private val STRUCTURAL_ADDRESS = setOf("ip", "mac", "wallet", "web", "remote", "local", "server", "socket", "inet", "bluetooth", "bt", "memory", "mem")

        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
        /** `+` then 8–15 digits with the usual separators (validated by digit count). */
        private val INTL_PHONE = Regex("""(?<![\w+])\+\d[\d\s().-]{6,20}\d(?![\w])""")
        /** Indian mobile: optional +91 / 0091 / 0, then 10 digits starting 6–9 (a space or dash after five allowed). */
        private val IN_PHONE = Regex("""(?<![\w+.])(?:(?:\+|00)91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?![\w])""")

        private const val MAX_DEPTH = 96
        private const val MAX_STRING_NESTING = 4

        private val STRICT = Json

        internal fun normalize(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

        /** `customerName` / `customer_name` / `Customer-Name` → [customer, name]. */
        internal fun tokenize(key: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString().lowercase()); cur.clear() } }
            for ((i, c) in key.withIndex()) {
                when {
                    !c.isLetterOrDigit() -> flush()
                    c.isUpperCase() && cur.isNotEmpty() && (key[i - 1].isLowerCase() || key[i - 1].isDigit() ||
                        (key.getOrNull(i + 1)?.isLowerCase() == true && key[i - 1].isUpperCase())) -> { flush(); cur.append(c) }
                    else -> cur.append(c)
                }
            }
            flush()
            return out
        }

        internal fun categoryOfKey(normalized: String): Category = when (normalized) {
            "name", "customer", "recipient", "receiver", "consignee", "firstname", "lastname", "fullname" -> Category.NAME
            "phone", "mobile", "contact", "contactnumber", "msisdn", "phonenumber", "mobilenumber" -> Category.PHONE
            "email", "emailaddress" -> Category.EMAIL
            "address", "landmark", "pincode", "street", "zip", "zipcode", "postalcode" -> Category.ADDRESS
            "lat", "lng", "lon", "latitude", "longitude" -> Category.LOCATION
            else -> Category.OTHER
        }
    }
}

/** What a scrub did, totalled — per category for the sentence, per kind for the breakdown. */
data class ScrubReport(
    val byCategory: Map<Scrubber.Category, Int> = emptyMap(),
    val byKind: Map<String, Int> = emptyMap(),
    val bodiesDropped: Int = 0,
    /** Events that had at least one value scrubbed. */
    val eventsTouched: Int = 0,
) {
    val total: Int get() = byCategory.values.sum()

    operator fun plus(o: ScrubReport) = ScrubReport(
        merge(byCategory, o.byCategory), merge(byKind, o.byKind), bodiesDropped + o.bodiesDropped, eventsTouched + o.eventsTouched,
    )

    /** "scrubbed 23 fields: 9 phone numbers, 6 names, 8 addresses" — largest first. */
    fun summary(): String {
        if (total == 0) return "nothing to scrub"
        val parts = byCategory.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<Scrubber.Category, Int>> { it.value }.thenBy { it.key.ordinal })
            .joinToString(", ") { (c, n) -> "$n ${if (n == 1) c.one else c.many}" }
        return "scrubbed $total ${if (total == 1) "field" else "fields"}: $parts"
    }

    companion object {
        fun of(kind: String, r: Scrubber.Result) = ScrubReport(
            r.counts, if (r.scrubbed > 0) mapOf(kind to r.scrubbed) else emptyMap(), r.bodiesDropped, if (r.scrubbed > 0) 1 else 0,
        )

        private fun <K> merge(a: Map<K, Int>, b: Map<K, Int>): Map<K, Int> {
            val out = LinkedHashMap(a)
            for ((k, v) in b) out[k] = (out[k] ?: 0) + v
            return out
        }
    }
}
