package io.github.siddharthjaswal.logpose.settings

/**
 * The one thing `:core` needs from its host that a plain JVM has no answer for: somewhere to keep
 * a handful of small settings between runs.
 *
 * Deliberately shaped like IntelliJ's `PropertiesComponent`, because that is what the plugin backs
 * it with and matching the API exactly is what lets an upgrade keep every existing key readable —
 * the string/int/boolean split, the "unset when null or equal to the default" rule, all of it. A
 * headless host backs it with a properties file instead; nothing in core knows the difference.
 *
 * Keys are shared vocabulary, not implementation detail: `logpose.mcp.token`,
 * `logpose.mock.rules`, `logpose.correlation.keys` and friends are user data that already exists
 * on disk, so they must never be renamed.
 */
interface KeyValueStore {

    fun get(key: String): String?

    /** Sets [value], or removes the key entirely when it is null. */
    fun set(key: String, value: String?)

    fun getInt(key: String, default: Int): Int

    /** Sets [value], storing nothing when it equals [default] (which is how the platform behaves). */
    fun setInt(key: String, value: Int, default: Int)

    fun getBoolean(key: String, default: Boolean): Boolean

    /** Sets [value], storing nothing when it equals [default] (which is how the platform behaves). */
    fun setBoolean(key: String, value: Boolean, default: Boolean)

    /** An in-memory store — for tests, and for a host with nowhere to persist. */
    class InMemory(private val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun getInt(key: String, default: Int): Int = map[key]?.toIntOrNull() ?: default
        override fun setInt(key: String, value: Int, default: Int) {
            if (value == default) map.remove(key) else map[key] = value.toString()
        }
        override fun getBoolean(key: String, default: Boolean): Boolean =
            map[key]?.toBooleanStrictOrNull() ?: default
        override fun setBoolean(key: String, value: Boolean, default: Boolean) {
            if (value == default) map.remove(key) else map[key] = value.toString()
        }
    }
}
