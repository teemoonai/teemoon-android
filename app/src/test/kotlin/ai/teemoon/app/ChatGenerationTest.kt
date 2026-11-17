// ports: teemoonTests/UnconfiguredSearchTests.swift @ v1.0.1 (WebSearchOfferLifetime, PendingSearchRetry, GroundingToolAttachment;
//        the tool-level suite is BraveWebSearchToolTest, the settings-screen suites ported with AddEditProviderModel/ProviderHealing);
//        GroundingToolAttachment's on-device rows: teemoonTests/UnconfiguredSearchTests.swift @ 8494cdb9
package ai.teemoon.app

import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.inference.RequestResult
import ai.teemoon.inference.ToolCallRecord
import ai.teemoon.inference.WireMessage
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class WebSearchOfferLifetimeTest {
    @Test fun theOfferAttachesToTheMessageItsTurnProduced() {
        val llm = ChatGeneration()
        llm.unconfiguredSearchQuery = "weather in tokyo"; llm.unconfiguredSearchThreadID = "t"
        llm.attachPendingOffer("m")
        assertEquals("weather in tokyo", llm.offerByMessageID["m"]); assertNull(llm.unconfiguredSearchQuery)
    }
    @Test fun aLaterTurnDoesNotEraseAnOfferAlreadyShown() {
        val llm = ChatGeneration()
        llm.unconfiguredSearchQuery = "weather in tokyo"; llm.attachPendingOffer("first")
        assertTrue(llm.beginTurn(0))   // the generate() prologue
        assertEquals("weather in tokyo", llm.offerByMessageID["first"])
    }
    @Test fun nothingAttachesWhenTheTurnNeverAskedToSearch() {
        val llm = ChatGeneration(); llm.attachPendingOffer("m"); assertNull(llm.offerByMessageID["m"])
    }
    @Test fun decliningClearsEveryCardInTheThreadAndMarksItDeclined() {
        val llm = ChatGeneration()
        llm.offerByMessageID["a"] = "weather in tokyo"; llm.offerByMessageID["b"] = "top story today"
        llm.declineOffer("t", listOf("a", "b"))
        assertTrue(llm.offerByMessageID.isEmpty()); assertTrue("t" in llm.declinedOfferThreadIDs)
    }
    @Test fun decliningIsPerThreadNotGlobal() {
        val llm = ChatGeneration(); llm.declineOffer("declined", emptyList())
        assertTrue("declined" in llm.declinedOfferThreadIDs); assertFalse("other" in llm.declinedOfferThreadIDs)
    }
}

class PendingSearchRetryTest {
    @Test fun tappingSetItUpRecordsTheThreadAndTheQuestion() {
        val llm = ChatGeneration(); llm.pendingSearchRetry = ChatGeneration.PendingSearchRetry("t", "u")
        assertEquals("t", llm.pendingSearchRetry?.threadID); assertEquals("u", llm.pendingSearchRetry?.userMessageID)
    }
    @Test fun aGenerationResetDoesNotClearThePendingRetry() {
        val llm = ChatGeneration(); llm.pendingSearchRetry = ChatGeneration.PendingSearchRetry("t", "u")
        llm.beginTurn(0); llm.endTurn()
        assertNotNull(llm.pendingSearchRetry)
    }
    @Test fun itIsThreadScopedSoItCannotFireInAnotherChat() {
        val llm = ChatGeneration(); llm.pendingSearchRetry = ChatGeneration.PendingSearchRetry("intended", "u")
        assertEquals("intended", llm.pendingSearchRetry?.threadID); assertFalse(llm.pendingSearchRetry?.threadID == "other")
    }
}

