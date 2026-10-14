// ports: teemoon/Inference/GenerationEngine.swift @ v1.0.4 (round budget + notice)
package ai.teemoon.inference

/**
 * Whether another tool round may run, and what to tell the MODEL when the
 * budget is spent.
 *
 * The measured rule: **the budget is enforced on the LOOP, not merely on
 * whether tools are offered.** Enforcing it only at tool-offering time lets a
 * model that keeps emitting the same call spin forever.
 *
 * GenerationEngine.run holds one of these per generation and asks it three
 * questions: `exhausted` (offer tools?), [shouldTellModelSearchesAreOver]
 * (inject the notice?) and [consume] (run this round?). Nothing else counts.
 */
class ToolRoundPolicy(val maxRounds: Int = DEFAULT_MAX_ROUNDS) {

    var roundsUsed: Int = 0
        private set

    val exhausted: Boolean get() = roundsUsed >= maxRounds

    /** Consumes a round. Returns false when the budget is already spent. */
    fun consume(): Boolean {
        if (exhausted) return false
        roundsUsed += 1
        return true
    }

    /**
     * Whether to inject [SEARCHES_EXHAUSTED_NOTICE] into the conversation now.
     *
     * Skipped when the last message is already a user turn: that is the
     * TEXT-BASED path, whose follow-up ends "Please answer based on these
     * results" — the same instruction — and two user messages in a row is a
     * shape some servers reject.
     */
    fun shouldTellModelSearchesAreOver(
        includeTools: Boolean,
        lastRole: String?,
        alreadyTold: Boolean,
    ): Boolean = !includeTools && roundsUsed > 0 && !alreadyTold && lastRole != "user"

    companion object {
        /** 4 hosted. On-device ships 2 — the caller passes that explicitly. */
        const val DEFAULT_MAX_ROUNDS = 4

        /**
         * VERBATIM from iOS. The wording is the measurement, not decoration:
         * failure-shaped phrasing produced empty answers (5 of 18), this
         * policy-shaped phrasing produced none (0 of 34). It is injected as a
         * `user` message into the conversation — it instructs the MODEL to
         * answer from what it has. It is not a label for the human; rendering
         * it in the transcript instead of sending it delivers exactly none of
         * the measured benefit.
         */
        const val SEARCHES_EXHAUSTED_NOTICE =
            "SEARCH POLICY: this conversation has used the searches available to it, " +
                "so no further searches will run. This is a setting, not a failure.\n" +
                "Answer the question now from the results already gathered above. Where " +
                "they are inconclusive, say what you found and what is still uncertain."
    }
}
