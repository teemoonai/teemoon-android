// near-send-gate-rule (the maintainer 2026-11-06): the composer's question over a fully verified record with one outage — NVIDIA
// unreachable, or Intel's collateral unreachable — at 100% (320 dp) and 200% (412 dp). The alert is the trust snapshot's
// own title, message and choices, as ChatScreen draws it.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.confidential.DcapVerifier
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.RecordDcapVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal object SendGateFixtures {
    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", "")
    /** The kit's baseline (fully verified), then [change]. */
    fun snapshot(change: (ConfidentialSession) -> Unit): TrustSnapshot {
        val record = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.imageProvenance = ai.teemoon.confidential.ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        s.dcapVerification = RecordDcapVerification(gateway = upToDate, model = upToDate)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        change(s)
        return trustSnapshot(s, p)
    }
    val nvidiaOutage: (ConfidentialSession) -> Unit = { it.gpuAttestation = NrasVerification.Inconclusive("NRAS unreachable") }
    /** Intel PCS unreachable, as DcapVerifier reports a collateral fetch that failed. */
    val intelOutage: (ConfidentialSession) -> Unit = { it.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", ""),
        model = DcapVerifier.collateralUnavailable(java.io.IOException("pcs unreachable"))) }
}

@androidx.compose.runtime.Composable
private fun Alert(t: TrustSnapshot) =
    TeemoonTheme { Surface { IosAlert(t.alertTitle, t.alertMessage, t.sendChoices.map { it.label to {} }, onDismiss = {},
        destructive = t.sendChoices.filter { it.isDestructive }.map { it.label }.toSet()) } }

class SendGateAlertSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 800, density = com.android.resources.Density.MEDIUM))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    @Test fun sendGate_nvidiaOutage() = run { val t = SendGateFixtures.snapshot(SendGateFixtures.nvidiaOutage); paparazzi.snapshot { Alert(t) } }
    @Test fun sendGate_intelOutage() = run { val t = SendGateFixtures.snapshot(SendGateFixtures.intelOutage); paparazzi.snapshot { Alert(t) } }

    /** trust-caption-sealed: the chat title over an NVIDIA outage — "encrypted · hardware not confirmed", the closed lock in orange. */
    @Test fun sendGate_nvidiaOutage_chatCaption() = run {
        val t = SendGateFixtures.snapshot(SendGateFixtures.nvidiaOutage)
        paparazzi.snapshot { TeemoonTheme { Surface { ChatScreen(focusOnEmpty = false, vm = ChatViewModel(provider = { null }), title = "near.ai", trust = t, hasProvider = true, whereLocality = ai.teemoon.providers.WhereLocality.CLOUD) } } }
    }

    /** trust-unchecked-glyph: the hardware card over an Intel outage — the orange "couldn't check" mark, not the red ✕. */
    @Test fun sendGate_intelOutage_hardware() = run {
        val t = SendGateFixtures.snapshot(SendGateFixtures.intelOutage)
        paparazzi.snapshot { TeemoonTheme { Surface { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) {
            EvidenceCard("genuine hardware", t.expert.hardwareSubtitle) { t.expert.hardware.forEach { EvidenceRowView(it) } } } } } }
    }
}

class SendGateAlertLargestTextSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 412, screenHeight = 1000, density = com.android.resources.Density.MEDIUM, fontScale = 2f))
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    @Test fun sendGate_nvidiaOutage_largestText() = run { val t = SendGateFixtures.snapshot(SendGateFixtures.nvidiaOutage); paparazzi.snapshot { Alert(t) } }
    @Test fun sendGate_intelOutage_largestText() = run { val t = SendGateFixtures.snapshot(SendGateFixtures.intelOutage); paparazzi.snapshot { Alert(t) } }
}
