// ports: teemoon/Inference/LLMError.swift @ v1.0.4 (isKeyRejection)
package ai.teemoon.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rejected key must END the turn, not be retried.
 *
 * The tool loop retries a failed call with a rephrased query, which is right for a bad query and useless for a bad
 * key: three identical failures and a burned round budget. `isKeyRejection` is what stops it.
 *
 * This was `braveKeyRejected` and gated on `source == BraveGrounding` before it read the status (B8). That was
 * indistinguishable from correct, because Brave is the only registered tool — so these tests are mostly about the
 * tool that does not exist yet, and they are written with a second `Source.Provider` standing in for it.
 */
class KeyRejectionTest {
    private fun err(source: LlmError.Source, status: Int?, body: String? = null) =
        LlmError(source = source, userMessage = "x", httpStatus = status, responseBody = body)

    private val brave = LlmError.Source.BraveGrounding
    private val other = LlmError.Source.Provider("some-future-tool")

    /** The three statuses that mean "your key", whoever answered with them. */
    @Test fun everyRejectionStatusStopsTheTurnForAnySource() {
        for (source in listOf(brave, other)) {
            for (status in listOf(401, 402, 403)) {
                assertTrue("$source $status must stop the turn", err(source, status).isKeyRejection)
            }
        }
    }

    /**
     * THE REGRESSION. Before the generalisation this was false for everything but Brave, so a second tool's 401
     * was swallowed and retried. If this ever goes back to checking the source first, this is the test that fails.
     */
    @Test fun aSecondToolsUnauthorizedIsAlsoAKeyRejection() {
        assertTrue(err(other, 401).isKeyRejection)
    }

    /** Statuses that mean something a retry might genuinely fix, or nothing at all. */
    @Test fun ordinaryFailuresDoNotStopTheTurn() {
        for (source in listOf(brave, other)) {
            for (status in listOf(400, 404, 429, 500, 502, 503)) {
                assertFalse("$source $status is not a key problem", err(source, status).isKeyRejection)
            }
            assertFalse("a transport error has no status", err(source, null).isKeyRejection)
        }
    }

    /**
     * 422 stays Brave-shaped, and deliberately: the envelope it reads is Brave's own
     * `SUBSCRIPTION_TOKEN_INVALID`. Another vendor's 422 means whatever that vendor says it means, and guessing
     * would turn an ordinary validation failure into a turn-ending key error.
     */
    @Test fun the422ClauseIsBraveSpecific() {
        val body = """{"error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"bad token"}}"""
        assertTrue("Brave's malformed-key body", err(brave, 422, body).isKeyRejection)
        assertFalse("another vendor's 422 is not read as a key problem", err(other, 422, body).isKeyRejection)
        assertFalse("Brave's 422 without the envelope is an ordinary validation failure", err(brave, 422, """{"error":"bad query"}""").isKeyRejection)
    }
}
