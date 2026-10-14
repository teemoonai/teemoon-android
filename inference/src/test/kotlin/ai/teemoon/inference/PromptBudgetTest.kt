// ports: teemoonTests/PromptBudgetTests.swift @ v1.0.1
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {
    @Test fun theBodegaTurn_isFlagged() { assertTrue(PromptBudget(4_500, 392).looksTruncated) }
    @Test fun theSecretWordProbe_isFlagged() { assertTrue(PromptBudget(6_200, 2_051).looksTruncated) }
    @Test fun theSameTurnAfterRaisingTheWindow_isNotFlagged() { assertFalse(PromptBudget(4_500, 4_364).looksTruncated) }
    @Test fun shortPromptsAreNeverJudged() { assertFalse(PromptBudget(400, 20).looksTruncated) }
    @Test fun aTokenizerDisagreementIsNotTruncation() { assertFalse(PromptBudget(4_000, 3_000).looksTruncated) }
    @Test fun evaluatingMoreThanEstimatedIsFine() { assertFalse(PromptBudget(2_000, 2_600).looksTruncated) }
    @Test fun atTheThresholdItStaysQuiet() {
        assertFalse(PromptBudget(2_000, 1_200).looksTruncated); assertTrue(PromptBudget(2_000, 1_199).looksTruncated)
    }
    @Test fun estimateCountsMessageContent() {
        val messages = listOf(mapOf("role" to "system", "content" to "a".repeat(400)), mapOf("role" to "user", "content" to "b".repeat(400)))
        assertEquals(200, PromptBudget.estimateSentTokens(messages))
    }
    @Test fun estimateCountsToolCallArguments() {
        val messages = listOf(mapOf("role" to "assistant", "content" to "",
            "tool_calls" to listOf(mapOf("function" to mapOf("name" to "web_search", "arguments" to "x".repeat(390))))))
        assertEquals(100, PromptBudget.estimateSentTokens(messages))
    }
    @Test fun estimateIgnoresMessagesWithoutContent() { assertEquals(0, PromptBudget.estimateSentTokens(listOf(mapOf("role" to "user")))) }
    @Test fun parserReadsPromptTokensFromTheUsageChunk() {
        val parser = SseStreamParser()
        parser.consume("""data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":392,"completion_tokens":1110,"total_tokens":1502}}""" + "\n\n")
        assertEquals(392, parser.promptTokens); assertEquals(1110, parser.completionTokens)
    }
    @Test fun parserLeavesPromptTokensNullWhenTheServerOmitsUsage() {
        val parser = SseStreamParser()
        parser.consume("""data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"hi"}}]}""" + "\n\n")
        assertNull(parser.promptTokens)
    }
}
