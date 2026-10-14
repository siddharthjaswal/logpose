package io.github.siddharthjaswal.logpose.desktop.emulators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The flag builder against `emulator -help` captured from emulator 37.2.12 on the dev Mac. */
class LaunchPlanTest {
    private val help = LaunchPlanTest::class.java.getResource("/emulators/emulator-help-37.2.12.txt")!!.readText()
    private val supported = LaunchPlan.supportedFlags(help)

    @Test fun `the captured help lists every flag a lean launch uses`() {
        for (f in listOf("-avd", "-port", "-memory", "-gpu", "-no-snapshot-save", "-no-snapshot-load", "-no-boot-anim", "-lowram", "-read-only")) {
            assertTrue(f in supported, "$f in emulator -help")
        }
        // Description text never becomes a flag.
        assertFalse("-no-audio'" in supported)
        assertFalse(supported.any { it.contains('<') })
    }

    @Test fun `a lean launch with every option`() {
        val plan = LaunchPlan.build(LaunchPlan.Options("Pixel_9a", 5562, 1536, coldBoot = true, lowRam = true), supported)
        assertEquals(
            listOf("-avd", "Pixel_9a", "-port", "5562", "-memory", "1536", "-gpu", "host", "-no-snapshot-save", "-no-snapshot-load", "-no-boot-anim", "-lowram"),
            plan.args,
        )
        assertEquals(emptyList<String>(), plan.skipped)
        assertEquals("emulator -avd Pixel_9a -port 5562 -memory 1536 -gpu host -no-snapshot-save -no-snapshot-load -no-boot-anim -lowram", plan.commandLine("emulator"))
    }

    @Test fun `warm boot without -lowram leaves both out`() {
        val plan = LaunchPlan.build(LaunchPlan.Options("Pixel_9a", 5554, 2048, coldBoot = false, lowRam = false), supported)
        assertEquals(listOf("-avd", "Pixel_9a", "-port", "5554", "-memory", "2048", "-gpu", "host", "-no-snapshot-save", "-no-boot-anim"), plan.args)
    }

    @Test fun `an emulator without a flag never gets it`() {
        val old = help.lineSequence().filterNot { it.trimStart().startsWith("-lowram") || it.trimStart().startsWith("-no-boot-anim") }.joinToString("\n")
        val plan = LaunchPlan.build(LaunchPlan.Options("A", 5554, 1536, coldBoot = true, lowRam = true), LaunchPlan.supportedFlags(old))
        assertFalse("-lowram" in plan.args)
        assertFalse("-no-boot-anim" in plan.args)
        assertEquals(listOf("-no-boot-anim", "-lowram"), plan.skipped)
        // -avd and -port are never conditional: they pin which AVD and which serial.
        assertEquals(listOf("-avd", "A", "-port", "5554"), plan.args.take(4))
    }

    @Test fun `the RAM floor is read from the emulator's own log line`() {
        val log = "INFO         | Duplicate loglines will be removed\nINFO         | Increasing RAM size to 2560MB\nINFO         |   Checking: hasSufficientHwGpu\n"
        assertEquals(2560, LaunchPlan.ramFloorFromLog(log))
        assertEquals(null, LaunchPlan.ramFloorFromLog("INFO | boot completed"))
    }
}
