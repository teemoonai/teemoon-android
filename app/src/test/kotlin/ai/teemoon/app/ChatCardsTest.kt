// ports: teemoonTests/DebugCopyRedactionTests.swift @ v1.0.1 (errorCardCopy/requestCardCopy — the header/url tests are in PresentationLogicTest),
//        teemoonTests/TurnPhasesTests.swift @ 6fdcb43 (theCardsTimingRowEndsWithEffortAndCache),
//        plus Kotlin-self pins for consoleAction, webSearchChipState, the activity label and the path crumb (no Swift tests exist)
package ai.teemoon.app

import ai.teemoon.inference.LlmError
import ai.teemoon.inference.ProviderWait
import ai.teemoon.inference.ToolCallRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCardsTest {
    private val liveKey = "sk-or-v1-fake00000000000000000000000000000000000000000000000000000-1f18"
    private val headers = mapOf("Authorization" to "Bearer $liveKey", "X-Api-Key" to liveKey, "x-subscription-token" to liveKey, "Content-Type" to "application/json", "User-Agent" to "teemoon/1.0")

    @Test fun errorCardCopy_carriesNoLiveToken() {
        val error = LlmError(LlmError.Source.Provider("near.ai glm 5.2"), LlmError.providerMessage(401, "near.ai"), httpStatus = 401,
            url = "https://cloud-api.near.ai/v1/chat/completions?api_key=$liveKey", requestHeaders = headers, requestBodyJson = "{\"model\":\"z-ai/glm-5.2\"}",
            responseBody = "{\"error\":{\"message\":\"Invalid API key\"}}")
        val copied = ErrorCardText.debugText(error)
        assertFalse(copied.contains(liveKey))
        assertTrue(copied.contains("--- Headers ---")); assertTrue(copied.contains("Content-Type: application/json"))
        assertTrue(copied.contains("--- Request Body ---")); assertTrue(copied.contains("z-ai/glm-5.2")); assertTrue(copied.contains("HTTP 401"))
        assertEquals("the display path is verbatim outside the UI-test host", "Bearer $liveKey", DebugHeaderRedaction.value("Bearer $liveKey", "Authorization"))
    }
    @Test fun requestCardCopy_carriesNoLiveToken() {
        val info = LastRequestDebugInfo(providerName = "near.ai", modelID = "zai-org/GLM-5.1-FP8", url = "https://cloud-api.near.ai/v1/chat/completions", requestHeaders = headers,
            requestBodyJson = "{\"model\":\"zai-org/GLM-5.1-FP8\"}", responseBody = "hello", toolCalls = emptyList(), threadID = "t", totalDuration = 2.341, timeToFirstToken = 0.612, outputTokens = 187, isE2EEActive = false, teeVerification = null)
        val copied = RequestDebugText.debugText(info)
        assertFalse(copied.contains(liveKey)); assertTrue(copied.contains("--- Headers ---")); assertTrue(copied.contains("User-Agent: teemoon/1.0")); assertTrue(copied.contains("zai-org/GLM-5.1-FP8"))
    }
    /**
     * A PROVIDER THAT ECHOES THE KEY DOES NOT PUT IT ON THE CLIPBOARD. Only the URL and headers were masked; a body, a
     * message, a tool result or an underlying error was copied verbatim (the local audit of c0ed24c, 2026-10-13).
     */
    @Test fun bothCardsMaskAStoredKeyAnywhereInTheCopiedText() {
        val previous = DebugHeaderRedaction.storedSecrets
        DebugHeaderRedaction.storedSecrets = { listOf(liveKey) }
        try {
            val error = LlmError(LlmError.Source.Provider("gw"), "the key $liveKey was rejected", httpStatus = 401, url = "https://gw.example.com/v1",
                requestBodyJson = "{\"key\":\"$liveKey\"}", messageHistory = "[\"$liveKey\"]", responseBody = "{\"error\":\"invalid key $liveKey\"}")
            val errorCopy = ErrorCardText.debugText(error)
            assertFalse(errorCopy, errorCopy.contains(liveKey)); assertFalse(errorCopy.contains(liveKey.take(16)))
            assertTrue("the rest of the card survives", errorCopy.contains("HTTP 401") && errorCopy.contains("invalid key"))
            val info = LastRequestDebugInfo(providerName = "gw", modelID = "m", url = "https://gw.example.com/v1", requestHeaders = emptyMap(),
                requestBodyJson = "{\"k\":\"$liveKey\"}", responseBody = "echo: $liveKey",
                toolCalls = listOf(ToolCallRecord(name = "web_search", arguments = "{}", result = "found $liveKey")), threadID = "t",
                totalDuration = 1.0, timeToFirstToken = 0.1, outputTokens = 1, isE2EEActive = false, teeVerification = null)
            val requestCopy = RequestDebugText.debugText(info)
            assertFalse(requestCopy, requestCopy.contains(liveKey)); assertFalse(requestCopy.contains(liveKey.take(16)))
            assertTrue(requestCopy.contains("echo:"))
        } finally { DebugHeaderRedaction.storedSecrets = previous }
    }

    @Test fun consoleAction_onlyKeyAndBillingStatusesEarnAButton() {
        fun err(status: Int?) = LlmError(LlmError.Source.Provider("near.ai"), "", httpStatus = status)
        val recovery = "https://cloud.near.ai/keys" to "near.ai"
        assertEquals("manage api key at near.ai" to recovery.first, ErrorCardText.consoleAction(err(401), recovery))
        assertEquals("add credits at near.ai" to recovery.first, ErrorCardText.consoleAction(err(402), recovery))
        assertNull(ErrorCardText.consoleAction(err(500), recovery)); assertNull(ErrorCardText.consoleAction(err(null), recovery)); assertNull(ErrorCardText.consoleAction(err(401), null))
    }
    @Test fun sourceLabel_namesBraveSearchForGroundingErrors() {
        assertEquals("Brave Search", ErrorCardText.sourceLabel(LlmError(LlmError.Source.BraveGrounding, "")))
        assertEquals("box", ErrorCardText.sourceLabel(LlmError(LlmError.Source.Provider("box"), "")))
    }
    @Test fun messageCount_readsTheHistoryArray() {
        assertEquals(2, ErrorCardText.messageCount(LlmError(LlmError.Source.Provider("x"), "", messageHistory = "[{\"role\":\"system\"},{\"role\":\"user\"}]")))
        assertEquals(0, ErrorCardText.messageCount(LlmError(LlmError.Source.Provider("x"), "", messageHistory = "not json")))
    }
    @Test fun webSearchChip_onlyNothingEquippedDims() {
        assertEquals(WebSearchChipState.DISABLED, webSearchChipState(hasProvider = false, hasKey = true, groundingEnabled = true))
        assertEquals(WebSearchChipState.UNCONFIGURED, webSearchChipState(hasProvider = true, hasKey = false, groundingEnabled = true))
        assertEquals(WebSearchChipState.ON, webSearchChipState(hasProvider = true, hasKey = true, groundingEnabled = true))
        assertEquals(WebSearchChipState.OFF, webSearchChipState(hasProvider = true, hasKey = true, groundingEnabled = false))
    }
    @Test fun activityLabel_followsTheStreamState() {
        assertEquals("thinking...", activityLabel(ActivityState.of(false, 0), 0.4))
        assertEquals("thinking... 45s", activityLabel(ActivityState.Thinking, 45.7))
        // Think Harder (iOS 14b2974): the same counter, while the old reply waits dimmed above.
        assertEquals("thinking harder...", activityLabel(ActivityState.of(false, 0, thinkingHarder = true), 0.4))
        assertEquals("thinking harder... 12s", activityLabel(ActivityState.ThinkingHarder, 12.9))
        assertEquals("a running search still wins", ActivityState.Searching, ActivityState.of(true, 0, thinkingHarder = true))
        assertEquals("searching...", activityLabel(ActivityState.of(true, 0), null))
        assertEquals("1 source", activityLabel(ActivityState.of(true, 1), null)); assertEquals("5 sources", activityLabel(ActivityState.of(true, 5), null))
        assertEquals("3 sources", sourcesChipLabel(3))
    }

    /** iOS 9e090f8 + 4510788: a running tool wins, then a request past 5 s without headers, then thinking; the number is the turn's. */
    @Test fun theWaitingChipShowsFromFiveSecondsWithTheTurnsCount() {
        val wait = ProviderWait(turn = 2, sentAtMs = 10_000, providerName = "fireworks")
        assertEquals(ActivityState.Thinking, ActivityState.of(false, 0, wait, nowMs = 14_999))
        assertEquals(ActivityState.WaitingForProvider("fireworks"), ActivityState.of(false, 0, wait, nowMs = 15_000))
        assertEquals(ActivityState.Searching, ActivityState.of(true, 0, wait, nowMs = 60_000))
        assertEquals(ActivityState.SourcesFound(3), ActivityState.of(true, 3, wait, nowMs = 60_000))
        assertEquals(ActivityState.Thinking, ActivityState.of(false, 0, null, nowMs = 60_000))
        // the follow-up request waited 5 s; the turn has run 31 s, and the chip says 31
        assertEquals("waiting for fireworks... 31s", activityLabel(ActivityState.WaitingForProvider("fireworks"), 31.4))
    }

    @Test fun aRequestsHeadersClearOnlyItsOwnWait() {
        val g = ChatGeneration()
        g.noteResponseWait(1, 1_000, "nvidia")
        g.noteResponseWait(2, 2_000, "nvidia")
        assertEquals(2, g.providerWait?.turn)
        g.noteResponseWait(1, 3_000, "nvidia"); assertEquals("an older request does not take over", 2_000L, g.providerWait?.sentAtMs)
        g.noteResponseWait(1, null, "nvidia"); assertEquals("an older request's headers leave the newer wait", 2, g.providerWait?.turn)
        g.noteResponseWait(2, null, "nvidia"); assertNull(g.providerWait)
        g.noteResponseWait(3, 4_000, "nvidia"); g.endTurn(); assertNull("the turn's end clears it", g.providerWait)
    }
    @Test fun pathCrumb_isBoundedAndAbsentForARoot() {
        assertNull(sourcePathCrumb("https://redact.dev")); assertNull(sourcePathCrumb("https://redact.dev/"))
        assertEquals("/r/twitter/comments/example", sourcePathCrumb("https://www.reddit.com/r/twitter/comments/example"))
        assertTrue(sourcePathCrumb("https://x.example/" + "a".repeat(60))!!.endsWith("…"))
    }
    @Test fun displayResultKeepsTheCardBounded() { assertTrue(ToolCallRecord("web_search", "{}", "x".repeat(10_000)).displayResult.length < 1800) }

    /** FaviconView: djb2 in a wrapping 64-bit UInt, mod 360 (values from an independent Python djb2). */
    @Test fun faviconTile_hueAndGlyphAsIos() {
        assertEquals(118, faviconHueDegrees("reddit.com")); assertEquals(325, faviconHueDegrees("github.com")); assertEquals(301, faviconHueDegrees("redact.dev"))
        assertEquals("R", faviconGlyph("www.reddit.com")); assertEquals("W", faviconGlyph("en.wikipedia.org")); assertEquals("E", faviconGlyph("m.example.com"))
    }

    /** SourceStackChipView.detents(for:): content height for one or two, medium for three to six, large for seven. */
    @Test fun sourcesSheet_detentsByCount() {
        val two = sourcesSheetTops(2, 800f)
        assertEquals(listOf(1f - 273f / 800f, SheetDetents.MEDIUM, SheetDetents.LARGE), two)
        assertEquals(listOf(SheetDetents.MEDIUM, SheetDetents.LARGE), sourcesSheetTops(3, 800f))
        assertEquals(listOf(SheetDetents.MEDIUM, SheetDetents.LARGE), sourcesSheetTops(6, 800f))
        assertEquals(listOf(SheetDetents.LARGE), sourcesSheetTops(7, 800f))
    }

    /** A key exists exactly when the web chip is on or off; then the offer cards go (iOS 008cc19). */
    @Test fun searchIsConfiguredWhenTheChipIsOnOrOff() {
        assertEquals(listOf(true, true, false, false), listOf(WebSearchChipState.ON, WebSearchChipState.OFF, WebSearchChipState.UNCONFIGURED, WebSearchChipState.DISABLED).map { it.searchConfigured })
    }

    /** The maintainer (card-one-line, iOS 6fdcb43): effort and cache go on the timing row; the copied text keeps its own lines. */
    @Test fun theCardsTimingRowEndsWithEffortAndCache() {
        val info = LastRequestDebugInfo(providerName = "fireworks", url = "https://api.fireworks.ai/inference/v1/chat/completions", requestHeaders = null,
            requestBodyJson = """{"reasoning_effort":"low"}""", responseBody = null, toolCalls = emptyList(), threadID = "t",
            totalDuration = 6.9, timeToFirstToken = 1.4, outputTokens = 412, isE2EEActive = false, teeVerification = null, cacheLine = "cache 92%")
        assertEquals("6.9s · ttft 1.4s · gen 5.5s · 412tok · 75tok/s · effort low · cache 92%", info.cardTimingLine)
        assertEquals("6.9s · ttft 1.4s · gen 5.5s · 412tok · 75tok/s", info.timingLine)
        val copied = RequestDebugText.debugText(info)
        assertTrue(copied.contains("\n6.9s · ttft 1.4s · gen 5.5s · 412tok · 75tok/s\n")); assertTrue(copied.contains("\neffort low\n"))
        assertFalse("the copy keeps its own lines", copied.contains("effort low · cache"))
        // On device the level is the runtime's switch, read from the card's "thinking" header (iOS 7cd8c10).
        val onDevice = info.copy(url = null, requestHeaders = mapOf("thinking" to "on"), requestBodyJson = "[]")
        assertEquals("thinking on", onDevice.effortLevel)
    }
}
