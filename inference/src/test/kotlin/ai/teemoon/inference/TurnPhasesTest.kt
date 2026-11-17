// ports: teemoonTests/TurnPhasesTests.swift @ c76f3bd (+ aMissingFigureReadsByHost of 6fdcb43), teemoonTests/GenerationTransportTests.swift @ v1.1.0 (LateReplySignatureTests)
// Pins for the 1.0.6 phase timings (TurnPhase): the engine times every request and tool round for every
// transport alike, and the card's line reads as offsets from the send.
package ai.teemoon.inference

import java.time.Instant
import org.junit.Assert.assertTrue
import ai.teemoon.confidential.ResponseVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnPhasesTest {
    private val t0 = Instant.parse("2026-10-19T20:00:00Z")
    private fun at(ms: Long) = t0.plusMillis(ms)

    /** iOS TurnPhasesTests.offsetsAreFromTheTapInOrder, byte for byte: the two cards print the same line. */
    @Test fun offsetsAreFromTheTapInOrder() {
        val phases = listOf(
            TurnPhase(TurnPhase.Kind.DONE, at(7300)), TurnPhase(TurnPhase.Kind.GATE, at(800)), TurnPhase(TurnPhase.Kind.SENT, at(810)),
            TurnPhase(TurnPhase.Kind.RESPONSE, at(1200), tokens = 4_000, cachedTokens = 3_680), TurnPhase(TurnPhase.Kind.FIRST_TOKEN, at(1310)),
            TurnPhase(TurnPhase.Kind.TOOL, at(2600), ms = 1200, bytes = 14_500, tokens = 3_625), TurnPhase(TurnPhase.Kind.FIRST_ANSWER, at(5900)),
            TurnPhase(TurnPhase.Kind.VERIFIED, at(9500)))
        assertEquals(92, TurnPhase.cachePercent(phases))
        assertEquals("gate 0.80 · sent 0.81 · resp 1.20 · first 1.31 · tool 1.2s 14KB ~3.6k tok · answer 5.90 · done 7.30 · signed 9.50 · cache 92%",
            TurnPhase.line(t0, phases))
    }

    @Test fun noUsageMeansNoCacheFigure() {
        val phases = listOf(TurnPhase(TurnPhase.Kind.RESPONSE, at(500), tokens = 100), TurnPhase(TurnPhase.Kind.DONE, at(1000)))
        assertEquals(null, TurnPhase.cachePercent(phases))
        assertEquals("resp 0.50 · done 1.00", TurnPhase.line(t0, phases))
        assertEquals(null, TurnPhase.line(t0, emptyList()))
    }

    /** The cache share is over every request in the send: a tool round's second request counts too. */
    @Test fun theCacheShareSumsEveryRequest() {
        assertEquals(50, TurnPhase.cachePercent(listOf(TurnPhase(TurnPhase.Kind.RESPONSE, at(1), tokens = 1000, cachedTokens = 0),
            TurnPhase(TurnPhase.Kind.RESPONSE, at(2), request = 2, tokens = 1000, cachedTokens = 1000))))
    }

    @Test fun parserReadsCachedTokensOpenAIAndDeepSeekStyle() {
        val openai = SseStreamParser().apply { consume("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":3968,\"completion_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":3840}}}\n\n") }
        assertEquals(3968, openai.promptTokens); assertEquals(3840, openai.cachedPromptTokens)
        val deepseek = SseStreamParser().apply { consume("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":3718,\"completion_tokens\":9,\"prompt_cache_hit_tokens\":3712,\"prompt_cache_miss_tokens\":6}}\n\n") }
        assertEquals(3712, deepseek.cachedPromptTokens)
    }

    @Test fun theEngineTimesEachRequestAndToolRound() = runTest {
        var clock = 0L
        val turns = listOf(
            TurnResult(content = "", toolCalls = mapOf(0 to ToolCallState(id = "c0", name = "web_search", arguments = """{"query":"x"}""")),
                report = TurnReport(url = "u", requestHeaders = null, requestBodyJson = null, responseAt = at(300), firstTokenAt = at(350))),
            TurnResult(content = "done.", report = TurnReport(url = "u", requestHeaders = null, requestBodyJson = null,
                responseAt = at(2300), firstTokenAt = at(2400), firstVisibleTokenAt = at(2500))),
        )
        var i = 0
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit) =
                turns[i++].also { clock += 500 }
        }
        val tool = object : Tool {
            override val name = "web_search"; override val description = "x"; override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
            override suspend fun execute(argumentsJson: String): String { clock += 1000; return "é".repeat(100) }
        }
        val results = ArrayList<RequestResult>()
        val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { results += result } }
        GenerationEngine(transport, listOf(tool), events, now = { at(clock) }).run(emptyList(), "q")

        val phases = results.last().phases
        assertEquals(listOf(TurnPhase.Kind.SENT, TurnPhase.Kind.RESPONSE, TurnPhase.Kind.FIRST_TOKEN, TurnPhase.Kind.TOOL,
            TurnPhase.Kind.SENT, TurnPhase.Kind.RESPONSE, TurnPhase.Kind.FIRST_TOKEN, TurnPhase.Kind.FIRST_ANSWER, TurnPhase.Kind.DONE),
            phases.map { it.kind })
        val toolPhase = phases.single { it.kind == TurnPhase.Kind.TOOL }
        assertEquals(1000L, toolPhase.ms); assertEquals(200, toolPhase.bytes); assertEquals(50, toolPhase.tokens); assertEquals(1, toolPhase.request)
        assertEquals(listOf(1, 2), phases.filter { it.kind == TurnPhase.Kind.SENT }.map { it.request })
    }

    /** iOS LateReplySignatureTests: the turn ends before a slow check, and the verdict still arrives. */
    @Test fun theTurnEndsBeforeASlowCheckAndTheVerdictStillArrives() = runTest {
        val turn = TurnResult(content = "answer", report = TurnReport(url = "u", requestHeaders = null, requestBodyJson = null,
            verify = { kotlinx.coroutines.delay(800); ResponseVerification.Verified("0xabc") }))
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit) = turn
        }
        var inline: ResponseVerification? = null; var late: ResponseVerification? = null
        val events = object : StreamCallbacks {
            override fun onText(text: String) {}
            override fun onSuccess(result: RequestResult) { inline = result.teeVerification }
            override val verifiesAfterTurn get() = true
            override fun onVerified(verification: ResponseVerification) { late = verification }
        }
        val started = testScheduler.currentTime
        GenerationEngine(transport, emptyList(), events, lateScope = backgroundScope).run(emptyList(), "q")
        assertTrue("run waited for the signature check", testScheduler.currentTime - started < 500)
        assertEquals(null, inline)
        // advanceUntilIdle leaves backgroundScope alone: step past the check's 800 ms instead.
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals(ResponseVerification.Verified("0xabc"), late)
    }

    @Test fun withoutAListenerTheVerdictStaysInline() = runTest {
        val turn = TurnResult(content = "answer", report = TurnReport(url = "u", requestHeaders = null, requestBodyJson = null,
            verify = { ResponseVerification.Verified("0xabc") }))
        val transport = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit) = turn
        }
        val results = ArrayList<RequestResult>()
        GenerationEngine(transport, emptyList(), object : StreamCallbacks { override fun onText(text: String) {}; override fun onSuccess(result: RequestResult) { results += result } })
            .run(emptyList(), "q")
        assertEquals(ResponseVerification.Verified("0xabc"), results.last().teeVerification)
        assertEquals(TurnPhase.Kind.VERIFIED, results.last().phases.last().kind)
    }

    /** iOS ef5c272: the maintainer's 0% was a chat's first message, which has nothing to reuse yet. */
    @Test fun theCardsCacheLineSaysWhyAFirstMessageHasNone() {
        val t0 = java.time.Instant.EPOCH
        fun resp(prompt: Int, cached: Int) = listOf(TurnPhase(TurnPhase.Kind.RESPONSE, t0, tokens = prompt, cachedTokens = cached))
        assertEquals("cache 92%", TurnPhase.cacheLine(resp(4_000, 3_680), firstMessage = false))
        assertEquals("a later message at 0% is a real miss", "cache 0%", TurnPhase.cacheLine(resp(4_000, 0), firstMessage = false))
        assertEquals("cache — first message", TurnPhase.cacheLine(resp(4_000, 0), firstMessage = true))
        assertEquals("a first message's search follow-up can reuse its own first request", "cache 50%", TurnPhase.cacheLine(resp(4_000, 2_000), firstMessage = true))
        assertEquals(null, TurnPhase.cacheLine(emptyList(), firstMessage = true))
    }

    /** iOS 6fdcb43: near.ai sends `prompt_tokens_details: null` on a miss, so a first near.ai message showed no cache line at all (the maintainer, GLM-5.3-Flash). */
    @Test fun aMissingFigureReadsByHost() {
        val t0 = java.time.Instant.EPOCH
        fun line(host: String?, onDevice: Boolean = false, cached: Int? = null, prompt: Int? = 4_000, first: Boolean = false, e2ee: Boolean = false) =
            TurnPhase.cacheLine(listOf(TurnPhase(TurnPhase.Kind.RESPONSE, t0, tokens = prompt, cachedTokens = cached)), first,
                TurnPhase.cacheReporting(host, onDevice, e2ee))
        for (host in listOf("cloud-api.near.ai", "api.fireworks.ai", "api.x.ai", "openrouter.ai")) {
            assertEquals(host, "cache 0%", line(host))
            assertEquals(host, "cache — first message", line(host, first = true))
            assertEquals(host, "cache 92%", line(host, cached = 3_680))
        }
        for (host in listOf("integrate.api.nvidia.com", "192.168.1.2")) {
            assertEquals(host, "cache — not reported", line(host))
            assertEquals("a figure still shows", "cache 50%", line(host, cached = 2_000))
        }
        assertEquals("no usage at all", "cache — not reported", line("cloud-api.near.ai", prompt = null))
        assertEquals("E2EE near.ai sent null on a repeated prompt", "cache — not reported", line("cloud-api.near.ai", prompt = 5_769, e2ee = true))
        assertEquals("on-device has no provider cache", null, line(null, onDevice = true))
        assertEquals(null, line("api.fireworks.ai", onDevice = true))
    }

    /** near.ai's miss, as the plaintext probe saw it: a prompt count and `prompt_tokens_details: null` — no figure, not a crash, not 0. */
    @Test fun aNullCacheDetailIsNoFigure() {
        val p = SseStreamParser().apply { consume("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":4520,\"completion_tokens\":7,\"prompt_tokens_details\":null}}\n\n") }
        assertEquals(4520, p.promptTokens)
        assertEquals(null, p.cachedPromptTokens)
        val hit = SseStreamParser().apply { consume("data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":4520,\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":4352}}}\n\n") }
        assertEquals(4352, hit.cachedPromptTokens)
    }
}
