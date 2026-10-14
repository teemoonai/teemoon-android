// ports: teemoonTests/ChatSearchHighlightTests.swift @ v1.0.1 (the three `attributed` tests assert on
//        AttributedString runs — here they assert on `segments`, which is what the Compose builder consumes)
package ai.teemoon.chat.search

import ai.teemoon.chat.search.SearchTestSupport.CLOSE
import ai.teemoon.chat.search.SearchTestSupport.OPEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A truncated snippet is a normal thing for sqlite to return, and swallowing the tail of a result would be worse than showing an unstyled one. */
class ChatSearchHighlightTest {

    @Test fun segments_splitsMatchedRunsOut() {
        val s = ChatSearchHighlight.segments("the ${OPEN}kettle$CLOSE broke")
        assertEquals(listOf(ChatSearchHighlight.Segment("the ", false), ChatSearchHighlight.Segment("kettle", true), ChatSearchHighlight.Segment(" broke", false)), s)
    }

    @Test fun segments_handlesMultipleMatches() {
        assertEquals(listOf("a", "b"), ChatSearchHighlight.segments("${OPEN}a$CLOSE and ${OPEN}b$CLOSE").filter { it.isMatch }.map { it.text })
    }

    @Test fun segments_unterminatedMatchStillRenders() {
        val s = ChatSearchHighlight.segments("start ${OPEN}tail")
        assertEquals("start tail", s.joinToString("") { it.text }); assertTrue(s.last().isMatch)
    }

    @Test fun segments_unpairedCloserIsNotARun() {
        val s = ChatSearchHighlight.segments("plain${CLOSE}text")
        assertEquals(1, s.size); assertFalse(s[0].isMatch); assertEquals("plaintext", s[0].text)
    }

    @Test fun plainText_dropsEveryDelimiter() {
        val plain = ChatSearchHighlight.plainText("…the ${OPEN}appliance${CLOSE}s were ${OPEN}late$CLOSE…")
        assertFalse(plain.contains(OPEN)); assertFalse(plain.contains(CLOSE)); assertEquals("…the appliances were late…", plain)
    }

    @Test fun attributed_emphasisesOnlyTheMatch() {
        val s = ChatSearchHighlight.segments("a ${OPEN}b$CLOSE c")
        assertEquals(listOf("b"), s.filter { it.isMatch }.map { it.text }); assertEquals("a b c", s.joinToString("") { it.text })
    }

    /** The trailing `*` in the match expression is what produces this: `**Appliance**s`, not a whole-word bold. */
    @Test fun attributed_prefixMatchBoldsThePrefixOnly() {
        val s = ChatSearchHighlight.segments("${OPEN}Applianc${CLOSE}es")
        assertEquals(listOf("Applianc"), s.filter { it.isMatch }.map { it.text }); assertEquals("Appliances", s.joinToString("") { it.text })
    }

    @Test fun attributed_plainSnippetHasNoEmphasis() { assertTrue(ChatSearchHighlight.segments("nothing matched here").none { it.isMatch }) }

    // Android-only: the two helpers the iOS suite leaves to the screenshot test.

    @Test fun centred_bringsALateMatchForwardOnAWordBoundary() {
        val snippet = "one two three four five six seven eight nine ten ${OPEN}kettle$CLOSE end"
        val c = ChatSearchHighlight.centred(snippet, lead = 12)
        assertTrue(c.startsWith("…")); assertTrue(c.contains("${OPEN}kettle")); assertTrue(c.length < snippet.length)
        assertFalse(c.substring(1).startsWith(" "))
        assertEquals("short ${OPEN}x$CLOSE", ChatSearchHighlight.centred("short ${OPEN}x$CLOSE"))   // early match: untouched
    }

    @Test fun excerpt_marksTheTermCaseAndDiacriticInsensitively() {
        val e = ChatSearchHighlight.excerpt("Café", listOf("nothing here", "we went to the cafe after lunch"))!!
        assertEquals("we went to the ${OPEN}cafe$CLOSE after lunch", e)
        val long = "x".repeat(100) + " kettle " + "y".repeat(100)
        val windowed = ChatSearchHighlight.excerpt("kettle", listOf(long), window = 10)!!
        assertTrue(windowed.startsWith("…") && windowed.endsWith("…")); assertTrue(windowed.contains("${OPEN}kettle$CLOSE"))
        assertNull(ChatSearchHighlight.excerpt("   ", listOf("anything"))); assertNull(ChatSearchHighlight.excerpt("zzz", listOf("anything")))
    }
}
