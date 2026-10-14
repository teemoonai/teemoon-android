// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the BraveAnswersFormatTests suite only)
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brave interleaves `<citation>{…}</citation>` and a trailing `<usage>{…}</usage>`
 * into the assistant's `content`, so teemoon rendered raw JSON (and base64
 * favicon URLs) inside the prose.
 *
 * NOT PORTED: `realAnswerSplitsIntoProseAndSources`. It reads the captured live
 * answer `teemoonTests/Fixtures/brave_answers_content.txt`, which crosses only
 * through `conformance/` (§7.2) and has no kit class yet.
 */
class BraveAnswersFormatTest {

    /**
     * `split` runs on every streaming delta, so a block that hasn't closed yet
     * must be withheld — otherwise the user watches `{"start_index": 58, "e`
     * type across the screen before it vanishes.
     */
    @Test fun partialBlocksAreWithheldWhileStreaming() {
        assertEquals("Paris is the capital.", BraveAnswersFormat.split("Paris is the capital.<cit").visible)
        assertEquals("Paris is the capital.", BraveAnswersFormat.split("Paris is the capital.<").visible)
        assertEquals("Paris.", BraveAnswersFormat.split("""Paris.<citation>{"url": "https://ex""").visible)
        assertEquals("Paris.", BraveAnswersFormat.split("Paris.<usage>{\"X-Request").visible)
        // A "<" that can't begin a block is ordinary text and must survive.
        assertEquals("2 < 3 is true", BraveAnswersFormat.split("2 < 3 is true").visible)
        assertEquals("use <b>bold</b>", BraveAnswersFormat.split("use <b>bold</b>").visible)
    }

    @Test fun textBetweenBlocksIsJoinedAndUsageIsNotASource() {
        val raw = """One.<citation>{"url": "https://a.example/x", "snippet": "s"}</citation> Two.""" +
            """<usage>{"X-Request-Total-Cost": 0.05}</usage>"""
        val (visible, sources) = BraveAnswersFormat.split(raw)
        assertEquals("One. Two.", visible)
        assertEquals(listOf("https://a.example/x"), sources.map { it.url })
        assertEquals("a.example", sources.first().domain)
    }

    /**
     * Every other provider streams plain content — the split must be a no-op
     * for text with no blocks (it runs per delta on the shared path).
     */
    @Test fun plainAnswersPassThroughUnchanged() {
        val plain = "Here is a normal answer with a [link](https://example.com) and math: 2<3."
        val (visible, sources) = BraveAnswersFormat.split(plain)
        assertEquals(plain, visible)
        assertTrue(sources.isEmpty())
    }

    /** Android-only: the properties the live-fixture test pins, on a literal. */
    @Test fun citationsAreDedupedByUrlTitlelessAndStripOfWww() {
        val raw = "**Paris** is the capital" +
            """<citation>{"start_index": 5, "end_index": 5, "number": 1, "url": "https://www.en.wikipedia.org/wiki/Paris", "favicon": "https://imgs.search.brave.com/x", "snippet": "Paris is the capital…"}</citation>""" +
            """<citation>{"number": 2, "url": "https://www.en.wikipedia.org/wiki/Paris", "snippet": "dup"}</citation>""" +
            """<citation>{"number": 3, "snippet": "no url"}</citation>""" +
            """<citation>{"number": 4, "url": "", "snippet": "empty url"}</citation>""" +
            """<citation>not json</citation>""" +
            " of France.<usage>{\"X-Request-Total-Cost\": 0.0548}</usage>"
        val (visible, sources) = BraveAnswersFormat.split(raw)
        assertEquals("**Paris** is the capital of France.", visible)
        assertEquals(1, sources.size)
        val wiki = sources.first()
        assertEquals("https://www.en.wikipedia.org/wiki/Paris", wiki.url)
        assertEquals("en.wikipedia.org", wiki.domain)
        assertEquals("", wiki.title)              // Brave sends none; the row shows the domain
        assertEquals("Paris is the capital…", wiki.snippet)
    }
}
