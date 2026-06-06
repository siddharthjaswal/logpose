package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.Transaction

/** Renders a transaction's request as a copy-pasteable `curl` command. */
object CurlBuilder {

    fun build(tx: Transaction): String {
        val sb = StringBuilder("curl -X ${tx.request.method} ").append(quote(tx.request.url))

        tx.request.headers.forEach { (k, v) ->
            sb.append(" \\\n  -H ").append(quote("$k: $v"))
        }

        val body = tx.request.body
        when {
            body?.parts != null -> body.parts!!.forEach { p ->
                val field = p.name ?: "file"
                val ref = p.filename?.let { "@$it" } ?: "<binary>"
                sb.append(" \\\n  -F ").append(quote("$field=$ref"))
            }
            // Skip our placeholder summaries like "(binary body, 1234 bytes)".
            body?.text != null && !body.text!!.startsWith("(") ->
                sb.append(" \\\n  --data-raw ").append(quote(body.text!!))
        }
        return sb.toString()
    }

    /** Single-quote for POSIX shells, escaping embedded single quotes. */
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
