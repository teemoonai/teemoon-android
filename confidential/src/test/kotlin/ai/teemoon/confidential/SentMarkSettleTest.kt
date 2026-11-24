// ports: teemoonTests/SentBeforeConfirmedWiringTests.swift @ 0e339959 (anUnknownTierResolvingToThirdPartyIsNotAPass; F5)
//        teemoon/Chat/ChatViewModel.swift @ 0e339959 (settleSentBeforeConfirmed: the outcome is degradeIsHardFailure, else
//        allow = pass, else unsettled; only while the provider can attest) — Android settles in ConfidentialSession.settleDue
//
// The trust side of reply-machine.json's sentBeforeConfirmedResolution, as a send marked "pending|<record>" sees it settle.
package ai.teemoon.confidential

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class SentMarkSettleTest {
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }
    @After fun clean() { NearAIModelCatalog.resetTierCache() }

    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")
    private val boundScope = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked)
    private val record = AttestationRecord.preview
    private val near = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)

    /** A fully verified near.ai record but for TLS, still checking; a sealed send was marked pending on it. */
    private fun markedWhileChecking(scope: CoroutineScope, tls: CompletableDeferred<TlsAttestation>): Pair<ConfidentialSession, MutableList<String>> {
        val s = ConfidentialSession(scope, { near }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://model.example" }, verifyTls = { tls.await() }, now = { record.fetchedAt }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        s.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate)
        s.gpuAttestation = NrasVerification.Verified
        s.nearScopeOverride = boundScope
        s.verifyTls(near)
        val outcomes = mutableListOf<String>()
        s.onRecordSettled = { _, o -> outcomes += o }
        assertNotNull("precondition: marked pending", s.sentBeforeConfirmedMark(sealed = true))
        return s to outcomes
    }

    @Test fun aCleanSettleIsAPass() = runTest {
        val tls = CompletableDeferred<TlsAttestation>()
        val (_, outcomes) = markedWhileChecking(this, tls)
        tls.complete(TlsAttestation.Verified); advanceUntilIdle()
        assertEquals(listOf("pass"), outcomes)
    }

    /**
     * Trust scope breaks hard (an actions-hash mismatch) while DCAP, NVIDIA and TLS all pass: the send was not
     * confirmed, so its mark is a hard fail. Android read only the three verdicts and called it a pass — the mark
     * cleared and the reply counted as end-to-end encrypted.
     */
    @Test fun aHardTrustScopeIsAHardFailNotAPass() = runTest {
        val tls = CompletableDeferred<TlsAttestation>()
        val (s, outcomes) = markedWhileChecking(this, tls)
        s.nearScopeOverride = boundScope.copy(deployLogBinding = DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH))
        tls.complete(TlsAttestation.Verified); advanceUntilIdle()
        assertEquals(listOf("hardFail"), outcomes)
    }

    /**
     * F5 (reply-machine.json tier-change-to-third-party-keeps-pending): near.ai's tier list turns the model third-party
     * while a check runs. Losing the attestation capability is not a verdict: the mark stays pending, never a pass, even
     * when the check later passes.
     */
    @Test fun anUnknownTierResolvingToThirdPartyIsNotAPass() = runTest {
        val tls = CompletableDeferred<TlsAttestation>()
        val (_, outcomes) = markedWhileChecking(this, tls)
        NearAIModelCatalog.recordTiers(mapOf(near.model to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
        assertFalse("precondition: the capability is gone", ProviderCapability.ATTESTATION in near.capabilities)
        tls.complete(TlsAttestation.Verified); advanceUntilIdle()
        assertFalse("a lost capability settled as a pass: $outcomes", "pass" in outcomes)
    }

    /**
     * Android-only (main-thread profile on the Pixel 10 Pro, 2026-11-07): the chip, the alert and the composer read
     * canSeal / softButSealed on every recomposition, and each built a whole TeeContext — decrypting the API key from
     * the Android Keystore on the main thread (1.5 s across one chat open, verification and a send). The peer comes
     * from the record alone, so these never ask for the key; only a request does.
     */
    @Test fun theSealChecksNeverReadTheKey() {
        var asked = 0
        val s = ConfidentialSession(CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { near }, credentialFor = { asked++; "sk" },
            services = ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record
        val sealed = s.canSeal; s.softButSealed
        assertEquals("the seal checks read the API key", 0, asked)
        assertEquals("a request still carries it", "sk", s.currentTeeContext()?.apiKey)
        assertEquals(record.modelEd25519PubKey != null, sealed)
    }
}

