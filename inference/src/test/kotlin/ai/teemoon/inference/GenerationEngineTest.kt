// ports: teemoonTests/GenerationTransportTests.swift @ v1.0.1 (the "Generation loop" suite;
//        LiteRTContextBudgetTests belongs to the on-device transport)
package ai.teemoon.inference

import ai.teemoon.confidential.ResponseVerification

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers the shared generation loop through a STUB transport.
 *
 * The assertions are about *meaning* — did the tool round chain, did the
 * follow-up messages take the right shape, did the reasoning fallback fire —
 * rather than about bytes. These behaviours are exactly what must NOT differ
 * between remote inference and on-device inference, which is why they are
 * tested once, here, and not per transport.
 */

/** Replays a scripted list of turns, recording what the loop sent it. */
private class ScriptedTransport(private val turns: List<TurnResult>) : GenerationTransport {
    /** Every `messages` list the loop handed over, in order, with the includeTools flag. */
    val requests = ArrayList<Pair<List<Map<String, Any>>, Boolean>>()
    val sentIncludeTools: List<Boolean> get() = requests.map { it.second }
    private var i = 0
    override suspend fun runTurn(
        messages: List<Map<String, Any>>,
        includeTools: Boolean,
        tools: List<Tool>,
        onPartial: (String) -> Unit,
    ): TurnResult {
        requests.add(messages.map { it } to includeTools)
        val t = if (i < turns.size) turns[i] else TurnResult(content = "")
        i += 1
        // Mimic a real transport streaming its text before returning.
        if (t.content.isNotEmpty()) onPartial(t.content)
        return t
    }
}

private class RecordingEvents : StreamCallbacks {
    val texts = ArrayList<String>()
    val warnings = ArrayList<String>()
    val toolRounds = ArrayList<String>()
    val awaiting = ArrayList<Pair<Int, Boolean>>()
    val sources = ArrayList<List<GroundingSource>>()
    val queries = ArrayList<List<String>>()
    val results = ArrayList<RequestResult>()
    var toolExecutionEnded = 0
    override fun onText(text: String) { texts.add(text) }
    override fun onWarning(message: String) { warnings.add(message) }
    override fun onToolRound(call: ToolCallState, result: String) { toolRounds.add(call.name) }
    override fun onAwaitingModel(turn: Int, awaiting: Boolean) { this.awaiting.add(turn to awaiting) }
    override fun onSourcesFound(sources: List<GroundingSource>) { this.sources.add(sources) }
    override fun onQueriesFound(queries: List<String>) { this.queries.add(queries) }
    override fun onToolExecutionEnded() { toolExecutionEnded += 1 }
    override fun onSuccess(result: RequestResult) { results.add(result) }
}

