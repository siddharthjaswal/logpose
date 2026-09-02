package io.github.siddharthjaswal.logpose.settings

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.CorrelationKeys

/**
 * Where a project's correlation keys live between sessions.
 *
 * Project-scoped, unlike the endpoint mutes: `order_id` is gandalf's vocabulary, not LogPose's
 * and not the IDE's, so two projects open side by side must not share (or overwrite) each other's
 * keys. Same storage the MCP token and the mock rules use — a project-scoped [KeyValueStore].
 *
 * This is the only file in the correlation feature that needs somewhere to persist. Everything the
 * key set *means* — what a valid name is, which spellings are the same key, how a list round-trips
 * — is [CorrelationKeys], and is tested without an IDE.
 */
object CorrelationSettings {

    private const val KEYS = "logpose.correlation.keys"
    private const val CONFIGURED = "logpose.correlation.configured"

    fun keys(store: KeyValueStore): List<CorrelationKey> = CorrelationKeys.parse(store.get(KEYS))

    fun setKeys(store: KeyValueStore, keys: List<CorrelationKey>) {
        val serialized = CorrelationKeys.serialize(keys)
        store.set(KEYS, serialized.ifEmpty { null })
        // Separate from the list itself: a user who opened the dialog and ticked nothing has still
        // seen the suggestions, and re-seeding them on every open would undo that decision.
        store.setBoolean(CONFIGURED, true, false)
    }

    /**
     * Whether this project has ever been through the keys dialog — the "seed with suggestions"
     * gate from PRD §4.1. Suggestions seed a *blank* vocabulary once; after that they're offered
     * on demand, so a key the user deliberately removed doesn't come back next time.
     */
    fun configured(store: KeyValueStore): Boolean = store.getBoolean(CONFIGURED, false)
}
