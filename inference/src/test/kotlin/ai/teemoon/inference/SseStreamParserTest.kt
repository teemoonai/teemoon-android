// ports: teemoonTests/SSEParserTests.swift @ v1.0.1 (event/delta cases)
package ai.teemoon.inference

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamParserTest {

    private fun chunk(content: String) =
        """data: {"choices":[{"index":0,"delta":{"content":"$content"}}]}""" + "\n\n"

    @Test fun emitsContentDeltas() {
        val p = SseStreamParser()
        val events = p.consume(chunk("Hello ")) + p.consume(chunk("world"))
        assertEquals(listOf("Hello ", "world"), events.filterIsInstance<SseEvent.Content>().map { it.text })
    }

    /** The network splits wherever it likes; an event straddling a read must survive. */
    @Test fun reassemblesAnEventSplitAcrossReads() {
        val p = SseStreamParser()
        val whole = chunk("split me")
        val cut = whole.length / 2
        assertTrue(p.consume(whole.substring(0, cut)).isEmpty())
        val events = p.consume(whole.substring(cut))
        assertEquals("split me", (events.single() as SseEvent.Content).text)
    }

    @Test fun recognisesDoneFraming() {
        val p = SseStreamParser()
        val events = p.consume("data: [DONE]\n\n")
        assertEquals(SseEvent.Done, events.single())
        assertTrue(p.sawDone)
    }

    /** DeepSeek-style key. */
    @Test fun readsReasoningContentKey() {
        val p = SseStreamParser()
        val e = p.consume("""data: {"choices":[{"delta":{"reasoning_content":"thinking"}}]}""" + "\n\n")
        val r = e.single() as SseEvent.Reasoning
        assertEquals("reasoning_content", r.key)
        assertEquals("thinking", r.text)
    }

    /** Ollama 0.32+ key — reading only the other one drops the whole chain of thought. */
    @Test fun readsOllamaReasoningKey() {
        val p = SseStreamParser()
        val e = p.consume("""data: {"choices":[{"delta":{"reasoning":"pondering"}}]}""" + "\n\n")
        assertEquals("reasoning", (e.single() as SseEvent.Reasoning).key)
    }

    @Test fun ignoresNonDataLinesAndGarbage() {
        val p = SseStreamParser()
        assertTrue(p.consume(": keep-alive\n\n").isEmpty())
        assertTrue(p.consume("data: not json\n\n").isEmpty())
        assertTrue(p.consume("""data: {"choices":[]}""" + "\n\n").isEmpty())
    }

    /** The OpenAI path: name once, arguments in slices, concatenated in order. */
    @Test fun assemblesStructuredToolCallFragments() {
        val p = SseStreamParser()
        p.consume("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"web_search","arguments":"{\"qu"}}]}}]}""" + "\n\n")
        p.consume("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ery\":\"windfinder\"}"}}]}}]}""" + "\n\n")
        val call = p.toolCalls[0]!!
        assertEquals("web_search", call.name)
        assertEquals("call_a", call.id)
        assertEquals("""{"query":"windfinder"}""", call.arguments)
    }

    @Test fun reportsFinishReason() {
        val p = SseStreamParser()
        val e = p.consume("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\n")
        assertEquals("tool_calls", (e.single() as SseEvent.Finish).reason)
    }

    /** SSEParser.swift: the first delta of ANY kind is time-to-first-token; the first non-empty content is when the user saw something. */
    @Test fun firstTokenAndFirstVisibleTokenAreStampedOnTheRawDeltas() {
        var t = 0L
        val p = SseStreamParser(now = { Instant.ofEpochMilli(++t) })
        p.consume("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}""" + "\n\n")
        assertEquals(Instant.ofEpochMilli(1), p.firstDeltaAt); assertEquals(null, p.firstVisibleAt)
        p.consume("""data: {"choices":[{"delta":{"content":""}}]}""" + "\n\n")
        assertEquals("an empty content delta is not visible", null, p.firstVisibleAt)
        p.consume(chunk("hi")); p.consume(chunk("there"))
        assertEquals(Instant.ofEpochMilli(1), p.firstDeltaAt); assertEquals("the clock only ticks when a stamp is taken", Instant.ofEpochMilli(2), p.firstVisibleAt)
    }

    @Test fun emptyContentIsNotAnEvent() {
        val p = SseStreamParser()
        assertTrue(p.consume("""data: {"choices":[{"delta":{"content":""}}]}""" + "\n\n").isEmpty())
    }
}