class GroundingToolAttachmentTest {
    @Test fun keylessStillAttachesSoTheModelCanSayItWantedTheWeb() {
        val tools = ChatGeneration().makeGroundingTools(Provider.fireworks, null, "t")
        assertEquals(1, tools.size); assertTrue(tools.first() is BraveWebSearchTool)
    }
    @Test fun aDeclinedThreadDoesNotPayForTheSchemaWhileKeyless() {
        val llm = ChatGeneration(); llm.declineOffer("t", emptyList())
        assertTrue(llm.makeGroundingTools(Provider.fireworks, null, "t").isEmpty())
    }
    @Test fun aDeclinedThreadStillAttachesOnceAKeyExists() {
        val llm = ChatGeneration(); llm.declineOffer("t", emptyList())
        assertEquals(1, llm.makeGroundingTools(Provider.fireworks, "k", "t").size)
    }
    /**
     * iOS GroundingToolAttachmentTests @ 8494cdb9. Measured there on the iPhone 16 Pro: the full schema is ~400 prefill
     * tokens and put a new on-device chat's first token at 1.65 s against 0.30 s without it. Keyless it cannot search,
     * so only `query` is sent.
     */
    @Test fun keylessOnDeviceSchemaIsQueryOnly() {
        val tools = ChatGeneration().makeGroundingTools(Provider.local(LocalModelCatalog.all.first()), null, "t")
        assertEquals(1, tools.size)
        val tool = tools.first()
        assertEquals("web_search", tool.name)
        val schema = Json.parseToJsonElement(tool.parametersSchema).jsonObject
        assertEquals(setOf("query"), schema["properties"]!!.jsonObject.keys)
        val bytes = Json.encodeToString(JsonObject.serializer(), schema).toByteArray().size
        assertTrue("keyless on-device schema is $bytes bytes", bytes < 300)
    }
    @Test fun keyedOnDeviceSchemaIsFull() {
        assertTrue(ChatGeneration().makeGroundingTools(Provider.local(LocalModelCatalog.all.first()), "k", "t").first() is BraveWebSearchTool)
    }
    @Test fun onDeviceKeyTurnedOffAttachesNothing() {
        assertTrue(ChatGeneration().makeGroundingTools(Provider.local(LocalModelCatalog.all.first()), null, "t", searchTurnedOff = true).isEmpty())
    }
    @Test fun cloudKeyTurnedOffStillAttaches() {
        assertEquals(1, ChatGeneration().makeGroundingTools(Provider.fireworks, null, "t", searchTurnedOff = true).size)
    }
    /** The offer trigger keeps BraveWebSearchTool's keyless contract: the first query raises the offer, the reply is the policy. */
    @Test fun theKeylessOnDeviceOfferRaisesTheOfferAndRepliesWithThePolicy() = kotlinx.coroutines.runBlocking {
        val llm = ChatGeneration()
        val tool = llm.makeGroundingTools(Provider.local(LocalModelCatalog.all.first()), null, "t").first()
        assertEquals(BraveWebSearchTool.SEARCH_POLICY, tool.execute("""{"query":"weather in tokyo"}"""))
        assertEquals("weather in tokyo", llm.unconfiguredSearchQuery); assertEquals("t", llm.unconfiguredSearchThreadID)
    }
    @Test fun builtInGroundingNeverAttachesTheTool() {
        assertTrue(ChatGeneration().makeGroundingTools(Provider.braveAnswers, "k", "t").isEmpty())
    }
    @Test fun theFirstKeylessQueryWinsAndCarriesItsThread() {
        val llm = ChatGeneration()
        val tool = llm.makeGroundingTools(Provider.fireworks, null, "t").first() as BraveWebSearchTool
        tool.onUnconfiguredSearch!!("weather in tokyo"); tool.onUnconfiguredSearch!!("second query")
        assertEquals("weather in tokyo", llm.unconfiguredSearchQuery); assertEquals("t", llm.unconfiguredSearchThreadID)
    }
    /**
     * G1 (pm sweep 2026-11-06): an on-device tool runs inside LiteRT's loop, not the turn's coroutine, so a stopped
     * turn's search can still call back during the next turn. Its keyless query must not raise an offer there.
     */
    @Test fun aStoppedTurnsToolCannotRaiseAnOfferInTheNextTurn() {
        val llm = ChatGeneration()
        llm.beginTurn(0)
        val old = llm.makeGroundingTools(Provider.fireworks, null, "t").first() as BraveWebSearchTool
        llm.stop(); llm.endTurn()
        llm.beginTurn(1)
        old.onUnconfiguredSearch!!("the stopped turn's query")
        assertNull(llm.unconfiguredSearchQuery)
    }
}

