package io.github.siddharthjaswal.logpose.ui

import com.intellij.ide.util.PropertiesComponent
import io.github.siddharthjaswal.logpose.model.Transaction

/**
 * Persistent set of "muted" endpoints. Muted transactions are still shown, but
 * faded — a way to push noisy polling endpoints (heartbeats, location pings) into
 * the background without losing them.
 *
 * Stored via [PropertiesComponent] (application level), so mutes survive IDE restarts.
 * Endpoints are normalized so muting one rider's `/app/v3/79096/location/` also mutes
 * every other id's location call.
 */
object MutedEndpoints {

    private const val KEY = "io.github.siddharthjaswal.logpose.mutedEndpoints"
    private const val SEP = "\n"

    private val props get() = PropertiesComponent.getInstance()

    fun patterns(): Set<String> =
        props.getValue(KEY)?.split(SEP)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** Collapse all-numeric path segments to `#` so dynamic ids don't fragment a mute. */
    fun normalize(path: String): String =
        path.split("/").joinToString("/") { seg ->
            if (seg.isNotEmpty() && seg.all(Char::isDigit)) "#" else seg
        }

    /** The canonical mute key for a transaction (normalized path, falling back to URL). */
    fun keyOf(tx: Transaction): String =
        normalize(tx.request.path.ifBlank { tx.request.url })

    fun isMuted(tx: Transaction): Boolean = patterns().contains(keyOf(tx))

    /** Mutes if not muted, unmutes if already muted. Returns the new muted state. */
    fun toggle(tx: Transaction): Boolean {
        val key = keyOf(tx)
        val set = patterns().toMutableSet()
        val nowMuted = set.add(key)
        if (!nowMuted) set.remove(key)
        save(set)
        return nowMuted
    }

    fun clearAll() = save(emptySet())

    private fun save(set: Set<String>) {
        if (set.isEmpty()) props.unsetValue(KEY) else props.setValue(KEY, set.joinToString(SEP))
    }
}
