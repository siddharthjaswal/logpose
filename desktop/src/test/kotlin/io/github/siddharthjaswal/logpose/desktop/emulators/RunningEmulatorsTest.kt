package io.github.siddharthjaswal.logpose.desktop.emulators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The running-AVD mapping over output captured on the dev Mac (a Wi-Fi phone beside an emulator). */
class RunningEmulatorsTest {
    private val devices = """
        |List of devices attached
        |adb-00173359D000017-zABNgD._adb-tls-connect._tcp	device
        |emulator-5562	device
        |emulator-5554	offline
        |
        |""".trimMargin()

    private val ps = """
        |34042 2528624 /Users/aurora/Library/Android/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64 -avd Pixel_9a -port 5562 -no-snapshot-load -no-snapshot-save -memory 1536 -gpu host -no-boot-anim -lowram
        |34049   4816 /Users/aurora/Library/Android/sdk/emulator/crashpad_handler --no-rate-limit --monitor-pid=34042 --database=/tmp/android-aurora/emu-crash-37.2.12.db
        |34052  14768 /Users/aurora/Library/Android/sdk/emulator/netsimd --host-dns=192.168.1.1
        |41000 900000 /Users/aurora/Library/Android/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64 -netdelay none -netspeed full -avd Small_API_34 -qt-hide-window -grpc-use-token
        |  555   1200 /bin/zsh -c grep qemu-system-aarch64 -avd Pixel_9a
        |""".trimMargin()

    @Test fun `emulator serials skip phones and offline emulators`() {
        assertEquals(listOf("emulator-5562"), RunningEmulators.emulatorSerials(devices))
        assertEquals(listOf("emulator-5562", "emulator-5554"), RunningEmulators.allEmulatorSerials(devices))
    }

    @Test fun `emu avd name answers name then OK`() {
        assertEquals("Pixel_9a", RunningEmulators.parseAvdName("Pixel_9a\r\nOK\r\n"))
        assertNull(RunningEmulators.parseAvdName("KO: unknown command\r\n"))
        assertNull(RunningEmulators.parseAvdName("error: device offline"))
        assertNull(RunningEmulators.parseAvdName(null))
        assertNull(RunningEmulators.parseAvdName("OK\r\n"))
    }

    @Test fun `ps keeps qemu processes only, with their AVD and port`() {
        val procs = RunningEmulators.parsePs(ps)
        assertEquals(
            listOf(RunningEmulators.QemuProcess(34042, 2528624, "Pixel_9a", 5562), RunningEmulators.QemuProcess(41000, 900000, "Small_API_34", null)),
            procs,
        )
    }

    @Test fun `join matches by port, a Studio launch without -port, and a still-booting AVD`() {
        val procs = RunningEmulators.parsePs(ps)
        val r = RunningEmulators.join(mapOf("emulator-5562" to "Pixel_9a"), procs)
        assertEquals(RunningEmulators.Running("Pixel_9a", "emulator-5562", 34042), r.first { it.avd == "Pixel_9a" })
        // Small_API_34 has a qemu but adb hasn't seen it yet: running, no serial.
        assertEquals(RunningEmulators.Running("Small_API_34", null, 41000), r.first { it.avd == "Small_API_34" })
        // A Studio launch (no -port) once adb sees it on the default port.
        val studio = RunningEmulators.join(mapOf("emulator-5554" to "Small_API_34"), procs)
        assertEquals(41000, studio.first { it.avd == "Small_API_34" }.pid)
        // adb knows an AVD whose process we can't see (another user's?): running, no pid.
        assertEquals(RunningEmulators.Running("Other", "emulator-5570", null), RunningEmulators.join(mapOf("emulator-5570" to "Other"), emptyList()).single())
    }

    @Test fun `free port skips taken pairs`() {
        assertEquals(5554, RunningEmulators.freePort(emptyList()))
        assertEquals(5556, RunningEmulators.freePort(listOf(5554)))
        assertEquals(5560, RunningEmulators.freePort(listOf(5554, 5556, 5559)))
    }

    @Test fun `footprint is parsed from the real output, with ps RSS as the labelled fallback`() {
        val real = RunningEmulatorsTest::class.java.getResource("/emulators/footprint-qemu.txt")!!.readText()
        assertEquals(HostMemory(3467, HostMemory.Source.FOOTPRINT), HostMemory.parseFootprint(real))
        assertEquals(HostMemory(1229, HostMemory.Source.FOOTPRINT), HostMemory.parseFootprint("    phys_footprint: 1.2 GB\n"))
        assertEquals(HostMemory(2, HostMemory.Source.FOOTPRINT), HostMemory.parseFootprint("phys_footprint: 2097152\n"))
        assertNull(HostMemory.parseFootprint("footprint: no process"))
        assertEquals(HostMemory(2469, HostMemory.Source.RSS), HostMemory.fromRssKb(2528624))
        assertEquals("footprint · as Activity Monitor", HostMemory.Source.FOOTPRINT.label)
    }
}
