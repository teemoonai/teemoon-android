// ports: android-only — the chip's caption/tone table, and a snapshot over a session in each headline state.
//
// The five SendPrep gate tests that used to sit here exercised a SECOND copy of
// SendPrep declared in TrustPresentation.kt. chat/SendPrepTest.kt is the mapped
// suite for teemoonTests/SendPrepTests.swift and covers the same five plus
// retryStillMeetsTheTrustGate and the Arrival copy. The duplicate copy is gone
// (2026-09 port review, B4), and with it these tests — they were green against
// code the app never called.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.AttestationState
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.TeeContext
import ai.teemoon.confidential.TrustChipMode
import ai.teemoon.confidential.TrustSendPolicy
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrustPresentationTest {

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }

    // MARK: - The chip

    /** The chip's word is TrustVerdict's caption; the tone never lets a hard break read as a benign amber, nor an absence as red. */
    @Test fun chip_captionAndToneFollowTheMode() {
        assertEquals(TrustChipModel("end-to-end encrypted", TrustTone.OK, TrustChipMode.VERIFIED), TrustChipModel.from(TrustChipMode.VERIFIED, AttestationState.OK, false))
        assertEquals(TrustChipModel("verification failed — sending blocked", TrustTone.FAILED, TrustChipMode.HARD_BLOCK), TrustChipModel.from(TrustChipMode.HARD_BLOCK, AttestationState.DEGRADED, false))
        assertEquals(TrustChipModel("encrypted — image unpublished", TrustTone.ADVISORY, TrustChipMode.SOFT_DEGRADE), TrustChipModel.from(TrustChipMode.SOFT_DEGRADE, AttestationState.DEGRADED, true))
        assertEquals(TrustChipModel("not end-to-end encrypted", TrustTone.ADVISORY, TrustChipMode.SOFT_DEGRADE), TrustChipModel.from(TrustChipMode.SOFT_DEGRADE, AttestationState.DEGRADED, false))
        assertEquals(TrustTone.NEUTRAL, TrustChipModel.from(TrustChipMode.ON_DEVICE, AttestationState.NONE, false).tone)
        assertEquals("on your own machine", TrustChipModel.from(TrustChipMode.SELF_HOSTED, AttestationState.NONE, false).label)
    }

    // MARK: - The no-plaintext-fallback guarantee, view-model half (E2EESecurityExploitTests)

    private fun record(key: ByteArray?) = AttestationRecord.preview.copy(modelEd25519PubKey = key, signingAddress = null, gpuSigningAddress = null)

    /** An attested provider with no E2EE peer (key blocked, never arrived, or invalid) gets NO send at all. */
    @Test fun attestedProvider_nilPeer_isRefused() {
        val provider = Provider(id = "p-near", name = "near.ai", endpoint = "https://completions.near.ai/v1", model = "z-ai/glm-5.2")
        assertTrue(ProviderCapability.ATTESTATION in provider.capabilities)
        assertTrue(mustRefuseUnsealedSend(provider, null))                                    // no context at all
        val keyless = TeeContext.from(provider, "k", record(null))!!  // a record with no key: peer is null
        assertNull(keyless.e2eePeer); assertTrue(mustRefuseUnsealedSend(provider, keyless))
        val lowOrder = TeeContext.from(provider, "k", record(ByteArray(32)))!!  // a low-order key: the guard refuses the peer
        assertNull(lowOrder.e2eePeer); assertTrue(mustRefuseUnsealedSend(provider, lowOrder))
    }

    /** Non-attested providers made no E2EE promise — they keep sending. */
    @Test fun nonAttestedProvider_isUnaffected() {
        val provider = Provider(id = "o", name = "Ollama", endpoint = "http://localhost:11434/v1", model = "llama3", requiresAPIKey = false)
        assertTrue(ProviderCapability.ATTESTATION !in provider.capabilities)
        assertTrue(!mustRefuseUnsealedSend(provider, null))
    }

    // MARK: - Snapshots

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun session(provider: Provider?) = ConfidentialSession(scope, { provider }, services = ConfidentialSession.Services(skipLiveAttestation = true))

    @Test fun snapshot_plainCloudProvider_isNotAttestable() {
        val p = Provider(id = "x", name = "acme", endpoint = "https://api.acme.example/v1", model = "acme/big-model")
        val s = trustSnapshot(session(p), p)
        assertEquals(AttestationState.NONE, s.state); assertEquals(TrustSendPolicy.ALLOW, s.verdict.sendPolicy)
        assertNotNull(s.none); assertEquals("not end-to-end encrypted", s.none!!.heroTitle)
        assertTrue(s.none.heroLead.startsWith("acme doesn't offer hardware attestation")); assertEquals("big-model", s.modelName)
        assertEquals(TrustChipMode.NONE, s.chip.mode)
    }

    /**
     * THE WAY OUT IS OFFERED TO THE PEOPLE WHO NEED IT.
     *
     * `offersEncryptedModels` was `provider.endpoint.contains("near.ai")` until 2026-09-21 — half of iOS's
     * `nearAIProvider`, with its `else providers.first { near.ai }` dropped — so the encrypted-model CTA was
     * hidden on every provider that is not near.ai, which is every provider a user could usefully switch AWAY
     * from. Found by running the build on a phone: the Fireworks screen explained that nothing could be
     * verified and then offered nothing.
     *
     * Broader than iOS deliberately: EncryptedModelCTA resolves to AddNearAIKey when no near.ai setup exists,
     * so unlike iOS's model picker there is never nothing to offer.
     */
    @Test fun everyUnattestableCloudProviderIsOfferedTheWayOut() {
        for (p in listOf(
            Provider(id = "f", name = "fireworks", endpoint = "https://api.fireworks.ai/inference/v1", model = "kimi"),
            Provider(id = "n", name = "nvidia", endpoint = "https://integrate.api.nvidia.com/v1", model = "nemotron"),
            Provider(id = "o", name = "openrouter", endpoint = "https://openrouter.ai/api/v1", model = "anthropic/claude"),
            Provider(id = "x", name = "acme", endpoint = "https://api.acme.example/v1", model = "acme/big"),
        )) {
            val none = trustSnapshot(session(p), p).none
            assertNotNull("${p.name} should be on a not-attestable screen", none)
            assertTrue("${p.name} is not offered the encrypted-model CTA — the screen it is for", none!!.offersEncryptedModels)
        }
    }

    /**
     * …and NOT on the two screens where it would be wrong. Offering to move the user off the most private option
     * teemoon has is the one place this button does harm, and it is why the CTA is scoped to the cloud screen
     * rather than to every `none` screen.
     */
    @Test fun theMostPrivateScreensAreNotOfferedAWayOut() {
        val home = Provider(id = "h", name = "home", endpoint = "http://192.168.1.20:11434/v1", model = "llama3", requiresAPIKey = false)
        assertFalse("self-hosted must not be offered a cloud provider", trustSnapshot(session(home), home).none!!.offersEncryptedModels)
    }

    @Test fun snapshot_selfHosted_namesTheMachineAndItsTransport() {
        val p = Provider(id = "h", name = "home", endpoint = "http://192.168.1.20:11434/v1", model = "llama3", requiresAPIKey = false)
        val s = trustSnapshot(session(p), p)
        assertEquals("on your own machine", s.none!!.heroTitle); assertEquals(TrustChipMode.SELF_HOSTED, s.chip.mode)
        assertTrue(s.none.rows.any { !it.positive && it.title == "not encrypted in transit" })
    }

    /** An attesting provider with no record yet is VERIFYING: the hero says so, the chip says so, and nothing claims a seal. */
    @Test fun snapshot_attestingProvider_beforeTheRecord_isVerifying() {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = trustSnapshot(session(p), p)
        assertEquals(AttestationState.VERIFYING, s.state); assertEquals("verifying…", s.verdict.heroTitle); assertEquals(TrustChipMode.VERIFYING, s.chip.mode)
        assertNull(s.none); assertEquals("", s.metaLine); assertEquals(TrustSendPolicy.ALLOW, s.verdict.sendPolicy)
    }

    /** A seeded record: the verdict, the chip and the gate all derive from the same session values, and the alert copy matches the policy. */
    @Test fun snapshot_seededRecord_isConsistentAcrossChipVerdictAndGate() {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = session(p); sess.attestation = preview
        val s = trustSnapshot(sess, p)
        assertNull(s.none); assertEquals(sess.attestationState, s.state); assertEquals(sess.sendPolicy, s.verdict.sendPolicy)
        assertEquals(sess.requiresE2EEConfirmation, s.isPaused)
        when (s.verdict.sendPolicy) {
            TrustSendPolicy.BLOCK -> { assertNull(s.bypassLabel); assertTrue(s.alertTitle.contains("blocked")) }
            TrustSendPolicy.CONFIRM -> assertNotNull(s.bypassLabel)
            TrustSendPolicy.ALLOW -> assertTrue(s.chip.tone == TrustTone.OK || s.chip.tone == TrustTone.ADVISORY)
        }
        assertTrue(s.verdict.everydayClaims.any { it.id == "sealed" }); assertTrue(s.summary.pillars.isNotEmpty())
    }
}
