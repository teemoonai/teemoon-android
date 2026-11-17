// ports: teemoonTests/UnknownTierStateTests.swift @ 31a83c22 (everyVerdictVerifiedButTheTierUnknownReadsVerifying)
//
// With the near.ai tier still unknown, a session whose every verdict verified must not read OK: the chip would say
// "end-to-end encrypted" while the tier could still resolve to third-party (and the send go out plain). It reads
// VERIFYING until the tier is known attested (chip-verifying-until-tier-known, the maintainer 2026-11-07).
package ai.teemoon.confidential

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class UnknownTierStateTest {
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }); NearAIModelCatalog.tierListSeenOverride = false }
    @After fun clean() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.tierListSeenOverride = null }

    private fun verifiedSession(model: String): ConfidentialSession {
        val record = AttestationRecord.preview.copy(model = model)
        val p = Provider.nearAI.copy(id = record.providerID, model = model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt }))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")
        s.dcapVerification = RecordDcapVerification(gateway = ok, model = ok)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        s.nearScopeOverride = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked)
        return s
    }

    @Test fun everyVerdictVerifiedButTheTierUnknownReadsVerifying() {
        val model = "zai-org/S1-UNKNOWN-TIER-PROBE"
        val s = verifiedSession(model)
        assertEquals("precondition: the tier is unknown", NearAIModelCatalog.Confidentiality.UNKNOWN, NearAIModelCatalog.confidentiality(model))
        // S30 (iOS 38647a07): every verdict answered, yet the unknown tier holds the send like a verdict still running
        assertEquals("an unknown tier does not hold the send", ConfidentialSession.PendingKind.HARDWARE, s.pendingKind)
        assertEquals(AttestationState.VERIFYING, s.attestationState)
        // known own-fleet: verified
        NearAIModelCatalog.recordTiers(mapOf(model to NearAIModelCatalog.Confidentiality.TEE_OWN))
        assertEquals(AttestationState.OK, s.attestationState)
    }
}
