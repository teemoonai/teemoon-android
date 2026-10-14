// android-only: the developer card's tool round is bounded before it reaches the screen.
package ai.teemoon.app

import ai.teemoon.inference.ToolCallRecord
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Brave round returns the full text of every source — `<source><url><title><content>` for each, tens of
 * thousands of characters. Rendered raw it fills the transcript with XML above the reply, which is what the
 * developer saw on a Pixel 10 Pro on 2026-09-22 asking "best iphone local ai app".
 *
 * `ToolCallRecord.displayResult` has capped this at 1500 since the port landed, with a trailer saying how much
 * exists so the truncation is honest. `ToolRecord` simply did not use it — the fourth "correct and unreached"
 * defect of the week (2026-09 port review, §4d).
 *
 * The cap now happens where the record is BUILT, not where it is drawn, so the next renderer cannot repeat it:
 * the uncapped string never reaches the state at all.
 */
class ToolRecordCapTest {

    private fun braveish(sources: Int): String = (1..sources).joinToString("\n") { i ->
        """<source index="$i"><url>https://example.com/$i</url><title>Result $i</title>""" +
            "<content>" + "prose about local ai apps. ".repeat(80) + "</content></source>"
    }

    @Test fun aBigToolResultIsBoundedBeforeItCanBeRendered() {
        val raw = braveish(15)
        assertTrue("the fixture must actually be big, or this proves nothing", raw.length > 20_000)
        val capped = ToolCallRecord("web_search", "{}", raw).displayResult
        assertTrue(capped.length < raw.length / 5)
        assertTrue("the truncation says how much exists", capped.contains("of ${raw.length} chars"))
        assertTrue("and where the rest is", capped.contains("copy has the full payload"))
    }

    /** A short result is untouched — the cap must not put a trailer on every row. */
    @Test fun aSmallToolResultIsUnchanged() {
        val small = """<source index="1"><url>https://example.com</url></source>"""
        assertTrue(ToolCallRecord("web_search", "{}", small).displayResult == small)
    }

    /**
     * THE REGRESSION GUARD, now on the surviving surface. The always-open transcript panel that showed this
     * uncapped is deleted — the debug card's ToolCallRow is the one place a tool round is drawn, collapsed behind
     * a chevron as iOS draws it — and a behaviorLedger rule holds that row to `displayResult`.
     */
    @Test fun theOnlySurfaceThatDrawsAToolRoundIsBounded() {
        val raw = braveish(15)
        assertTrue(ToolCallRecord("web_search", "{}", raw).displayResult.length <= ToolCallRecord.DISPLAY_CAP + 200)
    }
}
