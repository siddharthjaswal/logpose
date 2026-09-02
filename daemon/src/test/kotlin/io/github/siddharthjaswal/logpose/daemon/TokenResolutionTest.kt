package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `--token` → `$LOGPOSE_TOKEN` → the settings file → generated-and-saved, in that order. */
class TokenResolutionTest {

    private val saved = "logpose.mcp.token"

    @Test
    fun `the flag wins over everything`() {
        val store = KeyValueStore.InMemory(mutableMapOf(saved to "from-file"))
        assertEquals("from-flag", Daemon.resolveToken("from-flag", "from-env", store))
    }

    @Test
    fun `the env wins over the saved token`() {
        val store = KeyValueStore.InMemory(mutableMapOf(saved to "from-file"))
        assertEquals("from-env", Daemon.resolveToken(null, "from-env", store))
    }

    @Test
    fun `the saved token is used when neither is given`() {
        val store = KeyValueStore.InMemory(mutableMapOf(saved to "from-file"))
        assertEquals("from-file", Daemon.resolveToken(null, null, store))
    }

    @Test
    fun `a generated token is persisted so the printed command keeps working next run`() {
        val store = KeyValueStore.InMemory()
        val first = Daemon.resolveToken(null, null, store)
        assertTrue(first.length >= 16, "generated tokens must not be guessable: $first")
        assertEquals(first, store.get(saved))
        assertEquals(first, Daemon.resolveToken(null, null, store))
    }

    @Test
    fun `an explicit token is never written to disk`() {
        // It is the caller's to manage; persisting it would outlive the run that set it and
        // silently become the default for every later run.
        val store = KeyValueStore.InMemory()
        Daemon.resolveToken("ephemeral", null, store)
        assertNull(store.get(saved))
        Daemon.resolveToken(null, "from-env", store)
        assertNull(store.get(saved))
    }

    @Test
    fun `blank values fall through rather than authenticating nothing`() {
        val store = KeyValueStore.InMemory(mutableMapOf(saved to "from-file"))
        assertEquals("from-file", Daemon.resolveToken("", "  ", store))
        assertNotEquals("", Daemon.resolveToken("", "", KeyValueStore.InMemory()))
    }
}