/** The generate() epilogue and the callback folds — the Swift is normative here; there is no fixture class for the loop. */
class ChatGenerationTurnTest {
    private fun result(output: Int? = null, first: Instant? = null, visible: Instant? = null, body: String? = "hi") =
        RequestResult(url = "https://x/v1/chat/completions", requestHeaders = mapOf("a" to "b"), requestBodyJson = "{}", responseBody = body,
            toolCalls = emptyList(), outputTokens = output, firstTokenAt = first, firstVisibleTokenAt = visible, teeVerification = null, isE2eeActive = false)

    @Test fun awaitingIsMonotonicAcrossTurns() {
        val llm = ChatGeneration()
        llm.noteAwaiting(1, true); llm.noteAwaiting(2, true); assertEquals(2, llm.awaitingTurn)
        llm.noteAwaiting(1, false); assertEquals("an older turn ending cannot clear a newer wait", 2, llm.awaitingTurn)
        llm.noteAwaiting(2, false); assertNull(llm.awaitingTurn); assertFalse(llm.isAwaitingModel)
    }
    @Test fun thinkingTimeIsTheGapBetweenFirstAndFirstVisibleToken_jitterIsNotThinking() {
        val llm = ChatGeneration(); llm.beginTurn(0)
        val t0 = Instant.ofEpochMilli(1_000)
        llm.recordSuccess(result(first = t0, visible = t0.plusMillis(2_500)), "p", "m", "t")
        assertEquals(2.5, llm.lastRequestDebugInfo!!.thinkingTime!!, 0.001)
        llm.recordSuccess(result(first = t0, visible = t0.plusMillis(30)), "p", "m", "t")
        assertEquals("30 ms is jitter; the earlier measurement stands", 2.5, llm.lastRequestDebugInfo!!.thinkingTime!!, 0.001)
    }
    @Test fun finishFillsTotalsAndEstimatesTokensWhenTheServerGaveNone() {
        val llm = ChatGeneration(); llm.beginTurn(1_000)
        llm.seedDebugInfo("p", "m", "https://x", "t", expectingE2EE = false)
        llm.markFirstToken(1_400); llm.output = "x".repeat(400)
        assertNull(llm.finishGeneration("p", "https://x", "t", emptyList(), null, nowMs = 3_000))
        val info = llm.lastRequestDebugInfo!!
        assertEquals(2.0, info.totalDuration!!, 0.001); assertEquals(0.4, info.timeToFirstToken!!, 0.001)
        assertEquals(100, info.outputTokens); assertEquals(1.6, info.generationTime!!, 0.001); assertEquals(62.5, info.tokensPerSecond!!, 0.01)
    }
    @Test fun anEmptyReplyIsAnErrorWordedByWhetherSearchesRan() {
        val llm = ChatGeneration(); llm.beginTurn(0); llm.seedDebugInfo("near.ai", "m", null, "t", false)
        assertEquals("near.ai sent an empty answer. ask again to retry.", llm.finishGeneration("near.ai", null, "t", emptyList(), null, 10)!!.userMessage)
        val again = ChatGeneration(); again.beginTurn(0); again.seedDebugInfo("near.ai", "m", null, "t", false)
        val two = listOf(ToolCallRecord("web_search", "{}", ""), ToolCallRecord("web_search", "{}", ""))
        assertEquals("near.ai ran 2 searches but didn't write an answer. ask again to retry.", again.finishGeneration("near.ai", null, "t", two, null, 10)!!.userMessage)
        assertEquals("t", again.lastErrorThreadID)
    }
    @Test fun aCancelledEmptyTurnIsNotAnError() {
        val llm = ChatGeneration(); llm.beginTurn(0); llm.stop()
        assertNull(llm.finishGeneration("p", null, "t", emptyList(), null, 10))
    }
    @Test fun beginTurnRefusesWhileRunning() {
        val llm = ChatGeneration(); assertTrue(llm.beginTurn(0)); assertFalse(llm.beginTurn(1)); llm.endTurn(); assertTrue(llm.beginTurn(2))
    }
}

