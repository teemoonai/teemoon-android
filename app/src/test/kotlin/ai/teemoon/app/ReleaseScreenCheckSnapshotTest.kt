// The 1.1.0 release screen check: every trust state the maintainer approved on the review page on 2026-11-06 (near-trust-scope,
// trust-pending-state q1–q5, trust-quote-failed, trust-stale-record, trust-verifier-error, trust-hardware-fail-lines,
// trust-audit-claim, trust-audit-links-gated, trust-running-undecidable) and every state of the near.ai import page
// (import-page-strings v5–v9, its own full-height sheet), each at text 100% and 200%, light and dark, on a 411 dp
// phone. One golden per state × look, named <state>_<light|dark>_<100|200>. The import page is drawn at the phone's
// full 891 dp so "choose file…" and "import" have to share the screen as they do on a Pixel; the trust pages are drawn
// tall enough to show their whole scroll, since the words are what is being checked.
//
// Light is drawn though 1.1.0 ships dark only (Theme.kt FOLLOWS_SYSTEM_APPEARANCE = false): light is being designed
// for this release and the approved light palette must not leave a trust tint or a word illegible when it turns on.
package ai.teemoon.app

import ai.teemoon.chat.imports.ImportSummary
import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.confidential.EverydayAuditState
import ai.teemoon.confidential.ImageRef
import ai.teemoon.confidential.ManifestProvenance
import ai.teemoon.confidential.NearTrustScope
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.RecordDcapVerification
import ai.teemoon.confidential.TdxVerificationResult
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.confidential.TrustVerdict
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ReleaseScreenCheckSnapshotTest {
    private val phone = DeviceConfig.PIXEL_5.copy(screenWidth = 411, screenHeight = 891, density = Density.MEDIUM)
    @get:Rule val paparazzi = Paparazzi(deviceConfig = phone, useDeviceResolution = true) // unscaled: the words are read off these

    // The fixture model's tier is known (near.ai own TEE): an UNKNOWN tier reads "verifying…" (b2ccabc8, unknown tier →
    // VERIFYING), which turned the settled screens into verifying ones unnoticed.
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null })
        NearAIModelCatalog.recordTiers(mapOf((AttestationRecord.preview.model ?: Provider.nearAI.model) to NearAIModelCatalog.Confidentiality.TEE_OWN)) }

    private class Look(val name: String, val dark: Boolean, val scale: Float)
    private val looks = listOf(Look("light_100", false, 1f), Look("dark_100", true, 1f), Look("light_200", false, 2f), Look("dark_200", true, 2f))

    /** Draws [content] in all four looks; [tall] is the height at 100%, doubled at 200% (null: the phone's 891 dp at both). */
    private fun shoot(tall: Int? = null, content: @Composable () -> Unit) = looks.forEach { l ->
        val h = tall?.let { (it * l.scale).toInt() } ?: phone.screenHeight
        paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = h, fontScale = l.scale))
        paparazzi.snapshot(l.name) { TeemoonTheme(dark = l.dark) { Surface { content() } } }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    // ── trust: the fixtures of TrustScreenSnapshotTest / TrustLargestTextSnapshotTest ──────────────────────────────

    /** T1: the record is in; DCAP, NRAS and TLS asked and not answered (trust-pending-state). */
    private fun pending(): Pair<ConfidentialSession, Provider> {
        val preview = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        val p = Provider.nearAI.copy(id = preview.providerID, model = preview.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(scope, { p }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            directBase = { "https://x.completions.near.ai/v1" }, verifyDcap = { awaitCancellation() },
            verifyNras = { _, _ -> awaitCancellation() }, verifyTls = { awaitCancellation() },
            verifyManifests = { ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList()) }, fetchModelLayerManifest = null), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.adopt(preview, p)
        return s to p
    }

    /**
     * Answered DCAP, NVIDIA and TLS over the preview record, with [mutate] applied. Without [now] the record is fetched
     * "now": the hero's stamp reads the wall clock (AttestationSummary.timestampText) and the preview's fetchedAt is
     * fixed when the class loads, so a suite running past a minute would draw "verified 1 min ago" against the golden.
     */
    private fun settled(base: AttestationRecord = AttestationRecord.preview.copy(nvidiaPayload = "{}"), now: java.time.Instant? = null,
                        mutate: (ConfidentialSession) -> Unit = {}): Pair<ConfidentialSession, Provider> {
        val record = if (now == null) base.copy(fetchedAt = java.time.Instant.now()) else base
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(scope, { p }, services = if (now != null) ConfidentialSession.Services(skipLiveAttestation = true, now = { now })
            else ConfidentialSession.Services(skipLiveAttestation = true), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "01", ""))
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        mutate(s)
        return s to p
    }

    private fun quoteFailed(): Pair<ConfidentialSession, Provider> {
        val base = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        val mqv = base.modelQuoteVerification!!
        return settled(base.copy(modelQuoteVerification = TdxVerificationResult(mqv.measurements, signatureValid = false, certChainValid = mqv.certChainValid, certChainError = null)))
    }

    private fun withAudit(state: EverydayAuditState): TrustSnapshot {
        val (s, p) = settled()
        val snap = trustSnapshot(s, p)
        val v = TrustVerdict.make(TrustVerdict.Input(attestationState = snap.state, modelName = snap.modelName, auditState = state))
        val claim = v.everydayClaims.first { it.id == "audit" }
        return snap.copy(verdict = snap.verdict.copy(heroTitle = v.heroTitle, heroSubtitle = v.heroSubtitle,
            everydayClaims = snap.verdict.everydayClaims.filter { it.id != "audit" }.let { c ->
                c.take(c.indexOfFirst { it.id == "identity" } + 1) + claim + c.drop(c.indexOfFirst { it.id == "identity" } + 1) }))
    }

    @Composable private fun Everyday(snap: TrustSnapshot) = TrustScreen(snap, onBack = {}, onReverify = {})
    @Composable private fun Expert(snap: TrustSnapshot) = TrustScreen(snap, onBack = {}, onReverify = {}, onCopyScript = {}, initialRung = TrustRung.EXPERT)
    @Composable private fun HeroAndHardware(snap: TrustSnapshot) = Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Hero(snap, onReverify = {})
        EvidenceCard("genuine hardware", snap.expert.hardwareSubtitle) { snap.expert.hardware.forEach { EvidenceRowView(it) } }
        snap.expert.transport?.let { EvidenceCard("sealed transport", ExpertSections.TRANSPORT_SUBTITLE) { EvidenceRowView(it) } }
    }

    // trust-pending-state (q1 verifying, q2 TLS row, q3 GPU row, q4 sealed step checking, q5 rail spoke wheel)
    @Test fun trustPending_chip() { val (s, p) = pending(); val snap = trustSnapshot(s, p)
        shoot { ChatScreen(focusOnEmpty = false, vm = ChatViewModel(provider = { p }), title = p.name, trust = snap, hasProvider = true) } }
    @Test fun trustPending_everyday() { val (s, p) = pending(); val snap = trustSnapshot(s, p); shoot(1400) { Everyday(snap) } }
    @Test fun trustPending_expert() { val (s, p) = pending(); val snap = trustSnapshot(s, p); shoot(3000) { Expert(snap) } }
    @Test fun trustPending_hardware() { val (s, p) = pending(); val snap = trustSnapshot(s, p); shoot(1100) { HeroAndHardware(snap) } }

    // trust-quote-failed: everyday reuses "couldn't seal this chat …", expert "key in report_data, but the quote didn't verify"
    @Test fun trustQuoteFailed_everyday() { val (s, p) = quoteFailed(); val snap = trustSnapshot(s, p); shoot(1400) { Everyday(snap) } }
    @Test fun trustQuoteFailed_expert() { val (s, p) = quoteFailed(); val snap = trustSnapshot(s, p); shoot(3000) { Expert(snap) } }

    // trust-stale-record: 10 min cap, soft, "couldn't reach the attestation server."
    @Test fun trustStaleRecord_everyday() {
        val now = java.time.Instant.parse("2026-11-06T12:00:00Z")
        val (s, p) = settled(AttestationRecord.preview.copy(nvidiaPayload = "{}", fetchedAt = now.minusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 60)), now) { it.attestationFetchFailed = true }
        val snap = trustSnapshot(s, p); shoot(1400) { Everyday(snap) }
    }

    // trust-verifier-error + trust-hardware-fail-lines: a verifier that threw, as the session records it
    @Test fun trustVerifierError_hardware() {
        val (s, p) = settled { it.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Unchecked(ConfidentialSession.VERIFIER_ERROR))
            it.gpuAttestation = NrasVerification.Inconclusive(ConfidentialSession.VERIFIER_ERROR) }
        val snap = trustSnapshot(s, p); shoot(1100) { HeroAndHardware(snap) }
    }

    // trust-hardware-fail-lines: NVIDIA rejected the evidence — the GPU row carries NRAS's reason, not "verified via gateway"
    @Test fun trustNvidiaFailed_hardware() {
        val (s, p) = settled { it.gpuAttestation = NrasVerification.Failed("NVIDIA attestation verdict: FAIL") }
        val snap = trustSnapshot(s, p); shoot(1100) { HeroAndHardware(snap) }
    }

    // trust-audit-claim: compromisable → "reviewed, with a catch"; missing/unknown → incomplete
    @Test fun trustAuditReviewedWithCaveat_everyday() { val snap = withAudit(EverydayAuditState.REVIEWED_WITH_CAVEAT); shoot(1400) { Everyday(snap) } }
    @Test fun trustAuditIncomplete_everyday() { val snap = withAudit(EverydayAuditState.INCOMPLETE); shoot(1400) { Everyday(snap) } }

    // trust-audit-links-gated: a settled record's expert rung, where each audit/recipe link shows only where earned
    @Test fun trustSettled_expert() { val (s, p) = settled(); val snap = trustSnapshot(s, p); shoot(3000) { Expert(snap) } }

    // near-trust-scope (+ trust-running-undecidable in the proven set's rotate-token row): the model enclave group's rows
    @Composable private fun ScopeRows(scope: NearTrustScope) = Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        EnclaveGroupView(TrustScopeFixtures.group(bound = scope.deployLogBinding == ai.teemoon.confidential.DeployLogBinding.Bound),
            TrustRung.EXPERT, ai.teemoon.confidential.AuditIndex(fixedIndex = null), scope = scope)
    }
    @Test fun trustScope_proven() = shoot(2500) { ScopeRows(TrustScopeFixtures.proven) }
    @Test fun trustScope_notBound() = shoot(1600) { ScopeRows(TrustScopeFixtures.notBound) }
    @Test fun trustScope_unchecked() = shoot(1600) { ScopeRows(TrustScopeFixtures.unchecked) }
    @Test fun trustScope_broken() = shoot(2000) { ScopeRows(TrustScopeFixtures.broken) }

    // near-gate-nvidia-tls-split (2bd428c7 + de3b56e6), 100% dark: malformed evidence from near.ai was an orange ask
    // ("before", the old Inconclusive) and is now a red block ("after", Failed); teemoon's own fault stays an orange ask
    // whose hero reads the approved "this check failed inside teemoon, so it proves nothing." (never an outage).
    private fun darkPair(mutate: (ConfidentialSession) -> Unit) {
        val (s, p) = settled(mutate = mutate); val snap = trustSnapshot(s, p)
        paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = 1400))
        paparazzi.snapshot("everyday_dark_100") { TeemoonTheme(dark = true) { Surface { Everyday(snap) } } }
        paparazzi.unsafeUpdateConfig(deviceConfig = phone.copy(screenHeight = 3000))
        paparazzi.snapshot("expert_dark_100") { TeemoonTheme(dark = true) { Surface { Expert(snap) } } }
    }
    private val nvidiaMalformed = "GPU evidence payload unparseable" // NrasService.kt: an unparseable GPU payload
    private val tlsMalformed = "attestation response missing TLS fields" // TlsAttestationVerifier.kt: TLS fields missing
    @Test fun `split-nvidia-before`() = darkPair { it.gpuAttestation = NrasVerification.Inconclusive(nvidiaMalformed) }
    @Test fun `split-nvidia-after`() = darkPair { it.gpuAttestation = NrasVerification.Failed(nvidiaMalformed) }
    @Test fun `split-tls-before`() = darkPair { it.tlsAttestation = TlsAttestation.Inconclusive(tlsMalformed) }
    @Test fun `split-tls-after`() = darkPair { it.tlsAttestation = TlsAttestation.Failed(tlsMalformed) }
    @Test fun `fault-nvidia`() = darkPair { it.gpuAttestation = NrasVerification.Inconclusive(NrasVerification.TEEMOON_FAULT) }
    @Test fun `fault-tls`() = darkPair { it.tlsAttestation = TlsAttestation.Inconclusive(NrasVerification.TEEMOON_FAULT) }

    // ── import: the page at each state (fixtures of SettingsScreensSnapshotTest), on the phone's full height ────────

    private val file = ImportFile("private-chat-export-2026-10-15.json", 24_300, null)
    private val summary = ImportSummary(6, 25, 1, 4, imagesImported = 1)
    private fun importPage(state: ChatImportUi) = shoot {
        NearImportScreen(state, onChooseFile = {}, onConfirm = {}, onDone = {}, onDismissFailure = {}, onBack = {}) }

    @Test fun import_start() = importPage(ChatImportUi.Idle)
    @Test fun import_checking() = importPage(ChatImportUi.Checking(file))
    @Test fun import_confirm() = importPage(ChatImportUi.Confirm(summary, file))
    /** images.json as the emulator run showed it: the card that crowded "import" at 200%. */
    @Test fun import_confirmImages() = importPage(ChatImportUi.Confirm(ImportSummary(23, 42, imagesImported = 20, imagesSkipped = 11), ImportFile("images.json", 190_579, null)))
    @Test fun import_nothingNew() = importPage(ChatImportUi.Confirm(ImportSummary(chatsAlreadyHere = 6), file))
    @Test fun import_empty() = importPage(ChatImportUi.Confirm(ImportSummary(), file))
    @Test fun import_progress() = importPage(ChatImportUi.Importing(read = 4, total = 6))
    /** done with every small line: images skipped, items skipped, cut short. */
    @Test fun import_finished() = importPage(ChatImportUi.Finished(ImportSummary(6, 25, 1, 4, mayBeIncomplete = true, imagesImported = 1, imagesSkipped = 2)))
    @Test fun import_failed() = importPage(ChatImportUi.Failed(ChatImportUi.Failed.Reason.WRONG_FILE))
    @Test fun import_deleteOffer() = shoot {
        ChatsSettingsScreen(AppSettings(InMemoryPrefs()), onDeleteAllChats = {}, onBack = {})
        ExportDeleteAlert(ExportDelete.Offer(file.name), onDelete = {}, onNotNow = {}, onDismissFailure = {})
    }
}
