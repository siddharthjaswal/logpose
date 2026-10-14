package io.github.siddharthjaswal.logpose.desktop.emulators

import io.github.siddharthjaswal.logpose.desktop.wireless.FakeAdb
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The opt-in guest tweaks: what's allowed, what gets recorded, and that Undo reverses exactly that. */
class GuestTweaksTest {
    private val serial = "emulator-5562"

    @Test fun `the list never names Play services, WebView, Chrome, Bluetooth or a hand-off app`() {
        val overlap = GuestTweaks.ALL_PACKAGES.filter { it in GuestTweaks.NEVER }
        assertEquals(emptyList<String>(), overlap)
        assertEquals(GuestTweaks.ALL_PACKAGES.size, GuestTweaks.ALL_PACKAGES.toSet().size, "no duplicates")
        for (p in GuestTweaks.ALL_PACKAGES) {
            assertFalse(p.startsWith("com.google.android.gms"), p)
            assertFalse("webview" in p, p)
        }
        // Even a drifting list can't reach a protected package: plan filters through NEVER.
        assertFalse("com.google.android.gms" in GuestTweaks.plan(setOf("com.google.android.gms"), setOf("com.google.android.gms")))
    }

    @Test fun `plan takes only installed, enabled packages from the list`() {
        val installed = setOf("com.google.android.youtube", "com.android.printspooler", "com.android.traceur", "com.example.app")
        val enabled = setOf("com.google.android.youtube", "com.android.printspooler", "com.example.app")
        assertEquals(listOf("com.google.android.youtube", "com.android.printspooler"), GuestTweaks.plan(installed, enabled))
    }

    /** A device with packages, settings, and a `pm` that may refuse some packages. */
    private class Device(
        installed: Set<String>,
        val disabled: MutableSet<String> = mutableSetOf(),
        val settings: MutableMap<String, String> = mutableMapOf(),
        val refuseEnable: Set<String> = emptySet(),
    ) {
        val installed = installed.toMutableSet()
        val adb = FakeAdb { args ->
            val cmd = args.drop(3) // after -s <serial> shell
            when {
                cmd == listOf("pm", "list", "packages") -> this.installed.joinToString("\n") { "package:$it" }
                cmd == listOf("pm", "list", "packages", "-e") -> (this.installed - disabled).joinToString("\n") { "package:$it" }
                cmd.take(2) == listOf("pm", "disable-user") -> { disabled += cmd.last(); "Package ${cmd.last()} new state: disabled-user" }
                cmd.take(2) == listOf("pm", "enable") -> if (cmd.last() in refuseEnable) "Error: java.lang.SecurityException" else { disabled -= cmd.last(); "Package ${cmd.last()} new state: enabled" }
                cmd.take(2) == listOf("settings", "get") -> settings["${cmd[2]}/${cmd[3]}"] ?: "null"
                cmd.take(2) == listOf("settings", "put") -> { settings["${cmd[2]}/${cmd[3]}"] = cmd[4].removeSurrounding("'"); "" }
                cmd.take(2) == listOf("settings", "delete") -> { settings.remove("${cmd[2]}/${cmd[3]}"); "Deleted 1 rows" }
                else -> ""
            }
        }
    }

