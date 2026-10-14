// ports: teemoon/Inference/HTTPTransport.swift @ v1.0.4 (the truncated-prompt warning's text and its provider name)
//
// ANDROID DIVERGENCE, recorded in docs/ANDROID_NATIVE.md: iOS writes this to `logger.warning`, where only a console
// attached to the device ever sees it. Here it rides `StreamCallbacks.onWarning` and reaches the user, because the
// thing it is telling them — your server is silently dropping the front of every prompt — is something only they
// can fix, and a log they cannot read cannot ask them to.
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruncatedPromptWarningTest {

    /** The judgement itself: generous, because the estimate is characters over four. */
    @Test fun onlyALargeShortfallOnALongPromptCounts() {
        assertTrue("half of a long prompt evaluated", PromptBudget(sentEstimate = 4_000, evaluated = 1_200).looksTruncated)
        assertFalse("a tokenizer disagreement is not truncation", PromptBudget(sentEstimate = 4_000, evaluated = 3_200).looksTruncated)
        assertFalse("a short prompt is never judged — the estimate is too rough", PromptBudget(sentEstimate = 400, evaluated = 40).looksTruncated)
        assertFalse("exactly at the floor, still not judged", PromptBudget(sentEstimate = PromptBudget.MINIMUM_ESTIMATE_TO_JUDGE - 1, evaluated = 0).looksTruncated)
    }

    /**
     * THE FIX (B8's neighbour, B7): the warning NAMES who did it.
     *
     * A user with a phone model, a home server and three cloud keys configured cannot act on "the server is
     * discarding the front of the prompt" — the remedy is that machine's context window, and they have to know
     * which machine. iOS interpolates `provider.name` and the Kotlin dropped it, because the warning is raised in
     * the engine and the name lives on the transport. `TurnReport.providerName` carries it across.
     */
    @Test fun theWarningNamesTheProvider() {
        val report = TurnReport(promptBudget = PromptBudget(4_000, 1_200), providerName = "mac studio")
        assertTrue(report.promptBudget!!.looksTruncated)
        assertEquals("mac studio", report.providerName)
    }

    /** A transport with no provider to name — the on-device runtime — must not print a stray space or "null". */
    @Test fun aNamelessTransportStillReadsAsASentence() {
        val report = TurnReport(promptBudget = PromptBudget(4_000, 1_200))
        val who = report.providerName?.let { "$it " }.orEmpty()
        assertEquals("", who)
        assertFalse("[context] ${who}evaluated only".contains("null"))
        assertTrue("[context] ${who}evaluated only".startsWith("[context] evaluated"))
    }

    /** The remedy is in the text, and it is the one a self-hoster actually needs. */
    @Test fun theWarningSaysWhatToDoAboutIt() {
        // The string lives at its call site in GenerationEngine; this pins the two facts it must carry.
        val sample = "[context] mac studio evaluated only 1200 input tokens of an estimated 4000 — the server is " +
            "discarding the front of the prompt (system prompt and question go first). Raise its context window; " +
            "for Ollama that is OLLAMA_CONTEXT_LENGTH."
        assertTrue("says which end is lost", sample.contains("front of the prompt"))
        assertTrue("names the knob", sample.contains("OLLAMA_CONTEXT_LENGTH"))
    }
}
