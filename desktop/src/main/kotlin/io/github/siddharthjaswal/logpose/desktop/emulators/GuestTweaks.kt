package io.github.siddharthjaswal.logpose.desktop.emulators

import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * *Also slim Android itself* — opt-in, off by default, fully reversible. A subset of avdslim's guest
 * changes, applied over adb to one running emulator and recorded **before** anything changes, per
 * AVD, in LogPose's settings, so *Undo Android tweaks* puts back exactly what was there.
 *
 * What it changes: [PACKAGES] go `pm disable-user` (only those installed and enabled, and only those
 * get recorded), and [SETTINGS] cap cached background processes. Package states live in the AVD's
 * user data, so they persist across reboots — Android Studio's launches of this AVD are slimmed too
 * until undone (a *Wipe data* also undoes them).
 *
 * What it never touches: Google Play services (FCM, Auth, Maps SDK), the WebView, Chrome, the Play
 * Store, Bluetooth's APK (Android 16 won't boot without it), and the system apps an app under test
 * hands off to — see [NEVER]. Animations stay at 1×.
 */
object GuestTweaks {

    /**
     * avdslim's standard list minus the *hand-off* apps: an app under test opens the dialer (call the
     * customer), Maps (navigate), the camera (proof-of-delivery photo), the photo picker, contacts,
     * SMS, mail, calendar, Drive and TTS through intents, and disabling them turns those taps into
     * ActivityNotFoundException — a behaviour change the user didn't opt into. Grouped for the UI.
     */
    val PACKAGES: Map<String, List<String>> = linkedMapOf(
        "Assistant & on-device intelligence" to listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.google.android.as.oss",
            "com.google.android.apps.bard",
            "com.google.android.settings.intelligence",
        ),
        "Privacy Sandbox & ad measurement" to listOf(
            "com.google.android.adservices.api",
            "com.google.mainline.adservices",
            "com.google.android.ondevicepersonalization.services",
            "com.google.android.federatedcompute",
        ),
        "Wellbeing, restore, car, glasses, health UI" to listOf(
            "com.google.android.apps.wellbeing",
            "com.google.android.apps.restore",
            "com.google.android.apps.pulse",
            "com.google.android.projection.gearhead",
            "com.google.android.glasses.companion",
            "com.google.android.glasses.core",
            "com.google.android.healthconnect.controller",
            "com.google.android.apps.safetyhub",
            "com.google.android.markup",
            "com.android.emulator.multidisplay",
        ),
        "Media apps nothing hands off to" to listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.google.android.videos",
            "com.google.android.music",
            "com.android.music",
            "com.android.DeviceAsWebcam",
        ),
        "Carrier, emergency & satellite" to listOf(
            "com.android.imsserviceentitlement",
            "com.android.carrierdefaultapp",
            "com.google.android.cellbroadcastservice",
            "com.android.cellbroadcastreceiver",
            "com.google.android.telephony.satellite",
            "com.android.stk",
            "com.android.emergency",
        ),
        "Accessibility services (not TTS)" to listOf(
            "com.google.android.marvin.talkback",
            "com.google.android.apps.accessibility.voiceaccess",
        ),
        "Printing" to listOf(
            "com.android.printspooler",
            "com.android.bips",
            "com.google.android.printservice.recommendation",
        ),
        "Feedback, tracing, screensavers, wallpapers, clock, calculator" to listOf(
            "com.google.android.feedback",
            "com.android.traceur",
            "com.android.dreams.basic",
            "com.android.dreams.phototable",
            "com.android.wallpaper.livepicker",
            "com.google.android.apps.wallpaper",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.android.bluetoothmidiservice",
        ),
    )

    val ALL_PACKAGES: List<String> get() = PACKAGES.values.flatten()

    /**
     * Never disabled, whatever a list says: FCM/Auth/Maps live in GMS; WebView and Chrome render
     * in-app web content; Bluetooth's APK is boot-critical on Android 16; the rest are the hand-off
     * apps above and the framework itself. [plan] filters through this even if [PACKAGES] drifts.
     */
    val NEVER: Set<String> = setOf(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.webview", "com.android.webview", "com.android.chrome", "com.google.android.trichromelibrary",
        "com.google.android.bluetooth", "com.android.bluetooth",
        "com.android.systemui", "com.android.phone", "com.android.shell", "com.android.settings",
        "com.google.android.ext.services", "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
        "com.google.android.dialer", "com.android.dialer", "com.google.android.contacts", "com.android.contacts",
        "com.google.android.apps.messaging", "com.android.mms", "com.google.android.apps.maps",
        "com.android.camera2", "com.android.cameraextensions", "com.google.android.apps.photos", "com.android.gallery3d",
        "com.google.android.gm", "com.google.android.apps.docs", "com.google.android.calendar", "com.android.calendar",
        "com.google.android.tts", "com.google.android.inputmethod.latin", "com.google.android.apps.nexuslauncher",
    )

    /**
     * Settings changed, with the value set. `activity_manager_constants` is read by the activity
     * manager (it caps cached background processes at 4, so a backgrounded app is killed sooner —
     * the "changes how your app behaves under memory pressure" in the toggle). avdslim also turns
     * off auto-sync, location, Bluetooth and animations and marks setup done; those change what the
     * app under test sees (location for a delivery app!) and are left alone.
     */
    val SETTINGS: List<Setting> = listOf(
        Setting("global", "activity_manager_constants", "max_cached_processes=4"),
        Setting("global", "background_process_limit", "4"),
    )

    data class Setting(val namespace: String, val name: String, val value: String) {
        val key: String get() = "$namespace/$name"
    }

    /** What was changed on one AVD — exactly what Undo reverses. A null setting value means "was unset". */
    @Serializable
    data class Record(
        val avd: String,
        val appliedAtMillis: Long,
        val packages: List<String> = emptyList(),
        val settings: Map<String, String?> = emptyMap(),
    ) {
        /** For the session header and the capture's device info. */
        fun summary(): String = "Android slimmed by LogPose: ${packages.size} background packages disabled, cached processes capped at 4"
    }

    /** The packages to disable: on the list, installed, currently enabled, and never in [NEVER]. */
    fun plan(installed: Set<String>, enabled: Set<String>): List<String> =
        ALL_PACKAGES.filter { it in installed && it in enabled && it !in NEVER }

    /** `pm list packages` output → package names. */
    fun parsePackages(out: String?): Set<String> = out.orEmpty().lineSequence()
        .map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
        .filter { it.isNotEmpty() }.toSet()

    /** `settings get` prints `null` for an unset key. */
    fun parseSetting(out: String?): String? = out?.trim()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private const val KEY_PREFIX = "logpose.desktop.emulators.guest."

    fun load(settings: KeyValueStore, avd: String): Record? =
        settings.get(KEY_PREFIX + avd)?.let { runCatching { json.decodeFromString(Record.serializer(), it) }.getOrNull() }

    fun save(settings: KeyValueStore, record: Record?, avd: String) =
        settings.set(KEY_PREFIX + avd, record?.let { json.encodeToString(Record.serializer(), it) })

    /** Quoted for the device shell — adb joins argv into one remote command line. */
    internal fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * Applies and undoes [GuestTweaks] on one serial. Blocking adb — call it on Dispatchers.IO.
 * The record is written before the first change (a superset: everything this run *may* change)
 * and trimmed to what really changed afterwards, so a crash mid-way still leaves Undo complete.
 */
