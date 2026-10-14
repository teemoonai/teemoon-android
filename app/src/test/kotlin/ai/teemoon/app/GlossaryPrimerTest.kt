// ports: teemoon/Views/Chat/VerificationRunView.swift @ v1.0.1 (GlossaryEntry.primer, GlossarySheet, primerSection)
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The primer is the one piece of trust copy aimed at someone who does not yet know what any of it means. It is
 * DATA — a constant, making no claim about the current session — and these pin the properties the design review
 * settled on, which are easy to undo by editing the string.
 */
class GlossaryPrimerTest {
    private val primer = GlossaryEntry.primer

    /**
     * Three beats, not one wall. The first version was a single paragraph, and the review's objection was that it
     * handed the most anxious reader a dense block at exactly their moment of anxiety.
     */
    @Test fun thePrimerIsThreeSeparateBeats() {
        assertEquals("the primer is meant to be chunked: ${primer.beats.size} beat(s)", 3, primer.beats.size)
        for (beat in primer.beats) assertFalse("a beat still contains a blank line", beat.contains("\n\n"))
    }

    /** Each beat stays short enough to read standing up. A beat that has grown past this is the wall coming back. */
    @Test fun eachBeatIsShort() {
        for (beat in primer.beats) assertTrue("a beat is ${beat.length} chars: ${beat.take(40)}…", beat.length <= 320)
    }

    /**
     * Every beat carries exactly one bolded phrase — the thing a reader who skims only the bold still comes away
     * with. Balanced markers, or MarkdownInline renders the asterisks as text.
     */
    @Test fun everyBeatHasOneBalancedBoldRun() {
        for (beat in primer.beats) {
            // N balanced runs split into 2N+1 parts, so a balanced string always yields an ODD count.
            assertEquals("unbalanced ** in: ${beat.take(40)}…", 1, beat.split("**").size % 2)
            assertEquals("a beat should carry exactly one bolded phrase", 3, beat.split("**").size)
        }
    }

    /** Skimming only the bold still gives the three claims in order: what protects you, who proves it, what you can check. */
    @Test fun theBoldPhrasesAreTheThreeClaims() {
        val bold = primer.beats.map { it.split("**")[1] }
        assertEquals(listOf("sealed region of the chip", "Intel- and NVIDIA-signed proofs", "shows you the signed values"), bold)
    }

    /**
     * It describes the MECHANISM, never this session. A verdict here would be trust language composed by the UI
     * (CONTRIBUTING.md: trust copy is data) — and worse, a claim that stays on screen when the session's own checks say otherwise.
     */
    @Test fun thePrimerMakesNoClaimAboutTheCurrentSession() {
        val text = primer.definition.lowercase()
        for (verdict in listOf("verified", "this session", "your session is", "attested and", "confirmed")) {
            assertFalse("the primer states a verdict ('$verdict') — it must describe the mechanism only", text.contains(verdict))
        }
    }

    /** The one link out goes to the vendor's own documentation, over https. */
    @Test fun learnMorePointsAtNearAIsDocs() {
        assertTrue(primer.url.startsWith("https://"))
        assertTrue(primer.url.contains("docs.near.ai"))
    }

    /** The banner asks the question a first-time reader is actually asking, and the sheet's title answers it. */
    @Test fun theBannerAndTheSheetAgreeOnTheTitle() {
        assertTrue(PrimerBanner.TITLE.contains(primer.title))
        assertEquals(PrimerBanner.TITLE.lowercase(), PrimerBanner.TITLE)
        assertEquals(PrimerBanner.SUBTITLE.lowercase(), PrimerBanner.SUBTITLE)
        assertEquals(primer.title.lowercase(), primer.title)
    }

    /** MarkdownInline is what renders the beats; the bold must actually survive it rather than printing asterisks. */
    @Test fun theBeatsRenderWithoutStrayMarkers() {
        for (beat in primer.beats) {
            val rendered = MarkdownInline.annotate(beat).text
            assertFalse("asterisks reached the screen: $rendered", rendered.contains("*"))
            assertTrue(rendered.length < beat.length)   // the markers were consumed, not just hidden
        }
    }
}
