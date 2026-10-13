package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * The *How to connect* checklist (PRD §5.1, §11: "the top support cost"). Text only in M2; each
 * step names where the setting hides on the phones QA actually carries.
 */
object Onboarding {

    data class Step(val title: String, val body: String, val notes: List<Pair<String, String>> = emptyList())

    val steps: List<Step> = listOf(
        Step(
            "1. Turn on Developer options",
            "Open Settings, find the Build number, and tap it seven times. Enter your PIN if asked — " +
                "you'll see \"You are now a developer\".",
            listOf(
                "Pixel / stock Android" to "Settings → About phone → Build number",
                "Samsung" to "Settings → About phone → Software information → Build number",
                "Xiaomi / Redmi / POCO" to "Settings → About phone → MIUI version (or OS version on HyperOS)",
                "OnePlus / Oppo / Realme" to "Settings → About device → Version → Build number",
            ),
        ),
        Step(
            "2. For a cable: turn on USB debugging",
            "Settings → System → Developer options → USB debugging. Plug the phone in and tap Allow on the " +
                "prompt (tick \"Always allow from this computer\").",
            listOf(
                "Samsung" to "Developer options is at the bottom of the main Settings list",
                "Xiaomi / Redmi / POCO" to "Settings → Additional settings → Developer options. Also turn on " +
                    "\"USB debugging (Security settings)\" — it needs a Mi account and a SIM",
            ),
        ),
        Step(
            "3. Without a cable: turn on Wireless debugging (Android 11 and newer)",
            "Developer options → Wireless debugging → turn it on → Pair device with QR code. Then in LogPose " +
                "choose Add device and scan the code. On Android 10 and older, connect the cable once and " +
                "use Switch to wireless instead.",
        ),
    )

    /** Shown under the steps, and again when wireless discovery comes back empty. */
    const val LIMITS =
        "Wireless needs the phone and this Mac on the same Wi-Fi. Office and guest networks often stop " +
            "devices from seeing each other (\"client isolation\"), and VPNs can too — on those, use the cable."
}
