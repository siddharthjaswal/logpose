package io.github.siddharthjaswal.logpose.desktop.update

import io.github.siddharthjaswal.logpose.desktop.app.UpdateController
import io.github.siddharthjaswal.logpose.desktop.update.UpdateCheck.Version
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckTest {

    private fun v(s: String) = Version.parse(s) ?: error("unparseable $s")

    // ---- version comparison ---------------------------------------------------------------------

    @Test
    fun `versions compare numerically, not as text`() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.10") > v("1.0.2"))
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3+build.7")), "build metadata is ignored")
    }

    @Test
    fun `a pre-release sorts before its release, identifiers by semver rules`() {
        assertTrue(v("1.1.0-beta.1") < v("1.1.0"))
        assertTrue(v("1.1.0-beta.2") < v("1.1.0-beta.10"))
        assertTrue(v("1.1.0-alpha") < v("1.1.0-beta"))
        assertTrue(v("1.1.0-1") < v("1.1.0-alpha"), "numeric identifiers sort first")
        assertTrue(v("1.1.0-beta") < v("1.1.0-beta.1"))
    }

    @Test
    fun `junk is not a version`() {
        listOf("", "dev", "1.0", "1.0.0.0", "v1.0.0", "1.x.0", "1.0.0-").forEach { assertNull(Version.parse(it), it) }
    }

    // ---- tag filtering ----------------------------------------------------------------------------

    @Test
    fun `only desktop-v tags are Mac app releases`() {
        assertEquals(v("1.2.0"), UpdateCheck.versionOfTag("desktop-v1.2.0"))
        // The library's JitPack tags and the plugin's release tags share the release list.
        assertNull(UpdateCheck.versionOfTag("v1.7.1"))
        assertNull(UpdateCheck.versionOfTag("1.9.2"))
        assertNull(UpdateCheck.versionOfTag("desktop-1.2.0"))
        assertNull(UpdateCheck.versionOfTag("desktop-vnext"))
    }

    private fun release(tag: String, draft: Boolean = false, pre: Boolean = false, url: String = "https://github.com/siddharthjaswal/logpose/releases/tag/$tag") =
        """{"tag_name":"$tag","html_url":"$url","draft":$draft,"prerelease":$pre,"assets":[{"name":"x.dmg"}],"author":{"login":"sid"}}"""

    private val apiBody = "[" + listOf(
        release("v1.7.2"),                         // library / plugin
        release("desktop-v1.3.0", draft = true),   // not published
        release("desktop-v1.2.0-beta.1", pre = true),
        release("desktop-v1.1.0"),
        release("desktop-v1.0.1"),
        release("desktop-v9.0.0", url = "https://evil.example/releases/tag/desktop-v9.0.0"),
        release("desktop-vgarbage"),
    ).joinToString(",") + "]"

    @Test
    fun `parseReleases keeps published desktop releases on this repository only`() {
        val got = UpdateCheck.parseReleases(apiBody)
        assertEquals(listOf("desktop-v1.1.0", "desktop-v1.0.1"), got.map { it.tag })
        assertEquals("https://github.com/siddharthjaswal/logpose/releases/tag/desktop-v1.1.0", got.first().url)
    }

    @Test
    fun `malformed responses yield nothing, never throw`() {
        listOf("", "{}", "not json", """{"message":"API rate limit exceeded"}""", "[1, \"x\", null, {}]").forEach {
            assertEquals(emptyList<UpdateCheck.Release>(), UpdateCheck.parseReleases(it), it)
        }
    }

    @Test
    fun `newest is the highest strictly newer release, and nothing for a dev build`() {
        val releases = UpdateCheck.parseReleases(apiBody)
        assertEquals("desktop-v1.1.0", UpdateCheck.newest(releases, "1.0.0")?.tag)
        assertEquals("desktop-v1.1.0", UpdateCheck.newest(releases, "1.0.1")?.tag)
        assertNull(UpdateCheck.newest(releases, "1.1.0"))
        assertNull(UpdateCheck.newest(releases, "1.2.0"))
        assertNull(UpdateCheck.newest(releases, "dev"))
    }

    // ---- daily throttle ---------------------------------------------------------------------------

    @Test
    fun `a check is due once a day`() {
        val day = UpdateCheck.INTERVAL_MILLIS
        assertTrue(UpdateCheck.due(null, 1_000))
        assertFalse(UpdateCheck.due(1_000, 1_000 + day - 1))
        assertTrue(UpdateCheck.due(1_000, 1_000 + day))
        assertTrue(UpdateCheck.due(5 * day, day), "a clock set backwards does not silence checks forever")
    }

    private class Harness(var now: Long = 10L * UpdateCheck.INTERVAL_MILLIS, var body: String) {
        val settings = KeyValueStore.InMemory()
        var fetches = 0
        var opened: String? = null
        fun controller(version: String = "1.0.0") = UpdateController(
            CoroutineScope(Dispatchers.Unconfined), settings, version,
            fetch = { fetches++; body }, clock = { now }, io = Dispatchers.Unconfined, browse = { opened = it },
        )
    }

    @Test
    fun `the controller fetches at most once a day and remembers what it found across launches`() = runBlocking {
        val h = Harness(body = "[" + release("desktop-v1.1.0") + "]")
        val first = h.controller()
        assertEquals("desktop-v1.1.0", first.checkIfDue()?.tag)
        assertEquals(1, h.fetches)

        // Relaunch an hour later: no request, but the link is still there.
        h.now += 60 * 60 * 1000
        val second = h.controller()
        assertEquals("desktop-v1.1.0", second.ui.value.available?.tag)
        second.checkIfDue()
        assertEquals(1, h.fetches)

        // A day later it asks again — and the release is gone (pulled): so is the link.
        h.now += UpdateCheck.INTERVAL_MILLIS
        h.body = "[]"
        assertNull(second.checkIfDue())
        assertEquals(2, h.fetches)
        assertNull(second.ui.value.available)
    }

    @Test
    fun `a failed check is silent and retried next launch`() = runBlocking {
        val h = Harness(body = "")
        val c = UpdateController(
            CoroutineScope(Dispatchers.Unconfined), h.settings, "1.0.0",
            fetch = { h.fetches++; error("offline") }, clock = { h.now }, io = Dispatchers.Unconfined,
        )
        assertNull(c.checkIfDue())
        assertNull(h.settings.get(UpdateCheck.LAST_CHECK_KEY), "an unsuccessful check is not recorded")
        c.checkIfDue()
        assertEquals(2, h.fetches)
    }

    @Test
    fun `switched off means no request and no link, and dev builds never ask`() = runBlocking {
        val h = Harness(body = "[" + release("desktop-v1.1.0") + "]")
        val c = h.controller()
        c.setEnabled(false)
        h.fetches = 0
        assertNull(c.checkIfDue())
        assertEquals(0, h.fetches)
        assertEquals(false, h.settings.getBoolean(UpdateCheck.ENABLED_KEY, true))

        assertNull(h.controller(version = "dev").checkIfDue())
        assertEquals(0, h.fetches)
    }

    @Test
    fun `download opens the release page and nothing else`() = runBlocking {
        val h = Harness(body = "[" + release("desktop-v1.1.0") + "]")
        val c = h.controller()
        c.openDownload()
        assertNull(h.opened, "nothing to open before a check found something")
        c.checkIfDue()
        c.openDownload()
        assertEquals("https://github.com/siddharthjaswal/logpose/releases/tag/desktop-v1.1.0", h.opened)
    }
}
