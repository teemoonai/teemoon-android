// ports: teemoonTests/TrailingChipVisibilityTests.swift @ v1.0.1 (all 4), over TrailingActivityChip.visible
package ai.teemoon.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailing activity chip's visibility rule. Regressed once already on iOS, silently — scroll state crept back into
 * the decision. The rule is a pure function whose signature IS the contract: text on screen plus some form of unshown
 * work. Scroll state is not an input, and re-adding one cannot be done without changing the call site.
 */
class TrailingChipVisibilityTest {
    private fun v(hasText: Boolean, stalled: Boolean = false, executingTools: Boolean = false, awaitingModel: Boolean = false) =
        TrailingActivityChip.visible(hasText, stalled, executingTools, awaitingModel)

    /** The three work signals each show the chip on their own, text permitting. */
    @Test fun eachWorkSignalShowsTheChip() {
        assertTrue(v(hasText = true, stalled = true))
        assertTrue(v(hasText = true, executingTools = true))
        assertTrue(v(hasText = true, awaitingModel = true))
    }

    /** No text means the LEADING chip owns the job — the trailing slot stays out. */
    @Test fun noTextMeansNoTrailingChip() {
        assertFalse(v(hasText = false, stalled = true, executingTools = true, awaitingModel = true))
    }

    /** Streaming normally — text flowing, no stall, no tools, no wait — shows nothing: the growing text is its own progress. */
    @Test fun steadyStreamingShowsNoChip() {
        assertFalse(v(hasText = true))
    }

    /** THE REGRESSION GUARD: the second tool round — tool executing, then awaiting the follow-up turn. Always covered. */
    @Test fun secondToolRoundIsAlwaysCovered() {
        assertTrue(v(hasText = true, executingTools = true))
        assertTrue(v(hasText = true, stalled = true, awaitingModel = true))
    }
}
