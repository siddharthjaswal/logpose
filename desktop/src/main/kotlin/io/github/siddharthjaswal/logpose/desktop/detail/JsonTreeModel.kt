package io.github.siddharthjaswal.logpose.desktop.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The JSON tree's model: a payload flattened into the visible lines for a given expansion set, so
 * the composable is a plain `LazyColumn` over [flatten]'s output (virtualized, however big the
 * body) and every rule here is unit-tested.
 *
 * One addition over a plain tree: a **string that is itself JSON** — an HTTP body as the wire
 * carries it — is shown as the object it encodes (marked [Line.embedded]), because a 4 KB escaped
 * one-liner is exactly the unreadable thing LogPose exists to fix.
 */
object JsonTreeModel {

    enum class Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    data class Line(
        val path: String,
        val depth: Int,
        /** Object key or `[i]`; null for the root. */
        val key: String?,
        val kind: Kind,
        /** A scalar's display text (strings quoted); empty for containers. */
        val text: String,
        /** Children of a container; 0 for scalars. */
        val count: Int,
        val expanded: Boolean,
        /** True for a container decoded from a JSON-encoded string. */
        val embedded: Boolean,
    ) {
        val expandable: Boolean get() = kind == Kind.OBJECT || kind == Kind.ARRAY
    }

    const val ROOT = "$"
    private const val SEP = '\u001F'

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun flatten(root: JsonElement, expanded: Set<String>): List<Line> {
        val out = ArrayList<Line>()
        visit(root, ROOT, 0, null, expanded, out)
        return out
    }

    /**
     * What a freshly opened payload shows: containers above [depth], plus the path down to every
     * embedded JSON body so the body is open on arrival (headers stay folded).
     */
    fun defaultExpanded(root: JsonElement, depth: Int = 2): Set<String> {
        val result = HashSet<String>()
        fun walk(el: JsonElement, path: String, d: Int, ancestors: List<String>, parentDecoded: Boolean) {
            val node = decodeEmbedded(el)
            val container = node as? JsonObject ?: node as? JsonArray
            if (container == null) return
            val decoded = node !== el
            if (d < depth) result += path
            if (decoded) result += ancestors + path
            // A decoded body opens one level deeper than its own braces: its top-level arrays and
            // objects are the content, not structure.
            if (parentDecoded) result += path
            when (node) {
                is JsonObject -> node.forEach { (k, v) -> walk(v, child(path, k), d + 1, ancestors + path, decoded) }
                is JsonArray -> node.forEachIndexed { i, v -> walk(v, child(path, "[$i]"), d + 1, ancestors + path, decoded) }
                else -> Unit
            }
        }
        walk(root, ROOT, 0, emptyList(), false)
        return result
    }

    /** Every container path, for "expand all". */
    fun allContainers(root: JsonElement): Set<String> =
        flatten(root, AllExpanded).filter { it.expandable }.mapTo(HashSet()) { it.path }

    fun prettyPrint(root: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), root)

    /** The element a string encodes when it is a JSON object or array; [el] unchanged otherwise. */
    fun decodeEmbedded(el: JsonElement): JsonElement {
        if (el !is JsonPrimitive || !el.isString) return el
        val s = el.content.trim()
        if (s.length < 2 || !((s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']')))) return el
        val parsed = runCatching { Json.parseToJsonElement(s) }.getOrNull() ?: return el
        return if (parsed is JsonObject || parsed is JsonArray) parsed else el
    }

    private fun child(parent: String, key: String) = "$parent$SEP$key"

    private fun visit(el: JsonElement, path: String, depth: Int, key: String?, expanded: Set<String>, out: MutableList<Line>) {
        val node = decodeEmbedded(el)
        val embedded = node !== el
        when (node) {
            is JsonObject -> {
                val open = path in expanded
                out += Line(path, depth, key, Kind.OBJECT, "", node.size, open, embedded)
                if (open) node.forEach { (k, v) -> visit(v, child(path, k), depth + 1, k, expanded, out) }
            }
            is JsonArray -> {
                val open = path in expanded
                out += Line(path, depth, key, Kind.ARRAY, "", node.size, open, embedded)
                if (open) node.forEachIndexed { i, v -> visit(v, child(path, "[$i]"), depth + 1, "[$i]", expanded, out) }
            }
            JsonNull -> out += Line(path, depth, key, Kind.NULL, "null", 0, false, false)
            is JsonPrimitive -> {
                val kind = when {
                    node.isString -> Kind.STRING
                    node.booleanOrNull != null -> Kind.BOOL
                    else -> Kind.NUMBER
                }
                val text = if (node.isString) "\"" + node.content + "\"" else node.content
                out += Line(path, depth, key, kind, text, 0, false, false)
            }
        }
    }

    /** A set that contains every path — "expand all" without enumerating first. */
    private object AllExpanded : AbstractSet<String>() {
        override val size: Int get() = Int.MAX_VALUE
        override fun contains(element: String): Boolean = true
        override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    }
}
