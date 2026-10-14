// ports: teemoonTests/FaultTests.swift @ v1.0.1 (the "ThinkingContentParser Faults" suite; the two
//        PerformanceBenchmarks measures are XCTest timing, not portable)
package ai.teemoon.inference

import ai.teemoon.inference.ThinkingContentParser.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingContentParserTest {
    private fun p(s: String) = ThinkingContentParser.parse(s)

    @Test fun parse_noThinkTag_allIsAnswer() { assertEquals(Parsed(null, "Just an answer"), p("Just an answer")) }
    @Test fun parse_openThinkTag_noClose_allIsThinking() { assertEquals(Parsed("I'm thinking...", null), p("<think>I'm thinking...")) }
    @Test fun parse_emptyThinkTag_emptyThinking() { assertEquals(Parsed("", "Answer here"), p("<think></think>Answer here")) }
    @Test fun parse_thinkTagOnly_noAnswer() { assertEquals(Parsed("Thinking content", null), p("<think>Thinking content</think>")) }
    @Test fun parse_thinkWithWhitespaceAnswer_noAnswer() { assertEquals(Parsed("Thinking", null), p("<think>Thinking</think>   \n  ")) }
    @Test fun parse_normalCase_splitsCorrectly() { assertEquals(Parsed("Let me think", "Here's the answer"), p("<think>Let me think</think>Here's the answer")) }
    @Test fun parse_emptyString_emptyAnswer() { assertEquals(Parsed(null, ""), p("")) }
    @Test fun parse_whitespaceOnly_emptyAnswer() { assertEquals(Parsed(null, ""), p("   \n  ")) }
    /** The parser uses the first <think> and the first </think>. */
    @Test fun parse_nestedThinkTags_usesFirst() { assertEquals(Parsed("outer<think>inner", "after inner</think>final answer"), p("<think>outer<think>inner</think>after inner</think>final answer")) }
    @Test fun parse_textBeforeThinkTag_thinkingStartsAtTag() { assertEquals(Parsed("thinking", "answer"), p("preamble<think>thinking</think>answer")) }

    // MARK: Gemma 4 channel markup — what the Tensor NPU bundle emits

    @Test fun channelThoughtStillOpenIsThinkingWithNoAnswer() {
        val p = ThinkingContentParser.parse("<|channel>thought Thinking Process:\n1. Analyze the request")
        assertEquals("Thinking Process:\n1. Analyze the request", p.thinking); assertNull(p.answer)
    }

    @Test fun channelThoughtClosedSplitsIntoThinkingAndAnswer() {
        val p = ThinkingContentParser.parse("<|channel>thought Recall the moons.\nFinal Output Generation.<channel|>Three moons of Uranus are: Titania, Oberon, Umbriel.")
        assertEquals("Recall the moons.\nFinal Output Generation.", p.thinking)
        assertEquals("Three moons of Uranus are: Titania, Oberon, Umbriel.", p.answer)
    }

    /** The general bundle's `<think>` is untouched by the channel support, and whichever opener comes first wins. */
    @Test fun thinkTagsAreUnchangedAndTheFirstOpenerWins() {
        assertEquals(ThinkingContentParser.Parsed("a", "b"), ThinkingContentParser.parse("<think>a</think>b"))
        val p = ThinkingContentParser.parse("<think>t</think>answer mentioning <|channel>thought later")
        assertEquals("t", p.thinking); assertEquals("answer mentioning <|channel>thought later", p.answer)
    }

    /** A closer for the OTHER markup does not end this one: `</think>` inside a channel block is just text. */
    @Test fun aForeignCloserDoesNotEndTheBlock() {
        val p = ThinkingContentParser.parse("<|channel>thought I could write </think> here<channel|>done")
        assertEquals("I could write </think> here", p.thinking); assertEquals("done", p.answer)
    }
}