    @Test fun `apply records originals and only what it changed, and undo restores exactly that`() {
        val dev = Device(
            installed = setOf("com.google.android.youtube", "com.android.printspooler", "com.android.traceur", "com.google.android.gms", "com.google.android.webview"),
            disabled = mutableSetOf("com.android.traceur"), // the user had disabled it already
            settings = mutableMapOf("global/activity_manager_constants" to "max_phantom_processes=32"),
        )
        val store = KeyValueStore.InMemory()
        val t = GuestTweaker(dev.adb, store) { 42L }
        val r = t.apply(serial, "Pixel_9a") as GuestTweaker.Result.Done
        val rec = r.record!!
        assertEquals(listOf("com.google.android.youtube", "com.android.printspooler"), rec.packages)
        assertEquals(mapOf("global/activity_manager_constants" to "max_phantom_processes=32", "global/background_process_limit" to null), rec.settings)
        assertEquals(setOf("com.android.traceur", "com.google.android.youtube", "com.android.printspooler"), dev.disabled)
        assertEquals("max_cached_processes=4", dev.settings["global/activity_manager_constants"])
        assertEquals("4", dev.settings["global/background_process_limit"])
        assertFalse("com.google.android.gms" in dev.disabled || "com.google.android.webview" in dev.disabled)
        assertEquals(rec, GuestTweaks.load(store, "Pixel_9a"))
        // Never animations: avdslim's default-off scales aren't touched.
        assertFalse(dev.adb.calls.any { "window_animation_scale" in it })

        val u = t.undo(serial, "Pixel_9a")
        assertTrue(u is GuestTweaker.Result.Done, u.toString())
        assertEquals(setOf("com.android.traceur"), dev.disabled, "the user's own disable is left alone")
        assertEquals(mapOf("global/activity_manager_constants" to "max_phantom_processes=32"), dev.settings, "set back, and the unset key deleted")
        assertNull(GuestTweaks.load(store, "Pixel_9a"))
    }

    @Test fun `the record is written before the first change`() {
        val store = KeyValueStore.InMemory()
        var recordedBeforeDisable: GuestTweaks.Record? = null
        val inner = Device(setOf("com.google.android.youtube"))
        val adb = FakeAdb { args ->
            if (args.getOrNull(4) == "disable-user" && recordedBeforeDisable == null) recordedBeforeDisable = GuestTweaks.load(store, "A")
            inner.adb.run(args, 10)
        }
        GuestTweaker(adb, store).apply(serial, "A")
        assertNotNull(recordedBeforeDisable)
        assertEquals(listOf("com.google.android.youtube"), recordedBeforeDisable!!.packages)
    }

    @Test fun `a second apply keeps the first run's originals`() {
        val dev = Device(setOf("com.google.android.youtube", "com.android.printspooler"), settings = mutableMapOf("global/background_process_limit" to "8"))
        val store = KeyValueStore.InMemory()
        val t = GuestTweaker(dev.adb, store)
        t.apply(serial, "A")
        dev.installed += "com.android.bips"
        val second = (t.apply(serial, "A") as GuestTweaker.Result.Done).record!!
        assertEquals("8", second.settings["global/background_process_limit"], "not the slimmed 4")
        assertEquals(listOf("com.google.android.youtube", "com.android.printspooler", "com.android.bips"), second.packages)
        t.undo(serial, "A")
        assertEquals("8", dev.settings["global/background_process_limit"])
        assertEquals(emptySet<String>(), dev.disabled)
    }

    @Test fun `a package that won't re-enable stays recorded so Undo can run again`() {
        val dev = Device(setOf("com.google.android.youtube", "com.android.printspooler"), refuseEnable = setOf("com.android.printspooler"))
        val store = KeyValueStore.InMemory()
        val t = GuestTweaker(dev.adb, store)
        t.apply(serial, "A")
        val u = t.undo(serial, "A")
        assertTrue(u is GuestTweaker.Result.Failed)
        assertEquals(listOf("com.android.printspooler"), GuestTweaks.load(store, "A")!!.packages)
    }

    @Test fun `a guest that can't list packages changes nothing`() {
        val store = KeyValueStore.InMemory()
        val adb = FakeAdb { null }
        assertTrue(GuestTweaker(adb, store).apply(serial, "A") is GuestTweaker.Result.Failed)
        assertNull(GuestTweaks.load(store, "A"))
        assertEquals(1, adb.calls.size)
    }

    @Test fun `the summary is what captures carry`() {
        val r = GuestTweaks.Record("Pixel_9a", 1, listOf("a", "b"), emptyMap())
        assertEquals("Android slimmed by LogPose: 2 background packages disabled, cached processes capped at 4", r.summary())
    }
}
