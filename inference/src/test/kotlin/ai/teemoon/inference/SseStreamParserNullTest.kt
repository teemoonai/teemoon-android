// android-only regression: a JSON null delta field is absent, never the word "null" (found live against near.ai, 2026-09-03).
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseStreamParserNullTest {
    @Test fun nullContentDeltaIsAbsent() {
        val p = SseStreamParser()
        val events = p.consume("data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"hmm\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"pong\",\"reasoning_content\":null}}]}\n\n")
        assertEquals(listOf(SseEvent.Reasoning("reasoning_content", "hmm"), SseEvent.Content("pong")), events)
        assertEquals("c1", p.chatCompletionId)
    }

    /** Under E2EE the same null must not reach the decrypt hook — it is not a ciphertext. */
    @Test fun nullContentDeltaNeverReachesTheDecryptHook() {
        val p = SseStreamParser()
        var calls = 0
        p.consume("data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n") { calls++; null }
        assertEquals(0, calls); assertTrue(!p.decryptFailed)
    }
}
