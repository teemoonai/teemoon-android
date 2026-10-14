// ports: teemoonTests/AttestationEvidenceTests.swift @ v1.0.1
package ai.teemoon.confidential

import ai.teemoon.confidential.AttestationFixtures.ref
import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw-evidence strings the advanced section shows. */
class AttestationEvidenceTest {

    private fun summary(
        dcap: RecordDcapVerification? = null,
        nras: NrasVerification? = null,
        imageProvenance: ManifestProvenance? = null,
        verified: Int = 0, mismatched: Int = 0, gatewayTrust: Int = 0,
        attestation: AttestationRecord? = AttestationRecord.preview,
    ) = AttestationSummary(
        attestation = attestation, state = AttestationState.OK, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = null, lastE2EEFailReason = null,
        verifiedResponseCount = verified, mismatchedResponseCount = mismatched,
        gatewayTrustResponseCount = gatewayTrust,
        attestationFetchFailed = false, imageProvenance = imageProvenance,
        dcapVerification = dcap, nrasVerification = nras,
    )

    private fun verified(tcb: DcapTcbStatus) = DcapVerification.Verified(tcb, "01", "")

    @Test fun dcapEvidenceReflectsTcbStatus() {
        assertEquals("verified · TCB up to date", summary(dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.UP_TO_DATE))).dcapEvidence)
        assertTrue(summary(dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.OUT_OF_DATE))).dcapEvidence!!.contains("out of date"))
        // The specific reason + which quote is now surfaced, not a generic string.
        val evidence = summary(dcap = RecordDcapVerification(gateway = DcapVerification.Failed("collateral: HTTP 429 from api.trustedservices.intel.com (tcb)"))).dcapEvidence
        assertTrue(evidence!!.contains("gateway quote"))
        assertTrue(evidence.contains("HTTP 429"))
        assertNull(summary(dcap = null).dcapEvidence)
    }

    @Test fun dcapFailureReasonTagsWhichQuote() {
        val rec = RecordDcapVerification(
            gateway = verified(DcapTcbStatus.OUT_OF_DATE),
            model = DcapVerification.Failed("collateral: HTTP 429 from api.trustedservices.intel.com (tcb)"))
        assertEquals("model quote — collateral: HTTP 429 from api.trustedservices.intel.com (tcb)", rec.failureReason)
        assertEquals("gpu quote — TCB revoked by Intel", RecordDcapVerification(gpu = verified(DcapTcbStatus.REVOKED)).failureReason)
        assertNull(RecordDcapVerification(gateway = verified(DcapTcbStatus.UP_TO_DATE)).failureReason)
    }

    @Test fun nrasEvidence() {
        assertTrue(summary(nras = NrasVerification.Verified).nrasEvidence!!.contains("PASS"))
        assertTrue(summary(nras = NrasVerification.Failed("unreachable")).nrasEvidence!!.contains("unreachable"))
        assertNull(summary(nras = null).nrasEvidence)
    }

    @Test fun provenanceEvidenceNamesTheOrg() {
        val ref = ref("nearaidev/cloud-api")
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(verified = listOf(ref), thirdParty = listOf(ref)))
        assertTrue(s.provenanceEvidence!!.contains("github.com/nearai"))
        assertTrue(s.provenanceEvidence!!.contains("sidecar"))
    }

    @Test fun responseSigEvidenceDescribesEcrecover() {
        assertTrue(summary(verified = 3).responseSigEvidence!!.contains("ecrecover"))
        assertTrue(summary(mismatched = 1).responseSigEvidence!!.contains("mismatch"))
        assertTrue(summary(gatewayTrust = 2).responseSigEvidence!!.contains("gateway trust"))
        assertNull(summary().responseSigEvidence)  // no responses yet
    }

    @Test fun cipherSuiteMatchesNearAIProtocol() {
        // near.ai's documented v2 scheme — X25519 ECDH → HKDF-SHA256 → XChaCha20-Poly1305.
        val s = AttestationSummary.CIPHER_SUITE_LABEL
        assertTrue(s.contains("X25519 ECDH"))
        assertTrue(s.contains("HKDF-SHA256"))
        assertTrue(s.contains("XChaCha20-Poly1305"))
    }

    @Test fun nonceEvidenceReflectsFreshness() {
        // preview record has no recorded nonces → null (not checkable)
        assertNull(summary().nonceEvidence)
    }

    // MARK: Android-only — the sourced-repo form and gateway-trust indeterminacy

    @Test fun provenanceEvidenceNamesTheSourceRepos() {
        val a = ref("nearaidev/cloud-api").copy(sourceRepo = "https://github.com/nearai/private-ml-sdk")
        val b = ref("nearaidev/mesh", 'b').copy(sourceRepo = "https://github.com/nearai/dstack")
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(listOf(a, b), emptyList()))
        assertEquals("2 image(s) → nearai/dstack, nearai/private-ml-sdk", s.provenanceEvidence)
    }

    @Test fun gatewayTrustAloneIsIndeterminateNotGreen() {
        val s = summary(gatewayTrust = 2)
        assertEquals(StepState.PENDING, s.responseSigState)
        assertNull(s.responseSigValid)
        assertEquals("2 via gateway trust (not individually verified)", s.responseSigDetail)
        assertEquals("1 mismatch of 4", summary(verified = 1, mismatched = 1, gatewayTrust = 2).responseSigDetail)
        assertEquals("no signed responses yet", s.copy(gatewayTrustResponseCount = 0).responseSigDetail)
    }
}
