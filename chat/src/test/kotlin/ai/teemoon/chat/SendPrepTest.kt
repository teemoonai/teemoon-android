// ports: teemoonTests/SendPrepTests.swift @ v1.0.1
package ai.teemoon.chat

import ai.teemoon.confidential.TrustSendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPrepTest {

    private fun prep(prompt: String, hasProvider: Boolean, isDownloading: Boolean, trust: TrustSendPolicy, requirePrompt: Boolean = true) =
        SendPrep.prepare(prompt, hasProvider, isDownloading, trust, requirePrompt)

    @Test fun emptyPromptBlocks() {
        assertEquals(SendPrep.BLOCKED_EMPTY_PROMPT, prep("   ", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.ALLOW))
    }

    @Test fun noProviderBlocksBeforeTrust() {
        assertEquals(SendPrep.BLOCKED_NO_PROVIDER, prep("hi", hasProvider = false, isDownloading = false, trust = TrustSendPolicy.BLOCK))
    }

    @Test fun downloadingBlocksBeforeTrust() {
        assertEquals(SendPrep.BLOCKED_DOWNLOADING, prep("hi", hasProvider = true, isDownloading = true, trust = TrustSendPolicy.ALLOW))
    }

    @Test fun confirmAndBlockAreDistinct() {
        assertEquals(SendPrep.CONFIRM_E2EE, prep("hi", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.CONFIRM))
        assertEquals(SendPrep.BLOCKED_E2EE, prep("hi", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.BLOCK))
        assertEquals(SendPrep.READY, prep("hi", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.ALLOW))
    }

    @Test fun retryIgnoresEmptyPrompt() {
        assertEquals(SendPrep.READY, prep("", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.ALLOW, requirePrompt = false))
    }

    @Test fun arrivalCopyDistinguishesProgressManifestAndMissing() {
        val progressing = Arrival(name = "gemma 4 e2b", fraction = 0.4, kind = Arrival.Kind.PHONE)
        assertTrue(progressing.alertMessage.contains("40% downloaded"))
        val manifest = Arrival(name = "gemma4:e4b", fraction = null, kind = Arrival.Kind.HOME_MANIFEST)
        assertTrue(manifest.alertMessage.contains("onto that machine"))
        val missing = Arrival(name = "gemma 4 e2b", fraction = null, kind = Arrival.Kind.PHONE)
        assertTrue(missing.alertMessage.contains("isn't downloaded"))
    }

    /** Android-only: a retry with an empty prompt still meets the trust gate — retry is not a hole. */
    @Test fun retryStillMeetsTheTrustGate() {
        assertEquals(SendPrep.BLOCKED_E2EE, prep("", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.BLOCK, requirePrompt = false))
        assertEquals(SendPrep.CONFIRM_E2EE, prep("", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.CONFIRM, requirePrompt = false))
        assertEquals(SendPrep.BLOCKED_NO_PROVIDER, prep("", hasProvider = false, isDownloading = false, trust = TrustSendPolicy.ALLOW, requirePrompt = false))
    }

    /** Android-only: the progress figure truncates like Swift's Int(), never rounds up to a lie. */
    @Test fun progressPercentTruncates() {
        assertTrue(Arrival("m", 0.999, Arrival.Kind.HOME).alertMessage.contains("99% downloaded"))
        assertTrue(Arrival("m", 0.0, Arrival.Kind.PHONE).alertMessage.contains("0% downloaded"))
    }

    /**
     * A MISSING KEY IS REPORTED AS A MISSING KEY. It blocks after "no provider"
     * and before trust or a download — and before the wire, where it would
     * surface as an authentication failure the user cannot act on (148a131).
     */
    @Test fun noKeyBlocksAfterNoProviderAndBeforeTrust() {
        assertEquals(SendPrep.BLOCKED_NO_KEY, SendPrep.prepare(
            "hi", hasProvider = true, isDownloading = true,
            trust = TrustSendPolicy.BLOCK, hasKey = false))
        assertEquals(SendPrep.BLOCKED_NO_PROVIDER, SendPrep.prepare(
            "hi", hasProvider = false, isDownloading = false,
            trust = TrustSendPolicy.ALLOW, hasKey = false))
        assertEquals(SendPrep.BLOCKED_NO_KEY, SendPrep.prepare(
            "", hasProvider = true, isDownloading = false,
            trust = TrustSendPolicy.ALLOW, requirePrompt = false, hasKey = false))
    }

    /** A keyless provider — on-device, Brave — is unaffected: hasKey defaults to true. */
    @Test fun aKeylessProviderIsUnaffected() {
        assertEquals(SendPrep.READY, SendPrep.prepare(
            "hi", hasProvider = true, isDownloading = false, trust = TrustSendPolicy.ALLOW))
    }
}
