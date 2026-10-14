package io.github.siddharthjaswal.logpose.desktop.emulators

import java.io.File

/**
 * One Android Virtual Device as its files describe it: `~/.android/avd/<name>.ini` points at the
 * `<name>.avd/` folder, whose `config.ini` holds what the emulator (and Android Studio's ▶) boots.
 */
data class AvdInfo(
    val name: String,
    val dir: File,
    val displayName: String,
    /** "36.1", "35" — from `image.sysdir.1` / `target` (`android-36.1`). */
    val apiLevel: String?,
    /** `tag.id`: google_apis, google_apis_playstore, google_apis_ps16k, … */
    val tagId: String?,
    val tagDisplay: String?,
    val abi: String?,
    val ramMb: Int?,
    val gpuMode: String?,
    val gpuEnabled: String?,
    val heapMb: Int?,
    val playStore: Boolean,
    val pageSize16k: Boolean,
    /** A Quick Boot snapshot exists (`snapshots/default_boot`) — the emulator may resume it instead of booting. */
    val hasQuickBootSnapshot: Boolean,
) {
    val configFile: File get() = File(dir, "config.ini")

    /** "API 36.1 · Google APIs · arm64-v8a" */
    val imageLine: String
        get() = listOfNotNull(apiLevel?.let { "API $it" }, tagDisplay ?: tagId, abi).joinToString(" · ")

    /** "RAM 2048 MB · GPU host · heap 512 MB" */
    val configLine: String
        get() = listOf(
            "RAM ${ramMb?.let { "$it MB" } ?: "default"}",
            "GPU ${gpuMode ?: "auto"}",
            "heap ${heapMb?.let { "$it MB" } ?: "default"}",
        ).joinToString(" · ")

    companion object {
        fun from(name: String, dir: File, ini: AvdIni, hasQuickBootSnapshot: Boolean): AvdInfo {
            val sysdir = ini["image.sysdir.1"].orEmpty()
            val tag = ini["tag.id"]
            val api = Regex("android-([0-9][0-9A-Za-z.]*)").find(sysdir)?.groupValues?.get(1)
                ?: ini["target"]?.let { Regex("android-([0-9][0-9A-Za-z.]*)").find(it)?.groupValues?.get(1) }
            return AvdInfo(
                name = name,
                dir = dir,
                displayName = ini["avd.ini.displayname"]?.takeIf { it.isNotBlank() } ?: name.replace('_', ' '),
                apiLevel = api,
                tagId = tag,
                tagDisplay = ini["tag.display"]?.takeIf { it.isNotBlank() },
                abi = ini["abi.type"],
                ramMb = parseMb(ini["hw.ramSize"]),
                gpuMode = ini["hw.gpu.mode"],
                gpuEnabled = ini["hw.gpu.enabled"],
                heapMb = parseMb(ini["vm.heapSize"]),
                playStore = ImageKind.isPlayStore(ini),
                pageSize16k = ImageKind.is16k(ini),
                hasQuickBootSnapshot = hasQuickBootSnapshot,
            )
        }

        /** `2048`, `2048M`, `2048MB`, `2G` → MB. */
        fun parseMb(v: String?): Int? {
            val s = v?.trim()?.uppercase() ?: return null
            val m = Regex("^([0-9]+)\\s*(M|MB|G|GB)?$").find(s) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return if (m.groupValues[2].startsWith("G")) n * 1024 else n
        }
    }
}

/**
 * What kind of system image an AVD boots, for the two warnings worth giving before anyone slims it
 * (avdslim's README draws the same lines; the reasons are restated here, not copied):
 *
 * - **16 KB page size** (`*_ps16k`): the emulator raises RAM to a 4 GB floor for these images, so a
 *   smaller `hw.ramSize` simply isn't honoured.
 * - **Google Play** (`*_playstore`, `PlayStore.enabled`): Play Store and Play Protect keep updating
 *   and scanning in the background, and the image refuses `adb root`. A Google APIs image has the
 *   same Firebase / FCM / Maps APIs without that load.
 */
object ImageKind {
    fun is16k(ini: AvdIni): Boolean {
        val tag = ini["tag.id"].orEmpty().lowercase()
        val sysdir = ini["image.sysdir.1"].orEmpty().lowercase()
        return "ps16k" in tag || "page_size_16kb" in tag || "16kb" in tag || "ps16k" in sysdir || "16kb" in sysdir
    }

    fun isPlayStore(ini: AvdIni): Boolean {
        val flag = ini["PlayStore.enabled"]?.lowercase()
        return flag == "true" || flag == "yes" || "playstore" in ini["tag.id"].orEmpty().lowercase()
    }

    enum class Warning(val title: String, val detail: String) {
        PAGE_16K(
            "16 KB page-size image — slimming won't take effect",
            "The emulator raises RAM to a 4 GB floor for 16 KB images, whatever config.ini says. " +
                "Unless you are testing 16 KB native alignment, create a standard (4 KB) Google APIs AVD instead.",
        ),
        PLAY_STORE(
            "Google Play image — heavier than it needs to be",
            "Play Store keeps updating and scanning in the background. A Google APIs image has the same " +
                "Firebase, FCM and Maps APIs and runs lighter.",
        ),
    }

    fun warnings(avd: AvdInfo): List<Warning> = listOfNotNull(
        Warning.PAGE_16K.takeIf { avd.pageSize16k },
        Warning.PLAY_STORE.takeIf { avd.playStore },
    )
}

/** Finds the AVDs on this Mac. Reads files only; never starts the emulator. */
object AvdScanner {

    /**
     * Where AVDs live, the way the SDK tools resolve it: `ANDROID_AVD_HOME`, else
     * `ANDROID_USER_HOME/avd`, else `ANDROID_EMULATOR_HOME/avd`, else `~/.android/avd`.
     */
    fun avdHome(env: (String) -> String? = System::getenv, home: String = System.getProperty("user.home")): File {
        env("ANDROID_AVD_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        env("ANDROID_USER_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it, "avd") }
        env("ANDROID_EMULATOR_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it, "avd") }
        return File(home, ".android/avd")
    }

    /**
     * Every `<name>.ini` in [avdHome] whose folder has a `config.ini`, sorted by name. The `path=`
     * in the ini wins (Studio writes it absolute); `path.rel` (relative to `~/.android`) and the
     * `<name>.avd` sibling are the fallbacks.
     */
    fun scan(avdHome: File): List<AvdInfo> {
        val inis = avdHome.listFiles { f -> f.isFile && f.name.endsWith(".ini") }.orEmpty()
        return inis.mapNotNull { pointer ->
            val name = pointer.name.removeSuffix(".ini")
            val p = runCatching { AvdIni.parse(pointer.readText()) }.getOrNull()
            val candidates = listOfNotNull(
                p?.get("path")?.let(::File),
                p?.get("path.rel")?.let { File(avdHome.parentFile, it) },
                File(avdHome, "$name.avd"),
            )
            val dir = candidates.firstOrNull { File(it, "config.ini").isFile } ?: return@mapNotNull null
            val ini = runCatching { AvdIni.parse(File(dir, "config.ini").readText()) }.getOrNull() ?: return@mapNotNull null
            AvdInfo.from(name, dir, ini, hasQuickBootSnapshot = File(dir, "snapshots/default_boot").isDirectory)
        }.sortedBy { it.name.lowercase() }
    }
}