class WireTranscriptTest {
    private val turns = listOf(WireMessage("user", "one"), WireMessage("assistant", "1"), WireMessage("system", "stray"),
        WireMessage("user", "two"), WireMessage("assistant", "2"), WireMessage("user", "three"))

    @Test fun systemPromptLeadsAndTheLastMessageIsLeftToTheEngine() {
        val wire = ChatGeneration.buildWireMessages(turns, "be brief", maxMessages = null)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), wire.map { it.role })
        assertEquals("be brief", wire.first().content); assertEquals("2", wire.last().content)
    }
    @Test fun aStrayStoredSystemMessageNeverReachesTheWire() {
        assertFalse(ChatGeneration.buildWireMessages(turns, "p", null).any { it.content == "stray" })
    }
    @Test fun maxMessagesKeepsTheNewestAndCountsThePromptItself() {
        val wire = ChatGeneration.buildWireMessages(turns, "p", maxMessages = 3)
        assertEquals(listOf("system", "user", "assistant"), wire.map { it.role }); assertEquals("two", wire[1].content)
        assertEquals("a single-turn provider sends only the system prompt", listOf("system"), ChatGeneration.buildWireMessages(turns, "p", maxMessages = 1).map { it.role })
    }
    @Test fun omitSystemPromptDropsIt() {
        assertEquals(listOf("user", "assistant"), ChatGeneration.buildWireMessages(turns, "p", maxMessages = 3, omitSystemPrompt = true).map { it.role })
    }
    @Test fun datetimeTemplateResolves() {
        val at = ZonedDateTime.of(2026, 9, 3, 14, 5, 0, 0, ZoneId.of("UTC"))
        val resolved = ChatGeneration.resolvePromptTemplates("now: {{datetime}}.", at)
        assertFalse(resolved.contains("{{")); assertTrue(resolved, resolved.contains("2026")); assertTrue(resolved, resolved.contains("3"))
    }
}

/**
 * ports: teemoonTests/PromptPrefixStabilityTests.swift @ 224ec87. Prompt caching needs the request prefix byte-identical
 * from one turn to the next; `{{datetime}}` resolved to the minute, so most chats never hit the cache.
 */
class PromptPrefixStabilityTest {
    private val morning = ZonedDateTime.of(2026, 10, 20, 9, 2, 0, 0, ZoneId.of("UTC"))
    private val laterThatDay = morning.plusHours(3).plusMinutes(17)

