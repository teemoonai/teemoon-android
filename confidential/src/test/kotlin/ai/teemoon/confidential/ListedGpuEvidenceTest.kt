// ports: teemoonTests/ListedGPUEvidenceTests.swift @ 36d53c97 (listed unreachable NVIDIA; worst-of ordering)
// ports: teemoonTests/ListedGPUEvidenceTests.swift @ 49608f08 (1.1.1 audit A10: the expert row keeps NVIDIA's reason; every other line is the A10 line)
// ports: teemoonTests/ListedGPUEvidenceTests.swift @ ceb05738 (1.1.1 audit A3). iOS replays NVIDIA's recorded answer through
//        NRASService; here the session's verifyNras seam is a stand-in that holds the one property under test, the nonce each
//        payload is judged against (the NRAS service's own nonce check is NrasServiceTest's, over the kit).
//
// Audit 1.1.1 A3: every enclave near.ai lists holds the sealed key, so each one's GPU evidence goes to NRAS against the
// list's nonce, through the session's own GPU check, and the record's GPU verdict is the worst of them.
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListedGpuEvidenceTest {
    private val modelNonce = "11".repeat(32)
    private val listNonce = "22".repeat(32)

    /** NVIDIA's verdict, reduced to what these tests hold: evidence passes when it echoes the nonce it was asked against. */
    private val nras: suspend (String, String?) -> NrasVerification = { payload, nonce ->
        val expected = if (payload.startsWith("model")) modelNonce else listNonce
        // an NVIDIA that cannot be reached for this payload: an outage, never a verdict
        if (payload.endsWith("-unreachable")) NrasVerification.Inconclusive("NVIDIA attestation service unreachable")
        else if (nonce == expected) NrasVerification.Verified else NrasVerification.Failed("nonce mismatch")
    }

    private fun enclave(payload: String?) = ModelCandidates.Enclave("i", "c", "a", null, null, ModelCandidates.Result.Clean, nvidiaPayload = payload)

    /** The model leg verified, then [listed] checked against [asked] (the nonce the list claims to have been asked with). */
    private fun gpuVerdict(listed: List<String?>, asked: String? = listNonce, modelPayload: Boolean = true) = runTest {
        val preview = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(this, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, verifyNras = nras), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        val record = preview.copy(modelIntelQuote = "04", nvidiaPayload = if (modelPayload) "model-evidence" else null, modelNonce = modelNonce,
            modelCandidates = ModelCandidates.Listed(listed.map(::enclave), asked))
        s.attestation = record
        s.verifyGpu(record)
        advanceUntilIdle()
        result = s.gpuAttestation
    }
    private var result: NrasVerification? = null

    @Test fun listedEvidenceThatEchoesTheListsNonceVerifies() {
        gpuVerdict(listOf("listed-a", "listed-b"))
        assertEquals(NrasVerification.Verified, result)
    }

    @Test fun aListedEnclavesRejectedEvidenceFailsTheRecord() {
        gpuVerdict(listOf("listed-a", "listed-b"), asked = "00".repeat(32))
        assertTrue("$result", result?.isHardFailure == true)
        // Audit 1.1.1 A10 (iOS 976543da): the rejection names the listed enclave, in the GPU row and in the reason line.
        assertEquals(NrasVerification.ListedFailed("nonce mismatch"), result)
        assertEquals("the verdict's reason stays the A10 line", "another enclave near.ai lists for this model failed verification.", result?.reason)
        val s = SendGateRuleTest().also { it.offline() }.baselineSession(
            AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean))))
        s.gpuAttestation = result
        assertEquals("another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
        s.gpuAttestation = NrasVerification.Failed("NVIDIA rejected the evidence")
        assertEquals("the read enclave's own line is unchanged", "nvidia gpu attestation failed verification.", s.e2eeDegradedReason)
    }

    @Test fun aListedEnclaveWithNoEvidenceCouldNotBeChecked() {
        gpuVerdict(listOf("listed-a", null))
        assertEquals(NrasVerification.ListedInconclusive(NrasVerification.EVIDENCE_ABSENT), result)
        assertEquals("never the read enclave's evidence-absent line", "teemoon couldn't finish checking the enclaves near.ai listed.", result?.reason)
    }

    @Test fun listedEvidenceDoesNotStandInForTheModelLegs() {
        gpuVerdict(listOf("listed-a"), modelPayload = false)
        assertEquals(NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT), result)
    }

    @Test fun theWorstVerdictWins() {
        val f = NrasVerification.Failed("b"); val i = NrasVerification.Inconclusive("a"); val v = NrasVerification.Verified
        assertEquals(f, NrasVerification.worst(listOf(v, i, f)))
        assertEquals(i, NrasVerification.worst(listOf(v, i)))
        assertEquals(v, NrasVerification.worst(listOf(v, v)))
        assertEquals(null, NrasVerification.worst(emptyList()))
        // A listed couldn't-check is soft: any failure beats it, and the read enclave's own couldn't-check wins the tie.
        val listedSoft = NrasVerification.ListedInconclusive("x")
        assertEquals(listedSoft, NrasVerification.worst(listOf(v, listedSoft)))
        assertEquals(NrasVerification.ListedFailed("y"), NrasVerification.worst(listOf(listedSoft, NrasVerification.ListedFailed("y"))))
        assertEquals(NrasVerification.Failed("b"), NrasVerification.worst(listOf(listedSoft, NrasVerification.Failed("b"))))
        assertEquals(NrasVerification.Inconclusive("a"), NrasVerification.worst(listOf(listedSoft, NrasVerification.Inconclusive("a"))))
        assertEquals(NrasVerification.Inconclusive("a"), NrasVerification.worst(listOf(NrasVerification.Inconclusive("a"), listedSoft)))
    }

    /**
     * Audit 1.1.1 A10 (iOS 49608f08, pm): a listed enclave's NVIDIA rejection was stored as the A10 line alone, so NVIDIA's own
     * reason for that enclave was lost. Only the expert GPU row adds it back; every other line stays the A10 line.
     */
    @Test fun theExpertGpuRowKeepsNvidiasReasonBesideAListedEnclavesLine() {
        val a10 = "another enclave near.ai lists for this model failed verification."
        gpuVerdict(listOf("listed-a"), asked = "00".repeat(32))
        val verdict = result!!
        assertTrue(verdict.isHardFailure)
        assertEquals("the verdict's reason stays the A10 line", a10, verdict.reason)

        val s = SendGateRuleTest().also { it.offline() }.baselineSession(
            AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean))))
        s.gpuAttestation = verdict
        assertEquals("the everyday reason line is exactly the A10 line", a10, s.e2eeDegradedReason)

        fun summary(n: NrasVerification) = AttestationSummary(
            attestation = AttestationRecord.preview.copy(nvidiaPayload = "{}"), state = AttestationState.DEGRADED, timedOut = false, provider = Provider.nearAI,
            lastRequestUsedE2EE = true, lastE2EEFailReason = null, verifiedResponseCount = 0, mismatchedResponseCount = 0,
            attestationFetchFailed = false, nrasVerification = n)
        val listed = summary(verdict)
        assertEquals(StepState.STUCK, listed.nrasState)
        assertTrue(listed.gpuRowDetail, listed.gpuRowDetail.contains(a10))
        assertTrue("expert keeps NVIDIA's reason: ${listed.gpuRowDetail}", listed.gpuRowDetail.contains("nonce mismatch"))
        assertEquals("outside the expert GPU row the line is the A10 line alone", a10, listed.nrasDetail)

        // The read enclave's own GPU failure is unchanged.
        val own = summary(NrasVerification.Failed("NVIDIA rejected the evidence"))
        assertEquals("NVIDIA rejected the evidence", own.gpuRowDetail)
        s.gpuAttestation = NrasVerification.Failed("NVIDIA rejected the evidence")
        assertEquals("nvidia gpu attestation failed verification.", s.e2eeDegradedReason)
    }

    /**
     * Audit 1.1.1 A10 (iOS 36d53c97, pm 2026-11-20): a listed enclave's unreachable NVIDIA check read exactly like the read
     * enclave's. It names the listed enclaves (A7's unfinished line), asks, and only the expert GPU row adds NVIDIA's reason.
     */
    @Test fun aListedEnclavesUnreachableNvidiaNamesTheListedEnclaves() {
        val unfinished = "teemoon couldn't finish checking the enclaves near.ai listed."
        val unreachable = "NVIDIA attestation service unreachable"
        gpuVerdict(listOf("listed-unreachable"))
        val verdict = result!!
        assertTrue("$verdict", !verdict.isHardFailure && !verdict.isVerified)
        assertEquals("$verdict", unfinished, verdict.reason)

        val s = SendGateRuleTest().also { it.offline() }.baselineSession(
            AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean))))
        s.gpuAttestation = verdict
        assertEquals(unfinished, s.e2eeDegradedReason)
        assertEquals("soft: asks, never blocks", TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertFalse(s.degradeIsHardFailure)

        val summary = AttestationSummary(
            attestation = AttestationRecord.preview.copy(nvidiaPayload = "{}"), state = AttestationState.DEGRADED, timedOut = false, provider = Provider.nearAI,
            lastRequestUsedE2EE = true, lastE2EEFailReason = null, verifiedResponseCount = 0, mismatchedResponseCount = 0,
            attestationFetchFailed = false, nrasVerification = verdict)
        assertEquals(StepState.UNCHECKED, summary.nrasState)
        assertEquals("outside the expert GPU row the line is the A7 line alone", unfinished, summary.nrasDetail)
        assertEquals(summary.gpuRowDetail, "$unfinished $unreachable", summary.gpuRowDetail)

        // The read enclave's own unreachable NVIDIA keeps its line, and wins a tie with a listed one's.
        s.gpuAttestation = NrasVerification.Inconclusive(unreachable)
        assertEquals("nvidia gpu attestation couldn't be completed (service unreachable).", s.e2eeDegradedReason)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        // the tie with the read enclave's own couldn't-check is pinned by worst() above
    }

    /**
     * 1.1.1 audit integ read, item 3 (iOS ConfidentialSession+Verdict.swift @ 01a337a4 e2eeDegradedReason): beside a listed enclave's
     * GPU verdict, a TLS check that failed inside teemoon is named FIRST (iOS reads both teemoon-fault lines before the GPU verdict's
     * own), and the confirmation key's reason half is that same text, so one answer covers exactly what the ask said.
     */
    @Test fun aReadLegFaultIsNamedBeforeAListedGpuVerdict() {
        val s = SendGateRuleTest().also { it.offline() }.baselineSession(
            AttestationRecord.preview.copy(modelCandidates = ModelCandidatesTest.listedFor(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean))))
        s.tlsAttestation = TlsAttestation.Inconclusive(NrasVerification.TEEMOON_FAULT)
        for (gpu in listOf(NrasVerification.ListedFailed("nonce mismatch"), NrasVerification.ListedInconclusive("x"), NrasVerification.Failed("rejected"),
            NrasVerification.Inconclusive("unreachable"), NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT))) {
            s.gpuAttestation = gpu
            assertEquals("$gpu", ConfidentialSession.VERIFIER_ERROR, s.e2eeDegradedReason)
            assertTrue("$gpu", s.confirmationKey(pending = false)!!.endsWith("|" + ConfidentialSession.VERIFIER_ERROR))
        }
        // a GPU fault of teemoon's own reads the same line; the listed verdict alone still reads its own
        s.gpuAttestation = NrasVerification.Inconclusive(NrasVerification.TEEMOON_FAULT); s.tlsAttestation = null
        assertEquals(ConfidentialSession.VERIFIER_ERROR, s.e2eeDegradedReason)
        s.gpuAttestation = NrasVerification.ListedFailed("nonce mismatch")
        assertEquals("another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
    }
}
