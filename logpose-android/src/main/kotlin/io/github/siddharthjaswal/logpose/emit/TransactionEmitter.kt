package io.github.siddharthjaswal.logpose.emit

import io.github.siddharthjaswal.logpose.wire.Transaction

/** Sink for completed transactions. Swap in a custom one (e.g. a socket) if desired. */
fun interface TransactionEmitter {
    fun emit(tx: Transaction)
}
