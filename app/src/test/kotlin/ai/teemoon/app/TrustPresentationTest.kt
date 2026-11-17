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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class TrustPresentationTest {
    /** Models near.ai set up with its list loaded: see [ai.teemoon.providers.NearAIModelCatalog.tierListSeen] (iOS ea19232). */
    init { ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf("nearai/tier-list-seen-by-tests" to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN)) }


    // No saved tier list (pm 2026-10-31): the gate cases run where a fresh install does (UnseenTierSendGateTest).
    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.tierListSeenOverride = false
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }
    @After fun online() { NearAIModelCatalog.tierListSeenOverride = null }

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
    /** The record's model with its tier known own-fleet: an unknown tier reads verifying (chip-verifying-until-tier-known). */
    private fun tierKnown() = NearAIModelCatalog.recordTiers(mapOf((AttestationRecord.preview.model ?: Provider.nearAI.model) to NearAIModelCatalog.Confidentiality.TEE_OWN))

    @Test fun snapshot_seededRecord_isConsistentAcrossChipVerdictAndGate() {
        tierKnown()
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

    /**
     * T6 (pm 2026-11-06), a test only: a model quote whose signature failed on the phone. The key still sits in its
     * report_data, so e2eeKeyBound is true; the session degrades on !isVerified. Does the "sealed" step (everyday)
     * and the "key bound in the model's TDX quote" node (expert) still draw a pass over it? They must not.
     */
    @Test fun aQuoteThatFailedItsSignatureSealsNothing() {
        val preview = AttestationRecord.preview
        val mqv = preview.modelQuoteVerification!!
        val forged = preview.copy(modelQuoteVerification = ai.teemoon.confidential.TdxVerificationResult(mqv.measurements, signatureValid = false, certChainValid = mqv.certChainValid, certChainError = null))
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = session(p); sess.attestation = forged
        val s = trustSnapshot(sess, p)
        assertEquals("precondition: the session degrades", AttestationState.DEGRADED, s.state)
        assertNotEquals(ai.teemoon.confidential.TrustClaim.Status.OK, s.verdict.everydayClaims.first { it.id == "sealed" }.status)
        assertNotEquals(ChainNode.Status.OK, s.expertNodes.first { it.id == "bound" }.status)
        // trust-quote-failed (the maintainer 2026-11-06): the existing failure claim, and the expert node says what happened
        val sealed = s.verdict.everydayClaims.first { it.id == "sealed" }
        assertEquals(ai.teemoon.confidential.TrustClaim.Status.FAIL, sealed.status); assertEquals("couldn't seal this chat", sealed.title)
        val bound = s.expertNodes.first { it.id == "bound" }
        assertEquals("key in report_data, but the quote didn't verify", bound.title)
        assertEquals("the public key matches report_data, but the quote's signature or certificate chain failed, so nothing proves this key lives inside the enclave.", bound.detail)
    }

    /**
     * trust-pending-state Q4 (the maintainer 2026-11-06; iOS hardwareChecksPending): while DCAP or NVIDIA hasn't answered, the
     * seal is not proven — the everyday "sealed" step and the expert target and bound nodes read pending, whatever the
     * key binding reads. Answered, they pass.
     */
    @Test fun theSealWaitsForIntelAndNvidia() {
        val preview = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = session(p); sess.attestation = preview
        val pending = trustSnapshot(sess, p)
        assertEquals(ai.teemoon.confidential.TrustClaim.Status.PENDING, pending.verdict.everydayClaims.first { it.id == "sealed" }.status)
        assertEquals("sealed so only ${pending.modelName} can open it", pending.verdict.everydayClaims.first { it.id == "sealed" }.title)
        assertEquals(ChainNode.Status.PENDING, pending.expertNodes.first { it.id == "target" }.status)
        assertEquals(ChainNode.Status.PENDING, pending.expertNodes.first { it.id == "bound" }.status)
        sess.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "01", ""))
        sess.gpuAttestation = ai.teemoon.confidential.NrasVerification.Verified
        val answered = trustSnapshot(sess, p)
        assertEquals(ai.teemoon.confidential.TrustClaim.Status.OK, answered.verdict.everydayClaims.first { it.id == "sealed" }.status)
        assertEquals(ChainNode.Status.OK, answered.expertNodes.first { it.id == "target" }.status)
        assertEquals(ChainNode.Status.OK, answered.expertNodes.first { it.id == "bound" }.status)
    }

    /** trust-pending-state Q2 (the maintainer 2026-11-06): the transport row shows while TLS is being checked, not only once it answered. */
    @Test fun theTransportRowShowsWhileTlsIsChecked() {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = ConfidentialSession(scope, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://x.completions.near.ai/v1" }, verifyTls = { kotlinx.coroutines.awaitCancellation() }))
        sess.attestation = preview
        sess.verifyTls(p)
        val row = trustSnapshot(sess, p).expert.transport
        assertEquals(ExpertSections.TRANSPORT_LABEL, row?.label)
        assertEquals(ai.teemoon.confidential.StepState.PENDING, row?.state)
        assertEquals("checking the model node's certificate…", row?.detail)
    }

    /**
     * trust-pending-state (pm ruling on the A6 screen check, 2026-11-06): no green while a check that bears on it is
     * pending. An enclave's seal is its quote — and Intel's DCAP verdict on that quote may still be out, so the seal waits
     * in the pending style until DCAP settles, even when provenance has already answered.
     */
    @Test fun anEnclaveSealWaitsForIntel() {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = ConfidentialSession(scope, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, verifyDcap = { kotlinx.coroutines.awaitCancellation() }))
        sess.attestation = preview
        sess.imageProvenance = ai.teemoon.confidential.ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        sess.verifyDcap(preview)
        val pending = trustSnapshot(sess, p).expert.enclaveGroups
        assertTrue("no enclave groups to check", pending.isNotEmpty())
        assertTrue("a seal is green while DCAP is still out", pending.filter { it.quoteVerified == true }.all { it.quotePending })
        sess.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "01", ""))
        assertTrue("the seal still waits after DCAP answered", trustSnapshot(sess, p).expert.enclaveGroups.none { it.quotePending })
    }

    /** trust-unchecked-glyph (pm 2026-11-06): a TLS check that couldn't complete is orange, a mismatch stays red. */
    @Test fun anUnreachableTlsCheckIsCouldntCheckAndAMismatchIsRed() {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = ConfidentialSession(scope, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true))
        sess.attestation = preview
        sess.tlsAttestation = ai.teemoon.confidential.TlsAttestation.Inconclusive("no server certificate captured")
        val unreachable = trustSnapshot(sess, p).expert.transport
        assertEquals(ai.teemoon.confidential.StepState.UNCHECKED, unreachable?.state)
        assertEquals("probe of the model node: no server certificate captured — could not complete", unreachable?.detail)
        sess.tlsAttestation = ai.teemoon.confidential.TlsAttestation.Failed("fingerprint mismatch")
        assertEquals(ai.teemoon.confidential.StepState.STUCK, trustSnapshot(sess, p).expert.transport?.state)
        // trust-verifier-error: teemoon's own fault reads the approved line alone, nothing added (A6 screen check)
        sess.tlsAttestation = ai.teemoon.confidential.TlsAttestation.Inconclusive(ai.teemoon.confidential.NrasVerification.TEEMOON_FAULT)
        assertEquals(ai.teemoon.confidential.NrasVerification.TEEMOON_FAULT, trustSnapshot(sess, p).expert.transport?.detail)
    }

    /**
     * pm audit-index sweep #4f: with no verdict recorded, the block still says "source reviewed … · this exact build"
     * under the review's link. Nothing in hand says what the review found; the line claims more than is known.
     */
    @Test fun aReviewWithNoVerdictClaimsNoReview() {
        val (shown, note) = FableAuditCopy.withoutVerdict("source reviewed for egress · this exact build")
        assertTrue("no 'reviewed' claim without a verdict: $shown", shown?.contains("reviewed") != true)
        assertEquals(FableAuditCopy.VERDICT_NOT_RECORDED, note)
    }

    /**
     * trust-audit-tag-pages (the maintainer 2026-11-06; the #4c pin flipped): a tag page reviews whatever the tag named then, not
     * the bytes running — it is not coverage for "reviewed … in this exact setup", and the row shows no link to it.
     */
    @Test fun aTagPageIsNotCoverage() {
        val tagged = ai.teemoon.confidential.ImageEntry(id = "proxy", name = "nearaidev/vllm-proxy-rs", kind = ai.teemoon.confidential.ImageEntry.Kind.TAG_PINNED,
            tag = "v1").apply { plaintextRole = ai.teemoon.confidential.PlaintextExposure.Role.E2EE_TERMINATOR; auditURL = "https://example.org/images/nearaidev/vllm-proxy-rs/tag-v1.md" }
        assertFalse(coversForEveryday(tagged))
        assertFalse(coversForEveryday(tagged.copy().apply { auditURL = null }))
    }

    /**
     * ios/pm 2026-11-06 (near-send-gate-rule): wherever the seal holds — the key bound in a verified quote, no hard
     * break — a soft state's question must not say encryption is unavailable or offer "send unencrypted": the message
     * would go out sealed. Today an NVIDIA outage (Inconclusive) reaches exactly that alert. Held until iOS lands its
     * "still checking near.ai's hardware" alert and Android matches it.
     */
    @Test fun aSoftStateWhereTheSealHoldsNeverOffersUnencrypted() {
        val preview = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val sess = session(p); sess.attestation = preview; sess.lastRequestUsedE2EE = true
        sess.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "01", ""))
        sess.gpuAttestation = ai.teemoon.confidential.NrasVerification.Inconclusive("service unreachable")
        val s = trustSnapshot(sess, p)
        assertEquals("precondition: a soft state", TrustSendPolicy.CONFIRM, s.verdict.sendPolicy)
        assertEquals(true, preview.e2eeKeyBoundToModelTEE)
        assertNotEquals("end-to-end encryption unavailable", s.alertTitle)
        assertFalse(s.sendChoices.contains(E2EESendChoice.SendAnyway(stillEncrypted = false)))
        // near-send-gate-words (the maintainer 2026-11-06), from iOS 1cef8d95
        assertEquals("still checking near.ai's hardware", s.alertTitle)
        assertEquals(listOf("send anyway", "wait"), s.sendChoices.map { it.label })
    }

    /**
     * trust-caption-sealed (the maintainer 2026-11-06): a soft state in which the send stays sealed reads "encrypted · hardware
     * not confirmed" under a closed (orange) lock; a hard break and a record with no key keep their own lines.
     */
    @Test fun aSealedSoftStateSaysEncryptedHardwareNotConfirmed() {
        val sealed = SendGateFixtures.snapshot(SendGateFixtures.nvidiaOutage)
        assertEquals("encrypted · hardware not confirmed", sealed.chip.label)
        assertTrue("closed lock", sealed.chip.sealed); assertEquals(TrustTone.ADVISORY, sealed.chip.tone)
        val hard = SendGateFixtures.snapshot { it.gpuAttestation = ai.teemoon.confidential.NrasVerification.Failed("rejected") }
        assertNotEquals("encrypted · hardware not confirmed", hard.chip.label); assertFalse(hard.chip.sealed)
        val noKey = SendGateFixtures.snapshot { it.attestation = it.attestation!!.copy(modelEd25519PubKey = null) }
        assertEquals("not end-to-end encrypted", noKey.chip.label); assertFalse(noKey.chip.sealed)
    }

    /**
     * trust-provenance-unchecked (the maintainer 2026-11-06): no image was checked (GitHub didn't answer the first pass). The chip
     * keeps "end-to-end encrypted" and nothing is asked at send; the trust screen says so — header A, step B.
     */
    @Test fun aProvenancePassThatCouldNotRunIsSaidOnTheTrustScreen() {
        tierKnown()
        val t = SendGateFixtures.snapshot { it.imageProvenance = null; it.provenanceUnchecked = true }
        assertEquals("end-to-end encrypted", t.chip.label)
        assertEquals(ai.teemoon.confidential.TrustSendPolicy.ALLOW, t.verdict.sendPolicy)
        assertEquals("sealed to the model's hardware on the way in. teemoon couldn't check the running code against public source just now.", t.verdict.heroSubtitle)
        // the title's "— only it can read this" isn't backed while no image was checked: the same words, the tail dropped (pm)
        assertEquals("encrypted to ${t.modelName}", t.verdict.heroTitle)
        val step = "couldn't check the running code right now — github didn't answer. teemoon will try again."
        assertEquals(step, t.verdict.everydayClaims.first { it.id == "identity" }.title)
        assertEquals(ai.teemoon.confidential.StepState.UNCHECKED, t.summary.provenanceState)
        assertEquals(step, t.summary.provenanceDetail)
    }
}