    @Test fun theSystemPromptIsTheSameAllDay() {
        val a = ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT, morning)
        assertEquals(a, ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT, laterThatDay))
        assertFalse(a.contains("{{datetime}}"))
        // the private date line names its zone with an offset ("UTC+00:00"), which is not a clock time
        assertFalse("no clock time in the system prompt", a.replace(Regex("UTC[+\u2212]\\d{2}:\\d{2}"), "").contains(":"))
    }

    /** iOS dateAndTheOlderDatetimeMeanTheSameDay: both templates resolve to the day. */
    @Test fun dateAndTheOlderDatetimeMeanTheSameDay() {
        assertEquals(ChatGeneration.resolvePromptTemplates("{{datetime}}", morning), ChatGeneration.resolvePromptTemplates("{{date}}", morning))
        assertFalse(ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT, morning).contains("{{date}}"))
        assertTrue("the default prompt carries {{date}} (iOS 6ca792f)", AppSettings.DEFAULT_SYSTEM_PROMPT.startsWith("Today's date is {{date}}."))
    }

    /** iOS aPersonaWithADateGetsTimeNotes: only a prompt that asks for the date, and only when a system prompt is sent. */
    @Test fun aPersonaWithADateGetsTimeNotes() {
        assertTrue(ChatGeneration.wantsTimeNotes(AppSettings.DEFAULT_SYSTEM_PROMPT, omitSystemPrompt = false))
        assertTrue(ChatGeneration.wantsTimeNotes("it is {{datetime}}", omitSystemPrompt = false))
        assertFalse(ChatGeneration.wantsTimeNotes("no template here", omitSystemPrompt = false))
        assertFalse(ChatGeneration.wantsTimeNotes(AppSettings.DEFAULT_SYSTEM_PROMPT, omitSystemPrompt = true))
    }

    /** iOS's exact bytes, "\n\n[Sent at h:mm a]", from the message's own timestamp: the same turn, the same note, every request. */
    @Test fun theNoteIsTheMessagesOwnTime() {
        val at = morning.toInstant().toEpochMilli()
        val note = ChatGeneration.timeNote(at, ai.teemoon.providers.DisclosurePolicy(ai.teemoon.providers.DisclosurePolicy.Tier.PRIVATE, ZoneId.of("UTC")))
        assertEquals("\n\n[Sent at 9:02 AM]", note)
        assertEquals(note, ChatGeneration.timeNote(at, ai.teemoon.providers.DisclosurePolicy(ai.teemoon.providers.DisclosurePolicy.Tier.PRIVATE, ZoneId.of("UTC"))))
    }

    /** The history the second turn sends starts with exactly the bytes the first turn sent. */
    @Test fun historyIsAPrefixOfTheNextTurn() {
        val sys1 = ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT, morning)
        val sys2 = ChatGeneration.resolvePromptTemplates(AppSettings.DEFAULT_SYSTEM_PROMPT, laterThatDay)
        val turn1 = ChatGeneration.buildWireMessages(listOf(WireMessage("user", "first question")), sys1, maxMessages = null)
        val turn2 = ChatGeneration.buildWireMessages(listOf(WireMessage("user", "first question"), WireMessage("assistant", "first answer"),
            WireMessage("user", "follow-up")), sys2, maxMessages = null)
        assertEquals(turn1.map { it.content }, turn2.take(turn1.size).map { it.content })
        assertEquals(listOf("system", "user", "assistant"), turn2.map { it.role })
    }
}

class RequestDebugTextTest {
    @Test fun copiedCardRedactsAndListsEveryRound() {
        val info = LastRequestDebugInfo(providerName = "grok", modelID = "grok-4", url = "https://api.x.ai/v1/chat/completions?key=secret12345",
            requestHeaders = mapOf("Authorization" to "Bearer xai-abc123def456", "Content-Type" to "application/json"), requestBodyJson = "{}",
            responseBody = "62°F", toolCalls = listOf(ToolCallRecord("web_search", """{"query":"weather sf"}""", "<source index=\"1\"><url>https://a</url><title>A</title><content>c</content></source>"),
                ToolCallRecord("web_search", """{"query":"news","count":5}""", "")),
            threadID = "t", totalDuration = 4.137, timeToFirstToken = 0.821, outputTokens = 312, isE2EEActive = false, teeVerification = null)
        val text = RequestDebugText.debugText(info)
        assertTrue(text.startsWith("=== grok grok-4 Debug ===\n4.1s · ttft 0.8s · gen 3.3s · 312tok · 94tok/s"))
        assertFalse("the credential never leaves the device through a copied card", text.contains("xai-abc123def456"))
        assertTrue(text.contains("Authorization: Bearer ••••…f456")); assertFalse(text.contains("secret12345"))
        assertTrue(text.contains("--- Tool: web_search -> 1 \"weather sf\" 1 · Response JSON ---"))
        assertTrue(text.contains("--- Tool: web_search · n:5 \"news\" 2 · Response for LLM ---"))
        assertTrue(text.endsWith("--- Response ---\n62°F"))
    }

