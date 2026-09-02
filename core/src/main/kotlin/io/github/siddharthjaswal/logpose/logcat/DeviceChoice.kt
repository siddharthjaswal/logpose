package io.github.siddharthjaswal.logpose.logcat

/**
 * Which attached device a capture targets, decided from a saved preference and what `adb devices`
 * reports. Pure and unit-tested; where the preference is *stored* is the host's business (the
 * plugin keeps it per project, a headless host takes it from a flag).
 *
 * Null means *auto*: no `-s` flag, which is exactly the behaviour before the device picker existed
 * and correct for the common one-device case.
 */
object DeviceChoice {

    /** What capture start decided: the serial to pass to adb (null = auto), and anything worth
     *  telling the user about how it was decided. */
    data class Choice(val serial: String?, val notice: String? = null)

    /**
     * The capture-start decision.
     *
     * An honoured selection wins. With no selection and one device (or none), auto — identical
     * to before the picker existed. With two or more and nothing to go on, the first is picked
     * *and said out loud*, because the alternative is `adb logcat` failing with "more than one
     * device". A saved serial that is no longer attached falls back the same way rather than
     * failing on a device that isn't there.
     *
     * [ready] is the `state == device` slice of `adb devices -l`; an empty list also covers
     * "adb itself failed", in which case any saved serial is kept — attach will surface the real
     * error, which beats guessing here.
     */
    fun choose(preferred: String?, ready: List<Adb.DeviceInfo>): Choice {
        if (preferred != null) {
            if (ready.isEmpty() || ready.any { it.serial == preferred }) return Choice(preferred)
            val fallback = ready.first()
            return if (ready.size >= 2) {
                Choice(
                    fallback.serial,
                    "The saved device ($preferred) isn't attached — capturing from ${fallback.label} instead.",
                )
            } else {
                // One device attached: auto is unambiguous, and quieter than naming it.
                Choice(null)
            }
        }
        if (ready.size >= 2) {
            val first = ready.first()
            return Choice(
                first.serial,
                "Multiple devices attached — capturing from ${first.label}. " +
                    "Pick another with the device selector in the toolbar.",
            )
        }
        return Choice(null)
    }
}