class GuestTweaker(private val adb: AdbCommands, private val settings: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {

    sealed interface Result {
        data class Done(val record: GuestTweaks.Record?, val message: String) : Result
        data class Failed(val message: String) : Result
    }

    fun apply(serial: String, avd: String): Result {
        val installed = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages"), 20))
        if (installed.isEmpty()) return Result.Failed("Couldn't list the emulator's packages — is Android fully booted?")
        val enabled = GuestTweaks.parsePackages(adb.run(listOf("-s", serial, "shell", "pm", "list", "packages", "-e"), 20))
        val previous = GuestTweaks.load(settings, avd)
        val toDisable = GuestTweaks.plan(installed, enabled)
        // Originals: keep an earlier run's (a second run must not record slimmed values as original).
        val originals = LinkedHashMap(previous?.settings.orEmpty())
        for (s in GuestTweaks.SETTINGS) {
            if (s.key !in originals) originals[s.key] = GuestTweaks.parseSetting(adb.run(listOf("-s", serial, "shell", "settings", "get", s.namespace, s.name)))
        }
        val superset = GuestTweaks.Record(avd, clock(), (previous?.packages.orEmpty() + toDisable).distinct(), originals)
        GuestTweaks.save(settings, superset, avd)

        val disabled = toDisable.filter { pkg ->
            val out = adb.run(listOf("-s", serial, "shell", "pm", "disable-user", "--user", "0", pkg)).orEmpty()
            "disabled-user" in out || "new state" in out
        }
        for (s in GuestTweaks.SETTINGS) adb.run(listOf("-s", serial, "shell", "settings", "put", s.namespace, s.name, GuestTweaks.q(s.value)))
        val record = superset.copy(packages = (previous?.packages.orEmpty() + disabled).distinct())
        GuestTweaks.save(settings, record, avd)
        return Result.Done(record, "Slimmed Android on $avd: ${disabled.size} background packages disabled, cached processes capped at 4.")
    }

    /** Re-enables every recorded package and puts each setting back (or deletes it if it was unset). */
    fun undo(serial: String, avd: String): Result {
        val record = GuestTweaks.load(settings, avd) ?: return Result.Done(null, "Nothing to undo on $avd.")
        val failed = record.packages.filterNot { pkg ->
            val out = adb.run(listOf("-s", serial, "shell", "pm", "enable", "--user", "0", pkg)).orEmpty()
            "new state" in out && "enabled" in out
        }
        for ((key, original) in record.settings) {
            val (ns, name) = key.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (original == null) adb.run(listOf("-s", serial, "shell", "settings", "delete", ns, name))
            else adb.run(listOf("-s", serial, "shell", "settings", "put", ns, name, GuestTweaks.q(original)))
        }
        if (failed.isNotEmpty()) {
            // Keep only what's still disabled, so Undo can be run again.
            val rest = record.copy(packages = failed)
            GuestTweaks.save(settings, rest, avd)
            return Result.Failed("${failed.size} package(s) are still disabled: ${failed.joinToString(", ")}. Run Undo again.")
        }
        GuestTweaks.save(settings, null, avd)
        return Result.Done(null, "Undid the Android tweaks on $avd: ${record.packages.size} packages re-enabled, settings restored.")
    }
}
