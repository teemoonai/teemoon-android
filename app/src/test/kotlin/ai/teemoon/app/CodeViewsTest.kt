// Kotlin-self pins for SelfVerifyScriptCard's highlighter and gutter, CodeBlock's dimmed comments, and MoonPhase (no Swift tests exist).
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CodeViewsTest {
    @Test fun highlighterSplitsCommentsAndStringsAndClosesThemAtLineEnd() {
        val runs = ScriptHighlighter.runs("x = 1 # note\ns = 'a' + \"b\nrest")
        assertEquals(listOf(ScriptHighlighter.Tok.BASE, ScriptHighlighter.Tok.COMMENT, ScriptHighlighter.Tok.BASE, ScriptHighlighter.Tok.STRING, ScriptHighlighter.Tok.BASE, ScriptHighlighter.Tok.STRING, ScriptHighlighter.Tok.BASE), runs.map { it.tok })
        assertEquals("# note\n", runs[1].text); assertEquals("'a'", runs[3].text); assertEquals("\"b\n", runs[5].text)
        assertEquals("x = 1 # note\ns = 'a' + \"b\nrest", runs.joinToString("") { it.text })
    }
    @Test fun gutterCountsLinesFromOne() { assertEquals("1", ScriptHighlighter.lineNumbers("")); assertEquals("1\n2\n3", ScriptHighlighter.lineNumbers("a\nb\nc")) }
    @Test fun commentLinesAreTheOnesStartingWithHash() {
        assertEquals(listOf(true, false, true), ScriptHighlighter.commentLines("#!/bin/sh\nset -e\n  # indented").map { it.second })
    }
    @Test fun moonPhaseFollowsTheCycleFromTheReferenceNewMoon() {
        assertEquals("new moon", MoonPhase.currentName(LocalDate.of(2000, 1, 6)))
        assertEquals("full moon", MoonPhase.currentName(LocalDate.of(2000, 1, 21)))    // ~14.8 days in
        assertEquals("last quarter", MoonPhase.currentName(LocalDate.of(2000, 1, 28)))  // ~22 days in
        assertEquals(MoonPhase.names.size, MoonPhase.glyphs.size)
        assertEquals("a date before the reference still resolves", 8, MoonPhase.names.size.also { MoonPhase.index(LocalDate.of(1999, 12, 1)) })
    }
}
