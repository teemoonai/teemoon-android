// The trust sheet's two one-line pieces at the phone's largest font size (200%) on a Pixel 10 Pro's 412 dp, after iOS
// 8c524172: at AX5 the seam caption and the self-verify script card's header were wider than the screen and pushed the
// whole column off its left edge (pm 2026-11-01).
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TrustLargestTextSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 1400, density = com.android.resources.Density.MEDIUM, fontScale = 2f))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    /** T1 (5d52964) at 200%: the record in, DCAP, NRAS and TLS asked and not answered (TrustScreenSnapshotTest.pendingVerdicts). */
    private fun pending(): Pair<ConfidentialSession, Provider> {
        val preview = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://x.completions.near.ai/v1" }, verifyDcap = { kotlinx.coroutines.awaitCancellation() },
            verifyNras = { _, _ -> kotlinx.coroutines.awaitCancellation() }, verifyTls = { kotlinx.coroutines.awaitCancellation() },
            // provenance answered offline, one image (a pass over none is not a pass): only the three hard verdicts are pending, and nothing reaches the network
            verifyManifests = { ai.teemoon.confidential.ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList()) }, fetchModelLayerManifest = null), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.adopt(preview, p)
        return s to p
    }

    @Test fun trustPending_everyday_largestText() {
        val (s, p) = pending()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustPending_expert_largestText() {
        val (s, p) = pending()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    /** The expert rung's hardware card (and the transport card, hidden while TLS is pending — trust-pending-state). */
    @Test fun trustPending_hardware_largestText() {
        val (s, p) = pending()
        val x = trustSnapshot(s, p).expert
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            EvidenceCard("genuine hardware", x.hardwareSubtitle) { x.hardware.forEach { EvidenceRowView(it) } }
            x.transport?.let { EvidenceCard("sealed transport", ExpertSections.TRANSPORT_SUBTITLE) { EvidenceRowView(it) } }
        } } } }
    }

    @Test fun trustPending_chip_largestText() {
        val (s, p) = pending()
        val vm = ChatViewModel(provider = { p })
        paparazzi.snapshot { TeemoonTheme { Surface { ChatScreen(focusOnEmpty = false, vm = vm, title = p.name, trust = trustSnapshot(s, p), hasProvider = true) } } }
    }

    /** trust-quote-failed (T6) at 200%: the expert rung's chain names what happened. */
    @Test fun trustQuoteFailed_expert_largestText() {
        val base = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}")
        val mqv = base.modelQuoteVerification!!
        val record = base.copy(modelQuoteVerification = ai.teemoon.confidential.TdxVerificationResult(mqv.measurements, signatureValid = false, certChainValid = mqv.certChainValid, certChainError = null))
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "01", ""))
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    @Test fun seamAndScriptCard_largestText() {
        val preview = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now())
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = preview; s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.NotPerformed
        val snap = trustSnapshot(s, p)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            TrustSeamDivider()
            ReverifySection(snap, onCopyScript = {})
        } } } }
    }
}
