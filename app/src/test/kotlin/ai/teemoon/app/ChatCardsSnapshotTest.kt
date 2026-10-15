// Paparazzi baselines for the transcript cards: an error card with a console action, a developer card with a tool round, the chips.
package ai.teemoon.app

import ai.teemoon.inference.GroundingSource
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.ToolCallRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ChatCardsSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 1100, density = com.android.resources.Density.MEDIUM))

    /**
     * The second tool round (StreamingMessageView's trailing chip): a model wrote a preamble, then called a tool, and the
     * follow-up is in flight — text on screen, nothing moving. The chip under the text says teemoon is still working.
     */
    @Test fun streamingReply_trailingChipDuringASecondToolRound() {
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) {
            AssistantMessageView(messageId = "m", content = "Let me check the latest forecast for you.", generatingTimeMs = null,
                isLLMRunning = true, streaming = true, sources = emptyList(),
                trailing = { ActivityChip(ActivityState.of(isExecutingTools = true, sourceCount = 0), elapsedSeconds = 7.0) },
                showTrailing = TrailingActivityChip.visible(hasText = true, stalled = false, executingTools = true, awaitingModel = false))
        } } } }
    }

    /**
     * The chain that clipped: near.ai's grounded turn on a Pixel 10 Pro, 2026-09-22 — every figure long. The line must
     * shrink to show all six, tok/s included, rather than lose the last one off the right edge.
     */
    @Test fun debugCard_aLongStatsChainStillShowsTokPerSecond() {
        val info = LastRequestDebugInfo(providerName = "near.ai", modelID = "z-ai/glm-5.3-flash", url = "https://cloud-api.near.ai/v1/chat/completions",
            requestHeaders = mapOf("Content-Type" to "application/json"), requestBodyJson = "{ }", responseBody = "…", toolCalls = emptyList(),
            threadID = "t", totalDuration = 304.3, timeToFirstToken = 229.6, thinkingTime = 12.4, outputTokens = 11282, isE2EEActive = true, teeVerification = null)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) { RequestDebugCard(info, onCopy = {}) } } } }
    }

    /**
     * An on-device turn's two lines: the turn's timing as for any provider, then the engine's — backend, drafter, and
     * prefill beside decode. The S26 Ultra code edit of 2026-10-15 (Samsung RTL): 899 tokens in 9.3 s on the GPU with speculative
     * decoding, the prompt read at 2,139 tok/s.
     */
    @Test fun debugCard_onDeviceShowsPrefillAndDecode() {
        val info = LastRequestDebugInfo(providerName = "Gemma 4 E2B", modelID = "litert-community/gemma-4-E2B-it-litert-lm", url = null,
            requestHeaders = mapOf("runtime" to "LiteRT-LM"), requestBodyJson = "[ ]", responseBody = "…", toolCalls = emptyList(),
            threadID = "t", totalDuration = 10.0, timeToFirstToken = 0.6, outputTokens = 899, isE2EEActive = false, teeVerification = null,
            localStats = ai.teemoon.inference.LocalTurnStats(backend = "gpu", speculative = true, prefillTokens = 1191, prefillTokensPerSecond = 2139.0))
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) { RequestDebugCard(info, onCopy = {}) } } } }
    }

    /** The same card for an NPU turn without a drafter (Pixel 10 Pro): no "+ speculative". */
    @Test fun debugCard_onDeviceNpuTurn() {
        val info = LastRequestDebugInfo(providerName = "Gemma 4 E2B", modelID = "litert-community/gemma-4-E2B-it-litert-lm", url = null,
            requestHeaders = mapOf("runtime" to "LiteRT-LM"), requestBodyJson = "[ ]", responseBody = "…", toolCalls = emptyList(),
            threadID = "t", totalDuration = 14.2, timeToFirstToken = 0.5, outputTokens = 266, isE2EEActive = false, teeVerification = null,
            localStats = ai.teemoon.inference.LocalTurnStats(backend = "npu", speculative = false, prefillTokens = 1191, prefillTokensPerSecond = 2082.0))
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp)) { RequestDebugCard(info, onCopy = {}) } } } }
    }

    @Test fun chatCards_errorDebugChips() {
        val error = LlmError(LlmError.Source.Provider("near.ai glm 5.2"), LlmError.providerMessage(402, "near.ai"), httpStatus = 402,
            url = "https://cloud-api.near.ai/v1/chat/completions", responseBody = "{\"error\":{\"message\":\"Insufficient balance\"}}")
        val info = LastRequestDebugInfo(providerName = "grok", modelID = "grok-4.3", url = "https://api.x.ai/v1/chat/completions",
            requestHeaders = mapOf("Authorization" to "Bearer xai-abc123def456", "Content-Type" to "application/json"), requestBodyJson = "{ \"model\" : \"grok-4.3\" }",
            responseBody = "62°F, partly cloudy.", toolCalls = listOf(ToolCallRecord("web_search", "{\"query\":\"weather today San Francisco\"}", "<source index=\"1\"><url>https://sfgate.com/weather</url><title>SF Weather</title><content>62°F</content></source>")),
            threadID = "t", totalDuration = 4.137, timeToFirstToken = 0.821, outputTokens = 312, isE2EEActive = false, teeVerification = null)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ErrorCard(error, consoleRecovery = "https://cloud.near.ai/" to "near.ai", onDisableWebSearch = {}, onCopy = {})
            RequestDebugCard(info, onCopy = {})
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActivityChip(ActivityState.Thinking, 45.0); ActivityChip(ActivityState.SourcesFound(5), null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourcesChip(listOf(GroundingSource("https://redact.dev", "redact.dev", "Redact")))
                WebSearchChip(WebSearchChipState.ON, onClick = {}); WebSearchChip(WebSearchChipState.DISABLED, onClick = {})
            }
        } } } }
    }

    /** GroundingSourcesView with iOS's own preview sources: favicon tiles, domain › path crumbs, the fixed 12/15.5/13.5 type. */
    @Test fun groundingSourcesView_theIosPreviewSources() {
        val sources = listOf(
            GroundingSource("https://www.reddit.com/r/twitter/comments/example", "reddit.com", "How I bulk-deleted 14,000 tweets without paying for the API",
                "I wrote a small script that walks the timeline and hits the delete endpoint with a 200ms throttle … no rate-limit issues over 12 hours."),
            GroundingSource("https://github.com/teisseire117/DeleteTweets", "github.com", "teisseire117/DeleteTweets",
                "Python script that reads your Twitter archive and deletes tweets matching a date range or keyword. No API key required."),
            GroundingSource("https://redact.dev", "redact.dev", "Redact — mass delete posts and messages", ""),
        )
        paparazzi.snapshot { TeemoonTheme { Surface(color = TeemoonColors.groupedBackground) { GroundingSourcesView(sources, onClose = {}) } } }
    }
}
