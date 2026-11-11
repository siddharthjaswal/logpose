package io.github.siddharthjaswal.logpose.appcontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The orchestration, step by step, against a scripted device. What matters most is the order:
 * look before touching, stop before changing, and always relaunch.
 */
class AppControlTest {

    private val pkg = "com.acme.app.debug"
    private val serial = "emulator-5580"
    private val component = "com.acme.app.debug/com.acme.app.MainActivity"

    /** Answers by the command's prefix; records every line it was asked to run. */
    private class FakeShell(private val answers: Map<String, AppShell.Result>) : AppShell {
        val ran = mutableListOf<String>()
        override fun run(serial: String, line: String, timeoutMillis: Long): AppShell.Result {
            ran += line
            return answers.entries.firstOrNull { line.startsWith(it.key) }?.value ?: AppShell.Result(0, "")
        }
    }

    private fun ok(out: String = "") = AppShell.Result(0, out)
    private fun fail(out: String, exit: Int = 1) = AppShell.Result(exit, out)

    private fun device(vararg overrides: Pair<String, AppShell.Result>) = FakeShell(
        mapOf(
            "pm path" to ok("package:/data/app/~~x/com.acme.app.debug-1/base.apk"),
            "getprop ro.build.version.sdk" to ok("34"),
            "cmd package resolve-activity" to ok("priority=0 preferredOrder=0 match=0x108000\n$component"),
            "am start" to ok("Starting: Intent { cmp=$component }"),
            "pm clear" to ok("Success"),
            "if [ -d" to ok("logpose:external-cleared"),
        ) + overrides,
    )

    private fun verbs(shell: FakeShell) = shell.ran.map { it.substringBefore(" $pkg").substringBefore(" '") }

    @Test
    fun `restart stops the app, then launches its resolved launcher activity`() {
        val shell = device()
        val progress = mutableListOf<String>()
        val r = AppControl(shell).run(AppAction.RESTART, serial, pkg, progress = { progress += it })

        assertTrue(r.ok, r.error)
        assertEquals("am start -n $component", r.launchedVia)
        assertEquals(34, r.sdk)
        assertEquals(
            listOf("pm path", "getprop ro.build.version.sdk", "am force-stop", "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER", "am start -n"),
            verbs(shell),
        )
        assertEquals(listOf("Checking $pkg…", "Stopping…", "Launching…"), progress)
        assertEquals("Restarted by LogPose", r.markerTitle)
    }

    @Test
    fun `an unresolvable launcher falls back to monkey`() {
        val shell = device("cmd package resolve-activity" to ok("No activity found"), "monkey" to ok("Events injected: 1"))
        val r = AppControl(shell).run(AppAction.RESTART, serial, pkg)
        assertTrue(r.ok)
        assertEquals("monkey", r.launchedVia)
        assertEquals("monkey -p $pkg -c android.intent.category.LAUNCHER 1", shell.ran.last())
    }

    @Test
    fun `an am start error falls back to monkey too`() {
        val shell = device("am start" to ok("Error: Activity class {x} does not exist."), "monkey" to ok("Events injected: 1"))
        assertEquals("monkey", AppControl(shell).run(AppAction.RESTART, serial, pkg).launchedVia)
    }

    @Test
    fun `an app with no launcher is reported as stopped but not relaunched`() {
        val shell = device("cmd package resolve-activity" to ok("No activity found"), "monkey" to ok("** No activities found to run, monkey aborted."))
        val r = AppControl(shell).run(AppAction.RESTART, serial, pkg)
        assertEquals(AppControl.Failure.LAUNCH_FAILED, r.failure)
        assertTrue(r.stopped)
        assertFalse(r.launched)
    }

    @Test
    fun `clear cache on a debuggable build empties the caches between stop and launch`() {
        val shell = device()
        var markerAt = -1
        val r = AppControl(shell).run(AppAction.CLEAR_CACHE, serial, pkg, beforeLaunch = { markerAt = shell.ran.size })

        assertTrue(r.ok, r.error)
        assertTrue(r.cleared)
        assertEquals(AppOutput.ExternalCache.CLEARED, r.externalCache)
        assertEquals(
            listOf(
                "pm path", "getprop ro.build.version.sdk", "run-as", "am force-stop", "run-as",
                "if [ -d /sdcard/Android/data/com.acme.app.debug/cache ]; then rm -rf /sdcard/Android/data/com.acme.app.debug/cache/* /sdcard/Android/data/com.acme.app.debug/cache/.[!.]* && echo logpose:external-cleared; else echo logpose:external-absent; fi",
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER", "am start -n",
            ).map { it.substringBefore(" $pkg") },
            verbs(shell),
        )
        assertEquals(6, markerAt, "the marker is recorded after the clear and before the launch")
        assertEquals("Cache cleared", r.markerTitle)
    }

