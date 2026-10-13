package io.github.siddharthjaswal.logpose.desktop.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlatformToolsTest {

    /** Trimmed from the real repository2-3.xml (Oct 2026), plus a preview-channel package before it. */
    private val manifest = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <channel id="channel-0">stable</channel>
          <channel id="channel-2">dev</channel>
          <remotePackage path="platform-tools">
            <revision><major>38</major><minor>0</minor><micro>0</micro></revision>
            <channelRef ref="channel-2"/>
            <archives>
              <archive><complete><size>1</size><checksum type="sha1">ffff</checksum><url>platform-tools_r38.0.0-rc1-darwin.zip</url></complete><host-os>macosx</host-os></archive>
            </archives>
          </remotePackage>
          <remotePackage path="platform-tools">
            <type-details xsi:type="generic:genericDetailsType"/>
            <revision><major>37</major><minor>0</minor><micro>1</micro></revision>
            <display-name>Android SDK Platform-Tools</display-name>
            <channelRef ref="channel-0"/>
            <archives>
              <archive><complete><size>9054187</size><checksum type="sha1">477254aa5f903c15cf51001717bdf347fb6b53e0</checksum><url>platform-tools_r37.0.1-linux.zip</url></complete><host-os>linux</host-os></archive>
              <archive><complete><size>16110554</size><checksum type="sha1">6AE73F4DE6452DC57E62EC02B68EED92A4C21661</checksum><url>platform-tools_r37.0.1-darwin.zip</url></complete><host-os>macosx</host-os></archive>
            </archives>
          </remotePackage>
          <remotePackage path="emulator">
            <channelRef ref="channel-0"/>
            <archives><archive><complete><size>5</size><checksum type="sha1">aaaa</checksum><url>emulator-darwin.zip</url></complete><host-os>macosx</host-os></archive></archives>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    @Test fun `picks the stable macOS platform-tools archive, not the preview one`() {
        val a = PlatformTools.parseManifest(manifest, "aarch64")!!
        assertEquals("platform-tools_r37.0.1-darwin.zip", a.fileName)
        assertEquals(16110554L, a.sizeBytes)
        assertEquals("6ae73f4de6452dc57e62ec02b68eed92a4c21661", a.sha1)
        assertEquals("37.0.1", a.revision)
        assertEquals("https://dl.google.com/android/repository/platform-tools_r37.0.1-darwin.zip", a.url)
    }

    @Test fun `an archive for another architecture is skipped`() {
        val darwin = "r37.0.1-darwin.zip</url></complete><host-os>macosx</host-os>"
        val xml = manifest.replace(darwin, "$darwin<host-arch>x64</host-arch>")
        check(xml != manifest)
        assertNull(PlatformTools.parseManifest(xml, "aarch64"))
        assertNotNull(PlatformTools.parseManifest(xml, "x64"))
    }

    @Test fun `a manifest url that leaves the repository is refused`() {
        val xml = manifest.replace("platform-tools_r37.0.1-darwin.zip", "https://evil.example/adb.zip")
        assertNull(PlatformTools.parseManifest(xml, "aarch64"))
    }

    @Test fun `garbage and doctype manifests parse to null`() {
        assertNull(PlatformTools.parseManifest("not xml"))
        assertNull(PlatformTools.parseManifest("""<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""))
    }

    @Test fun `sha1 matches the known digest`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PlatformTools.sha1Hex("abc".byteInputStream()))
    }

    @Test fun `verify accepts the exact file and names a size or checksum mismatch`(@TempDir dir: File) {
        val f = File(dir, "x.zip").apply { writeText("abc") }
        val good = PlatformTools.Archive("x.zip", 3, "A9993E364706816ABA3E25717850C26C9CD0D89D", "1")
        assertNull(PlatformTools.verify(f, good))
        assert(PlatformTools.verify(f, good.copy(sizeBytes = 4))!!.contains("bytes"))
        assert(PlatformTools.verify(f, good.copy(sha1 = "00"))!!.contains("Checksum mismatch"))
    }

    @Test fun `zip-slip entries are refused`(@TempDir dir: File) {
        assertNotNull(PlatformTools.safeEntryTarget(dir, "platform-tools/adb"))
        assertNull(PlatformTools.safeEntryTarget(dir, "../evil"))
        assertNull(PlatformTools.safeEntryTarget(dir, "platform-tools/../../evil"))
        assertNull(PlatformTools.safeEntryTarget(dir, "/etc/passwd"))
    }

    @Test fun `install unpacks platform-tools, marks adb executable and replaces an old copy`(@TempDir dir: File) {
        val zip = File(dir, "pt.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("platform-tools/")); z.closeEntry()
            z.putNextEntry(ZipEntry("platform-tools/adb")); z.write("#!/bin/sh\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("platform-tools/NOTICE.txt")); z.write("n".toByteArray()); z.closeEntry()
        }
        val support = File(dir, "support").apply { File(this, "platform-tools/stale").apply { parentFile.mkdirs(); writeText("old") } }
        val adb = PlatformTools.install(zip, support)
        assertEquals(File(support, "platform-tools/adb").path, adb.path)
        assert(adb.canExecute())
        assert(!File(support, "platform-tools/stale").exists())
        assert(!File(support, "platform-tools.staging").exists())
    }
}
