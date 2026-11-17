// The activity chip that stands in for a reply with nothing shown yet. With the reveal's 600 ms hold it also covered
// the answer's first moments after a thinking block — reading "thinking... 4s" under "thought for 3s" (the Pixel
// after-clip, 2026-10-25). The thinking disclosure already says what is happening there, so the chip is for a reply
// that has neither thinking nor shown text.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyPlaceholderTest {
    @Test fun nothingShownAndNoThinking() = assertEquals(true, ReplyPlaceholder.shown(streaming = true, hasPlaceholder = true, thinking = null, shownAnswer = ""))
    @Test fun heldAnswerAfterThinking() = assertEquals(false, ReplyPlaceholder.shown(streaming = true, hasPlaceholder = true, thinking = "weigh it", shownAnswer = ""))
    @Test fun textShown() = assertEquals(false, ReplyPlaceholder.shown(streaming = true, hasPlaceholder = true, thinking = null, shownAnswer = "They"))
    @Test fun finished() = assertEquals(false, ReplyPlaceholder.shown(streaming = false, hasPlaceholder = true, thinking = null, shownAnswer = ""))

    // ...and nothing at all: the answer's body drew "…" for empty streaming markdown, a stray line under the disclosure.
    @Test fun theHeldAnswerDrawsNoBody() {
        assertEquals(false, ReplyPlaceholder.bodyShown(streaming = true, thinking = "weigh it", shownAnswer = ""))
        assertEquals(true, ReplyPlaceholder.bodyShown(streaming = true, thinking = "weigh it", shownAnswer = "They"))
        assertEquals(true, ReplyPlaceholder.bodyShown(streaming = false, thinking = "weigh it", shownAnswer = ""))
    }
}