    @Test
    fun `clear cache on a release build changes nothing and offers the full clear`() {
        val shell = device("run-as" to fail("run-as: package not debuggable: $pkg"))
        val r = AppControl(shell).run(AppAction.CLEAR_CACHE, serial, pkg)

        assertEquals(AppControl.Failure.NOT_DEBUGGABLE, r.failure)
        assertTrue(r.offerClearAll)
        assertFalse(r.stopped, "a release build is reported before the app is touched")
        assertTrue(shell.ran.none { it.startsWith("am force-stop") || it.startsWith("pm clear") }, "never silently escalated")
        assertTrue(r.error!!.contains("Nothing was changed"))
    }

    @Test
    fun `clear all data with re-grant reads permissions first and grants them back after the clear`() {
        val dumpsys = """
            User 0: ceDataInode=1 installed=true
              runtime permissions:
                android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
                android.permission.CAMERA: granted=true, flags=[ USER_SET ]
        """.trimIndent()
        val shell = device(
            "dumpsys package" to ok(dumpsys),
            "pm grant $pkg android.permission.CAMERA" to fail("Exception occurred while executing 'grant':\njava.lang.SecurityException: nope", 255),
        )
        val r = AppControl(shell).run(AppAction.CLEAR_DATA, serial, pkg, AppControl.Options(regrantPermissions = true))

        assertTrue(r.ok, r.error)
        assertTrue(r.cleared)
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), r.regranted)
        assertEquals(listOf("android.permission.CAMERA"), r.regrantFailed)
        val order = verbs(shell)
        assertTrue(order.indexOf("dumpsys package") < order.indexOf("am force-stop"))
        assertTrue(order.indexOf("am force-stop") < order.indexOf("pm clear"))
        assertTrue(order.indexOf("pm clear") < order.indexOf("pm grant"))
        assertEquals("App data cleared", r.markerTitle)
    }

    @Test
    fun `clear all data without re-grant never reads dumpsys`() {
        val shell = device()
        AppControl(shell).run(AppAction.CLEAR_DATA, serial, pkg)
        assertTrue(shell.ran.none { it.startsWith("dumpsys") || it.startsWith("pm grant") })
    }

    @Test
    fun `a failed pm clear still relaunches the app, and says the clear failed`() {
        val shell = device("pm clear" to ok("Failed"))
        val r = AppControl(shell).run(AppAction.CLEAR_DATA, serial, pkg)
        assertEquals(AppControl.Failure.CLEAR_FAILED, r.failure)
        assertFalse(r.cleared)
        assertTrue(r.launched)
        assertEquals("Restarted by LogPose", r.markerTitle, "the marker says what really happened")
    }

    @Test
    fun `a package that isn't installed is reported before anything runs`() {
        val shell = device("pm path" to fail(""))
        val r = AppControl(shell).run(AppAction.RESTART, serial, pkg)
        assertEquals(AppControl.Failure.NOT_INSTALLED, r.failure)
        assertEquals("$pkg isn't installed on $serial.", r.error)
        assertEquals(listOf("pm path $pkg"), shell.ran)
    }

    @Test
    fun `adb's own failures stop the run with a plain reason`() {
        val offline = AppControl(device("pm path" to fail("error: device offline"))).run(AppAction.RESTART, serial, pkg)
        assertEquals(AppControl.Failure.OFFLINE, offline.failure)
        assertTrue(offline.error!!.contains("offline"))
        val unauthorized = AppControl(device("pm path" to fail("error: device unauthorized."))).run(AppAction.RESTART, serial, pkg)
        assertEquals(AppControl.Failure.UNAUTHORIZED, unauthorized.failure)
        assertTrue(unauthorized.error!!.contains("USB-debugging"))
        val gone = AppControl(device("pm path" to fail("error: device 'emulator-5580' not found"))).run(AppAction.RESTART, serial, pkg)
        assertEquals(AppControl.Failure.DEVICE_NOT_FOUND, gone.failure)
    }

    @Test
    fun `an invalid package never reaches the shell`() {
        val shell = device()
        val r = AppControl(shell).run(AppAction.CLEAR_DATA, serial, "com.acme;reboot")
        assertEquals(AppControl.Failure.INVALID_PACKAGE, r.failure)
        assertEquals(emptyList<String>(), shell.ran)
        assertNull(r.launchedVia)
    }
}
