// ports: teemoon/Confidential/AttestationSummary.swift @ v1.0.1 (everything but StepState, its own file;
//        runResult / verificationRun are the VerificationRun.swift extension, in VerificationRun.kt)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Roll-up state of one attestation pillar. */
enum class PillarState {
    /** every relevant check passed */
    VERIFIED,
    /** a check failed (degrades the badge) */
    ATTENTION,
    /** still verifying */
    CHECKING,
}

/** One technical check inside a pillar's expanded detail. */
data class PillarCheck(val label: String, val state: StepState, val detail: String) {
    val id: String get() = label
}

/**
 * A user-facing pillar: a plain-language claim, its status, and the exact
 * technical checks that back it. The attestation sheet groups every check
 * under one of three of these (genuine hardware / published code / private
 * to you) so the meaning leads and the jargon is one tap down.
 */
data class AttestationPillar(
    val id: String,
    val title: String,
    val systemImage: String,
    val state: PillarState,
    val summaryLine: String,
    val chipLabel: String,
    val checks: List<PillarCheck>,
)

/**
 * Pass/fail interpretation of attestation data for the attestation sheet.
 *
 * Construct one per render from the current attestation record and the
 * session state values the checks read (passed in as plain values — never
 * referencing the session directly). Pure logic — no UI, no app state — so
 * it's testable.
 */
