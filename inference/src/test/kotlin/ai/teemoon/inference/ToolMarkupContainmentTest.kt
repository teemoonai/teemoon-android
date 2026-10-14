package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolMarkupContainmentTest {

    @Test fun removesAPairedGlmBlock() {
        val r = ToolMarkupContainment.sanitize("""before <tool_call>{"name":"x"}</tool_call> after""")
        assertEquals("before  after", r.clean)
        assertTrue(r.removed.isNotEmpty())
    }

    @Test fun removesAGemmaFence() {
        val r = ToolMarkupContainment.sanitize("a ```tool_code\nsearch(q=\"x\")\n``` b")
        assertEquals("a  b", r.clean)
    }

    /** A TRUNCATED call has no closing tag — the orphan pass must still catch it. */
    @Test fun removesOrphanTokensFromATruncatedCall() {
        val r = ToolMarkupContainment.sanitize("""answer <tool_call>{"name":"web_""")
        assertFalse(r.clean.contains("<tool_call>"))
        assertTrue(r.removed.contains("<tool_call>"))
    }

    @Test fun removesDeepSeekUnicodeFamilies() {
        val r = ToolMarkupContainment.sanitize("x <｜tool▁calls▁begin｜>junk<｜tool▁calls▁end｜> y")
        assertEquals("x  y", r.clean)
    }

    @Test fun removesAttributeOpeners() {
        val r = ToolMarkupContainment.sanitize("<function=web_search><parameter=query>q</parameter>done")
        assertFalse(r.clean.contains("<function="))
        assertFalse(r.clean.contains("<parameter="))
    }

    /** Clean prose must pass through untouched and report nothing removed. */
    @Test fun leavesCleanProseAlone() {
        val r = ToolMarkupContainment.sanitize("just an ordinary answer")
        assertEquals("just an ordinary answer", r.clean)
        assertTrue(r.removed.isEmpty())
    }
}
