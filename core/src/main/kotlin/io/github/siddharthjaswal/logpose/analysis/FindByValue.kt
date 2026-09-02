package io.github.siddharthjaswal.logpose.analysis

/**
 * What "Find by value…" says back while a human is typing (PRD §4.2.1).
 *
 * The dialog owns a text field and a label; *what goes in the label* is a decision, and the PRD
 * is specific about it: state the count before committing so a typo is obvious rather than an
 * empty screen, label a bare value with the key that holds it, and say "too short to match
 * safely" out loud instead of returning nothing. All three are pure functions of a parsed
 * [FindQuery] and a match count, so they live here and are tested without a dialog.
 */
object FindByValue {

    /** How the message reads — the dialog maps this onto a colour in the UI. */
    enum class Tone {
        /** Nothing to say yet. */
        NEUTRAL,

        /** The input can't group anything, and the message explains why. */
        PROBLEM,

        /** There is a group to open. */
        READY,
    }

    /**
     * [message] is shown under the field; [grouping] is non-null exactly when the dialog may
     * commit, so the OK button follows it rather than re-deciding.
     */
    data class Preview(
        val message: String,
        val grouping: Grouping?,
        val tone: Tone,
        /** Events the grouping would open, or 0 when there is no grouping. */
        val matches: Int = 0,
    )

    /**
     * Reads a parsed query into what the dialog shows.
     *
     * [matchCount] is called only for a query that could match — a too-short value is refused
     * before anything scans the capture. [keyLabelFor] supplies the configured key that holds a
     * bare value ([Correlation.keyLabelFor]); returning null leaves it reading `value 21053953`,
     * which is the honest label when no key claims it.
     */
    fun preview(
        query: FindQuery,
        matchCount: (Grouping) -> Int,
        keyLabelFor: (String) -> String?,
    ): Preview = when (query) {
        is FindQuery.Empty -> Preview("", null, Tone.NEUTRAL)

        is FindQuery.TooShort -> Preview(
            message = tooShort(query),
            grouping = null,
            tone = Tone.PROBLEM,
        )

        is FindQuery.Ready -> {
            val grouping = Groupings.forValue(query.value, query.key ?: keyLabelFor(query.value))
            val count = matchCount(grouping)
            if (count <= 0) {
                Preview("no events carry that value", null, Tone.PROBLEM)
            } else {
                Preview(
                    message = "${events(count)}  ·  ${grouping.shortLabel}",
                    grouping = grouping,
                    tone = Tone.READY,
                    matches = count,
                )
            }
        }
    }

    /**
     * The short-value refusal. It names the value, because the usual cause is a paste that
     * clipped, and it names the per-key opt-in, because that's the only way to overrule it.
     */
    private fun tooShort(query: FindQuery.TooShort): String {
        val floor = "at least ${query.minLength} characters"
        val optIn = query.key?.let {
            " — or tick \"short values\" for $it in Correlation keys"
        } ?: " — or tick \"short values\" for the key that holds it"
        return "\"${query.value}\" is too short to match safely: use $floor$optIn."
    }

    private fun events(count: Int) = if (count == 1) "1 event" else "$count events"
}
