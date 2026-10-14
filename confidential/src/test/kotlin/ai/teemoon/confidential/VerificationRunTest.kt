// ports: teemoonTests/VerificationRunTests.swift @ v1.0.1 (the VerificationRun suite; RecipeClassification,
//        TagPinnedExtraction and RecipeRowPartition are iOS-only and belong to EnclaveGrouping / ProvenanceService)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The verification-run structure the redesigned sheet renders: sections in
 * near.ai's documented order, results rolled up from real inputs, and TLS /
 * gateway sections appearing only when applicable.
 */
class VerificationRunTest {

    private fun summary(
        attestation: AttestationRecord? = AttestationRecord.preview,
        state: AttestationState = AttestationState.OK,
        verifiedResponseCount: Int = 0,
        imageProvenance: ManifestProvenance? = null,
        dcap: RecordDcapVerification? = null,
        nras: NrasVerification? = null,
        tls: TlsAttestation? = null,
        modelLayerVerification: ModelLayerVerification? = null,
        degradeIsHardFailure: Boolean = false,
    ) = AttestationSummary(
        attestation = attestation, state = state, timedOut = false, provider = Provider.nearAI,
        lastRequestUsedE2EE = null, lastE2EEFailReason = null,
        verifiedResponseCount = verifiedResponseCount, mismatchedResponseCount = 0,
        attestationFetchFailed = false, imageProvenance = imageProvenance,
        dcapVerification = dcap, nrasVerification = nras, tlsAttestation = tls,
        modelLayerVerification = modelLayerVerification,
        degradeIsHardFailure = degradeIsHardFailure,
    )

    /** Image steps carry a "/" in their label; the engine step does not. */
    private fun imageSteps(s: AttestationSummary): List<RunStep> =
        section(s, "provenance")?.steps?.filter { it.url != null && it.label.contains("/") } ?: emptyList()

    private fun section(s: AttestationSummary, id: String): VerificationSection? = s.verificationRun.firstOrNull { it.id == id }

    private fun verified(tcb: DcapTcbStatus) = DcapVerification.Verified(tcb, "01", "")
    private val allVerifiedEmpty = ManifestProvenance.AllVerified(emptyList(), emptyList())
    private val digestA = "a".repeat(64)

