package io.github.siddharthjaswal.logpose.settings

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.CorrelationKeys

/**
 * Where a project's correlation keys live between sessions.
 *
 * Project-scoped, unlike the endpoint mutes: `order_id` is gandalf's vocabulary, not LogPose's
 * and not the IDE's, so two projects open side by side must not share (or overwrite) each other's
 * keys. It reaches this through a [KeyValueStore] like the MCP token and the mock rules do — but
 * both halves back that store with the **same** `.logpose/correlation.properties`
 * ([FileKeyValueStore.sharedCorrelation]) rather than the IDE's private `PropertiesComponent`, so a
 * vocabulary configured in the tool window is the one the daemon reads, and vice versa. See
 * [migrateIfNeeded] for the one-time move off the old private storage.
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

    /**
     * One-time move of a project's vocabulary from [from] to [to].
     *
     * Correlation keys used to live only in the IDE's private `PropertiesComponent` (or, for a
     * daemon, hand-seeded into `daemon.properties`); they now live in the shared
     * `.logpose/correlation.properties` both halves read. This copies whatever the old store held
     * into the new one **once** — guarded on [to] being empty, so it never clobbers keys already
     * edited through the shared file, and it is idempotent: after the first run [to] is non-empty
     * and every later call is a no-op. The old store is left untouched (nothing is lost if a user
     * downgrades). Pure over [KeyValueStore], so it is tested without an IDE or a file.
     */
    fun migrateIfNeeded(from: KeyValueStore, to: KeyValueStore) {
        // Already populated (or explicitly configured) in the shared file — leave it alone.
        if (to.get(KEYS) != null || to.getBoolean(CONFIGURED, false)) return
        val existing = from.get(KEYS)
        val wasConfigured = from.getBoolean(CONFIGURED, false)
        // Nothing worth moving — a project that never opened the dialog stays blank, and the shared
        // file is not created until the user actually configures keys.
        if (existing == null && !wasConfigured) return
        if (existing != null) to.set(KEYS, existing)
        if (wasConfigured) to.setBoolean(CONFIGURED, true, false)
    }
}
