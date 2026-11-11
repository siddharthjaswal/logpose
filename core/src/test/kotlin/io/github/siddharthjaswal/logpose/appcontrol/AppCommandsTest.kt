package io.github.siddharthjaswal.logpose.appcontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The exact device command lines, and reading what real devices print back. */
class AppCommandsTest {

    private val pkg = "in.shadowfax.gandalf.debug"

    // ---- builders ----------------------------------------------------------------------------

    @Test
    fun `every command line is exactly what goes over adb shell`() {
        assertEquals("pm path $pkg", AppCommands.packagePath(pkg))
        assertEquals("am force-stop $pkg", AppCommands.forceStop(pkg))
        assertEquals(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg",
            AppCommands.resolveLauncher(pkg),
        )
        assertEquals(
            "am start -n 'in.shadowfax.gandalf.debug/in.shadowfax.gandalf.MainActivity' -a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
            AppCommands.start("in.shadowfax.gandalf.debug/in.shadowfax.gandalf.MainActivity"),
        )
        assertEquals("monkey -p $pkg -c android.intent.category.LAUNCHER 1", AppCommands.monkeyLaunch(pkg))
        assertEquals("run-as $pkg true", AppCommands.runAsProbe(pkg))
        assertEquals(
            "run-as $pkg sh -c 'rm -rf cache/* cache/.[!.]* code_cache/* code_cache/.[!.]*'",
            AppCommands.clearInternalCache(pkg),
        )
        assertEquals(
            "if [ -d /sdcard/Android/data/$pkg/cache ]; then rm -rf /sdcard/Android/data/$pkg/cache/* " +
                "/sdcard/Android/data/$pkg/cache/.[!.]* && echo logpose:external-cleared; else echo logpose:external-absent; fi",
            AppCommands.clearExternalCache(pkg),
        )
        assertEquals("pm clear $pkg", AppCommands.pmClear(pkg))
        assertEquals("dumpsys package $pkg", AppCommands.dumpsysPackage(pkg))
        assertEquals("pm grant $pkg android.permission.CAMERA", AppCommands.grant(pkg, "android.permission.CAMERA"))
        assertEquals("getprop ro.build.version.sdk", AppCommands.sdkLevel())
    }

    @Test
    fun `the debug suffix is kept exactly`() {
        assertTrue(AppCommands.isValidPackage("in.shadowfax.gandalf.debug"))
        assertTrue(AppCommands.forceStop("in.shadowfax.gandalf.debug").endsWith(" in.shadowfax.gandalf.debug"))
    }

    @Test
    fun `nothing a shell treats specially reaches a command`() {
        for (bad in listOf("com.acme;reboot", "com.acme && rm -rf /", "com", "com.acme app", "\$(id).x", "com.acme'", "", ".com.acme", "com..acme", "com.1acme"))
            assertFalse(AppCommands.isValidPackage(bad), bad)
        assertThrows<IllegalArgumentException> { AppCommands.pmClear("com.acme;reboot") }
        assertThrows<IllegalArgumentException> { AppCommands.start("com.acme/.Main';reboot;'") }
        assertThrows<IllegalArgumentException> { AppCommands.grant("com.acme", "android.permission.CAMERA;reboot") }
        assertTrue(AppCommands.isValidComponent("com.acme/.MainActivity"))
        assertTrue(AppCommands.isValidComponent("com.acme/com.acme.Outer\$Inner"))
    }

    // ---- parsing ------------------------------------------------------------------------------

    @Test
    fun `resolve-activity --brief gives the component on its last line`() {
        val api34 = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
            "in.shadowfax.gandalf.debug/in.shadowfax.gandalf.ui.SplashActivity\n"
        assertEquals("in.shadowfax.gandalf.debug/in.shadowfax.gandalf.ui.SplashActivity", AppOutput.launcherComponent(api34))
        assertEquals("com.acme/.Main", AppOutput.launcherComponent("com.acme/.Main"))
        assertNull(AppOutput.launcherComponent("No activity found\n"))
        assertNull(AppOutput.launcherComponent(""))
        // An older `cmd` that doesn't know --brief prints its usage instead: fall back to monkey.
        assertNull(AppOutput.launcherComponent("Error: Unknown option: --brief\n"))
    }

    @Test
    fun `am start failures are read from the text, not only the exit status`() {
        assertTrue(AppOutput.startSucceeded("Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] cmp=com.acme/.Main }", 0))
        assertFalse(AppOutput.startSucceeded("Starting: Intent { cmp=com.acme/.Nope }\nError type 3\nError: Activity class {com.acme/com.acme.Nope} does not exist.", 0))
        assertFalse(AppOutput.startSucceeded("Exception occurred while executing 'start':\njava.lang.SecurityException: Permission Denial", 255))
        assertFalse(AppOutput.startSucceeded("", 1))
    }