    private fun cardInfo(local: ai.teemoon.inference.LocalTurnStats?) = LastRequestDebugInfo(providerName = "Gemma 4 E2B", modelID = "m", url = null,
        requestHeaders = null, requestBodyJson = null, responseBody = null, toolCalls = emptyList(), threadID = "t",
        totalDuration = 10.0, timeToFirstToken = 0.6, outputTokens = 899, isE2EEActive = false, teeVerification = null, localStats = local)

    @Test fun anOnDeviceTurnLeadsWithDecodeThenPrefillThenTheBackend() {
        val info = cardInfo(ai.teemoon.inference.LocalTurnStats("gpu", speculative = true, prefillTokens = 1191, prefillTokensPerSecond = 2139.4))
        assertEquals("decode 96tok/s · prefill 2139tok/s · gpu + speculative", info.localStatsLine)
        assertNull("decode is shown once, on the speed line", info.firstLineTokensPerSecond)
        assertTrue(RequestDebugText.debugText(info).contains("decode 96tok/s · prefill 2139tok/s · gpu + speculative"))
    }

    @Test fun anNpuTurnWithoutADrafterSaysNothingAboutSpeculativeDecoding() {
        val info = cardInfo(ai.teemoon.inference.LocalTurnStats("npu", speculative = false, prefillTokens = 1191, prefillTokensPerSecond = 2082.0))
        assertEquals("decode 96tok/s · prefill 2082tok/s · npu", info.localStatsLine)
    }

    @Test fun aCloudTurnKeepsItsOneLine() {
        val info = cardInfo(null)
        assertNull(info.localStatsLine)
        assertEquals(96.0, info.firstLineTokensPerSecond!!, 0.5)
    }

    @Test fun theTransportsLocalStatsReachTheCard() {
        val gen = ChatGeneration()
        gen.seedDebugInfo("Gemma 4 E2B", "m", null, "t", expectingE2EE = false)
        val stats = ai.teemoon.inference.LocalTurnStats("gpu", speculative = true, prefillTokens = 10, prefillTokensPerSecond = 100.0)
        gen.recordSuccess(RequestResult(url = null, requestHeaders = null, requestBodyJson = null, responseBody = "x", toolCalls = emptyList(),
            outputTokens = 5, teeVerification = null, isE2eeActive = false, localStats = stats), "Gemma 4 E2B", "m", "t")
        assertEquals(stats, gen.lastRequestDebugInfo?.localStats)
    }

    /**
     * The maintainer 2026-11-12 (1.1.0, via pm): the prompt's time stops leaking the time zone and the language. A
     * plain-TLS cloud gets the day in UTC and "[Sent at 16:42 UTC]"; a private destination local time with the zone named
     * once; every formatter English whatever the phone's language (conformance/disclosure.json, DisclosurePolicy).
     */
    @Test fun aPlainTlsCloudGetsUtcAndEveryFormatterIsEnglish() {
        val minimised = ai.teemoon.providers.DisclosurePolicy(ai.teemoon.providers.DisclosurePolicy.Tier.MINIMISED)
        val at = java.time.ZonedDateTime.of(2026, 11, 13, 1, 42, 0, 0, java.time.ZoneId.of("Asia/Tokyo"))   // 2026-11-12 16:42 UTC
        assertEquals("\n\n[Sent at 16:42 UTC]", ChatGeneration.timeNote(at.toInstant().toEpochMilli(), minimised))
        assertEquals("the UTC day, not Tokyo's", "Thursday, November 12, 2026 (UTC)", ChatGeneration.resolvePromptTemplates("{{date}}", at, minimised))
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val tokyo = ai.teemoon.providers.DisclosurePolicy(ai.teemoon.providers.DisclosurePolicy.Tier.PRIVATE, java.time.ZoneId.of("Asia/Tokyo"))
            assertEquals("English on a German phone", "\n\n[Sent at 1:42 AM]", ChatGeneration.timeNote(at.toInstant().toEpochMilli(), tokyo))
            assertEquals("Friday, November 13, 2026 (Asia/Tokyo, UTC+09:00)", ChatGeneration.resolvePromptTemplates("{{date}}", at, tokyo))
        } finally { java.util.Locale.setDefault(was) }
    }
}
