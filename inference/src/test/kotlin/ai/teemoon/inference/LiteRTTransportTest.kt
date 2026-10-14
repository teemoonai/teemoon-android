// Kotlin-self pins for LiteRTTransport over a scripted engine — the measured rationale from the Swift source as tests:
// seed-don't-replay, the assistant-is-MODEL role map, oldest-first trimming, streamed elision, tool records on the report,
// the memory gate before load, one resident engine, the generation gate; one shared build per key (56d51d7) and no eviction
// under a running turn (5525c8a's rule). (LiteRTLiveTests / LocalEngineSmokeTests are device-only.)
package ai.teemoon.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRTTransportTest {
    private class ScriptedEngine(val pieces: List<String>, val runTool: Boolean = false) : LocalEngineHandle {
        var specs = ArrayList<LocalConversationSpec>(); var closed = false
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {
            specs += spec
            if (runTool) spec.tools.firstOrNull()?.let { it.execute("""{"query":"weather sf","count":5}""") }
            pieces.forEach { onPiece(it) }
        }
        override fun benchmark() = LocalBenchmark(0.8, 120, 42, 900.0, 60.0)
        override fun close() { closed = true }
    }
    private class Factory(val engine: () -> ScriptedEngine) : LocalEngineFactory {
        var opened = ArrayList<String>()
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle { opened += "$modelPath@$contextTokens" + (if (requireNpu) "!npu" else ""); return engine() }
        override val backendLabel = "test"
    }
    private fun cache(factory: LocalEngineFactory, memoryCheck: (Int) -> Unit = {}, residency: LocalEngineResidency = LocalEngineResidency(), gate: LocalGenerationGate = LocalGenerationGate()) =
        LocalEngineCache(factory, residency = residency, gate = gate, memoryCheck = memoryCheck, fileExists = { true }, nowMs = run { var t = 0L; { t += 100; t } })
    private fun transport(cache: LocalEngineCache, events: StreamCallbacks? = null, tools: LiteRTToolBridge = LiteRTToolBridge(LiteRTToolRegistry()), gate: LocalGenerationGate = LocalGenerationGate()) =
        LiteRTTransport("/models/gemma-4-E2B-it.litertlm", 2468, cache = cache, bridge = tools, gate = gate, events = events, backendLabel = "test")
    private fun msg(role: String, content: String): Map<String, Any> = mapOf("role" to role, "content" to content)

    @Test fun historyIsSeededNotReplayed_andTheAssistantIsTheModel() = runTest {
        val engine = ScriptedEngine(listOf("hi")); val t = transport(cache(Factory { engine }))
        t.runTurn(listOf(msg("system", "be brief"), msg("user", "one"), msg("assistant", "1"), msg("user", "two")), includeTools = false, tools = emptyList()) {}
        val spec = engine.specs.single()
        assertEquals("be brief", spec.systemMessage); assertEquals("two", spec.userText)
        assertEquals(listOf(LocalRole.USER to "one", LocalRole.MODEL to "1"), spec.history)
        assertEquals(LocalRole.MODEL, LiteRTTransport.role("assistant")); assertEquals(LocalRole.TOOL, LiteRTTransport.role("tool")); assertEquals(LocalRole.USER, LiteRTTransport.role(null))
    }
    @Test fun trimmingKeepsTheMostRecentTurnsThatFit() {
        val turns = listOf(msg("user", "a".repeat(400)), msg("assistant", "b".repeat(400)), msg("user", "c".repeat(400)))
        val kept = LiteRTTransport.trimmedHistory(turns, budgetTokens = 210)
        assertEquals(listOf("b".repeat(400), "c".repeat(400)), kept.map { it["content"] })
        assertTrue(LiteRTTransport.trimmedHistory(turns, 0).isEmpty()); assertEquals(1, LiteRTTransport.estimatedTokens("abc"))
    }
    /**
     * The runtime is asked for what iOS asks for. iOS declares thinking ON and 1024 tokens but passes neither to
     * LiteRT — no ThinkingConfig (runtime default: off) and no maxOutputTokens, its own cap being 4800. The first
     * port passed the declarations, and only Android thought (see LocalDefaults).
     */
    @Test fun theConversationAsksForWhatIosActuallyAsksFor() = runTest {
        val engine = ScriptedEngine(listOf("hi")); val t = transport(cache(Factory { engine }))
        t.runTurn(listOf(msg("user", "one")), includeTools = false, tools = emptyList()) {}
        val spec = engine.specs.single()
        assertFalse("thinking is off, as on iOS", spec.enableThinking)
        assertEquals("iOS answerReserveTokens", 4800, spec.maxOutputTokens)
        assertEquals(LocalDefaults.TEMPERATURE, spec.temperature)
    }
    /** Gemma's thinking never streams into the bubble: the chip covers it, as it does for a cloud model's reasoning. */
    @Test fun thinkingNeverReachesAPartial_inEitherMarkup() = runTest {
        for (pieces in listOf(
            listOf("<think>", "weighing ", "it", "</think>", "Sunny", " today."),
            listOf("<|channel>thought\n", "weighing ", "it", "<channel|>", "Sunny", " today."),
        )) {
            val partials = ArrayList<String>()
            val out = transport(cache(Factory { ScriptedEngine(pieces) })).runTurn(listOf(msg("user", "q")), false, emptyList()) { partials += it }
            assertTrue("no thinking in $partials", partials.none { it.contains("weighing") || it.contains("think") || it.contains("channel") })
            assertEquals("Sunny today.", partials.last().trim())
            assertTrue(out.reasoning.orEmpty().contains("weighing it"))
        }
    }

    /**
     * The opener arrives in PIECES on the device (Pixel 10 Pro, 2026-10-03: "<|channel>" on screen while gemma thought):
     * before "thought" lands, "<|channel>" is not yet the full opener, so it parsed as answer text and streamed.
     * Every prefix of an opener is held back until it either completes or proves to be prose.
     */
    @Test fun aSplitOpenerNeverFlashesOnScreen() = runTest {
        for (pieces in listOf(
            listOf("<|", "chan", "nel>", "thought", "\nweighing", " it", "<chan", "nel|>", "Sunny", " today."),
            listOf("<", "th", "ink>", "weighing it", "</th", "ink>", "Sunny today."),
        )) {
            val partials = ArrayList<String>()
            val out = transport(cache(Factory { ScriptedEngine(pieces) })).runTurn(listOf(msg("user", "q")), false, emptyList()) { partials += it }
            assertTrue("markup on screen: $partials", partials.none { it.contains("<") || it.contains("channel") || it.contains("think") || it.contains("weighing") })
            assertEquals("Sunny today.", partials.last().trim())
            assertTrue(out.reasoning.orEmpty().contains("weighing it"))
        }
    }

    /** A reply that merely STARTS with "<" is prose once it stops matching an opener — held only while it might be one. */
    @Test fun proseThatStartsLikeMarkupStillStreams() = runTest {
        val partials = ArrayList<String>()
        transport(cache(Factory { ScriptedEngine(listOf("<", "3 is a heart")) })).runTurn(listOf(msg("user", "q")), false, emptyList()) { partials += it }
        assertEquals("<3 is a heart", partials.last())
    }

    @Test fun partialsAreElided_andTheRawAnswerKeepsThinking() = runTest {
        val engine = ScriptedEngine(listOf("<think>hmm</think>Checking. ", "<tool_", "call>", """{"name":"web_search","arguments":{"query":"x"}}""", "</tool_", "call>Found it."))
        val partials = ArrayList<String>()
        val out = transport(cache(Factory { engine })).runTurn(listOf(msg("user", "q")), false, emptyList()) { partials += it }
        assertTrue(partials.none { it.contains("<tool_") || it.contains("web_search") })
        assertTrue(partials.last().contains("Found it."))
        assertEquals("hmm", out.reasoning); assertTrue(out.content.contains("Checking.")); assertTrue(out.toolCalls.isEmpty())
        assertEquals(42, out.completionTokens); assertNull(out.report.url); assertEquals("LiteRT-LM", out.report.requestHeaders?.get("runtime")); assertEquals("test", out.report.requestHeaders?.get("backend"))
    }
    @Test fun toolRoundsRunInsideTheRuntimeAndAreReportedNotReExecuted() = runTest {
        val tool = object : Tool {
            override val name = "web_search"; override val description = "x"; override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}}}"""
            override suspend fun execute(argumentsJson: String) = """<source index="1"><url>https://a</url><title>A</title><content>c</content></source>"""
        }
        val foundQueries = ArrayList<String>(); var foundSources = 0; var ended = 0
        val events = object : StreamCallbacks { override fun onText(text: String) {}; override fun onQueriesFound(queries: List<String>) { foundQueries += queries }; override fun onSourcesFound(sources: List<GroundingSource>) { foundSources += sources.size }; override fun onToolExecutionEnded() { ended++ } }
        val engine = ScriptedEngine(listOf("62°F"), runTool = true)
        val out = transport(cache(Factory { engine }), events).runTurn(listOf(msg("user", "weather?")), includeTools = true, tools = listOf(tool)) {}
        assertEquals(listOf("weather sf"), foundQueries); assertEquals(1, foundSources); assertEquals(1, ended)
        assertEquals("the engine must not run the call a second time", 0, out.toolCalls.size)
        assertEquals(1, out.report.executedToolCalls.size); assertEquals("web_search", out.report.executedToolCalls.single().name)
        assertEquals("1", out.report.requestHeaders?.get("tools-offered"))
    }
    @Test fun theMemoryGateRunsBeforeTheLoadAndLeavesNothingResident() = runTest {
        val factory = Factory { ScriptedEngine(listOf("x")) }; val residency = LocalEngineResidency()
        val c = cache(factory, memoryCheck = { need -> if (need > 2000) throw LocalInferenceError.InsufficientMemory(need, 1000) }, residency = residency)
        try { transport(c).runTurn(listOf(msg("user", "q")), false, emptyList()) {}; assertTrue("should have refused", false) } catch (e: LocalInferenceError.InsufficientMemory) { assertEquals(2468 + 512, e.needMB) }
        assertTrue(factory.opened.isEmpty()); assertTrue(residency.resident.value.isEmpty())
    }
    @Test fun oneEngineStaysResident_theOtherIsEvictedAndClosed() = runTest {
        val engines = ArrayList<ScriptedEngine>(); val factory = Factory { ScriptedEngine(listOf("x")).also { engines += it } }
        val residency = LocalEngineResidency(); val c = cache(factory, residency = residency)
        c.engine("/a.litertlm", 8192, 100, 0); c.engine("/a.litertlm", 8192, 100, 0)
        assertEquals("the same key reuses the engine", 1, factory.opened.size)
        c.engine("/a.litertlm", 4096, 100, 0)
        assertEquals("a different context size is a different engine", 2, factory.opened.size); assertTrue(engines[0].closed); assertEquals(1, c.residentCount)
        assertEquals(setOf("/a.litertlm"), residency.resident.value); assertEquals(100L, residency.lastLoad?.tookMs)
        c.evictAll(); assertEquals(0, c.residentCount); assertTrue(residency.resident.value.isEmpty()); assertTrue(engines[1].closed)
    }
    @Test fun aMissingBundleIsRefusedBeforeAnyLoad() = runTest {
        val factory = Factory { ScriptedEngine(listOf("x")) }
        val c = LocalEngineCache(factory, LocalEngineResidency(), fileExists = { false })
        try { c.engine("/gone.litertlm", 8192, 1, 0); assertTrue(false) } catch (e: LocalInferenceError.ModelNotDownloaded) { assertEquals("gone.litertlm", e.id) }
        assertTrue(factory.opened.isEmpty())
    }
    @Test fun generationsAreSerialized() = runTest {
        val gate = LocalGenerationGate(); var inFlight = 0; var maxInFlight = 0
        val jobs = (1..3).map { this@runTest.launch { gate.withTurn { inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); yield(); inFlight-- } } }
        jobs.forEach { it.join() }
        assertEquals(1, maxInFlight); assertFalse(gate.isBusy)
    }

    // MARK: a small window (the Tensor NPU bundle is compiled for 4096)

    /**
     * The fixed part of the prompt cannot be trimmed, so a window it does not fit is refused BEFORE the runtime is
     * asked, with the cause in the message — the runtime's own "Input token ids are too long" names none.
     */
    @Test fun aPromptThatCannotFitTheWindowIsRefusedUpFrontWithTheCauseNamed() = runTest {
        val engine = ScriptedEngine(listOf("never"))
        val t = LiteRTTransport("/m/gemma-4-E2B-it_Google_Tensor_G5.litertlm", 3770, cache = cache(Factory { engine }), bridge = LiteRTToolBridge(LiteRTToolRegistry()),
            gate = LocalGenerationGate(), backendLabel = "test", requireNpu = true, contextTokens = 4096)
        val huge = "x".repeat(4 * 4200)   // ~4200 tokens by the transport's own pessimistic estimate
        val thrown = runCatching { t.runTurn(listOf(msg("system", huge), msg("user", "hi")), includeTools = false, tools = emptyList()) {} }.exceptionOrNull()
        assertTrue("expected PromptTooLarge, got $thrown", thrown is LocalInferenceError.PromptTooLarge)
        val e = thrown as LocalInferenceError.PromptTooLarge
        assertEquals(4096, e.windowTokens); assertTrue(e.promptTokens > 4096); assertEquals(0, e.toolTokens)
        assertTrue("the message should tell the user what to do: ${e.message}", e.message!!.contains("shorter message"))
        assertTrue("the runtime must never have been asked", engine.specs.isEmpty())
    }

    /**
     * The 4800-token answer reserve was sized for an 8192 window. On 4096 it would leave the history budget negative
     * and silently drop every prior turn; the reserve is a third of a small window instead, so history survives.
     */
    @Test fun aSmallWindowKeepsRecentHistoryRatherThanDroppingItAll() = runTest {
        val engine = ScriptedEngine(listOf("ok"))
        val t = LiteRTTransport("/m/gemma-4-E2B-it_Google_Tensor_G5.litertlm", 3770, cache = cache(Factory { engine }), bridge = LiteRTToolBridge(LiteRTToolRegistry()),
            gate = LocalGenerationGate(), backendLabel = "test", requireNpu = true, contextTokens = 4096)
        t.runTurn(listOf(msg("system", "be brief"), msg("user", "one"), msg("assistant", "1"), msg("user", "two"), msg("assistant", "2"), msg("user", "three")), includeTools = false, tools = emptyList()) {}
        val spec = engine.specs.single()
        assertEquals("the small window dropped the history", 4, spec.history.size)
        assertEquals("three", spec.userText)
    }

    /**
     * A warm-up and a turn asking for the same engine share ONE build (56d51d7): the open suspends for seconds, and
     * without the in-flight map the second caller found no engine and built another — two multi-gigabyte engines.
     */
    @Test fun aWarmUpAndATurnShareOneBuild() = runTest {
        val release = CompletableDeferred<Unit>()
        val engine = ScriptedEngine(listOf("hi"))
        val factory = object : LocalEngineFactory {
            var opens = 0
            override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle { opens++; release.await(); return engine }
            override val backendLabel = "test"
        }
        val c = cache(factory)
        val warm = async { c.engine("/m.litertlm", 8192, 2400, 512) }
        val turn = async { c.engine("/m.litertlm", 8192, 2400, 512) }
        testScheduler.runCurrent()
        release.complete(Unit)
        assertTrue(warm.await() === turn.await())
        assertEquals("one build, shared", 1, factory.opens)
        assertEquals(1, c.residentCount)
    }

    /** A failed build is not cached: the next caller tries again rather than inheriting the failure forever. */
    @Test fun aFailedBuildIsNotShared_theNextCallerRetries() = runTest {
        var attempt = 0
        val factory = object : LocalEngineFactory {
            override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle {
                if (attempt++ == 0) throw LocalInferenceError.LoadFailed("first try"); return ScriptedEngine(listOf("hi"))
            }
            override val backendLabel = "test"
        }
        val c = cache(factory)
        assertTrue(runCatching { c.engine("/m.litertlm", 8192, 2400, 512) }.isFailure)
        c.engine("/m.litertlm", 8192, 2400, 512)
        assertEquals(1, c.residentCount)
    }

    /**
     * NEVER EVICT UNDER A RUNNING TURN. Backgrounding sends TRIM_MEMORY_UI_HIDDEN; closing the engine a decode streams
     * from pulls the native session out from under it. The eviction waits for the turn and happens inside the gate,
     * before it releases — iOS's rule from 5525c8a.
     */
    @Test fun anEvictionDuringATurnWaitsForTheTurnToEnd() = runTest {
        val gate = LocalGenerationGate()
        val midStream = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var closedMidStream = false
        val engine = object : LocalEngineHandle {
            var closed = false
            override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {
                onPiece("half"); midStream.complete(Unit); resume.await(); closedMidStream = closed; onPiece(" done")
            }
            override fun benchmark(): LocalBenchmark? = null
            override fun close() { closed = true }
        }
        val c = cache(object : LocalEngineFactory {
            override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = engine
            override val backendLabel = "test"
        }, gate = gate)
        val t = transport(c, gate = gate)
        val turn = async { t.runTurn(listOf(msg("user", "q")), false, emptyList()) {} }
        midStream.await()
        c.evictAll()                                      // the app went to the background mid-answer
        assertEquals("still resident while the turn runs", 1, c.residentCount)
        resume.complete(Unit)
        assertEquals("half done", turn.await().content)
        assertFalse("the engine was closed under the decode", closedMidStream)
        assertEquals("evicted once the turn ended", 0, c.residentCount)
        assertTrue(engine.closed)
    }

    /** With no turn running, an eviction is immediate — memory pressure still gets its gigabytes back at once. */
    @Test fun anEvictionWithNoTurnIsImmediate() = runTest {
        val engine = ScriptedEngine(listOf("hi")); val c = cache(Factory { engine })
        c.engine("/m.litertlm", 8192, 2400, 512)
        c.evictAll()
        assertEquals(0, c.residentCount); assertTrue(engine.closed)
    }
}
