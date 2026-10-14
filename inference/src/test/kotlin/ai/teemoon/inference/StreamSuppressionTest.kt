// ports: teemoonTests/StreamSuppressionTests.swift @ v1.0.1 (all 8) — END TO END: real SSE bytes through SseStreamParser,
//        its Content events through StreamingMarkupElision, composed exactly as OpenAiTransport composes them. The
//        elision's own cases are StreamingMarkupElisionTest; what this adds is the pair, and the tool call's recovery.
package ai.teemoon.inference

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "It still returns the bottom half of a fast generation in one batch." The batching was in DELIVERY: suppression was
 * sticky — once a tool-call marker was seen, content stopped being forwarded for the rest of the turn. The contract:
 * prose before a marker streams; prose after a CLOSED call streams too; raw markup never reaches the forwarded stream,
 * even split across deltas (it cannot be retracted once shown); an UNTERMINATED region stays suppressed to end of turn.
 */
class StreamSuppressionTest {
    /** One SSE content-delta event, JSON-built (not templated) so quotes and newlines arrive as a server sends them. */
    private fun chunk(text: String): String {
        val payload = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", JsonPrimitive(0)); put("delta", buildJsonObject { put("content", JsonPrimitive(text)) }); put("finish_reason", JsonNull)
            }) })
        }
        return "data: $payload\n\n"
    }

    private class Run(val visible: String, val raw: String, val sawDone: Boolean)

    /** The transport's loop: parse, elide Content, finish — and keep the raw text the call is recovered from. */
    private fun run(sse: String, hasTools: Boolean): Run {
        val parser = SseStreamParser(); val elision = StreamingMarkupElision(enabled = hasTools); val raw = StringBuilder()
        for (event in parser.consume(sse)) if (event is SseEvent.Content) { raw.append(event.text); elision.append(event.text) }
        elision.finish()
        return Run(elision.visibleContent, raw.toString(), parser.sawDone)
    }

    private val webSearch = RecoveryToolSpec("web_search", listOf("query", "count"), listOf("query"))

    /** Regression (2026-08-06): the transport breaks its read loop on [DONE], so the parser must record that it came. */
    @Test fun doneIsRecordedSoTheTransportCanStopReading() {
        val parser = SseStreamParser()
        parser.consume(chunk("hello "))
        assertFalse("no [DONE] has arrived yet", parser.sawDone)
        parser.consume("data: [DONE]\n\n")
        assertTrue("the wire said [DONE]; the flag must say so", parser.sawDone)
    }

    /** The control: no marker, everything streams. */
    @Test fun allContentIsForwardedWhenNoToolMarkerAppears() {
        val sse = (0 until 10).joinToString("") { chunk("part $it. ") }
        assertTrue("ordinary content must stream through untouched", run(sse, hasTools = true).visible.contains("part 9."))
    }

    /** The fix: prose after a CLOSED call streams, the markup between the halves never does, and the call is still recovered. */
    @Test fun proseAfterAClosedToolCallResumesStreaming() {
        var sse = (0 until 5).joinToString("") { chunk("first half $it. ") }
        sse += chunk("""<tool_call>{"name": "web_search", "arguments": {"query": "windfinder"}}</tool_call>""")
        sse += (0 until 5).joinToString("") { chunk("second half $it. ") }
        sse += "data: [DONE]\n\n"
        val r = run(sse, hasTools = true)
        assertTrue("text before the marker should already have streamed", r.visible.contains("first half 4."))
        assertTrue("content after a CLOSED tool call must keep streaming — withholding it to [DONE] is the reported " +
            "'bottom half of a fast generation in one batch'", r.visible.contains("second half 4."))
        assertFalse("opening markup leaked", r.visible.contains("<tool_call>"))
        assertFalse("closing markup leaked", r.visible.contains("</tool_call>"))
        assertFalse("tool-call payload leaked", r.visible.contains("web_search"))
        // Resuming the prose must not cost the tool round.
        assertEquals("the closed call must still be recovered at [DONE]", "web_search", ToolCallFormat.parseAny(r.raw, listOf(webSearch))[0]?.name)
    }

    /** Non-negotiable: raw markup never appears in the forwarded stream, even spread across many deltas. */
    @Test fun toolCallMarkupIsNeverForwarded() {
        val sse = chunk("Checking. ") + chunk("<tool_call>") + chunk("""{"name": "web_search", "arguments": {"query": "Da Nang"}}""") +
            chunk("</tool_call>") + chunk("Found it.") + "data: [DONE]\n\n"
        val v = run(sse, hasTools = true).visible
        for (leak in listOf("<tool_call>", "</tool_call>", "web_search", "Da Nang")) assertFalse("$leak leaked", v.contains(leak))
        assertTrue(v.contains("Checking.")); assertTrue(v.contains("Found it."))
    }

    /** A region that opens and never closes: partial markup must not leak, nor what the model wrote after the marker. */
    @Test fun unterminatedMarkupStaysSuppressed() {
        var sse = (0 until 5).joinToString("") { chunk("first half $it. ") }
        sse += chunk("<tool_call>") + chunk("""{"name": "web_search"""")
        sse += (0 until 5).joinToString("") { chunk("tail $it. ") }
        sse += "data: [DONE]\n\n"
        val v = run(sse, hasTools = true).visible
        assertTrue("prose before the marker still streams", v.contains("first half 4."))
        assertFalse("unterminated markup leaked", v.contains("<tool_call>"))
        assertFalse("payload of an unterminated call leaked", v.contains("web_search"))
        assertFalse("everything inside an unterminated region stays suppressed — the end-of-turn sanitize pass, not the " +
            "stream, decides what of it survives", v.contains("tail 4."))
    }

    /** Markers split across SSE deltas: neither missed (leaking markup) nor forwarded-then-retracted. */
    @Test fun markerSplitAcrossChunkBoundariesIsStillElided() {
        val sse = chunk("Before. ") + chunk("<tool_") + chunk("call>") + chunk("""{"name": "web_search", "arguments": {"query": "split"}}""") +
            chunk("</tool_") + chunk("call>After.") + "data: [DONE]\n\n"
        val v = run(sse, hasTools = true).visible
        assertTrue(v.contains("Before."))
        assertTrue("prose resuming right after a split closing marker must stream", v.contains("After."))
        assertFalse("a partial marker was forwarded — it can't be retracted once shown", v.contains("<tool_"))
        assertFalse("markup fragment leaked", v.contains("call>"))
        assertFalse("payload leaked", v.contains("web_search"))
    }

    /** A second marker family — Gemma's fenced ```tool_code — gets the same region elision, and is still recovered. */
    @Test fun gemmaToolCodeFenceIsElidedAndProseResumes() {
        val sse = chunk("Let me look that up. ") + chunk("```tool_code\nweb_search(query=\"weather in SF\")\n```") +
            chunk(" Here is what I found.") + "data: [DONE]\n\n"
        val r = run(sse, hasTools = true)
        assertTrue(r.visible.contains("Let me look that up."))
        assertTrue("prose after the closed fence must stream", r.visible.contains("Here is what I found."))
        assertFalse("fence markup leaked", r.visible.contains("tool_code"))
        assertFalse("fenced call payload leaked", r.visible.contains("web_search"))
        assertEquals("the Gemma call must still be recovered", "web_search", ToolCallFormat.parseAny(r.raw, listOf(webSearch))[0]?.name)
    }

    /** A held-back tail that turns out to be prose (a `<` at a chunk boundary) must still reach the visible text. */
    @Test fun ambiguousMarkerPrefixAtChunkBoundaryIsReleased() {
        val sse = chunk("a <tool") + chunk(" is what it is. ") + "data: [DONE]\n\n"
        assertTrue("prose that merely LOOKS like a marker prefix must not be lost", run(sse, hasTools = true).visible.contains("a <tool is what it is."))
    }
}
