// ports: teemoonTests/LiteRTConversationReuseTests.swift @ a6ce6e8 (anInlineThoughtIsStrippedFromTheReply,
//        anInlineThoughtNeverReachesAPartial)
//        + Android-only, through the transport over a scripted engine: Think Harder's ThinkingConfig and cap, the
//        "thinking" header, a capped turn marked truncated, and the audit's sentinel (3917.8) — the thought is in no
//        partial, not the reply, not reasoning, not the card's body or headers, not the log.
//
// the maintainer, 2026-10-22: Gemma's thinking is handled like cloud reasoning — the "thinking…" timer, then the answer; the
// thought is never shown or stored. The GPU bundle sends it in Message.channels["thought"], which the factory never
// reads; the Tensor NPU bundle writes it INLINE as `<|channel>thought…<channel|>` (Pixel 10 Pro, 2026-10-22).
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceThinkingTest {
    private val sentinel = "SENTINEL-THOUGHT-7f3a"

    private class Engine(val pieces: List<String>, val decodeTokens: Int = 42) : LocalEngineHandle {
        val specs = ArrayList<LocalConversationSpec>()
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) { specs += spec; pieces.forEach { onPiece(it) } }
        override fun benchmark() = LocalBenchmark(0.8, 120, decodeTokens, 900.0, 18.0)
        override fun close() {}
        override fun dropKept() {}
    }
    private class Factory(val engine: Engine) : LocalEngineFactory {
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = engine
        override val backendLabel = "test"
    }
    private fun transport(engine: Engine, thinking: Boolean, log: (String) -> Unit = {}) = LiteRTTransport("/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm", 2468,
        cache = LocalEngineCache(Factory(engine), residency = LocalEngineResidency(), gate = LocalGenerationGate(), memoryCheck = {}, fileExists = { true }, nowMs = { 0L }),
        bridge = LiteRTToolBridge(LiteRTToolRegistry()), gate = LocalGenerationGate(), backendLabel = "test", thinking = thinking, log = log)
    private fun msg(role: String, content: String): Map<String, Any> = mapOf("role" to role, "content" to content)

    /** Android's NPU bundle puts the thought inline; it is stripped from the reply. */
    @Test fun anInlineThoughtIsStrippedFromTheReply() {
        val raw = "<|channel>thought\nCount the r's. $sentinel<channel|>There are 3 r's."
        val parsed = ThinkingContentParser.parse(LiteRTTransport.strippingInlineThought(raw))
        assertNull(parsed.thinking)
        assertEquals("There are 3 r's.", parsed.answer)
        val cutOff = ThinkingContentParser.parse(LiteRTTransport.strippingInlineThought("<|channel>thought\nstill $sentinel"))
        assertTrue("a thought cut off by the cap leaves no reply", cutOff.answer.isNullOrEmpty())
        assertNull("and nothing for the reasoning-only fallback to promote", cutOff.thinking)
        assertEquals("Hello.", LiteRTTransport.strippingInlineThought("Hello."))
    }

    /** While it streams, an inline thought never reaches a partial, however the chunks split it. */
    @Test fun anInlineThoughtNeverReachesAPartial() {
        val stream = "Sure. <|channel>thought\nCount: $sentinel<channel|>There are 3 r's."
        for (size in 1..12) {
            val filter = InlineThoughtFilter()
            var shown = ""
            for (chunk in stream.chunked(size)) {
                shown += filter.append(chunk)
                assertTrue("chunk size $size: $shown", !shown.contains(sentinel) && !shown.contains("<|"))
            }
            shown += filter.finish()
            assertEquals("chunk size $size", "Sure. There are 3 r's.", shown)
        }
        val cut = InlineThoughtFilter()
        assertEquals("", cut.append("<|channel>thought\n$sentinel"))
        assertEquals("a thought the cap cut off shows nothing", "", cut.finish())
        val plain = InlineThoughtFilter()
        assertEquals("a < b", plain.append("a < b") + plain.finish())
    }

    /** The same through the transport: no partial, the reply, reasoning, the card or the log ever holds the thought. */
    @Test fun theThoughtIsInNoPartialNoReplyNoCardAndNoLog() = runTest {
        val stream = "Sure. <|channel>thought\nCount: $sentinel<channel|>There are 3 r's."
        for (size in 1..12) {
            val partials = ArrayList<String>(); val logs = ArrayList<String>()
            val out = transport(Engine(stream.chunked(size)), thinking = true, log = { logs += it })
                .runTurn(listOf(msg("system", "persona"), msg("user", "How many r's in strawberry?")), false, emptyList()) { partials += it }
            assertTrue("chunk size $size: $partials", partials.none { it.contains(sentinel) || it.contains("<|") || it.contains("channel") })
            assertEquals("chunk size $size", "Sure. There are 3 r's.", out.content)
            assertTrue(out.reasoning.isEmpty())
            assertFalse(out.report.requestBodyJson.orEmpty().contains(sentinel))
            assertFalse(out.report.requestHeaders.orEmpty().values.any { it.contains(sentinel) })
            assertTrue(logs.none { it.contains(sentinel) })
        }
    }

    /** Think Harder: thinking on, room for a thought on top of the answer, and the card says so. */
    @Test fun thinkHarderTurnsThinkingOnWithRoomForTheThought() = runTest {
        val on = Engine(listOf("408")); val off = Engine(listOf("408"))
        val outOn = transport(on, thinking = true).runTurn(listOf(msg("user", "17 × 24?")), false, emptyList()) {}
        val outOff = transport(off, thinking = false).runTurn(listOf(msg("user", "17 × 24?")), false, emptyList()) {}
        assertTrue(on.specs.single().enableThinking); assertFalse(off.specs.single().enableThinking)
        assertEquals("4800 answer + 2048 thinking (iOS a6ce6e8)", 6848, on.specs.single().maxOutputTokens)
        assertEquals(4800, off.specs.single().maxOutputTokens)
        assertEquals("on", outOn.report.requestHeaders?.get("thinking")); assertEquals("off", outOff.report.requestHeaders?.get("thinking"))
        assertFalse(outOn.wasTruncated)
    }

    /** The cap ended the turn mid-thought: truncated, no reply, no reasoning — the engine's "no reply" error, never the thought. */
    @Test fun aTurnTheCapEndsMidThoughtIsTruncatedWithNoReply() = runTest {
        val out = transport(Engine(listOf("<|channel>thought\n", "13:15 = 12:60 + 15 ", sentinel), decodeTokens = 6848), thinking = true)
            .runTurn(listOf(msg("user", "minutes from 9:40 to 13:15?")), false, emptyList()) {}
        assertTrue(out.wasTruncated)
        assertEquals("", out.content)
        assertEquals("", out.reasoning)
    }
}
