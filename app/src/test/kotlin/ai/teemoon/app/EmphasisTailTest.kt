// Unclosed emphasis in the streaming tail (the maintainer 2026-10-25, via pm: "Both"): `*Have a good` painted its literal
// asterisk until the closing one arrived (the 02 stream clip). Held back like a link: the marker goes, the words show
// plain, and they turn italic or bold when the closer arrives.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EmphasisTailTest {
    private fun d(s: String) = MarkdownStreamSplitter.displayTail(s)

    @Test fun anUnclosedOpenerLosesItsMarker() {
        assertEquals("updated each morning. Have a good", d("updated each morning. *Have a good"))
        assertEquals("the three things you", d("the **three things you"))
        assertEquals("an italic", d("an _italic"))
        assertEquals("bold and ital", d("**bold and *ital"))
    }

    @Test fun closedEmphasisIsUntouched() {
        assertEquals("*Have a good walk.*", d("*Have a good walk.*"))
        assertEquals("The **three things** you must", d("The **three things** you must"))
        assertEquals("**bold** then ital", d("**bold** then *ital"))
    }

    @Test fun aMarkerStillArrivingIsHeld() {
        assertEquals("a good ", d("a good *"))
        assertEquals("a good ", d("a good **"))
    }

    @Test fun whatIsNotEmphasisStays() {
        assertEquals("snake_case_name", d("snake_case_name"))
        assertEquals("2 * 3 = 6", d("2 * 3 = 6"))
        assertEquals("* a list item", d("* a list item"))
        assertEquals("- Water: at least two", d("- Water: at least **two"))
        assertEquals("use `a*b` here", d("use `a*b` here"))
        assertEquals("use `*x", d("use `*x"))
        assertEquals("```\n*not emphasis\n", d("```\n*not emphasis\n"))
    }

    @Test fun onlyTheLastParagraphIsRead() =
        assertEquals("Done *here*.\n\nNext para", d("Done *here*.\n\n*Next para"))
}
