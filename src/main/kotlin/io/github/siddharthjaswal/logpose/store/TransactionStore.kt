package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.Transaction
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds captured transactions and notifies listeners on change. Filtering is a
 * pure function over the captured list, so the UI can re-query cheaply as the
 * user types. A capped ring keeps memory bounded during long sessions.
 */
class TransactionStore(private val capacity: Int = 2_000) {

    // Insertion-ordered, keyed by transaction id: O(1) add + dedup. Re-putting an
    // existing id (e.g. a response arriving after its request) updates in place and
    // keeps its original position. Oldest entries evict once over capacity.
    private val all = object : LinkedHashMap<String, Transaction>(256, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Transaction>): Boolean =
            size > capacity
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun add(tx: Transaction) {
        all[tx.id] = tx
        listeners.forEach { it() }
    }

    @Synchronized
    fun clear() {
        all.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<Transaction> = all.values.toList()

    fun addListener(l: () -> Unit) { listeners.add(l) }

    companion object {
        /**
         * Filters transactions by a free-text query. Supported forms:
         *  - plain text         -> substring match on the URL (case-insensitive)
         *  - `status:4xx|5xx|2xx|404` -> match response code / class
         *  - `method:POST`      -> match HTTP method
         *  - `-foo`             -> exclude transactions whose URL contains "foo"
         *
         * Multiple space-separated terms are AND-ed.
         */
        fun filter(items: List<Transaction>, query: String): List<Transaction> {
            val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (terms.isEmpty()) return items
            return items.filter { tx -> terms.all { matches(tx, it) } }
        }

        private fun matches(tx: Transaction, term: String): Boolean {
            if (term.startsWith("-") && term.length > 1) {
                return !tx.request.url.contains(term.substring(1), ignoreCase = true)
            }
            if (term.startsWith("status:", ignoreCase = true)) {
                val code = tx.response?.code ?: return false
                val v = term.substringAfter(":").lowercase()
                return when {
                    v.endsWith("xx") && v.length == 3 -> code / 100 == v[0].digitToIntOrNull()
                    else -> v.toIntOrNull() == code
                }
            }
            if (term.startsWith("method:", ignoreCase = true)) {
                return tx.request.method.equals(term.substringAfter(":"), ignoreCase = true)
            }
            return tx.request.url.contains(term, ignoreCase = true)
        }
    }
}
