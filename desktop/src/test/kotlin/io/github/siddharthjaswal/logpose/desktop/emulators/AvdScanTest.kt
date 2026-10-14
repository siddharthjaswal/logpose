package io.github.siddharthjaswal.logpose.desktop.emulators

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Listing AVDs, and the image-type warnings. */
class AvdScanTest {
    @TempDir lateinit var root: File

    private fun ini(vararg kv: Pair<String, String>) = AvdIni.parse(kv.joinToString("\n") { "${it.first}=${it.second}" } + "\n")

    @Test fun `16 KB and Play Store images are recognised from tag and sysdir`() {
        assertTrue(ImageKind.is16k(ini("tag.id" to "google_apis_ps16k")))
        assertTrue(ImageKind.is16k(ini("image.sysdir.1" to "system-images/android-35/google_apis_ps16k/arm64-v8a/")))
        assertTrue(ImageKind.is16k(ini("tag.id" to "google_apis", "image.sysdir.1" to "system-images/android-36/page_size_16kb/x")))
        assertFalse(ImageKind.is16k(ini("tag.id" to "google_apis", "image.sysdir.1" to "system-images/android-36.1/google_apis/arm64-v8a/")))
        assertTrue(ImageKind.isPlayStore(ini("tag.id" to "google_apis_playstore")))
        assertTrue(ImageKind.isPlayStore(ini("PlayStore.enabled" to "true")))
        assertTrue(ImageKind.isPlayStore(ini("PlayStore.enabled" to "yes")))
        assertFalse(ImageKind.isPlayStore(ini("PlayStore.enabled" to "false", "tag.id" to "google_apis")))
    }

    @Test fun `warnings follow the image, Google APIs gets none`() {
        fun info(vararg kv: Pair<String, String>) = AvdInfo.from("X", root, ini(*kv), false)
        assertEquals(listOf(ImageKind.Warning.PAGE_16K), ImageKind.warnings(info("tag.id" to "google_apis_ps16k")))
        assertEquals(listOf(ImageKind.Warning.PLAY_STORE), ImageKind.warnings(info("tag.id" to "google_apis_playstore", "PlayStore.enabled" to "true")))
        assertEquals(emptyList<ImageKind.Warning>(), ImageKind.warnings(info("tag.id" to "google_apis", "PlayStore.enabled" to "false")))
        assertTrue(ImageKind.Warning.PAGE_16K.title.contains("won't take effect"))
        assertTrue(ImageKind.Warning.PLAY_STORE.detail.contains("Google APIs"))
    }

    @Test fun `AvdInfo reads API, tag, ABI and the memory keys`() {
        val a = AvdInfo.from(
            "Pixel_9a", root,
            ini(
                "avd.ini.displayname" to "Pixel 9a", "image.sysdir.1" to "system-images/android-36.1/google_apis/arm64-v8a/",
                "tag.id" to "google_apis", "tag.display" to "Google APIs", "abi.type" to "arm64-v8a",
                "hw.ramSize" to "2048M", "hw.gpu.mode" to "host", "vm.heapSize" to "512",
            ),
            false,
        )
        assertEquals("36.1", a.apiLevel)
        assertEquals("API 36.1 · Google APIs · arm64-v8a", a.imageLine)
        assertEquals("RAM 2048 MB · GPU host · heap 512 MB", a.configLine)
        assertEquals(2048, AvdInfo.parseMb("2G"))
        assertEquals(null, AvdInfo.parseMb("lots"))
    }

    @Test fun `scan follows the pointer ini and skips folders without one`() {
        val home = File(root, ".android/avd").apply { mkdirs() }
        // Studio's absolute path=
        val elsewhere = File(root, "disk/Pixel_9a.avd").apply { mkdirs() }
        File(elsewhere, "config.ini").writeText("hw.ramSize=2048\ntag.id=google_apis\n")
        File(home, "Pixel_9a.ini").writeText("avd.ini.encoding=UTF-8\npath=${elsewhere.path}\npath.rel=avd/Pixel_9a.avd\ntarget=android-36.1\n")
        // path.rel only
        File(home, "Small.avd").mkdirs()
        File(home, "Small.avd/config.ini").writeText("hw.ramSize=1024\n")
        File(home, "Small.ini").writeText("path.rel=avd/Small.avd\n")
        // a pointer to nothing, and Gradle's managed devices (a folder, no .ini)
        File(home, "Gone.ini").writeText("path=/nope/Gone.avd\n")
        File(home, "gradle-managed").mkdirs()
        val found = AvdScanner.scan(home)
        assertEquals(listOf("Pixel_9a", "Small"), found.map { it.name })
        assertEquals(elsewhere, found[0].dir)
        assertEquals(1024, found[1].ramMb)
    }

    @Test fun `avd home honours the SDK's environment variables`() {
        assertEquals(File("/x/avd"), AvdScanner.avdHome({ if (it == "ANDROID_AVD_HOME") "/x/avd" else null }, "/Users/q"))
        assertEquals(File("/u/avd"), AvdScanner.avdHome({ if (it == "ANDROID_USER_HOME") "/u" else null }, "/Users/q"))
        assertEquals(File("/Users/q/.android/avd"), AvdScanner.avdHome({ null }, "/Users/q"))
    }
}
