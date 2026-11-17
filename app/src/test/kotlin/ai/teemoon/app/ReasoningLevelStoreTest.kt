// ports: teemoonTests/ReasoningLevelTests.swift @ 0e98aaf1 (aChoiceCountsOnlyInDeveloperMode; iOS keeps the dictionary
//        in UserDefaults, here AppSettings over its Prefs seam, one JSON string under the same key)
//        + Android-only: the transport reads the stored choice through ReasoningLevel.chooser, and on device a choice
//        of "on" thinks.
package ai.teemoon.app

import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.ReasoningLevel
import ai.teemoon.providers.Provider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningLevelStoreTest {
    @After fun forget() { ReasoningLevel.chooser = { null } }

    private fun provider(model: String) = Provider(id = "p", name = "p", endpoint = "https://api.fireworks.ai/inference/v1/chat/completions", model = model)
    private val fireworks = provider("m")

    @Test fun aChoiceCountsOnlyInDeveloperMode() {
        val s = AppSettings(InMemoryPrefs())
        s.setReasoningLevel("medium", fireworks)
        assertNull("developer mode off: the choice is ignored", s.chosenReasoningLevel(fireworks))
        s.developerModeEnabled = true
        assertEquals("medium", s.chosenReasoningLevel(fireworks))
        assertNull("per model", s.chosenReasoningLevel(provider("other")))
        s.developerModeEnabled = false
        assertEquals("kept for when it comes back on", "medium", s.storedReasoningLevel(fireworks))
        s.setReasoningLevel(null, fireworks)
        assertNull(s.storedReasoningLevel(fireworks))
    }

    /** Stored under the iOS key, one entry per API host and model. */
    @Test fun storedAsOneDictionaryUnderTheIosKey() {
        val prefs = InMemoryPrefs()
        val s = AppSettings(prefs)
        s.setReasoningLevel("high", fireworks)
        s.setReasoningLevel("medium", provider("other"))
        assertEquals("""{"api.fireworks.ai|m":"high","api.fireworks.ai|other":"medium"}""", prefs.getString("reasoningLevels"))
        assertEquals("high", AppSettings(prefs).storedReasoningLevel(fireworks))
    }

    /** A damaged entry is no choice (today's low), never a crash in the send path. */
    @Test fun anUnreadableDictionaryIsNoChoice() {
        val prefs = InMemoryPrefs().apply { putString("reasoningLevels", "{not json"); putBoolean("developerModeEnabled", true) }
        assertNull(AppSettings(prefs).chosenReasoningLevel(fireworks))
    }

    /** What the app installs as the transport's chooser: the stored pick in developer mode, nothing outside it. */
    @Test fun theTransportReadsTheStoredChoice() {
        val s = AppSettings(InMemoryPrefs())
        ReasoningLevel.chooser = s::chosenReasoningLevel
        s.setReasoningLevel("medium", fireworks)
        assertEquals(JsonPrimitive("low"), ReasoningEffort.FAST.bodyFields(fireworks)?.get("reasoning_effort"))
        s.developerModeEnabled = true
        assertEquals(JsonPrimitive("medium"), ReasoningEffort.FAST.bodyFields(fireworks)?.get("reasoning_effort"))
    }

    /**
     * Developer mode off, whatever is stored for the model — every level any control offers — the transport is handed no
     * pick (pm 2026-11-13: model info's hiding is display only; the wire is today's). The bytes that follow from "no pick"
     * are ReasoningEffortTest.withNoPickTheBodyIsTheSameBytesWhateverTheChooser, in :inference (a real OkHttp client
     * here meets Paparazzi's Android classes and looks for Conscrypt).
     */
    @Test fun developerModeOffHandsTheTransportNoPick() {
        for (level in listOf("none", "minimal", "low", "medium", "high", "xhigh", "off", "on")) {
            val s = AppSettings(InMemoryPrefs())
            s.setReasoningLevel(level, fireworks)
            ReasoningLevel.chooser = s::chosenReasoningLevel
            assertNull("\"$level\" stored, developer mode off", ReasoningLevel.chosen(fireworks))
            s.developerModeEnabled = true
            assertEquals("the control: developer mode on hands it over", level, ReasoningLevel.chosen(fireworks))
        }
    }
}