    @Test
    fun `monkey says whether it injected the launch`() {
        assertTrue(AppOutput.monkeySucceeded("  bash arg: -p\n  bash arg: com.acme\nEvents injected: 1\n## Network stats: elapsed time=31ms"))
        assertFalse(AppOutput.monkeySucceeded("** No activities found to run, monkey aborted."))
    }

    @Test
    fun `run-as failures are recognised across releases`() {
        assertEquals(AppOutput.RunAsFailure.NOT_DEBUGGABLE, AppOutput.runAsFailure("run-as: package not debuggable: com.acme", 1))
        assertEquals(AppOutput.RunAsFailure.NOT_DEBUGGABLE, AppOutput.runAsFailure("run-as: Package 'com.acme' is not debuggable", 1))
        assertEquals(AppOutput.RunAsFailure.UNKNOWN_PACKAGE, AppOutput.runAsFailure("run-as: unknown package: com.acme", 1))
        assertEquals(AppOutput.RunAsFailure.UNKNOWN_PACKAGE, AppOutput.runAsFailure("run-as: Package 'com.acme' is unknown", 1))
        assertEquals(AppOutput.RunAsFailure.NOT_AN_APP, AppOutput.runAsFailure("run-as: package is not an application: com.acme", 1))
        assertEquals(AppOutput.RunAsFailure.OTHER, AppOutput.runAsFailure("run-as: Could not set capabilities: Operation not permitted", 1))
        assertEquals(AppOutput.RunAsFailure.OTHER, AppOutput.runAsFailure("", 1))
        assertNull(AppOutput.runAsFailure("", 0))
    }

    @Test
    fun `pm clear succeeds only on Success`() {
        assertTrue(AppOutput.pmClearSucceeded("Success\n"))
        assertFalse(AppOutput.pmClearSucceeded("Failed\n"))
        assertFalse(AppOutput.pmClearSucceeded("Exception occurred while executing 'clear':\njava.lang.SecurityException: PID 1234 does not have permission"))
        assertFalse(AppOutput.pmClearSucceeded(""))
    }

    @Test
    fun `the external cache step reports which case it was`() {
        assertEquals(AppOutput.ExternalCache.CLEARED, AppOutput.externalCache("logpose:external-cleared\n"))
        assertEquals(AppOutput.ExternalCache.ABSENT, AppOutput.externalCache("logpose:external-absent\n"))
        assertEquals(AppOutput.ExternalCache.UNREACHABLE, AppOutput.externalCache("rm: /sdcard/Android/data/com.acme/cache/x: Permission denied"))
    }

    @Test
    fun `pm grant succeeds silently and fails loudly`() {
        assertTrue(AppOutput.grantSucceeded("", 0))
        assertFalse(AppOutput.grantSucceeded("Exception occurred while executing 'grant':\njava.lang.SecurityException: Permission android.permission.X requested by com.acme is not a changeable permission type", 255))
    }

    @Test
    fun `granted runtime permissions are read from the first user's block, minus system-fixed ones`() {
        val dumpsys = """
            Packages:
              Package [com.acme] (d1e2f3):
                userId=10190
                requested permissions:
                  android.permission.CAMERA
                  android.permission.POST_NOTIFICATIONS
                install permissions:
                  android.permission.INTERNET: granted=true
                User 0: ceDataInode=12345 installed=true hidden=false suspended=false
                  gids=[3003]
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
                    android.permission.CAMERA: granted=false, flags=[ USER_SET ]
                    android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SET|REVOKE_WHEN_REQUESTED ]
                    android.permission.READ_PHONE_STATE: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT ]
                  disabledComponents:
                User 10: ceDataInode=0 installed=true
                  runtime permissions:
                    android.permission.CAMERA: granted=true, flags=[ USER_SET ]
        """.trimIndent()
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_FINE_LOCATION"),
            AppOutput.grantedRuntimePermissions(dumpsys),
        )
        assertEquals(emptyList<String>(), AppOutput.grantedRuntimePermissions("Unable to find package: com.acme"))
    }

    @Test
    fun `adb's own failures are told apart from the device's output`() {
        assertEquals(AppOutput.AdbProblem.OFFLINE, AppOutput.adbProblem("error: device offline", 1))
        assertEquals(AppOutput.AdbProblem.UNAUTHORIZED, AppOutput.adbProblem("error: device unauthorized.\nThis adb server's \$ADB_VENDOR_KEYS is not set", 1))
        assertEquals(AppOutput.AdbProblem.NOT_FOUND, AppOutput.adbProblem("error: device 'emulator-5580' not found", 1))
        assertEquals(AppOutput.AdbProblem.NOT_FOUND, AppOutput.adbProblem("adb: no devices/emulators found", 1))
        assertEquals(AppOutput.AdbProblem.TIMEOUT, AppOutput.adbProblem("", null))
        assertNull(AppOutput.adbProblem("Success", 0))
        assertNull(AppOutput.adbProblem("run-as: package not debuggable: com.acme", 1))
    }
}
