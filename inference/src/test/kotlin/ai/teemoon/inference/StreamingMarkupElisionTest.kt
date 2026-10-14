// ports: teemoonTests/SSEParserTests.swift @ v1.0.1 (suppression cases)
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkupElisionTest {

    @Test fun forwardsPlainProseUntouched() {
        val e = StreamingMarkupElision()
        assertEquals("hello world", e.append("hello world"))
        assertEquals("hello world", e.visibleContent)
        assertFalse(e.markerSeen)
    }

    /**
     * The regression this class exists for: prose → call → prose must keep
     * streaming AFTER the region closes, not batch the second half to [DONE].
     */
    @Test fun resumesForwardingAfterTheRegionCloses() {
        val e = StreamingMarkupElision()
        val visible = StringBuilder()
        visible.append(e.append("before "))
        visible.append(e.append("""<tool_call>{"name":"x"}</tool_call>"""))
        visible.append(e.append(" after"))
        visible.append(e.finish())
        assertEquals("before  after", visible.toString())
        assertTrue(e.markerSeen)
        assertFalse(e.isSuppressing)
    }

    /** A marker split across deltas must not leak its first half. */
    @Test fun holdsBackAPartialOpeningMarker() {
        val e = StreamingMarkupElision()
        assertEquals("hi ", e.append("hi <tool_"))
        assertEquals("", e.append("""call>{"a":1}"""))
        assertTrue(e.isSuppressing)
        assertEquals(" done", e.append("</tool_call> done"))
        assertEquals("hi  done", e.visibleContent)
    }

    /** A CLOSING marker split across deltas must still be recognised. */
    @Test fun recognisesACloseSplitAcrossDeltas() {
        val e = StreamingMarkupElision()
        e.append("<tool_call>payload</tool")
        assertTrue(e.isSuppressing)
        assertEquals(" tail", e.append("_call> tail"))
        assertFalse(e.isSuppressing)
    }

    /** Held-back text that never became a marker belongs to the reply. */
    @Test fun finishReleasesAHeldBackNonMarker() {
        val e = StreamingMarkupElision()
        assertEquals("done ", e.append("done <tool"))
        assertEquals("<tool", e.finish())
        assertEquals("done <tool", e.visibleContent)
    }

    /** An UNTERMINATED region stays elided — partial markup must never leak. */
    @Test fun finishDoesNotLeakAnUnterminatedRegion() {
        val e = StreamingMarkupElision()
        e.append("""prose <tool_call>{"name":"x"""")
        assertEquals("", e.finish())
        assertEquals("prose ", e.visibleContent)
    }

    @Test fun gemmaFenceIsElidedToo() {
        val e = StreamingMarkupElision()
        val visible = e.append("a ```tool_code\nsearch(q=\"x\")\n``` b") + e.finish()
        assertEquals("a  b", visible)
    }

    @Test fun disabledPassesEverythingThrough() {
        val e = StreamingMarkupElision(enabled = false)
        val raw = """x <tool_call>{"name":"y"}</tool_call> z"""
        assertEquals(raw, e.append(raw))
    }
}
