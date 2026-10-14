// ports: teemoonTests/TranscriptDeepLinkTests.swift @ v1.0.1
package ai.teemoon.chat.search

import ai.teemoon.chat.search.ChatSearchGranularity.MESSAGES
import ai.teemoon.chat.search.SearchTestSupport.CLOSE
import ai.teemoon.chat.search.SearchTestSupport.OPEN
import ai.teemoon.chat.search.SearchTestSupport.record
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.math.abs

class TranscriptDeepLinkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun depositThenConsume_returnsTheMessageOnce() {
        val link = TranscriptDeepLink(); val thread = "t"; val message = "m"
        link.deposit(thread, message, 0.4)
        val target = link.consume(thread)
        assertEquals(message, target?.messageID); assertEquals(0.4, target!!.fraction, 0.0)
        assertNull("one-shot — a second open must not jump", link.consume(thread))
    }
    @Test fun consumeForADifferentThread_discardsTheStaleTarget() {
        val link = TranscriptDeepLink()
        link.deposit("tapped", "m")
        assertNull(link.consume("openedInstead"))
        assertNull("a target the user navigated away from must not fire on a later visit", link.consume("tapped"))
    }
    @Test fun eachDeposit_bumpsTheStamp() {
        val link = TranscriptDeepLink(); val before = link.stamp
        link.deposit("a", "1"); link.deposit("b", "2")
        assertEquals("same-thread taps re-fire only because the stamp moves", before + 2, link.stamp)
    }
    @Test fun matchDeepInALongMessage_locatesItsFraction() {
        val filler = "gold market commentary. ".repeat(120); val tail = " more commentary.".repeat(40)
        val content = filler + "sanctions on Iran shifted demand" + tail
        val snippet = "…sanctions on ${OPEN}Iran${CLOSE} shifted demand…"
        val fraction = TranscriptDeepLink.matchFraction(snippet, content)
        val expected = filler.length.toDouble() / content.length
        assertTrue("the match sits at $expected, got $fraction", abs(fraction - expected) < 0.02)
    }
    @Test fun matchAtTheStart_isNearZero() {
        val content = "Iran question here" + " padding".repeat(200)
        assertTrue(TranscriptDeepLink.matchFraction("${OPEN}Iran${CLOSE} question here…", content) < 0.02)
    }
    @Test fun snippetNotFoundInContent_fallsBackToTheTop() {
        assertEquals(0.0, TranscriptDeepLink.matchFraction("…${OPEN}iran${CLOSE} something that is not in the message…", "entirely different text"), 0.0)
    }
    @Test fun emptyContent_isZeroNotNaN() { assertEquals(0.0, TranscriptDeepLink.matchFraction("x", ""), 0.0) }
    @Test fun aRealFTSSnippet_locatesTheMatch() = runTest {
        val index = SearchTestSupport.index(File(tmp.root, "deeplink-${UUID.randomUUID()}.sqlite"))
        val filler = (0 until 150).joinToString(" ") { "paragraph $it about the gold market and demand cycles." }
        val content = filler + " Sanctions on Iran altered refinery flows that quarter. " + "closing commentary. ".repeat(30)
        index.index(listOf(record(content, role = "assistant")))
        val snippet = index.search("iran", MESSAGES, 5).first().snippet
        val fraction = TranscriptDeepLink.matchFraction(snippet, content)
        val expected = filler.length.toDouble() / content.length
        assertNotEquals("the snippet must carry a highlight", -1, snippet.indexOf(OPEN))
        assertTrue("real snippet located at $fraction, expected ≈$expected", abs(fraction - expected) < 0.05)
    }
}
