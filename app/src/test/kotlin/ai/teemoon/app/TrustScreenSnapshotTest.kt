// Paparazzi baselines for the screens with layout risk (CONTRIBUTING.md: snapshots for anything with layout
// risk). Record with `./gradlew recordPaparazziDebug`, verify with `verifyPaparazziDebug`. The narrow
// device is deliberate: a 320 px screen is what squeezed the chip out of the chat bar.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import ai.teemoon.confidential.EncryptedModelCTA
import ai.teemoon.providers.EndpointDirectory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier

class TrustScreenSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 320, screenHeight = 1400, density = com.android.resources.Density.MEDIUM))

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private fun session(provider: Provider?) = ConfidentialSession(scope, { provider }, services = ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))

    private fun seeded(): Pair<ConfidentialSession, Provider> {
        val preview = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now())
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = session(p); s.attestation = preview; s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.NotPerformed
        return s to p
    }

    /**
     * T1 (5d52964): the record is in and DCAP, NRAS and TLS have been asked and not answered. Before the fix this
     * rendered green ("end-to-end encrypted"); now it is verifying (the old render: 198f27c's trustPendingBefore).
     */
    private fun pendingVerdicts(launched: Boolean = true): Pair<ConfidentialSession, Provider> {
        val preview = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(scope, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://x.completions.near.ai/v1" }, verifyDcap = { kotlinx.coroutines.awaitCancellation() },
            verifyNras = { _, _ -> kotlinx.coroutines.awaitCancellation() }, verifyTls = { kotlinx.coroutines.awaitCancellation() },
            // provenance answered offline, one image (a pass over none is not a pass): only the three hard verdicts are pending, and nothing reaches the network
            verifyManifests = { ai.teemoon.confidential.ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList()) }, fetchModelLayerManifest = null), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        if (launched) s.adopt(preview, p) else s.attestation = preview
        return s to p
    }

    @Test fun trustPending_everyday() {
        val (s, p) = pendingVerdicts()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustPending_expert() {
        val (s, p) = pendingVerdicts()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    /** The expert rung's hardware card (and the transport card, hidden while TLS is pending — trust-pending-state). */
    @Test fun trustPending_hardware() {
        val (s, p) = pendingVerdicts()
        val x = trustSnapshot(s, p).expert
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            EvidenceCard("genuine hardware", x.hardwareSubtitle) { x.hardware.forEach { EvidenceRowView(it) } }
            x.transport?.let { EvidenceCard("sealed transport", ExpertSections.TRANSPORT_SUBTITLE) { EvidenceRowView(it) } }
        } } } }
    }

    @Test fun trustPending_chip() {
        val (s, p) = pendingVerdicts()
        val vm = ChatViewModel(provider = { p })
        paparazzi.snapshot { TeemoonTheme { Surface { ChatScreen(focusOnEmpty = false, vm = vm, title = p.name, trust = trustSnapshot(s, p), hasProvider = true) } } }
    }

    /** Answered DCAP and NVIDIA over the preview record, with [mutate] applied: the review page's settled states. */
    private fun settled(record: AttestationRecord = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}"), now: java.time.Instant? = null,
                        mutate: (ConfidentialSession) -> Unit = {}): Pair<ConfidentialSession, Provider> {
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(scope, { p }, services = if (now != null) ConfidentialSession.Services(skipLiveAttestation = true, now = { now })
            else ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "01", ""))
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        s.imageProvenance = ai.teemoon.confidential.ManifestProvenance.AllVerified(listOf(ai.teemoon.confidential.ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        mutate(s)
        return s to p
    }

    /** trust-quote-failed (T6): the model quote's signature failed; the key is still in its report_data. */
    private fun quoteFailed(): Pair<ConfidentialSession, Provider> {
        val base = AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}")
        val mqv = base.modelQuoteVerification!!
        return settled(base.copy(modelQuoteVerification = ai.teemoon.confidential.TdxVerificationResult(mqv.measurements, signatureValid = false, certChainValid = mqv.certChainValid, certChainError = null)))
    }

    @Test fun trustQuoteFailed_everyday() {
        val (s, p) = quoteFailed()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustQuoteFailed_expert() {
        val (s, p) = quoteFailed()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    /** trust-stale-record: past 10 minutes, the refetch failed. */
    @Test fun trustStaleRecord_everyday() {
        val now = java.time.Instant.parse("2026-11-06T12:00:00Z")
        val (s, p) = settled(AttestationRecord.preview.copy(fetchedAt = java.time.Instant.now()).copy(nvidiaPayload = "{}", fetchedAt = now.minusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 60)), now) { it.attestationFetchFailed = true }
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    /** trust-verifier-error: DCAP's and NVIDIA's verifiers threw — couldn't-check, soft (near-send-gate-rule), as the session records them. */
    @Test fun trustVerifierError_hardware() {
        val (s, p) = settled { it.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = ai.teemoon.confidential.DcapVerification.Unchecked(ConfidentialSession.VERIFIER_ERROR))
            it.gpuAttestation = NrasVerification.Inconclusive(ConfidentialSession.VERIFIER_ERROR) }
        val snap = trustSnapshot(s, p)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Hero(snap, onReverify = {})
            EvidenceCard("genuine hardware", snap.expert.hardwareSubtitle) { snap.expert.hardware.forEach { EvidenceRowView(it) } }
        } } } }
    }

    /**
     * trust-audit-claim (the maintainer 2026-11-06): the everyday chain's audit step in its new states, over a settled record.
     * The claim and the hero's title and subtitle are the verdict's own (TrustVerdict.make with the rollup's state and
     * exposure; trust-leaks-wording); everything else is the session's.
     */
    private fun withAudit(state: ai.teemoon.confidential.EverydayAuditState, leaksExposure: String? = null): TrustSnapshot {
        val (s, p) = settled()
        val snap = trustSnapshot(s, p)
        val v = ai.teemoon.confidential.TrustVerdict.make(ai.teemoon.confidential.TrustVerdict.Input(attestationState = snap.state,
            modelName = snap.modelName, auditState = state, leaksExposure = leaksExposure))
        val claim = v.everydayClaims.first { it.id == "audit" }
        // a LEAKS review demotes a green hero to advisory, as trustSnapshot does
        val tone = if (state == ai.teemoon.confidential.EverydayAuditState.LEAKS && snap.heroTone == TrustTone.OK) TrustTone.ADVISORY else snap.heroTone
        return snap.copy(heroTone = tone, verdict = snap.verdict.copy(heroTitle = v.heroTitle, heroSubtitle = v.heroSubtitle,
            everydayClaims = snap.verdict.everydayClaims.filter { it.id != "audit" }.let { c ->
            c.take(c.indexOfFirst { it.id == "identity" } + 1) + claim + c.drop(c.indexOfFirst { it.id == "identity" } + 1) }))
    }

    @Test fun trustAuditReviewedWithCaveat_everyday() {
        val snap = withAudit(ai.teemoon.confidential.EverydayAuditState.REVIEWED_WITH_CAVEAT)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(snap, onBack = {}, onReverify = {}) } } }
    }

    /** trust-leaks-wording: a reviewed build is the one state whose hero ends "'s enclave key" (iOS 37de10d9). */
    @Test fun trustAuditReviewed_everyday() {
        val snap = withAudit(ai.teemoon.confidential.EverydayAuditState.REVIEWED)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(snap, onBack = {}, onReverify = {}) } } }
    }

    /** trust-leaks-wording: a LEAKS review speaks in the reviewer's words (line B), in the hero and the audit step. */
    @Test fun trustAuditLeaks_everyday() {
        val snap = withAudit(ai.teemoon.confidential.EverydayAuditState.LEAKS,
            "every prompt is written to the container log and shipped to the operator's telemetry.")
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(snap, onBack = {}, onReverify = {}) } } }
    }

    /** …and with no exposure recorded, line A. */
    @Test fun trustAuditLeaksNoExposure_everyday() {
        val snap = withAudit(ai.teemoon.confidential.EverydayAuditState.LEAKS)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(snap, onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustAuditIncomplete_everyday() {
        val snap = withAudit(ai.teemoon.confidential.EverydayAuditState.INCOMPLETE)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(snap, onBack = {}, onReverify = {}) } } }
    }

    /** trust-provenance-unchecked: GitHub didn't answer the first pass — header A, step B; the chip stays "end-to-end encrypted". */
    @Test fun trustProvenanceUnchecked_everyday() {
        val (s, p) = settled { it.imageProvenance = null; it.provenanceUnchecked = true }
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustScreen_everyday_seededRecord() {
        val (s, p) = seeded()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustScreen_proof_seededRecord() {
        val (s, p) = seeded()
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(s, p), onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT) } } }
    }

    /**
     * The expert rung's last two sections at 320 px — "what you still trust" with its three symbols, and the script card,
     * whose header holds a middle-truncating filename, the language pill and the copy capsule on one line (iOS
     * SelfVerifyScriptCard: the filename yields, the pill and the button never wrap).
     */
    @Test fun expertTail_residualTrustAndScriptCard() {
        val (s, p) = seeded()
        val snap = trustSnapshot(s, p)
        paparazzi.snapshot { TeemoonTheme { Surface { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            EvidenceCard("what you still trust", "verification shrinks what you trust — it can't take it to zero. this is the part no proof removes.") {
                snap.expert.residualExpert.forEach { (icon, text) -> TrustRow(icon, text) }
            }
            ReverifySection(snap, onCopyScript = {})
        } } } }
    }

    @Test fun trustScreen_verifying() {
        val p = Provider.nearAI
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    @Test fun trustScreen_notAttestable() {
        val p = Provider(id = "x", name = "acme", endpoint = "https://api.acme.example/v1", model = "acme/big-model")
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    /**
     * The not-attestable screen WITH its way out — the state a real user is in on Fireworks, NVIDIA or
     * OpenRouter, and the one `trustScreen_notAttestable` above does not cover because it passes no CTA.
     *
     * Both halves are here: the expert reason card, and the green offer under it. Neither had a pixel record
     * until 2026-09-21, which is part of why `offersEncryptedModels` could be wrong for weeks.
     */
    @Test fun trustScreen_notAttestable_withTheWayOut() {
        val p = Provider(id = "f", name = "fireworks", endpoint = "https://api.fireworks.ai/inference/v1", model = "kimi-k2p6")
        paparazzi.snapshot {
            TeemoonTheme {
                Surface {
                    TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {},
                        encryptedModelCTA = EncryptedModelCTA.resolve(Provider.nearAI, hasKey = true),
                        onEncryptedModelCTA = {}, initialRung = TrustRung.EXPERT)
                }
            }
        }
    }

    @Test fun trustScreen_selfHosted() {
        val p = Provider(id = "h", name = "home", endpoint = "http://192.168.1.20:11434/v1", model = "llama3", requiresAPIKey = false)
        paparazzi.snapshot { TeemoonTheme { Surface { TrustScreen(trustSnapshot(session(p), p), onBack = {}, onReverify = {}) } } }
    }

    /** The chat bar on a narrow screen: the chip must be visible on its own row under the title. */
    @Test fun chatScreen_chipOnNarrowScreen() {
        val (s, p) = seeded()
        val vm = ChatViewModel(provider = { p })
        paparazzi.snapshot { TeemoonTheme { Surface { ChatScreen(focusOnEmpty = false, vm = vm, title = p.name, trust = trustSnapshot(s, p), hasProvider = true) } } }
    }
}
