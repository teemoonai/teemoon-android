// ports: teemoonTests/AttestationPillarTests.swift @ v1.0.1
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-pillar grouping the redesigned attestation sheet renders: every
 * check lands under genuine-hardware / published-code / private-to-you, and
 * each pillar's roll-up state reflects its checks.
 */
class AttestationPillarTest {

    private fun summary(
        attestation: AttestationRecord?,
        state: AttestationState = AttestationState.OK,
        lastRequestUsedE2EE: Boolean? = null,
        verifiedResponseCount: Int = 0,
        imageProvenance: ManifestProvenance? = null,
        dcap: RecordDcapVerification? = null,
        nras: NrasVerification? = null,
    ) = AttestationSummary(
        attestation = attestation, state = state, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = lastRequestUsedE2EE, lastE2EEFailReason = null,
        verifiedResponseCount = verifiedResponseCount, mismatchedResponseCount = 0,
        attestationFetchFailed = false, imageProvenance = imageProvenance,
        dcapVerification = dcap, nrasVerification = nras,
    )

    private fun pillar(s: AttestationSummary, id: String) = s.pillars.firstOrNull { it.id == id }
    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", "")
    private val allVerified = ManifestProvenance.AllVerified(emptyList(), emptyList())

    @Test fun threePillarsInTrustOrder() {
        val s = summary(AttestationRecord.preview, verifiedResponseCount = 3)
        assertEquals(listOf("hardware", "code", "private"), s.pillars.map { it.id })
        assertTrue(s.pillars.all { it.checks.isNotEmpty() })
    }

    @Test fun checksLandUnderTheRightPillar() {
        val s = summary(AttestationRecord.preview, verifiedResponseCount = 2, imageProvenance = allVerified,
            dcap = RecordDcapVerification(gateway = upToDate))
        val hardware = pillar(s, "hardware")!!.checks.map { it.label }.toSet()
        assertTrue("Intel DCAP verification" in hardware)
        assertTrue("TDX quote signature" in hardware)
        assertTrue("Intel certificate chain" in hardware)
        val code = pillar(s, "code")!!.checks.map { it.label }.toSet()
        assertTrue("Running code matches manifest" in code)
        assertTrue("Images built from near.ai source" in code)
        val priv = pillar(s, "private")!!.checks.map { it.label }.toSet()
        assertTrue("End-to-end encryption" in priv)
        assertTrue("Signing key bound to hardware" in priv)
        assertTrue("Response signatures" in priv)
    }

    @Test fun allVerified_pillarsAreVerified() {
        val s = summary(AttestationRecord.preview, verifiedResponseCount = 2, imageProvenance = allVerified,
            dcap = RecordDcapVerification(gateway = upToDate))
        assertEquals(PillarState.VERIFIED, pillar(s, "hardware")?.state)
        assertEquals(PillarState.VERIFIED, pillar(s, "code")?.state)
        assertEquals(PillarState.VERIFIED, pillar(s, "private")?.state)
        assertEquals("encrypted", pillar(s, "private")?.chipLabel)
    }

    @Test fun e2eeDown_onlyPrivacyPillarNeedsAttention() {
        // E2EE failed on the last request, but hardware + code are fine — the
        // privacy failure must not drag the other pillars down.
        val s = summary(AttestationRecord.preview, state = AttestationState.DEGRADED, lastRequestUsedE2EE = false, imageProvenance = allVerified)
        assertEquals(PillarState.ATTENTION, pillar(s, "private")?.state)
        assertEquals(PillarState.VERIFIED, pillar(s, "hardware")?.state)
        assertEquals(PillarState.VERIFIED, pillar(s, "code")?.state)
    }

    @Test fun noManifest_codePillarIsAbsent() {
        // previewDegraded carries no compose manifest → nothing to vouch for.
        val s = summary(AttestationRecord.previewDegraded, state = AttestationState.DEGRADED)
        assertNull(pillar(s, "code"))
    }

    @Test fun dcapHardFailure_marksHardwareAttention() {
        val s = summary(AttestationRecord.preview, dcap = RecordDcapVerification(gateway = DcapVerification.Failed("PCCS collateral unavailable")))
        assertEquals(PillarState.ATTENTION, pillar(s, "hardware")?.state)
        assertEquals("needs attention", pillar(s, "hardware")?.chipLabel)
    }

    @Test fun verifying_pillarsAreChecking() {
        val s = summary(null, state = AttestationState.VERIFYING)
        // With no record yet, verifying pillars still surface with pending rows.
        assertTrue(s.pillars.isNotEmpty())
        for (p in s.pillars) assertEquals(PillarState.CHECKING, p.state)
    }

    @Test fun hardwareLineNamesGPUWhenPresent() {
        val s = summary(AttestationRecord.preview)  // preview has gpuArch HOPPER
        assertTrue(pillar(s, "hardware")?.summaryLine?.contains("NVIDIA") == true)
    }

    // MARK: Android-only — resolveState and the GPU / NRAS rows

    @Test fun resolveStateFollowsTheFrontier() {
        val verifying = summary(null, state = AttestationState.VERIFYING)
        assertEquals(StepState.LIVE, verifying.resolveState(null, ready = false, isLiveGroup = true, isFirst = true))
        assertEquals(StepState.PENDING, verifying.resolveState(null, ready = false, isLiveGroup = true, isFirst = false))
        assertEquals(StepState.STUCK, verifying.copy(timedOut = true).resolveState(null, ready = false))
        val ok = summary(AttestationRecord.preview)
        assertEquals(StepState.PENDING, ok.resolveState(null, ready = true))
        assertEquals(StepState.DONE, ok.resolveState(true, ready = true))
        assertEquals(StepState.STUCK, ok.resolveState(false, ready = true))
    }

    @Test fun nrasRowAppearsOnlyWithGpuEvidence() {
        assertNull(summary(AttestationRecord.preview, nras = NrasVerification.Verified).nrasState)
        val withEvidence = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        assertEquals(StepState.DONE, summary(withEvidence, nras = NrasVerification.Verified).nrasState)
        assertEquals(StepState.STUCK, summary(withEvidence, nras = NrasVerification.Inconclusive("unreachable")).nrasState)
        assertEquals("unreachable", summary(withEvidence, nras = NrasVerification.Inconclusive("unreachable")).nrasDetail)
        assertEquals(StepState.PENDING, summary(withEvidence).nrasState)
        assertEquals(StepState.LIVE, summary(withEvidence, state = AttestationState.VERIFYING).nrasState)
    }

    @Test fun gpuRowIsSupplementaryAndNeverCounted() {
        val s = summary(AttestationRecord.preview)
        assertTrue(s.showGPURow)
        assertNull(s.gpuTdxValid)
        assertEquals("GPU node unreachable — verified via gateway", s.gpuTdxDetail)
        // Unreachable GPU → no row after verification, and nothing in the banner count.
        assertTrue(pillar(s, "hardware")!!.checks.none { it.label == "GPU secure enclave" })
        val withGpu = AttestationRecord.preview.copy(gpuQuoteVerification = AttestationFixtures.verification(ByteArray(64), signatureValid = false))
        val bad = summary(withGpu)
        assertEquals(false, bad.gpuTdxValid)
        assertEquals("TDX signature verification failed", bad.gpuTdxDetail)
        assertEquals(s.checksTotal, bad.checksTotal)
    }
}