data class AttestationSummary(
    /** The attestation record to interpret (null when none has been fetched). */
    val attestation: AttestationRecord?,
    /** Combined security state driving header copy and progressive resolution. */
    val state: AttestationState,
    /** Whether the initial verification timed out (view-owned timer state). */
    val timedOut: Boolean,
    /** The provider in use — determines whether a separate GPU node exists. */
    val provider: Provider?,
    /** Whether the last request actually used E2EE (null before any request, or when no live session data is available). */
    val lastRequestUsedE2EE: Boolean?,
    /** Brief reason when [lastRequestUsedE2EE] is false. */
    val lastE2EEFailReason: String?,
    /** Responses whose signatures verified this session. */
    val verifiedResponseCount: Int,
    /** Responses whose signatures mismatched this session. */
    val mismatchedResponseCount: Int,
    /** Whether the most recent attestation refresh failed. */
    val attestationFetchFailed: Boolean,
    /**
     * Responses accepted via gateway trust only this session — the signer was
     * not individually attested. Deliberately not counted as verified.
     */
    val gatewayTrustResponseCount: Int = 0,
    /** Image-provenance result for the manifest (null = pending / not checked). */
    val imageProvenance: ManifestProvenance? = null,
    /** Real DCAP verification result for the record's quotes (null = pending). */
    val dcapVerification: RecordDcapVerification? = null,
    /** NVIDIA NRAS outcome for the record's GPU evidence (null = pending). */
    val nrasVerification: NrasVerification? = null,
    /** TLS-attestation outcome — HTTPS terminates in the model TEE (null = pending). */
    val tlsAttestation: TlsAttestation? = null,
    /**
     * Verdict of the model-layer (inner) compose hash check (null = pending /
     * not attempted). HASH_MISMATCH is an adversarial integrity break;
     * FETCH_FAILED is transient/advisory.
     */
    val modelLayerVerification: ModelLayerVerification? = null,
    /**
     * Whether a DEGRADED state is a HARD adversarial integrity break (→ red)
     * rather than a transient/operational degrade (→ orange advisory). Supplied
     * by the session; defaults false so previews/tests read as advisory.
     */
    val degradeIsHardFailure: Boolean = false,
    /**
     * Whether the SEND PATH is still sealed while the session is degraded —
     * the model key is 32 bytes and bound to a verified model TEE, with no
     * GPU/TLS check in an unverified state.
     *
     * Not every degrade is an encryption failure. An unpublished image (a
     * clean GitHub 404) and an incomplete provenance trace both degrade the
     * verdict while the bytes on the wire stay end-to-end encrypted — telling
     * that user "E2EE is unavailable" is false, and false in the direction
     * that makes them stop using the encryption they still have. Defaults
     * false so a caller that does not supply it gets the cautious reading
     * rather than an unearned claim.
     */
    val e2eeIntact: Boolean = false,
) {

    // MARK: Header content

    /** Severity of the hero verdict, for tinting and glyph choice. */
    enum class HeaderSeverity { OK, ADVISORY, FAILED, NEUTRAL }

    /**
     * The hero verdict is never greener than the worst check below it (design
     * review: a flagged gateway under a green "end-to-end encrypted" shield is
     * exactly the moment a security-conscious user loses trust). A flagged run
     * demotes an otherwise-ok verdict to advisory; a failed check demotes it to
     * failed. [runResult] is computed from [verificationRun] (VerificationRun.kt).
     */
    val headerSeverity: HeaderSeverity
        get() {
            if (state == AttestationState.VERIFYING && timedOut) return HeaderSeverity.ADVISORY
            return when (state) {
                AttestationState.NONE -> HeaderSeverity.NEUTRAL
                AttestationState.VERIFYING -> HeaderSeverity.NEUTRAL
                // A degrade is orange (advisory) only when it's operational/transient.
                // A HARD adversarial break — tamper, replay, MITM, an unbound key — is
                // red: it must never render as the same benign advisory as an
                // out-of-date TCB note.
                AttestationState.DEGRADED -> if (degradeIsHardFailure) HeaderSeverity.FAILED else HeaderSeverity.ADVISORY
                AttestationState.OK -> when (runResult) {
                    SectionResult.FAILED -> HeaderSeverity.FAILED
                    SectionResult.FLAGGED -> HeaderSeverity.ADVISORY
                    else -> HeaderSeverity.OK
                }
            }
        }

    val headerTitle: String
        get() {
            if (state == AttestationState.VERIFYING && timedOut) return "can’t reach verifier"
            return when (state) {
                AttestationState.OK -> when (runResult) {
                    SectionResult.FAILED -> "verification failed"
                    SectionResult.FLAGGED -> "verified — with a warning"
                    else -> "end-to-end encrypted"
                }
                AttestationState.DEGRADED -> {
                    if (degradeIsHardFailure) return "verification failed"
                    // NOTHING WAS VERIFIED. attestationState degrades on
                    // `attestation == nil && attestationFetchFailed` — the report never
                    // arrived — and degradeIsHardFailure returns false there because it
                    // needs a record to find a hard break in. That combination used to
                    // land on "verified hardware": the one claim this screen exists to
                    // make, in the one state with no evidence behind it.
                    if (attestation == null) return "verification unavailable"
                    "verified hardware"
                }
                AttestationState.VERIFYING -> "verifying…"
                AttestationState.NONE -> "connection info"
            }
        }

    val headerSubtitle: String
        get() {
            if (state == AttestationState.VERIFYING && timedOut) {
                return "messages are encrypted, but the destination can’t be confirmed yet."
            }
            return when (state) {
                AttestationState.OK -> when (runResult) {
                    SectionResult.FAILED -> "we couldn’t confirm this conversation is sealed. treat it as unencrypted until it re-verifies."
                    SectionResult.FLAGGED -> "your session is sealed, but one check couldn’t be completed. read it before you trust this chat."
                    else -> "only you and the attested TEE hardware can read these messages."
                }
                AttestationState.DEGRADED -> {
                    if (degradeIsHardFailure) return "a security check failed — treat this chat as unverified until it re-verifies."
                    if (attestation == null) return "we couldn’t reach the attestation service, so none of this connection has been checked yet."
                    // Sealed, but not fully verified: an unpublished image, or a
                    // provenance trace that came back incomplete. Name the check that
                    // failed instead of retracting a guarantee that still holds.
                    if (e2eeIntact) return "your messages are still sealed to attested hardware, but one check couldn’t be completed."
                    "tamper-proof hardware verified, but E2EE is unavailable."
                }
                AttestationState.VERIFYING -> "confirming this conversation runs inside sealed hardware."
                AttestationState.NONE -> "this provider does not support hardware attestation."
            }
        }

    /**
     * Icon name for the hero header (SF Symbol names, kept as the shared
     * vocabulary; the Compose side maps them). One shield metaphor whose state
     * resolves — glyph changes with severity, not just hue.
     */
    val headerIcon: String
        get() {
            if (state == AttestationState.VERIFYING && timedOut) return "exclamationmark.triangle.fill"
            return when (state) {
                AttestationState.OK -> when (runResult) {
                    SectionResult.FAILED -> "xmark.shield.fill"
                    SectionResult.FLAGGED -> "exclamationmark.shield.fill"
                    else -> "checkmark.shield.fill"
                }
                AttestationState.DEGRADED -> {
                    if (degradeIsHardFailure) return "xmark.shield.fill"
                    // No record — the same situation as the timed-out hero above: the
                    // verifier is unreachable. A lock glyph would picture a guarantee
                    // that was never established.
                    if (attestation == null) return "exclamationmark.triangle.fill"
                    "lock.trianglebadge.exclamationmark.fill"
                }
                AttestationState.VERIFYING -> "lock.fill"
                AttestationState.NONE -> "globe"
            }
        }

    // MARK: Verification state

    /**
     * E2EE: true only if encryption is active AND the key is bound to an attested model TEE.
     * The model_attestations response includes the model TEE's own TDX quote with the
     * Ed25519 key in report_data — this proves the key belongs to genuine TEE hardware.
     */
    val e2eePassed: Boolean?
        get() {
            // Hide row entirely for non-attestation providers.
            if (state == AttestationState.NONE) return null
            // No Ed25519 key means E2EE is unavailable → show as failed.
            if (attestation?.modelEd25519PubKey == null) {
                return if (state == AttestationState.VERIFYING) null else false
            }
            // After a request: reflect actual E2EE result.
            if (lastRequestUsedE2EE == false) return false
            // Model TEE attestation is the primary check — Ed25519 key bound to a verified TDX quote.
            val mqv = attestation.modelQuoteVerification
            if (mqv != null) {
                if (!mqv.isVerified) return false
                if (attestation.e2eeKeyBoundToModelTEE == false) return false
                return true
            }
            // No model attestation available — E2EE key is unverified.
            return false
        }

    val e2eeFailDetail: String
        get() {
            if (attestation?.modelEd25519PubKey == null) return "Ed25519 encryption key unavailable"
            if (lastRequestUsedE2EE == false) return lastE2EEFailReason ?: "Encryption failed on last request"
            val mqv = attestation.modelQuoteVerification
            if (mqv != null) {
                if (!mqv.signatureValid) return "Model TDX signature verification failed"
                if (!mqv.certChainValid) return mqv.certChainError ?: "Model cert chain invalid"
                if (attestation.e2eeKeyBoundToModelTEE == false) return "Ed25519 key not found in model TDX report_data"
            }
            if (attestation.modelQuoteVerification == null) return "Model TEE attestation unavailable"
            return "Encryption target is unverified"
        }

    /** Gateway TDX quote ECDSA signature. */
    val gwTdxSignatureValid: Boolean? get() = attestation?.quoteVerification?.signatureValid

    /** Gateway TDX quote Intel certificate chain. */
    val gwCertChainValid: Boolean? get() = attestation?.quoteVerification?.certChainValid

    /** Gateway signing key is embedded in TDX report_data (binds key to hardware). */
    val keyBoundToHardware: Boolean? get() = attestation?.signingKeyBoundToHardware

    /** Model TEE TDX quote verified + Ed25519 key bound to it. */
    val modelTdxValid: Boolean? get() = attestation?.modelQuoteVerification?.isVerified

    /** E2EE key cryptographically bound to the model TEE's TDX quote report_data. */
    val e2eeKeyBound: Boolean? get() = attestation?.e2eeKeyBoundToModelTEE

    /** Whether the provider routes through a separate GPU node. */
    val hasGPUNode: Boolean
        get() {
            val p = provider ?: return false
            val inference = p.inferenceBaseUrl ?: return false
            return inference != p.openAIBaseUrl
        }

    /**
     * GPU node TDX verification. Returns true/false only when the GPU actually
     * responded with a quote. Returns null (informational) when the GPU node is
     * unreachable — this is supplementary, not a security failure, since the
     * model TEE is verified via gateway.
     */
    val gpuTdxValid: Boolean? get() = attestation?.gpuQuoteVerification?.isVerified

    /** Whether the GPU row should be shown at all. */
    val showGPURow: Boolean
        get() {
            // Show when we have GPU info from the gateway (gpuArch) or a GPU node exists.
            if (attestation == null) return false
            return hasGPUNode || attestation.gpuArch != null
        }

    val gpuTdxDetail: String
        get() {
            val gpuV = attestation?.gpuQuoteVerification ?: return "GPU node unreachable — verified via gateway"
            return when {
                gpuV.isVerified -> "TDX quote verified on device"
                !gpuV.signatureValid -> "TDX signature verification failed"
                else -> gpuV.certChainError ?: "Certificate chain invalid"
            }
        }

    /**
     * Response ECDSA signature — true if all verified, false if any mismatch, null if no data yet.
     * Gateway-trust-only responses surface the row (data exists) but never turn it green
     * on their own: with zero individually verified responses the check stays indeterminate
     * rather than claiming per-response verification that didn't happen.
     */
    val responseSigValid: Boolean?
        get() = when (responseSigState) {
            StepState.DONE -> true
            StepState.STUCK -> false
            else -> null
        }

    /**
     * Row state for the response-signatures row: null = no data yet (row hidden),
     * DONE = all individually verified, STUCK = at least one mismatch,
     * PENDING = only gateway-trust responses so far (indeterminate, not green).
     */
    val responseSigState: StepState?
        get() {
            if (verifiedResponseCount <= 0 && mismatchedResponseCount <= 0 && gatewayTrustResponseCount <= 0) return null
            if (mismatchedResponseCount > 0) return StepState.STUCK
            return if (verifiedResponseCount > 0) StepState.DONE else StepState.PENDING
        }

    /** Whether the enclave booted the manifest we're showing (the "running code" binding). null when inputs are absent. */
    val codeIdentityValid: Boolean? get() = attestation?.codeIdentityVerified

    /** Detail line for the code-identity row. */
    val codeIdentityDetail: String
        get() = when (codeIdentityValid) {
            true -> "Enclave booted exactly this manifest"
            false -> "Manifest does not match the booted measurement"
            null -> "Manifest not available to verify"
        }

    /**
     * Image-provenance row state: null = hidden (no manifest to check),
     * DONE = every image traces to a pinned near.ai workflow, STUCK = at least
     * one image unverified, LIVE = still checking.
     */
    val provenanceState: StepState?
        get() {
            if (codeIdentityValid == null) return null  // only meaningful with a manifest
            return when (imageProvenance) {
                is ManifestProvenance.AllVerified -> StepState.DONE
                is ManifestProvenance.Incomplete -> StepState.STUCK
                null -> if (state == AttestationState.VERIFYING) StepState.LIVE else StepState.PENDING
            }
        }

    val provenanceDetail: String
        get() = when (val p = imageProvenance) {
            is ManifestProvenance.AllVerified -> {
                val sidecars = if (p.thirdParty.isEmpty()) "" else " · ${p.thirdParty.size} third-party sidecar(s) pinned by manifest"
                "${p.verified.size} near.ai image(s) signed by its published workflow$sidecars"
            }
            is ManifestProvenance.Incomplete -> {
                val total = p.verified.size + p.failures.size
                "${p.failures.size} of $total near.ai image(s) unverified — ${namedFailures(p.failures)}"
            }
            null -> "Checking image provenance…"
        }

    /**
     * Nonce-echo (anti-replay) check: every fetched quote must echo the nonce
     * teemoon sent in report_data[32..64]. null = not checkable.
     */
    val nonceEchoed: Boolean? get() = attestation?.nonceEchoed

    val nonceDetail: String
        get() = when (nonceEchoed) {
            true -> "Quote was generated for this request, not replayed"
            false -> "Quote does not echo the request nonce — possible replay"
            null -> "Nonce not available to verify"
        }

    /**
     * Intel DCAP row state: null = hidden (no quote to verify), DONE = every
     * present quote verified via dcap-qvl (QE binding + TCB), STUCK = a hard
     * failure (unverified quote, no collateral, or revoked TCB), LIVE/PENDING =
     * still checking.
     */
    val dcapState: StepState?
        get() {
            if (attestation == null || attestation.intelQuote.isEmpty()) return null
            val v = dcapVerification ?: return if (state == AttestationState.VERIFYING) StepState.LIVE else StepState.PENDING
            return when {
                v.hasHardFailure -> StepState.STUCK
                v.allPresentVerified -> StepState.DONE
                else -> StepState.STUCK
            }
        }

    val dcapDetail: String
        get() {
            val dcap = dcapVerification ?: return "Checking quote with Intel PCS…"
            if (dcap.hasHardFailure) return dcap.failureReason ?: "Quote failed DCAP verification (or collateral unavailable)"
            return when (dcap.worstTcbStatus) {
                DcapTcbStatus.UP_TO_DATE -> "QE binding + TCB verified, up to date"
                DcapTcbStatus.OUT_OF_DATE -> "Genuine hardware — TCB behind on Intel platform updates"
                DcapTcbStatus.CONFIGURATION_NEEDED -> "Genuine hardware — platform configuration update needed"
                else -> "Quote verified via dcap-qvl"
            }
        }

    /**
     * NVIDIA NRAS row state: null = hidden (no GPU evidence in the record),
     * DONE = NVIDIA verdict PASS + evidence nonce matched, STUCK = failed (bad
     * verdict, nonce mismatch, or NRAS unreachable — fail-closed), LIVE/PENDING
     * = still checking.
     */
    val nrasState: StepState?
        get() {
            if (attestation?.nvidiaPayload == null) return null
            return when (nrasVerification) {
                is NrasVerification.Verified -> StepState.DONE
                // Both a genuine rejection and an unreachable check are unverified (the
                // session degrades either way); the hard-vs-soft distinction drives the
                // hero severity, not this per-row glyph.
                is NrasVerification.Failed, is NrasVerification.Inconclusive -> StepState.STUCK
                null -> if (state == AttestationState.VERIFYING) StepState.LIVE else StepState.PENDING
            }
        }

    val nrasDetail: String
        get() = when (val n = nrasVerification) {
            is NrasVerification.Verified -> "NVIDIA confirmed genuine attested GPUs for this request"
            is NrasVerification.Failed -> n.reason
            is NrasVerification.Inconclusive -> n.reason
            null -> "Checking GPU evidence with NVIDIA…"
        }

    /** Detail line for the response-signatures row, covering the gateway-trust case. */
    val responseSigDetail: String
        get() {
            if (mismatchedResponseCount > 0) {
                val total = verifiedResponseCount + mismatchedResponseCount + gatewayTrustResponseCount
                return "$mismatchedResponseCount mismatch of $total"
            }
            val parts = ArrayList<String>()
            if (verifiedResponseCount > 0) parts.add("$verifiedResponseCount verified this session")
            if (gatewayTrustResponseCount > 0) parts.add("$gatewayTrustResponseCount via gateway trust (not individually verified)")
            return if (parts.isEmpty()) "no signed responses yet" else parts.joinToString(" · ")
        }

    val allChecks: List<Boolean>
        get() {
            val checks = ArrayList<Boolean>()
            e2eePassed?.let { checks.add(it) }
            gwTdxSignatureValid?.let { checks.add(it) }
            gwCertChainValid?.let { checks.add(it) }
            keyBoundToHardware?.let { checks.add(it) }
            modelTdxValid?.let { checks.add(it) }
            e2eeKeyBound?.let { checks.add(it) }
            codeIdentityValid?.let { checks.add(it) }
            // Provenance counts only once a definitive result exists (pending → excluded).
            when (imageProvenance) {
                is ManifestProvenance.AllVerified -> checks.add(true)
                is ManifestProvenance.Incomplete -> checks.add(false)
                null -> Unit
            }
            // DCAP counts only once a definitive result exists (pending → excluded).
            dcapVerification?.let { checks.add(!it.hasHardFailure && it.allPresentVerified) }
            nonceEchoed?.let { checks.add(it) }
            // NRAS counts only once a definitive result exists (pending → excluded).
            nrasVerification?.let { checks.add(it.isVerified) }
            // GPU row is supplementary — excluded from the pass/fail banner.
            // It provides additional assurance but the model TEE via gateway
            // already proves the E2EE key belongs to genuine TEE hardware.
            responseSigValid?.let { checks.add(it) }
            return checks
        }

    val checksTotal: Int get() = allChecks.size
    val checksPassed: Int get() = allChecks.count { it }
    val allChecksPassed: Boolean get() = checksPassed == checksTotal

    /** "verified just now" / "verified 3 mins ago" / "verified 14:02", with a stale note after a failed refresh. */
    fun timestampText(now: Instant = Instant.now()): String {
        val attestation = attestation ?: return "No attestation data"
        val elapsed = now.epochSecond - attestation.fetchedAt.epochSecond
        val staleNote = if (attestationFetchFailed) " (refresh failed)" else ""
        return when {
            elapsed < 60 -> "verified just now$staleNote"
            elapsed < 3600 -> {
                val mins = (elapsed / 60).toInt()
                "verified $mins min${if (mins == 1) "" else "s"} ago$staleNote"
            }
            else -> "verified ${shortTime.format(attestation.fetchedAt)}$staleNote"
        }
    }

    /**
     * Maps a check result to a step state during progressive verification.
     * [isLiveGroup] = this group of checks is the current frontier.
     * [isFirst] = this is the first row in the group (gets the spinner).
     */
    fun resolveState(result: Boolean?, ready: Boolean, isLiveGroup: Boolean = false, isFirst: Boolean = false): StepState {
        if (state == AttestationState.VERIFYING && !ready) {
            if (timedOut) return StepState.STUCK
            return if (isLiveGroup && isFirst) StepState.LIVE else StepState.PENDING
        }
        result ?: return StepState.PENDING
        return if (result) StepState.DONE else StepState.STUCK
    }

    // MARK: - Pillars (grouped, meaning-first view)

    /**
     * The three pillars, in trust order. Empty-check pillars are dropped by the
     * sheet. Each pillar's state is derived from its checks: any failed check ⇒
     * ATTENTION; otherwise CHECKING while still verifying, else VERIFIED.
     */
    val pillars: List<AttestationPillar>
        get() = listOf(hardwarePillar, codePillar, privacyPillar).filter { it.checks.isNotEmpty() }

    private fun pillarState(checks: List<PillarCheck>): PillarState {
        if (checks.any { it.state == StepState.STUCK }) return PillarState.ATTENTION
        if (state == AttestationState.VERIFYING) return PillarState.CHECKING
        return PillarState.VERIFIED
    }

    /**
     * Maps a `Boolean?` check value to a row. null → a pending row only while
     * verifying (and only when [showWhilePending]), otherwise the row is omitted
     * so post-verification pillars don't carry blank rows.
     */
    private fun boolRow(label: String, value: Boolean?, detail: String, showWhilePending: Boolean = true): PillarCheck? =
        when (value) {
            true -> PillarCheck(label, StepState.DONE, detail)
            false -> PillarCheck(label, StepState.STUCK, detail)
            null -> if (state == AttestationState.VERIFYING && showWhilePending) PillarCheck(label, StepState.PENDING, detail) else null
        }

    /** Maps an already-resolved optional [StepState] (null = hidden) to a row. */
    private fun stepRow(label: String, stepState: StepState?, detail: String): PillarCheck? =
        stepState?.let { PillarCheck(label, it, detail) }

    private val hardwarePillar: AttestationPillar
        get() {
            val checks = listOfNotNull(
                stepRow("Intel DCAP verification", dcapState, dcapDetail),
                boolRow("TDX quote signature", gwTdxSignatureValid,
                    if (gwTdxSignatureValid == false) "ECDSA signature verification failed" else "Signed by TEE hardware, not fabricated"),
                boolRow("Intel certificate chain", gwCertChainValid,
                    if (gwCertChainValid == false) (attestation?.quoteVerification?.certChainError ?: "Chain invalid") else "Chains to Intel’s SGX Root CA"),
                boolRow("Attestation freshness", nonceEchoed, nonceDetail),
                stepRow("NVIDIA GPU attestation", nrasState, nrasDetail),
                if (showGPURow) boolRow("GPU secure enclave", gpuTdxValid, gpuTdxDetail, showWhilePending = false) else null,
            )
            val st = pillarState(checks)
            val line = when (st) {
                PillarState.VERIFIED ->
                    if (hasNVIDIA) "Genuine Intel TDX + $gpuName secure hardware, confirmed by Intel and NVIDIA."
                    else "Genuine Intel TDX secure hardware, confirmed against Intel."
                PillarState.ATTENTION -> "The hardware quote could not be fully verified."
                PillarState.CHECKING -> "Confirming this is genuine Intel TDX hardware…"
            }
            return AttestationPillar("hardware", "genuine hardware", "cpu.fill", st, line, chip(st), checks)
        }

    private val codePillar: AttestationPillar
        get() {
            val checks = listOfNotNull(
                boolRow("Running code matches manifest", codeIdentityValid, codeIdentityDetail),
                stepRow("Images built from near.ai source", provenanceState, provenanceDetail),
            )
            val st = pillarState(checks)
            val line = when (st) {
                PillarState.VERIFIED -> "The enclave booted near.ai’s published open-source code — nothing swapped in."
                PillarState.ATTENTION -> "Some running code could not be traced to near.ai’s published source."
                PillarState.CHECKING -> "Tracing the running images to near.ai’s published source…"
            }
            return AttestationPillar("code", "published code", "chevron.left.forwardslash.chevron.right", st, line, chip(st), checks)
        }

    private val privacyPillar: AttestationPillar
        get() {
            val checks = listOfNotNull(
                boolRow("End-to-end encryption", e2eePassed,
                    if (e2eePassed == false) e2eeFailDetail else "Encrypted to the model enclave’s key on this device"),
                boolRow("Model TEE verified", modelTdxValid,
                    if (modelTdxValid == false) "Model TDX signature verification failed" else "The model runs inside its own attested enclave"),
                boolRow("Encryption key bound to TEE", e2eeKeyBound,
                    if (e2eeKeyBound == false) "Ed25519 key not in model TDX report_data" else "The key was generated inside that enclave"),
                boolRow("Signing key bound to hardware", keyBoundToHardware,
                    if (keyBoundToHardware == false) "Address not in TDX report_data" else "The response-signing key lives in the enclave"),
                stepRow("Response signatures", responseSigState, responseSigDetail),
            )
            val st = pillarState(checks)
            val line = when (st) {
                PillarState.VERIFIED -> "Messages are end-to-end encrypted to this specific enclave — only it can read them."
                PillarState.ATTENTION -> privacyAttentionLine
                PillarState.CHECKING -> "Establishing the encrypted channel to the model enclave…"
            }
            return AttestationPillar("private", "private to you", "lock.fill", st, line, chip(st, verified = "encrypted"), checks)
        }

    private fun chip(st: PillarState, verified: String = "verified"): String = when (st) {
        PillarState.VERIFIED -> verified
        PillarState.ATTENTION -> "needs attention"
        PillarState.CHECKING -> "checking"
    }

    /** Human GPU name for the hardware line (from arch), defaulting generically. */
    private val gpuName: String get() = attestation?.gpuModelName ?: "NVIDIA GPU"
    private val hasNVIDIA: Boolean get() = attestation?.gpuArch != null || attestation?.nvidiaPayload != null

    /** Plain-language reason the privacy pillar is degraded, reusing the existing E2EE fail detail where it's meaningful. */
    private val privacyAttentionLine: String
        get() {
            if (attestation?.modelEd25519PubKey == null) {
                return "Encryption to the model is unavailable right now — messages use TLS to the verified enclave."
            }
            if (lastRequestUsedE2EE == false && lastE2EEFailReason != null) return lastE2EEFailReason
            return "The model’s encryption could not be fully verified."
        }

    // MARK: - Advanced evidence (raw technical detail for the advanced section)

    /** dcap-qvl verdict, e.g. "verified · TCB up to date". null if not run. */
    val dcapEvidence: String?
        get() {
            val dcap = dcapVerification ?: return null
            if (dcap.hasHardFailure) return dcap.failureReason ?: "failed verification"
            return when (dcap.worstTcbStatus) {
                DcapTcbStatus.UP_TO_DATE -> "verified · TCB up to date"
                DcapTcbStatus.OUT_OF_DATE -> "verified · TCB out of date (genuine hardware, behind on patches)"
                DcapTcbStatus.CONFIGURATION_NEEDED -> "verified · platform configuration update needed"
                DcapTcbStatus.REVOKED -> "revoked by Intel"
                DcapTcbStatus.OTHER, null -> "verified"
            }
        }

    /** Anti-replay evidence — whether the quote echoed our request nonce. */
    val nonceEvidence: String?
        get() = nonceEchoed?.let { if (it) "echoed in report_data[32:64] — generated for this request" else "mismatch — possible replay" }

    /** NVIDIA NRAS GPU attestation verdict. */
    val nrasEvidence: String?
        get() = when (val n = nrasVerification) {
            is NrasVerification.Verified -> "NVIDIA verdict PASS · evidence nonce matched"
            is NrasVerification.Failed -> "failed — ${n.reason}"
            is NrasVerification.Inconclusive -> "couldn't complete — ${n.reason}"
            null -> null
        }

    /** Image-provenance evidence — the specific source repos the images' Sigstore certificates name, falling back to the org when unknown. */
    val provenanceEvidence: String?
        get() = when (val p = imageProvenance) {
            is ManifestProvenance.AllVerified -> {
                val sidecars = if (p.thirdParty.isEmpty()) "" else " · ${p.thirdParty.size} third-party sidecar(s) pinned"
                val repos = p.verified.mapNotNull { it.sourceRepo }.toSortedSet().map { it.replace("https://github.com/", "") }
                val source = if (repos.isEmpty()) "github.com/nearai" else repos.joinToString(", ")
                "${p.verified.size} image(s) → $source$sidecars"
            }
            is ManifestProvenance.Incomplete ->
                "${p.failures.size} of ${p.verified.size + p.failures.size} unverified — ${namedFailures(p.failures)}"
            null -> null
        }

    /** Per-response signature verification mode this session. */
    val responseSigEvidence: String?
        get() {
            if (verifiedResponseCount <= 0 && mismatchedResponseCount <= 0 && gatewayTrustResponseCount <= 0) return null
            if (mismatchedResponseCount > 0) return "SHA-256 content binding + EIP-191 ecrecover — $mismatchedResponseCount mismatch"
            if (verifiedResponseCount > 0) return "SHA-256 content binding + EIP-191 ecrecover · $verifiedResponseCount verified this session"
            return "gateway trust only — signer not individually attested"
        }

    // MARK: Share report

    /**
     * Plain-text attestation report for the "Copy Report" share action.
     *
     * The session counts are passed explicitly (rather than read from the
     * stored live counts) to preserve the sheet's original behavior of
     * reporting the counts it was initialized with.
     */
    fun shareText(e2eeMessageCount: Int, verifiedResponseCount: Int, mismatchedResponseCount: Int, gatewayTrustResponseCount: Int = 0): String {
        val attestation = attestation ?: return "near.ai TEE Attestation\nNo data available."
        val fetched = mediumDateShortTime.format(attestation.fetchedAt)
        val verified = if (attestation.quoteVerification?.isVerified == true) "VERIFIED" else "NOT VERIFIED"
        val lines = arrayListOf("near.ai TEE Attestation ($verified)", "Fetched: $fetched", "")
        if (e2eeMessageCount > 0) lines.add("E2EE messages this session: $e2eeMessageCount")
        if (verifiedResponseCount > 0) lines.add("Signed responses this session: $verifiedResponseCount")
        if (mismatchedResponseCount > 0) lines.add("Signature mismatches this session: $mismatchedResponseCount")
        if (gatewayTrustResponseCount > 0) lines.add("Accepted via gateway trust (not individually verified): $gatewayTrustResponseCount")
        if (e2eeMessageCount > 0 || verifiedResponseCount > 0 || mismatchedResponseCount > 0 || gatewayTrustResponseCount > 0) lines.add("")
        attestation.quoteVerification?.let { v ->
            lines.add("Gateway TDX Signature: ${if (v.signatureValid) "Valid" else "Invalid"}")
            lines.add("Gateway Cert Chain: ${if (v.certChainValid) "Valid" else "Invalid"}")
        }
        attestation.gpuQuoteVerification?.let { v ->
            lines.add("GPU Node TDX Signature: ${if (v.signatureValid) "Valid" else "Invalid"}")
            lines.add("GPU Node Cert Chain: ${if (v.certChainValid) "Valid" else "Invalid"}")
        }
        dcapVerification?.let { dcap ->
            val verdict = if (dcap.hasHardFailure) "FAILED" else if (dcap.allPresentVerified) "Verified" else "Incomplete"
            val tcb = dcap.worstTcbStatus?.let { " (TCB: ${it.rawValue})" } ?: ""
            lines.add("Intel DCAP (dcap-qvl): $verdict$tcb")
        }
        codeIdentityValid?.let { lines.add("Code identity (manifest ↔ MRCONFIGID): ${if (it) "Verified" else "FAILED"}") }
        when (val p = imageProvenance) {
            is ManifestProvenance.AllVerified -> {
                val sidecars = if (p.thirdParty.isEmpty()) "" else ", ${p.thirdParty.size} third-party sidecar(s) digest-pinned"
                lines.add("Image provenance (Sigstore/Rekor): Verified (${p.verified.size} near.ai image(s)$sidecars)")
            }
            is ManifestProvenance.Incomplete ->
                lines.add("Image provenance (Sigstore/Rekor): INCOMPLETE (${p.failures.size} of ${p.verified.size + p.failures.size} near.ai image(s) unverified)")
            null -> Unit
        }
        nonceEchoed?.let { lines.add("Attestation freshness (nonce echo): ${if (it) "Verified" else "FAILED"}") }
        nrasVerification?.let { lines.add("NVIDIA GPU attestation (NRAS): ${if (it.isVerified) "PASS" else "FAILED"}") }
        responseSigValid?.let { lines.add("Response signatures (content binding + ecrecover): ${if (it) "Verified" else "MISMATCH"}") }
        lines.add("Checks passed: $checksPassed/$checksTotal (pending checks excluded)")
        lines.add("")
        if (attestation.mrtd.isNotEmpty()) lines.add("MRTD (Intel TDX):         ${attestation.mrtd}")
        if (attestation.composeHash.isNotEmpty()) lines.add("Compose hash (mr_config): ${attestation.composeHash}")
        attestation.gpuNodeComposeHash?.takeIf { it.isNotEmpty() }?.let { lines.add("GPU TEE compose hash:     $it") }
        attestation.modelFileHash?.takeIf { it.isNotEmpty() }?.let { lines.add("Model YAML SHA256:        $it") }
        attestation.modelEd25519PubKey?.let { lines.add("Model Ed25519 pubkey:     ${it.toHex()}") }
        attestation.signingAddress?.takeIf { it.isNotEmpty() }?.let { lines.add("Gateway signing address:  $it") }
        attestation.gpuSigningAddress?.takeIf { it.isNotEmpty() }?.let { lines.add("GPU signing address:      $it") }
        lines.add("")
        lines.add("Verify: https://cloud-api.near.ai/v1/attestation/report?nonce=manual&signing_algo=ecdsa")
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * Names the failing images and why, bounded so the line stays readable:
         * e.g. "`glm51-sgl-awq-tp4-patched` (no published attestation (GitHub
         * 404)); `mesh` (couldn't reach GitHub …) · +1 more".
         */
        fun namedFailures(failures: List<ProvenanceFailure>, limit: Int = 2): String {
            val shown = failures.take(limit).map { f ->
                val name = f.ref.image.split('/').lastOrNull { it.isNotEmpty() } ?: f.ref.image
                val why = f.reason.failureReason ?: "unverified"
                "$name ($why)"
            }
            val more = if (failures.size > limit) " · +${failures.size - limit} more" else ""
            return shown.joinToString("; ") + more
        }

        /** The actual E2EE cipher suite (matches near.ai's published v2 protocol). */
        const val CIPHER_SUITE_LABEL = "X25519 ECDH · HKDF-SHA256 · XChaCha20-Poly1305 (Ed25519→X25519)"

        /** `Date.formatted(date: .abbreviated, time: .shortened)` — the device's locale and zone. */
        val mediumDateShortTime: DateTimeFormatter
            get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault())

        /** `Date.formatted(date: .omitted, time: .shortened)`. */
        val shortTime: DateTimeFormatter
            get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
}