/** Decodes its arguments strictly, so a string-typed `count` is a visible failure, as on iOS. */
private object EchoTool : Tool {
    override val name = "web_search"
    override val description = "Search the web."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"]}"""
    override suspend fun execute(argumentsJson: String): String {
        val obj = Json.parseToJsonElement(argumentsJson) as JsonObject
        val query = obj["query"]!!.jsonPrimitive.content
        val count = (obj["count"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            ?: return "[typeMismatch: count arrived as ${obj["count"]}]"
        return "RESULT for $query x$count"
    }
}

private class ThrowingTool(private val error: Throwable) : Tool {
    override val name = "web_search"
    override val description = "throws"
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
    override suspend fun execute(argumentsJson: String): String = throw error
}

private fun call(name: String = "web_search", args: String = """{"query":"x","count":1}""", id: String = "c0") =
    mapOf(0 to ToolCallState(id = id, name = name, arguments = args))

private fun calling(args: String = """{"query":"x","count":1}""") = TurnResult(content = "", toolCalls = call(args = args))

class GenerationEngineTest {

    private fun engine(
        transport: GenerationTransport,
        tools: List<Tool> = emptyList(),
        events: StreamCallbacks = RecordingEvents(),
        maxToolRounds: Int = ToolRoundPolicy.DEFAULT_MAX_ROUNDS,
        groundsItsOwnAnswers: Boolean = false,
        sourcesInToolResult: (String) -> List<GroundingSource> = { emptyList() },
    ) = GenerationEngine(transport, tools, events, maxToolRounds, groundsItsOwnAnswers, sourcesInToolResult)

    @Test fun plainAnswerStreamsAndFinishes() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(listOf(TurnResult(content = "Paris.")))
        assertEquals("Paris.", engine(transport, events = events).run(emptyList(), "hi"))
        assertEquals("Paris.", events.texts.last())
        // One turn only — no tools were offered, so no round to chain.
        assertEquals(1, transport.requests.size)
        assertEquals(listOf(false), transport.sentIncludeTools)
    }

    @Test fun promptAndPriorTurnsReachTheTransport() = runTest {
        val transport = ScriptedTransport(listOf(TurnResult(content = "ok")))
        engine(transport).run(
            listOf(WireMessage("system", "be terse"), WireMessage("user", "first")), "and then?")
        val sent = transport.requests.first().first
        assertEquals(3, sent.size)
        assertEquals("system", sent[0]["role"])
        assertEquals("first", sent[1]["content"])
        assertEquals("user", sent[2]["role"])
        assertEquals("and then?", sent[2]["content"])
    }

    /**
     * A structured tool round must come back as OpenAI-shaped follow-up: an
     * assistant turn carrying `tool_calls`, then one `tool` message per result,
     * keyed by `tool_call_id`.
     */
    @Test fun structuredToolRoundChainsWithToolRole() = runTest {
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "", toolCalls = call(args = """{"query": "swift", "count": 3}""", id = "call_a")),
            TurnResult(content = "Swift is a language.")))
        val out = engine(transport, listOf(EchoTool)).run(emptyList(), "hi")
        assertEquals("Swift is a language.", out)
        assertEquals(2, transport.requests.size)

        val followUp = transport.requests.last().first
        val assistant = followUp.first { it["role"] == "assistant" }
        assertNotNull(assistant["tool_calls"])
        val toolMsg = followUp.first { it["role"] == "tool" }
        assertEquals("call_a", toolMsg["tool_call_id"])
        assertTrue((toolMsg["content"] as String).contains("RESULT for swift x3"))
    }

    /**
     * A *text-recovered* tool round cannot use the `tool` role — a model that
     * emitted a call as prose may not accept a structured reply. It gets a
     * user-role results message instead, and tools are withheld from the next round.
     */
    @Test fun textBasedToolRoundChainsAsUserMessageAndDisablesTools() = runTest {
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "Let me look.", toolCalls = call(args = """{"query": "swift", "count": 3}"""),
                isTextBasedToolCall = true),
            TurnResult(content = " Done.")))
        engine(transport, listOf(EchoTool)).run(emptyList(), "hi")

        val followUp = transport.requests.last().first
        assertFalse(followUp.any { it["role"] == "tool" })
        val userResults = followUp.last()
        assertEquals("user", userResults["role"])
        assertTrue((userResults["content"] as String).contains("RESULT for swift x3"))
        assertTrue((userResults["content"] as String).contains("Please answer based on these results"))
        assertFalse("a text round must not send structured tool_calls",
            followUp.any { it.containsKey("tool_calls") })
        // First round offered tools; the round after a text-based call must not.
        assertEquals(listOf(true, false), transport.sentIncludeTools)
    }

    /**
     * Arguments arriving as strings (`"count": "3"`) must be coerced against
     * the tool's own schema before decoding, or the call fails with a
     * typeMismatch and the tool never runs. Small on-device models do this
     * constantly, so it is shared-loop behaviour, not a GLM special case.
     */
    @Test fun stringifiedScalarArgumentsAreCoercedBeforeTheToolRuns() = runTest {
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "", toolCalls = call(args = """{"query": "swift", "count": "3"}""")),
            TurnResult(content = "done")))
        engine(transport, listOf(EchoTool)).run(emptyList(), "hi")
        val toolMsg = transport.requests.last().first.first { it["role"] == "tool" }
        val content = toolMsg["content"] as String
        assertTrue("coercion failed — got: $content", content.contains("RESULT for swift x3"))
    }

    /** Some models put their whole reply in the reasoning field and leave content empty. */
    @Test fun reasoningOnlyReplyFallsBackToReasoningText() = runTest {
        val events = RecordingEvents()
        val out = engine(ScriptedTransport(listOf(TurnResult(content = "", reasoning = "The answer is 4."))),
            events = events).run(emptyList(), "hi")
        assertEquals("The answer is 4.", out)
        // Emitted, not just returned: nothing was streamed.
        assertTrue(events.texts.contains("The answer is 4."))
    }

    /**
     * A reasoning-only reply that was CUT OFF must not be shown. Measured:
     * Qwen3-0.6B looping on the date teemoon injects emitted 3,616 characters
     * over exactly 1024 tokens ending "...asking about the 2026 F".
     */
    @Test fun truncatedReasoningIsWithheldRatherThanShownAsTheAnswer() = runTest {
        val events = RecordingEvents()
        val out = engine(ScriptedTransport(listOf(TurnResult(
            content = "",
            reasoning = "Let me think. The date is 2026 but races are held in different years. But the",
            completionTokens = 1024, wasTruncated = true))), events = events).run(emptyList(), "hi")
        assertTrue("a truncated scratchpad was shown as the answer: $out", out.isBlank())
        assertTrue(events.warnings.any { it.contains("truncated scratchpad") })
    }

    /** ...but a FINISHED reasoning-only reply still is. */
    @Test fun completeReasoningOnlyRepliesAreStillShown() = runTest {
        val out = engine(ScriptedTransport(listOf(TurnResult(
            content = "", reasoning = "The answer is 4.", completionTokens = 120, wasTruncated = false))))
            .run(emptyList(), "hi")
        assertEquals("The answer is 4.", out)
    }

    /** Reasoning must NOT override a real answer. */
    @Test fun reasoningIsIgnoredWhenContentExists() = runTest {
        val out = engine(ScriptedTransport(listOf(TurnResult(content = "4", reasoning = "thinking out loud"))))
            .run(emptyList(), "hi")
        assertEquals("4", out)
    }

    /**
     * Residual tool markup a recovery parser didn't consume must never reach
     * the answer — neither the returned text nor the persisted result body.
     */
    @Test fun residualToolMarkupIsStrippedFromTheAnswer() = runTest {
        val events = RecordingEvents()
        val out = engine(ScriptedTransport(listOf(
            TurnResult(content = "Here you go <tool_call>{\"name\":\"nope\"}</tool_call> done."))),
            events = events).run(emptyList(), "hi")
        assertFalse("tool markup leaked into the answer: $out", out.contains("<tool_call>"))
        val body = events.results.last().responseBody!!
        assertFalse("tool markup leaked into the result body: $body", body.contains("<tool_call>"))
        assertTrue(events.warnings.any { it.contains("[containment]") })
    }

    /**
     * The round budget is finite: a model that only ever calls tools must be
     * stopped rather than looping forever.
     *
     * REGRESSION: the budget originally gated only whether tools were
     * *offered*, not whether the loop continued. Withholding tools does not
     * stop a model emitting a tool call anyway — and text recovery parses one
     * out of raw output regardless — so a stubborn model looped forever,
     * running real tool calls every round.
     */
    @Test fun toolRoundsAreBounded() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(List(10) { calling() })
        engine(transport, listOf(EchoTool), events).run(emptyList(), "hi")
        // 4 tool rounds allowed, then tools are withheld and the loop ends on
        // the next turn: 5 turns total, and only the first 4 offered tools.
        assertEquals(listOf(true, true, true, true, false), transport.sentIncludeTools)
        assertEquals(4, events.toolRounds.size)
        assertTrue(events.warnings.any { it.contains("round budget exhausted") })
    }

    /**
     * When the budget runs out the tools vanish from the request — and the
     * model must be TOLD, on that same turn. Measured on GLM-5.1 via near.ai:
     * silence produced an empty answer 5 times in 18; the notice produced none
     * in 34 (p = 0.0033, Fisher exact).
     */
    @Test fun anExhaustedBudgetTellsTheModelSearchesAreOver() = runTest {
        val transport = ScriptedTransport(List(10) { calling() })
        engine(transport, listOf(EchoTool)).run(emptyList(), "hi")
        // Rounds 1-4 offer tools and must say nothing; the 5th withholds them
        // and must carry the notice as its final message.
        transport.requests.forEachIndexed { i, (sent, _) ->
            val carriesNotice = sent.any { it["content"] == ToolRoundPolicy.SEARCHES_EXHAUSTED_NOTICE }
            assertEquals("turn ${i + 1} ${if (carriesNotice) "carried" else "omitted"} the notice",
                i == 4, carriesNotice)
        }
        assertEquals(listOf(true, true, true, true, false), transport.sentIncludeTools)
    }

    /**
     * ...and a model that stops when told then produces a real answer, rather
     * than the empty string. GLM answers on that turn once it knows the
     * searches are over.
     */
    @Test fun aModelThatStopsWhenToldAnswersInsteadOfEmpty() = runTest {
        val stubborn = object : GenerationTransport {
            override suspend fun runTurn(
                messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>,
                onPartial: (String) -> Unit,
            ): TurnResult {
                val told = messages.any { it["content"] == ToolRoundPolicy.SEARCHES_EXHAUSTED_NOTICE }
                if (!told) return calling()
                onPartial("No CP car wash exists nearby; here is what I found.")
                return TurnResult(content = "No CP car wash exists nearby; here is what I found.")
            }
        }
        val out = engine(stubborn, listOf(EchoTool)).run(emptyList(), "hi")
        assertTrue("the round budget ran out and the user got nothing at all", out.isNotBlank())
        assertTrue(out.contains("No CP car wash"))
    }

    /**
     * The TEXT-BASED path already ends its follow-up with "Please answer based
     * on these results", so it must not also get the notice — two user
     * messages in a row is a shape some servers reject.
     */
    @Test fun theTextBasedPathIsNotGivenASecondUserMessage() = runTest {
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "Let me look.", toolCalls = call(args = """{"query": "swift", "count": 3}"""),
                isTextBasedToolCall = true),
            TurnResult(content = " Done.")))
        engine(transport, listOf(EchoTool)).run(emptyList(), "hi")
        val followUp = transport.requests.last().first
        assertFalse(followUp.any { it["content"] == ToolRoundPolicy.SEARCHES_EXHAUSTED_NOTICE })
        val roles = followUp.map { it["role"] as String }
        assertFalse("two consecutive user messages: $roles",
            roles.zipWithNext().any { (a, b) -> a == "user" && b == "user" })
    }

    /**
     * The LAST emitted text must be the clean, canonical answer — because the
     * stream's owner keeps the last snapshot and that is what gets persisted.
     * A transport is allowed to stream anything it likes (raw `<think>` for the
     * live thinking block); the engine guarantees the final word.
     */
    @Test fun lastEmittedTextIsTheCleanAnswerNotTheRawPartial() = runTest {
        val rawStreaming = object : GenerationTransport {
            override suspend fun runTurn(
                messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>,
                onPartial: (String) -> Unit,
            ): TurnResult {
                onPartial("<think>let me consider</think>Paris.")
                return TurnResult(content = "Paris.", reasoning = "let me consider")
            }
        }
        val events = RecordingEvents()
        engine(rawStreaming, events = events).run(emptyList(), "hi")
        val last = events.texts.last()
        assertFalse("raw thinking markup would be persisted — last emission was: $last", last.contains("<think>"))
        assertEquals("Paris.", last)
        // The raw partial must still have been emitted, or the live "thinking…"
        // block has nothing to render.
        assertTrue("the raw partial was suppressed", events.texts.any { it.contains("<think>") })
    }

    /**
     * The engine must say when it is WAITING ON A TURN with nothing to show —
     * the signal the activity chip needs. The gap is the one after a tool
     * round: nothing changes on screen while the follow-up request flies and
     * its prompt prefills (3.0s per round on DeepSeek V4 Flash via near.ai).
     */
    @Test fun theEngineSaysWhenItIsWaitingWithNothingToShow() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "Let me look that up.", toolCalls = call()),
            TurnResult(content = "Here you go.")))
        engine(transport, listOf(EchoTool), events).run(emptyList(), "hi")
        val seen = events.awaiting
        assertTrue("turn 1 never announced: $seen", seen.contains(1 to true))
        assertTrue("turn 2 never announced: $seen", seen.contains(2 to true))
        assertTrue(seen.contains(1 to false))
        assertTrue(seen.contains(2 to false))
        // Turn 2's wait must not be cleared before it is announced — that
        // ordering is the whole reason the edge carries a turn index.
        assertTrue("turn 2 was cleared before it started: $seen",
            seen.indexOf(2 to true) < seen.lastIndexOf(2 to false))
    }

    /** A turn that produces text must clear its own wait on the FIRST character, once. */
    @Test fun theWaitClearsOnTheFirstVisibleCharacter() = runTest {
        val events = RecordingEvents()
        val atChunk = ArrayList<Int>()
        val chunked = object : GenerationTransport {
            override suspend fun runTurn(
                messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>,
                onPartial: (String) -> Unit,
            ): TurnResult {
                onPartial("Hel"); atChunk.add(events.awaiting.size)
                onPartial("Hello."); atChunk.add(events.awaiting.size)
                return TurnResult(content = "Hello.")
            }
        }
        engine(chunked, events = events).run(emptyList(), "hi")
        // By the first chunk's return the wait was already cleared: edges are
        // [(1,true), (1,false)] — the clear is not deferred to turn end.
        assertEquals("got ${events.awaiting}", listOf(true, false), events.awaiting.take(2).map { it.second })
        // And it is emitted ONCE per chunk stream (plus the unconditional clear at turn end).
        assertTrue("the clear fired per-chunk: ${events.awaiting}",
            events.awaiting.count { it == (1 to false) } <= 2)
    }

    /**
     * A transport with no attestation story (on-device) reports no
     * verification — which must read as "not applicable", not as a failure.
     */
    @Test fun absentVerificationIsNotAFailedVerification() = runTest {
        val events = RecordingEvents()
        engine(ScriptedTransport(listOf(TurnResult(content = "hi"))), events = events).run(emptyList(), "hi")
        val r = events.results.last()
        assertNull(r.teeVerification)
        assertFalse(r.isE2eeActive)
        assertEquals("hi", r.responseBody)
        assertTrue(events.warnings.isEmpty())
    }

    // MARK: - Android-only coverage of the remainder (no Swift test exists)

    /** The TEE check is AWAITED so the turn's result includes it — no polling after generate returns. */
    @Test fun teeVerificationIsAwaitedAndLandsInTheResult() = runTest {
        val events = RecordingEvents()
        val verified = ResponseVerification.Verified("0xabc")
        val transport = ScriptedTransport(listOf(TurnResult(
            content = "hi", completionTokens = 7,
            report = TurnReport(url = "https://example.test/v1", isE2eeActive = true, verify = { verified }))))
        engine(transport, events = events).run(emptyList(), "hi")
        val r = events.results.single()
        assertEquals(verified, r.teeVerification)
        assertTrue(r.isE2eeActive)
        assertEquals("https://example.test/v1", r.url)
        assertEquals(7, r.outputTokens)
        assertTrue("a verified signature is not a warning", events.warnings.isEmpty())
    }

    /** A signature mismatch is surfaced, never a silent drop. */
    @Test fun signatureMismatchIsSurfacedAsAWarning() = runTest {
        val events = RecordingEvents()
        val mismatch = ResponseVerification.Unverified(
            ResponseVerification.UnverifiedReason.SignatureMismatch(expected = "0xa", got = "0xb"))
        engine(ScriptedTransport(listOf(TurnResult(content = "hi", report = TurnReport(verify = { mismatch })))),
            events = events).run(emptyList(), "hi")
        assertEquals(mismatch, events.results.single().teeVerification)
        assertTrue(events.warnings.any { it.contains("[TEE] Signature MISMATCH") && it.contains("0xb") })
    }

    /** The developer panel sees each tool round as it completes, then the final answer. */
    @Test fun intermediateResultIsPublishedAfterEachToolRound() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(listOf(
            calling("""{"query": "a", "count": 1}"""),
            calling("""{"query": "b", "count": 2}"""),
            TurnResult(content = "done", completionTokens = 3)))
        engine(transport, listOf(EchoTool), events).run(emptyList(), "hi")
        assertEquals(3, events.results.size)
        assertNull(events.results[0].responseBody)
        assertEquals(1, events.results[0].toolCalls.size)
        assertEquals(2, events.results[1].toolCalls.size)
        val last = events.results[2]
        assertEquals("done", last.responseBody)
        assertEquals(listOf("web_search", "web_search"), last.toolCalls.map { it.name })
        assertEquals("RESULT for b x2", last.toolCalls[1].result)
        assertEquals(3, last.outputTokens)
        assertEquals(2, events.toolExecutionEnded)
    }

    /** Tool calls the transport ran itself are appended to the record, so on-device chats list them. */
    @Test fun transportExecutedToolCallsAreAppended() = runTest {
        val events = RecordingEvents()
        val ran = ToolCallRecord("web_search", """{"query":"q"}""", "r")
        engine(ScriptedTransport(listOf(TurnResult(content = "hi", report = TurnReport(executedToolCalls = listOf(ran))))),
            events = events).run(emptyList(), "hi")
        assertEquals(listOf(ran), events.results.single().toolCalls)
    }

    /** The `query` of each call is announced before the tools run, in call order. */
    @Test fun queriesAreReportedBeforeToolsRun() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "", toolCalls = mapOf(
                1 to ToolCallState("c1", "web_search", """{"query":"second","count":1}"""),
                0 to ToolCallState("c0", "web_search", """{"parameters":"{\"query\":\"first\"}"}"""))),
            TurnResult(content = "done")))
        engine(transport, listOf(EchoTool), events).run(emptyList(), "hi")
        assertEquals(listOf(listOf("first", "second")), events.queries)
    }

    /** Sources found in tool results reach the rail through the seam iOS fills with the Brave parser. */
    @Test fun toolResultSourcesReachTheRail() = runTest {
        val events = RecordingEvents()
        val src = GroundingSource("https://a.example/x", "a.example", "A")
        val transport = ScriptedTransport(listOf(calling(), TurnResult(content = "done")))
        engine(transport, listOf(EchoTool), events, sourcesInToolResult = { if (it.contains("RESULT")) listOf(src) else emptyList() })
            .run(emptyList(), "hi")
        assertEquals(listOf(listOf(src)), events.sources)
    }

    /** A call for a tool that is not registered yields the iOS string, so the model can recover. */
    @Test fun unknownToolYieldsTheNotFoundString() = runTest {
        val transport = ScriptedTransport(listOf(
            TurnResult(content = "", toolCalls = call(name = "nope")), TurnResult(content = "done")))
        engine(transport, listOf(EchoTool)).run(emptyList(), "hi")
        val toolMsg = transport.requests.last().first.first { it["role"] == "tool" }
        assertEquals("[Tool 'nope' not found]", toolMsg["content"])
    }

    /** Auth failures in a tool are the USER's problem (their key) and propagate; the round is ended first. */
    @Test fun authFailuresInAToolPropagate() = runTest {
        val events = RecordingEvents()
        val transport = ScriptedTransport(listOf(calling(), TurnResult(content = "never")))
        val tool = ThrowingTool(LlmError(LlmError.Source.BraveGrounding, "Brave web search failed (HTTP 401).", httpStatus = 401))
        try {
            engine(transport, listOf(tool), events).run(emptyList(), "hi")
            fail("a 401 from a tool must propagate")
        } catch (e: LlmError) {
            assertEquals(401, e.httpStatus)
        }
        assertEquals(1, events.toolExecutionEnded)
        assertEquals(1, transport.requests.size)
    }

    /** A malformed key is Brave's 422 SUBSCRIPTION_TOKEN_INVALID: the turn stops with the cause, no rephrase loop. */
    @Test fun aBraveInvalidTokenPropagatesLikeA401() = runTest {
        val transport = ScriptedTransport(listOf(calling(), TurnResult(content = "done")))
        val events = RecordingEvents()
        val body = """{"error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"The provided subscription token is invalid.","meta":{"component":"authentication"},"status":422},"type":"ErrorResponse"}"""
        val tool = ThrowingTool(LlmError(LlmError.Source.BraveGrounding, LlmError.groundingMessage(422, body), httpStatus = 422, responseBody = body))
        try {
            engine(transport, listOf(tool), events).run(emptyList(), "hi")
            fail("an invalid Brave token must propagate")
        } catch (e: LlmError) {
            assertEquals(422, e.httpStatus)
            assertTrue(e.isKeyRejection)
        }
        assertEquals(1, transport.requests.size)
    }

    /** Every other tool failure becomes result text the model can read and route around. */
    @Test fun otherToolErrorsBecomeResultText() = runTest {
        suspend fun resultFor(error: Throwable): String {
            val transport = ScriptedTransport(listOf(calling(), TurnResult(content = "done")))
            engine(transport, listOf(ThrowingTool(error))).run(emptyList(), "hi")
            return transport.requests.last().first.first { it["role"] == "tool" }["content"] as String
        }
        val rateLimited = resultFor(LlmError(LlmError.Source.BraveGrounding, "Brave web search rate limit reached (HTTP 429).", httpStatus = 429))
        assertTrue(rateLimited, rateLimited.startsWith("[web_search error: Brave web search rate limit reached (HTTP 429). Try rephrasing"))
        val crashed = resultFor(IllegalStateException("boom"))
        assertTrue(crashed, crashed.startsWith("[Tool call failed: ") && crashed.contains("boom"))
    }

    /** A self-grounding provider's citations are split out of the answer, live and at the end. */
    @Test fun groundedProviderSplitsCitationsOutOfTheAnswer() = runTest {
        val events = RecordingEvents()
        val raw = """Paris.<citation>{"url": "https://a.example/x", "snippet": "s"}</citation><usage>{"c": 1}</usage>"""
        val out = engine(ScriptedTransport(listOf(TurnResult(content = raw))), events = events,
            groundsItsOwnAnswers = true).run(emptyList(), "hi")
        assertEquals("Paris.", out)
        assertEquals("Paris.", events.results.single().responseBody)
        assertEquals(listOf("https://a.example/x"), events.sources.single().map { it.url })
        // The streamed partial was split too — no citation JSON ever reached the screen.
        assertTrue(events.texts.none { it.contains("<citation>") || it.contains("a.example") })
    }

    /** Every other provider streams plain content — the split is identity for them. */
    @Test fun nonGroundingProvidersKeepTheirAngleBrackets() = runTest {
        val events = RecordingEvents()
        val out = engine(ScriptedTransport(listOf(TurnResult(content = "2 <citation> 3"))), events = events)
            .run(emptyList(), "hi")
        assertEquals("2 <citation> 3", out)
        assertTrue(events.sources.isEmpty())
    }

    @Test fun recoverySpecReadsTheToolSchema() {
        val spec = GenerationEngine.recoverySpec(EchoTool)
        assertEquals("web_search", spec.name)
        assertTrue(spec.paramNames.contains("query"))
        assertEquals(listOf("query"), spec.requiredParams)
    }
}
