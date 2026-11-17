// android-only: the conformance rule (iOS bbfeb0f, conformance/requests.json) through the app layer — iOS 58 read 0%
// cache on Fireworks sends while its transport alone held the prefix (release, 2026-10-22). Two normal sends in one
// chat: what ChatViewModel → GenerationEngine hand the transport must extend turn to turn — send 2's messages begin
// with send 1's, entry for entry, and the tools and the effort are the same. The serializer's half (extending messages
// give extending bytes, keys sorted) is ConformanceRequestsTest's, on the plain JVM; here OkHttp would pick its Android
// platform once Paparazzi has run in the same JVM. A guard only: the live three-send cache check is the gate.
//
// The three cases below (the maintainer via pm, 2026-10-22, for 1.0.6) measure the rule where a chat's history is not plain
// text: a turn that searched, a reply re-asked with Think Harder, a reply that came with thinking. Each compares the
// sorted-key JSON of request N's `messages`, its closing `]` dropped, against request N+1's: a byte prefix or not, and
// where not, the first byte offset, message index and role that differ (the shape pm handed iOS).
package ai.teemoon.app

import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.LiteRTToolBridge
import ai.teemoon.inference.LiteRTToolRegistry
import ai.teemoon.inference.LiteRTTransport
import ai.teemoon.inference.LocalBenchmark
import ai.teemoon.inference.LocalConversationSpec
import ai.teemoon.inference.LocalEngineCache
import ai.teemoon.inference.LocalEngineFactory
import ai.teemoon.inference.LocalEngineHandle
import ai.teemoon.inference.LocalEngineResidency
import ai.teemoon.inference.LocalGenerationGate
import ai.teemoon.inference.ReasoningEffort
import ai.teemoon.inference.Tool
import ai.teemoon.inference.ToolCallState
import ai.teemoon.inference.TurnResult
import ai.teemoon.providers.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsecutiveSendsPrefixTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun up() = Dispatchers.setMain(dispatcher)
    @After fun down() = Dispatchers.resetMain()

    private class Turn(val messages: List<Map<String, Any>>, val includeTools: Boolean, val tools: List<Tool>, val effort: ReasoningEffort?)
    private class Wire(val effort: () -> ReasoningEffort?) : GenerationTransport {
        val turns = ArrayList<Turn>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            turns += Turn(messages, includeTools, tools, effort()); onPartial("Rayleigh scattering."); return TurnResult(content = "Rayleigh scattering.")
        }
    }

    @Test fun theSecondSendExtendsTheFirstsRequest() = runTest(dispatcher) {
        val place = Provider.fireworks.withModel("accounts/fireworks/models/glm-5p3-flash")
        var clock = 1_770_000_000_000L
        var lastEffort: ReasoningEffort? = null
        val wire = Wire { lastEffort }
        val vm = ChatViewModel(provider = { place }, credential = { "k" }, groundingKey = { "b" }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT },
            now = { clock }, makeTransport = { _, _, _, _, effort, _ -> lastEffort = effort; wire })
        vm.send("why is the sky blue?"); advanceUntilIdle(); clock += 60_000
        vm.send("and sunsets?"); advanceUntilIdle()
        assertEquals(2, wire.turns.size)
        val (one, two) = wire.turns
        assertTrue("search is offered on both", one.includeTools && two.includeTools && one.tools.isNotEmpty())
        assertEquals("send 2's messages begin with send 1's, entry for entry", one.messages, two.messages.take(one.messages.size))
        assertTrue("send 2 adds the reply and the new question", two.messages.size > one.messages.size)
        assertEquals("the same tools, in order", one.tools.map { Triple(it.name, it.description, it.parametersSchema) }, two.tools.map { Triple(it.name, it.description, it.parametersSchema) })
        assertEquals("the same level", ReasoningEffort.FAST, one.effort); assertEquals(one.effort, two.effort)
    }

    // MARK: - The prefix, in bytes

    /** Where request N+1 stops extending request N: the byte offset in N+1's `messages`, and the message there. */
    private data class Break(val offset: Int, val index: Int, val role: String?)

    /** `messages` as OpenAiTransport.makeBody writes it: objects with sorted keys at every depth, arrays in order. */
    private fun bytes(messages: List<Map<String, Any>>): String = Json.encodeToString(JsonElement.serializer(), JsonArray(messages.map { it.json() }))
    private fun Any?.json(): JsonElement = when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(entries.sortedBy { it.key.toString() }.associate { (k, v) -> k.toString() to v.json() })
        is List<*> -> JsonArray(map { it.json() })
        is Boolean -> JsonPrimitive(this); is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    /** Null when N+1's messages begin with N's, byte for byte; else the first difference. */
    private fun prefixBreak(n: List<Map<String, Any>>, next: List<Map<String, Any>>): Break? {
        val a = bytes(n).dropLast(1); val b = bytes(next)
        if (b.startsWith(a)) return null
        val offset = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: a.length
        // The message the offset falls in: count N+1's messages whose bytes end before it.
        var end = 1; var index = 0
        while (index < next.size) { end += bytes(listOf(next[index])).length - 2 + (if (index > 0) 1 else 0); if (end > offset) break; index++ }
        return Break(offset, index, next.getOrNull(index)?.get("role") as? String).also { println("prefix break: $it\n  N:   ${a.drop(maxOf(0, offset - 40)).take(120)}\n  N+1: ${b.drop(maxOf(0, offset - 40)).take(120)}") }
    }

    private val fireworks = Provider.fireworks.withModel("accounts/fireworks/models/glm-5p3-flash")

    /** Every request the engine hands the transport, in order; the reply is scripted per request. */
    private class Script(val reply: (Int, List<Map<String, Any>>) -> TurnResult) : GenerationTransport {
        val requests = ArrayList<List<Map<String, Any>>>()
        override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
            requests += messages.toList()
            return reply(requests.size, messages).also { if (it.content.isNotEmpty()) onPartial(it.content) }
        }
    }

    // MARK: - (1) a turn that searched

    /**
     * Send 1 searches: request 1 is answered with a web_search call, request 2 carries the assistant's tool_calls and
     * the tool's result and gets the reply. Send 2 is built from the stored history, which holds the reply's text only.
     * Keyless, so the search answers from BraveWebSearchTool's own refusal and nothing leaves the JVM.
     */
    @Test fun aSearchTurnsNextSendAndItsToolRounds() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val wire = Script { n, _ ->
            if (n == 1) TurnResult(content = "", toolCalls = mapOf(0 to ToolCallState(id = "call-1", name = "web_search", arguments = """{"query":"why is the sky blue"}""")))
            else TurnResult(content = "Rayleigh scattering.")
        }
        val vm = ChatViewModel(provider = { fireworks }, credential = { "k" }, groundingKey = { null }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT },
            now = { clock }, makeTransport = { _, _, _, _, _, _ -> wire })
        vm.send("why is the sky blue?"); advanceUntilIdle(); clock += 60_000
        vm.send("and sunsets?"); advanceUntilIdle()
        assertEquals("search round, answer round, send 2", 3, wire.requests.size)
        val (round1, round2, send2) = wire.requests
        assertEquals(listOf("system", "user", "assistant", "tool"), round2.map { it["role"] })
        assertNull("the search's first request is a prefix of the answer round's", prefixBreak(round1, round2))
        assertNull("…and of send 2's", prefixBreak(round1, send2))
        val b = prefixBreak(round2, send2)
        println("(1) search turn: round 2 → send 2: $b; send 2 roles ${send2.map { it["role"] }}")
        assertEquals("send 2 resends the reply only, not the tool_calls and the tool result", listOf("system", "user", "assistant", "user"), send2.map { it["role"] })
        assertEquals("so the prefix breaks at the assistant turn", 2, b?.index)
    }

    // MARK: - (2) Think Harder, then a normal follow-up

    @Test fun aThinkHarderReplysNextSend() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val efforts = ArrayList<ReasoningEffort?>()
        val wire = Script { n, _ -> TurnResult(content = "answer $n") }
        val vm = ChatViewModel(provider = { fireworks }, credential = { "k" }, groundingKey = { "b" }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT },
            now = { clock }, makeTransport = { _, _, _, _, effort, _ -> efforts += effort; wire })
        vm.send("17 × 24?"); advanceUntilIdle(); clock += 60_000
        assertTrue(vm.canThinkHarder(vm.state.value.messages.last().id))
        vm.thinkHarder(vm.state.value.messages.last().id); advanceUntilIdle(); clock += 60_000
        vm.send("and 17 × 25?"); advanceUntilIdle()
        assertEquals(3, wire.requests.size)
        val (asked, reasked, followUp) = wire.requests
        val reask = prefixBreak(asked, reasked)
        val next = prefixBreak(reasked, followUp)
        println("(2) think harder: send 1 → re-ask: $reask; re-ask → follow-up: $next; efforts $efforts")
        println("    send 1 user: ${asked.last()["content"]}\n    re-ask user: ${reasked.last()["content"]}")
        assertEquals(listOf(ReasoningEffort.FAST, ReasoningEffort.THINK_HARDER, ReasoningEffort.FAST), efforts)
        assertNull("the follow-up extends the re-ask's messages", next)
    }

    // MARK: - (3) a reply that came with thinking

    /** Cloud: the thought arrives as the reasoning field; three sends, so the reply is resent twice. */
    @Test fun aCloudReplysThinkingIsDroppedTheSameWayOnEverySend() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val wire = Script { n, _ -> TurnResult(content = "answer $n", reasoning = "SENTINEL-THOUGHT-$n") }
        val vm = ChatViewModel(provider = { fireworks }, credential = { "k" }, groundingKey = { "b" }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT },
            now = { clock }, makeTransport = { _, _, _, _, _, _ -> wire })
        for (q in listOf("q1", "q2", "q3")) { vm.send(q); advanceUntilIdle(); clock += 60_000 }
        val (one, two, three) = wire.requests
        println("(3) cloud thinking: 1 → 2: ${prefixBreak(one, two)}; 2 → 3: ${prefixBreak(two, three)}")
        assertFalse(bytes(three).contains("SENTINEL"))
        assertNull(prefixBreak(one, two)); assertNull(prefixBreak(two, three))
    }

    /** On-device Gemma: the thought arrives INLINE (`<|channel>thought…<channel|>`, the NPU bundle) through the real transport. */
    @Test fun aGemmaReplysInlineThoughtIsDroppedTheSameWayOnEverySend() = runTest(dispatcher) {
        var clock = 1_770_000_000_000L
        val engine = object : LocalEngineHandle {
            var n = 0
            override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) {
                n++; "<|channel>thought\nSENTINEL-THOUGHT-$n<channel|>answer $n".chunked(5).forEach { onPiece(it) }
            }
            override fun benchmark() = LocalBenchmark(0.8, 120, 42, 900.0, 18.0)
            override fun close() {}
            override fun dropKept() {}
        }
        val factory = object : LocalEngineFactory {
            override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = engine
            override val backendLabel = "test"
        }
        val gate = LocalGenerationGate()
        val litert = LiteRTTransport("/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm", 2468,
            cache = LocalEngineCache(factory, residency = LocalEngineResidency(), gate = gate, memoryCheck = {}, fileExists = { true }, nowMs = { 0L }),
            bridge = LiteRTToolBridge(LiteRTToolRegistry()), gate = gate, backendLabel = "test", thinking = true)
        val wire = Script { _, _ -> TurnResult(content = "") }
        val recording = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                wire.requests += messages.toList(); return litert.runTurn(messages, includeTools, tools, onPartial)
            }
        }
        val gemma = Provider(id = "g", name = "Gemma 4 E2B", endpoint = "on-device", model = "litert-community/gemma-4-E2B-it-litert-lm",
            requiresAPIKey = false, supportsModelBrowsing = false, localModelID = "litert-community/gemma-4-E2B-it-litert-lm")
        val vm = ChatViewModel(provider = { gemma }, systemPrompt = { AppSettings.DEFAULT_SYSTEM_PROMPT }, now = { clock }, makeTransport = { _, _, _, _, _, _ -> recording })
        for (q in listOf("q1", "q2", "q3")) { vm.send(q); advanceUntilIdle(); clock += 60_000 }
        assertEquals(3, wire.requests.size)
        val (one, two, three) = wire.requests
        println("(3) gemma inline thought: 1 → 2: ${prefixBreak(one, two)}; 2 → 3: ${prefixBreak(two, three)}; reply stored as ${vm.state.value.messages.map { it.text }}")
        assertFalse(bytes(three).contains("SENTINEL") || bytes(three).contains("<|channel>"))
        assertNull(prefixBreak(one, two)); assertNull(prefixBreak(two, three))
    }
}
