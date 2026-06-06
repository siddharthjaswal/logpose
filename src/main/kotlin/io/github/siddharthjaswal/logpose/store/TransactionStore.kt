package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.Transaction
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds captured transactions and notifies listeners on change. Filtering is a
 * pure function over the captured list, so the UI can re-query cheaply as the
 * user types. A capped ring keeps memory bounded during long sessions.
 */
class TransactionStore(private val capacity: Int = 2_000) {

    private val all = ArrayDeque<Transaction>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun add(tx: Transaction) {
        // Coalesce: a later emission for the same id (e.g. response after request)
        // replaces the earlier partial entry.
        val existing = all.indexOfFirst { it.id == tx.id }
        if (existing >= 0) {
            all[existing] = tx
        } else {
            all.addLast(tx)
            while (all.size > capacity) all.removeFirst()
        }
        listeners.forEach { it() }
    }

    @Synchronized
    fun clear() {
        all.clear()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<Transaction> = all.toList()

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