    @Test fun sectionsFollowNearAIDocOrder() {
        val s = summary(verifiedResponseCount = 2, imageProvenance = allVerifiedEmpty,
            dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.UP_TO_DATE)),
            nras = NrasVerification.Verified, tls = TlsAttestation.Verified)
        // model → gateway → tls → chat → provenance
        assertEquals(listOf("model", "gateway", "tls", "chat", "provenance"), s.verificationRun.map { it.id })
        for (sec in s.verificationRun) assertTrue(sec.steps.isNotEmpty())
    }

    @Test fun eachSectionLinksItsDoc() {
        val s = summary(tls = TlsAttestation.Verified)
        assertTrue(section(s, "model")!!.docURL.endsWith("/model"))
        assertTrue(section(s, "tls")!!.docURL.endsWith("/tls"))
    }

    @Test fun tlsNotPerformed_dropsTheSection() {
        assertNull(section(summary(tls = TlsAttestation.NotPerformed), "tls"))
    }

    @Test fun tlsFailure_showsFailedSectionAndFailsRun() {
        val s = summary(tls = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint"))
        val tls = section(s, "tls")
        assertEquals(SectionResult.FAILED, tls?.result)
        assertTrue(tls!!.steps.first().detail.contains("does not match"))
        assertEquals(SectionResult.FAILED, s.runResult)
    }

    @Test fun provenanceIsMarkedTeemoonExtra() {
        val s = summary(imageProvenance = allVerifiedEmpty)
        assertEquals(true, section(s, "provenance")?.isExtra)
        assertEquals(false, section(s, "model")?.isExtra)
    }

    @Test fun provenancePerImageStep_linksSourceReleaseAndSigstore() {
        val repo = "https://github.com/nearai/private-ml-sdk"
        val commit = "84367f0253fa94aa6816d64210e5812215ee2622"
        val verified = listOf(ImageRef(image = "nearaidev/cloud-api", digest = digestA, sourceRepo = repo,
            sourceRef = "refs/tags/v0.5.1", sourceCommit = commit))
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(verified, emptyList()))
        // evidence names the actual repo, not just the org
        assertTrue(s.provenanceEvidence!!.contains("nearai/private-ml-sdk"))

        val step = section(s, "provenance")!!.steps.first { it.label == "nearaidev/cloud-api" }
        // detail carries the running digest and attested version
        assertTrue(step.detail.contains("sha256:aaaaaaaaaaaa"))
        assertTrue(step.detail.contains("v0.5.1"))
        // tapping the step navigates directly to the code that's running
        assertEquals("$repo/tree/$commit", step.url)
        // secondary links: release tag and public sigstore log
        val urls = step.links.map { it.url }
        assertTrue("$repo/releases/tag/v0.5.1" in urls)
        assertTrue("https://search.sigstore.dev/?hash=$digestA" in urls)
    }

    @Test fun provenanceBranchBuild_hasNoReleaseLink() {
        val verified = listOf(ImageRef(image = "nearaidev/cloud-api", digest = digestA,
            sourceRepo = "https://github.com/nearai/cloud-api", sourceRef = "refs/heads/main", sourceCommit = "84367f02"))
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(verified, emptyList()))
        val step = section(s, "provenance")!!.steps.first { it.label == "nearaidev/cloud-api" }
        assertTrue(step.detail.contains("main"))
        assertEquals("https://github.com/nearai/cloud-api/tree/84367f02", step.url)
        assertFalse(step.links.any { it.url.contains("/releases/tag/") })
    }

    @Test fun provenanceStepsIdentifyTheirMachine() {
        val verified = listOf(
            ImageRef(image = "nearaidev/compose-manager", digest = "e".repeat(64), sourceRepo = "https://github.com/nearai/compose-manager", hosts = listOf("model")),
            ImageRef(image = "nearaidev/cloud-api", digest = digestA, sourceRepo = "https://github.com/nearai/cloud-api", hosts = listOf("gateway")),
            ImageRef(image = "nearaidev/dstack-vpc", digest = "c".repeat(64), sourceRepo = "https://github.com/nearai/dstack-vpc", hosts = listOf("gateway", "model")),
        )
        val steps = imageSteps(summary(imageProvenance = ManifestProvenance.AllVerified(verified, emptyList())))
        // each detail names its machine, grouped gateway → shared → model
        assertEquals(listOf("nearaidev/cloud-api", "nearaidev/dstack-vpc", "nearaidev/compose-manager"), steps.map { it.label })
        assertTrue(steps[0].detail.startsWith("runs on gateway ·"))
        assertTrue(steps[1].detail.startsWith("runs on gateway + model ·"))
        assertTrue(steps[2].detail.startsWith("runs on model ·"))
    }

    @Test fun provenanceTagWithoutCommit_navigatesToTagTree() {
        val verified = listOf(ImageRef(image = "nearaidev/cloud-api", digest = digestA,
            sourceRepo = "https://github.com/nearai/cloud-api", sourceRef = "refs/tags/v0.5.1", sourceCommit = null))
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(verified, emptyList()))
        val step = section(s, "provenance")!!.steps.first { it.label == "nearaidev/cloud-api" }
        assertEquals("https://github.com/nearai/cloud-api/tree/v0.5.1", step.url)
    }

    @Test fun provenanceWithoutRepoFallsBackToOrg() {
        val s = summary(imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", digestA)), emptyList()))
        assertTrue(s.provenanceEvidence!!.contains("github.com/nearai"))
        assertTrue(imageSteps(s).isEmpty())
    }

    @Test fun engineStepPinsAttestedSource() {
        val s = summary(imageProvenance = allVerifiedEmpty, modelLayerVerification = ModelLayerVerification.VERIFIED)
        val engine = section(s, "provenance")!!.steps.first { it.label.contains("inference engine") }
        assertEquals(RunStepStatus.PASS, engine.status)
        assertTrue(engine.detail.contains("prod/GLM-5.1-SGL-AWQ-TP4.yaml"))
        assertTrue(engine.detail.contains("v0.0.296"))
        assertEquals("https://github.com/nearai/cvm-compose-files/blob/c545c95545dba47d8bea293aaae317089ea52f4d/prod/GLM-5.1-SGL-AWQ-TP4.yaml", engine.url)
    }

    @Test fun engineStep_fetchFailedFlags_hashMismatchFails_absentFieldsDropStep() {
        fun engine(s: AttestationSummary) = section(s, "provenance")?.steps?.firstOrNull { it.label.contains("inference engine") }
        // Transient fetch failure → advisory (orange), never a tamper accusation.
        assertEquals(RunStepStatus.FLAG, engine(summary(imageProvenance = allVerifiedEmpty, modelLayerVerification = ModelLayerVerification.FETCH_FAILED))?.status)
        // Hash mismatch → HARD fail (red): the recipe does not match the action
        // log's pin. Must NOT collapse to the same advisory as a fetch failure.
        assertEquals(RunStepStatus.FAIL, engine(summary(imageProvenance = allVerifiedEmpty, modelLayerVerification = ModelLayerVerification.HASH_MISMATCH))?.status)
        // record without compose-manager action fields → no engine step
        // (section may be absent entirely, hence null)
        assertNull(engine(summary(attestation = AttestationRecord.previewDegraded, imageProvenance = allVerifiedEmpty)))
    }

    @Test fun degrade_hardFailureRendersRed_softStaysOrange() {
        // HARD adversarial break (tamper/replay/MITM) → red "verification failed".
        val hard = summary(state = AttestationState.DEGRADED, degradeIsHardFailure = true)
        assertEquals(AttestationSummary.HeaderSeverity.FAILED, hard.headerSeverity)
        assertEquals("verification failed", hard.headerTitle)
        assertEquals("xmark.shield.fill", hard.headerIcon)
        assertTrue(hard.headerSubtitle.contains("treat this chat as unverified"))
        // SOFT/operational degrade (E2EE simply unavailable) → orange advisory,
        // NOT the same red as a security break.
        val soft = summary(state = AttestationState.DEGRADED, degradeIsHardFailure = false)
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, soft.headerSeverity)
        assertEquals("verified hardware", soft.headerTitle)
        assertEquals("lock.trianglebadge.exclamationmark.fill", soft.headerIcon)
    }

    @Test fun outOfDateTcb_passesNotFlags() {
        // TLS + provenance resolved so no pending section keeps the run "running".
        val s = summary(imageProvenance = allVerifiedEmpty,
            dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.OUT_OF_DATE)), tls = TlsAttestation.NotPerformed)
        // Genuine hardware behind on Intel platform updates is a note, not a
        // warning — the quote still proves real sealed silicon and the E2EE
        // binding holds. Only `revoked` breaks (separate hard-failure path).
        assertEquals(SectionResult.PASSED, section(s, "gateway")?.result)
        assertEquals(SectionResult.PASSED, s.runResult)
    }

    @Test fun pendingTLS_keepsRunRunning() {
        val s = summary(dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.UP_TO_DATE)), tls = null)   // TLS still in flight
        assertEquals(SectionResult.RUNNING, section(s, "tls")?.result)
        assertEquals(SectionResult.RUNNING, s.runResult)
    }

    @Test fun directOnlyPath_hasNoGatewaySection() {
        // A record with no gateway signing address (direct model only).
        val base = AttestationRecord.preview
        val record = AttestationRecord(
            composeHash = base.composeHash, mrtd = "", osImageHash = "", intelQuote = "",
            modelIntelQuote = "aa", modelNonce = null,
            composeManifest = null, gpuArch = null, gpuNodeComposeHash = null, modelFileHash = null,
            signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = base.modelEd25519PubKey,
            quoteVerification = null, gpuQuoteVerification = null,
            modelQuoteVerification = base.modelQuoteVerification,
            fetchedAt = Instant.now(), providerID = base.providerID)
        val s = summary(attestation = record, tls = TlsAttestation.Verified)
        assertNull(section(s, "gateway"))
    }

    // MARK: - Design-review imports (July 2026)

    /**
     * The hero verdict is never greener than its worst check — a FAILED check
     * demotes the green hero to failed, with an honest subtitle. Genuine
     * hardware behind on Intel platform updates (outOfDate TCB) is a note, not
     * a warning, so the verdict stays green (see outOfDateTcb_passesNotFlags).
     */
    @Test fun heroVerdictTracksWorstCheck() {
        val dcap = RecordDcapVerification(model = verified(DcapTcbStatus.UP_TO_DATE), gateway = verified(DcapTcbStatus.OUT_OF_DATE))
        // outOfDate TCB no longer demotes the hero: genuine hardware, E2EE holds.
        val outOfDate = summary(verifiedResponseCount = 2, imageProvenance = allVerifiedEmpty,
            dcap = dcap, nras = NrasVerification.Verified, tls = TlsAttestation.Verified, modelLayerVerification = ModelLayerVerification.VERIFIED)
        assertEquals(SectionResult.PASSED, outOfDate.runResult)
        assertEquals(AttestationSummary.HeaderSeverity.OK, outOfDate.headerSeverity)
        assertEquals("end-to-end encrypted", outOfDate.headerTitle)
        assertEquals("checkmark.shield.fill", outOfDate.headerIcon)

        val failed = summary(dcap = dcap, tls = TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint"))
        assertEquals(AttestationSummary.HeaderSeverity.FAILED, failed.headerSeverity)
        assertEquals("verification failed", failed.headerTitle)
        assertEquals("xmark.shield.fill", failed.headerIcon)
        assertTrue(failed.headerSubtitle.contains("treat it as unencrypted"))

        // and all-green stays green
        val ok = dcap.copy(gateway = verified(DcapTcbStatus.UP_TO_DATE))
        val verified = summary(verifiedResponseCount = 2, imageProvenance = allVerifiedEmpty,
            dcap = ok, nras = NrasVerification.Verified, tls = TlsAttestation.Verified, modelLayerVerification = ModelLayerVerification.VERIFIED)
        assertEquals("end-to-end encrypted", verified.headerTitle)
        assertEquals(AttestationSummary.HeaderSeverity.OK, verified.headerSeverity)
        assertEquals("checkmark.shield.fill", verified.headerIcon)
    }

    /** Chip taxonomy: one register (passed/advisory/failed/checking/pending) and a glyph on every state. */
    @Test fun chipTaxonomyOneRegisterWithGlyphs() {
        assertEquals("advisory", SectionResult.FLAGGED.chipLabel)
        assertEquals("checking", SectionResult.RUNNING.chipLabel)
        assertEquals("pending", SectionResult.NOT_RUN.chipLabel)
        assertEquals("checkmark.circle.fill", SectionResult.PASSED.chipIcon)
        assertEquals("exclamationmark.triangle.fill", SectionResult.FLAGGED.chipIcon)
        assertEquals("xmark.octagon.fill", SectionResult.FAILED.chipIcon)
    }

    /** Proof rows mark literal attested tokens with backticks, and the inline ✓ that competed with the step icons is gone. */
    @Test fun proofDetailsMarkLiteralTokens() {
        val s = summary(dcap = RecordDcapVerification(model = verified(DcapTcbStatus.UP_TO_DATE)))
        val details = section(s, "model")!!.steps.map { it.detail }
        assertTrue(details.any { it.contains("`UpToDate`") })
        assertTrue(details.any { it.contains("`mr_config`") || it.contains("`report_data`") })
        assertFalse(details.any { it.contains("✓") })
    }

    // MARK: Android-only — the branches the Swift suite leaves to the view

    /** A flagged section (TLS inconclusive) rolls up to FLAGGED, which the hero reads as advisory — the OPEN this row closes. */
    @Test fun inconclusiveTlsFlagsTheRunAndDemotesTheHero() {
        val s = summary(imageProvenance = allVerifiedEmpty,
            dcap = RecordDcapVerification(gateway = verified(DcapTcbStatus.UP_TO_DATE)),
            tls = TlsAttestation.Inconclusive("verifier unreachable"))
        val tls = section(s, "tls")!!
        assertEquals(SectionResult.FLAGGED, tls.result)
        assertEquals(RunStepStatus.FLAG, tls.steps.single().status)
        assertEquals("verifier unreachable — could not complete", tls.steps.single().detail)
        assertEquals(SectionResult.FLAGGED, s.runResult)
        assertEquals(AttestationSummary.HeaderSeverity.ADVISORY, s.headerSeverity)
        assertEquals("verified — with a warning", s.headerTitle)
    }

    /** No record at all → no sections but TLS pending → the run is running, never passed. */
    @Test fun emptyRunIsRunningNotPassed() {
        val s = summary(attestation = null, state = AttestationState.VERIFYING)
        assertEquals(listOf("tls"), s.verificationRun.map { it.id })
        assertEquals(SectionResult.RUNNING, s.runResult)
        assertEquals(SectionResult.RUNNING, summary(attestation = null, tls = TlsAttestation.NotPerformed).runResult)
        assertTrue(summary(attestation = null, tls = TlsAttestation.NotPerformed).verificationRun.isEmpty())
    }

    /** The chat section mirrors the response-signature row: pass with evidence, fail on a mismatch, pending on gateway trust alone. */
    @Test fun chatSectionFollowsResponseSignatures() {
        assertNull(section(summary(), "chat"))
        val done = section(summary(verifiedResponseCount = 3), "chat")!!
        assertEquals(SectionResult.PASSED, done.result)
        assertTrue(done.steps.last().detail.contains("ecrecover"))
        val stuck = section(summary().copy(mismatchedResponseCount = 1), "chat")!!
        assertEquals(SectionResult.FAILED, stuck.result)
        assertEquals("1 mismatch of 1", stuck.steps.single().detail)
        val pending = section(summary().copy(gatewayTrustResponseCount = 2), "chat")!!
        assertEquals(SectionResult.RUNNING, pending.result)
        assertEquals(RunStepStatus.PENDING, pending.steps.single().status)
    }

    /** The model section: NRAS and DCAP failures fail it; a failed DCAP on the gateway stands in when there is no model quote verdict. */
    @Test fun modelSectionFailsOnHardEvidence() {
        val withGpu = AttestationRecord.preview.copy(nvidiaPayload = "{}")
        val nrasFailed = section(summary(attestation = withGpu, nras = NrasVerification.Failed("nonce mismatch")), "model")!!
        assertEquals(SectionResult.FAILED, nrasFailed.result)
        assertEquals("GPU check", nrasFailed.steps.first().label)
        val dcapFailed = section(summary(dcap = RecordDcapVerification(gateway = DcapVerification.Failed("no collateral"))), "model")!!
        assertEquals(SectionResult.FAILED, dcapFailed.result)
        assertEquals("no collateral", dcapFailed.steps.first { it.label == "hardware quote" }.detail)
        // Pending DCAP is a pending step; the bindings still pass, so the section passes rather than "runs".
        val pendingDcap = section(summary(), "model")!!
        assertEquals(RunStepStatus.PENDING, pendingDcap.steps.first { it.label == "hardware quote" }.status)
        assertEquals(SectionResult.PASSED, pendingDcap.result)
    }

    @Test fun stepAndLinkIdsAreStable() {
        assertEquals("a·b", RunStep("a", "b", RunStepStatus.PASS).id)
        assertEquals("source·https://x", RunLink("source", "https://x").id)
    }
}
