// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ 01a337a4 (1.1.1 audit integ read item 8: e2eeDegradedReason: the stale-unrefreshable record is named right after the missing-attestation guard, before every other cause)
// ports: teemoon/Confidential/RunningPairCheck.swift + TrustScope.swift @ 9d21baba (1.1.1 audit cut review item 3: verifyComposeFiles gives each action log one FetchBudget)
// ports: teemoon/Confidential/ConfidentialSession+Verifiers.swift @ 01a337a4 (1.1.1 audit integ read item 5: verifyDCAP never throws: a verifier that throws leaves every quote, the listed ones too, unchecked)
// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ 01a337a4 (1.1.1 audit integ read item 3: e2eeDegradedReason: teemoon-fault lines (GPU, TLS), no-evidence, listed verdicts, the GPU own, the TLS own, in iOS order)
// ports: teemoon/Confidential/ConfidentialSession+Verifiers.swift @ 36d53c97 (a listed NVIDIA couldn't-check is ListedInconclusive)
// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ 38850f79 (pm 2026-11-20: a listed enclave's unchecked DCAP reads the A7 unfinished line; an unchecked read leg keeps the Intel line)
// ports: teemoon/Confidential/ConfidentialSession+Verifiers.swift @ 49608f08 (1.1.1 audit A10: a listed NVIDIA rejection is ListedFailed(nvidia))
// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ beb43b71 (1.1.1 audit A8: enclaveListFailedAsks)
// ports: teemoon/Confidential/ConfidentialSession+Verifiers.swift @ 976543da (1.1.1 audit A10: a listed enclave's NVIDIA rejection is recorded as the A10 line)
// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ 976543da (1.1.1 audit A10: e2eeDegradedReason names a listed enclave's DCAP, NVIDIA, action-log, boot-log and list failures; the read enclave keeps its own lines)
// ports: teemoon/Confidential/ConfidentialSession+Verdict.swift @ e2f62bcc (1.1.1: a model key with no readable quote is a hard failure; its reason is the unbound-key line)
//        teemoon/Confidential/ConfidentialSession.swift @ v1.0.6 (e2eeKey and attestationOutcome poll to their deadline)
//        teemoon/Confidential/ConfidentialSession.swift, +Verdict.swift, +Refresh.swift @ 38647a07 (S30: sendPending, pendingKind,
//        provenancePending, staleRefetchPending, provenanceUncheckedAsks, chipState, the pending confirm reasons)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.6 (adopt: the record and its verifiers in one step)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4 (a sealed session reads VERIFYING until provenance settles)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4 (provenanceSettled, with a run token)
//        teemoon/Confidential/ConfidentialSession.swift @ v1.0.6 (verdictsPending / settleVerifiers / abandonTurn; prepareTurn settles)
//        teemoon/Confidential/ConfidentialSession.swift @ v1.0.6 (verdictsPending is attested-only)
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.6 (clearDerivedState drops the verifier handles)
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.6 (directHostMissing; a failed fetch retried once a minute)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4 (e2eeDegradedReason names the directory)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4 (an inconclusive pass keeps the last verdict)
//        TEEContext / prepareTurn / finishTurn / canSeal are here since the E2EE row landed; NOT here:
//        the Xcode-preview / -DesignTour process flags. The UITEST_SEED_ATTESTATION door IS here (`uiTestSeededDegraded`,
//        the "degraded" case, and `uiTestPlantsPendingVerifier` for "verifyingPending", f2fb40b), reachable from UiTestSeeding on a
//        debuggable build.
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ ceb05738 (1.1.1 audit A3: verifyGpu checks every listed enclave's GPU evidence)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ c5e329f2 (1.1.1 audit A5: the listed scopes resolve their chat path against record.gpuNodeHost)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift, +Verifiers.swift, ConfidentialSession.swift @ 1fe69e3d (1.1.1 audit A4: listedNearScopes, listedScopeBroken, listedScopeEvidenceAbsent)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ 7814a0fd (C3a: candidatesBroken degrades and is hard; modelCandidatesListFailed asks)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ 217dc4cb (+ d2d70d45: sendPolicy's downgrade guard first; rememberConfirmation accepts it; hasConfirmed false while owed; see TierDowngrade.kt)
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The attested, end-to-end-encrypted inference session — the app's core
 * concept, as one type. Owns the attestation lifecycle for the active
 * provider, the E2EE availability state machine, and the per-session
 * verification history. What that state *means* is the verdict section
 * below; fetch is the refresh section; the four post-fetch checks are the
 * verifiers section.
 *
 * A main-scope state holder: mutate and read it from one thread (the UI's),
 * and give it that thread's [scope]. Every service it calls out to is a seam
 * on [Services], so the session runs offline in tests and degrades honestly
 * where a service is not ported yet.
 */
class ConfidentialSession(
    private val scope: CoroutineScope,
    /** ProviderStore.activeProvider — the store itself waits on credentials. */
    private val activeProviderSource: () -> Provider?,
    /** ProviderStore.credential(for:). */
    private val credentialFor: (Provider) -> String = { "" },
    private val services: Services = Services(),
    /** Where iOS writes its log lines. */
    private val warn: (String) -> Unit = {},
    /** near.ai models a request went out sealed to (item 1, TIER_DOWNGRADE.md). */
    val sealedModels: SealedModels,
) {
    /**
     * The network and verification seams. A null verifier means the service is
     * NOT WIRED (tests, and AppState before the services land): its result stays pending (null), and a missing attestation
     * fetch fails CLOSED — the record never lands, so the state reads
     * degraded rather than inventing a verdict.
     */
    class Services(
        /** near.ai's live /endpoints directory — `EndpointDirectory.shared.directBase(forModel:)`. */
        val directBase: suspend (model: String) -> String? = { NearAIModelCatalog.endpointDirectory.directBase(it) },
        /** `AttestationService.fetch(baseURL:apiKey:model:providerID:gpuNodeURL:)`. */
        val fetchRecord: (suspend (baseUrl: String, apiKey: String, model: String, providerID: String, gpuNodeUrl: String) -> AttestationRecord)? = AttestationService.recordFetcher(),
        /** `ProvenanceService.fetchModelLayerManifest(path:commit:expectedSHA256:)`. */
        val fetchModelLayerManifest: (suspend (path: String, commit: String, expectedSha256: String) -> ModelLayerFetch)? = { path, commit, sha -> ProvenanceService.default.fetchModelLayerManifest(path, commit, sha) },
        /** `ProvenanceService.verifyManifests` over (host, manifest) pairs. */
        val verifyManifests: (suspend (List<Pair<String, String>>) -> ManifestProvenance)? = { ProvenanceService.default.verifyManifests(it) },
        /** `PlaintextExposure.scoped(_:toModel:)`. */
        val scopeManifest: (inner: String, model: String?) -> String = { inner, model -> PlaintextExposure.scoped(inner, model) },
        /** `ModelArtifact.parse(fromComposeYAML:servingModel:)`. */
        val parseArtifact: (yaml: String, model: String?) -> ModelArtifact? = { yaml, model -> ModelArtifact.parse(yaml, model) },
        /** `DCAPService.verify(record:)`. */
        val verifyDcap: (suspend (AttestationRecord) -> RecordDcapVerification)? = null,
        /** `NRASService.verify(payloadJSON:expectedNonceHex:)`. */
        val verifyNras: (suspend (payloadJson: String, expectedNonceHex: String?) -> NrasVerification)? = null,
        /** `TLSAttestationVerifier.verify(directBaseURL:)`. */
        val verifyTls: (suspend (directBaseUrl: String) -> TlsAttestation)? = null,
        /** Previews and capture harnesses must not fetch (iOS reads process flags; here the host says so). */
        val skipLiveAttestation: Boolean = false,
        val now: () -> Instant = { Instant.now() },
        /**
         * near.ai's tier list, asked for again with each attestation while it has never been seen: a keyless GET of
         * /v1/models that records the tiers (NearAIModelCatalog.fetchLive). Null: not wired (tests, previews).
         */
        val fetchTiers: (suspend () -> Boolean)? = null,
        /**
         * [TIER_DEADLINE_MS] (iOS VerdictDeadline(after:)). Null only for a test of the window BEFORE the deadline that
         * runs the scheduler to idle: advanceUntilIdle would cross any deadline, however long.
         */
        val tierDeadlineMs: Long? = TIER_DEADLINE_MS,
    )

    /**
     * Attestation record for the currently active near.ai provider. null while
     * loading or when the active provider doesn't support attestation.
     *
     * READ GATE: the getter refuses to expose a record fetched for a different
     * (provider, model) than the currently active one. After three distinct
     * stale-display bugs (missed trigger, keepExisting trap, adoption race),
     * the invariant lives at the type level: no matter which refresh path or
     * task ordering slips through, a mismatched record reads as null — the UI
     * shows "verifying" and the E2EE context can never seal to a stale key.
     * Records without a model stamp (legacy/tests) gate on provider id alone.
     */
    var attestation: AttestationRecord?
        get() {
            val rec = attestationStored ?: return null
            // Design-tour fixtures plant a full verified record; the live
            // (provider, model) read gate would hide it when the preset's default
            // model differs from the fixture stamp.
            val p = activeProviderSource() ?: return null
            if (rec.providerID != p.id) return null
            val model = rec.model
            if (model != null && model != p.model) return null
            return rec
        }
        set(value) { attestationStored = value; touch(); if (value != null) activeProviderSource()?.let(::watchTier) }
    /** Backing storage; the public accessor applies the read gate. */
    private var attestationStored: AttestationRecord? = null

    /**
     * Bumps on every mutation of the session's observable state — iOS is
     * `@Observable`; Compose collects this and re-reads the snapshot. The
     * value is a counter, never the state itself.
     */
    // sentBeforeConfirmed settle state (settleDue): declared before anything that touches, since init does
    private val awaitingSettle = LinkedHashSet<String>()
    /** E2EE messages held while their record was pending: counted on a pass, never on a hard fail (countsAsE2EE). */
    private val heldE2EE = HashMap<String, Int>()
    private val failedRecords = HashSet<String>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private fun touch() { _changes.value += 1; settleDue() }

    /** Set to true when the attestation fetch fails, so the UI can stop showing a spinner. */
    var attestationFetchFailed = false
        set(value) { field = value; touch() }
    /**
     * The server answered; near.ai's directory just lists no confidential
     * host for this model. Distinct from unreachable, and retried.
     */
    var directHostMissing = false
        set(value) { field = value; touch() }
    /** When the last attestation fetch was started — the pre-send retry's clock. */
    var attestationAttemptedAt: Instant? = null
    /**
     * ConfidentialSession.SeededAttestation (iOS): a FORCED verdict for product E2E only. The trust states a journey has
     * to assert — sending blocked, a reply that didn't check out, E2EE unavailable — need a server that fails in one
     * specific way, so without this door only the happy path was ever walked. It answers [attestationState],
     * [degradeIsHardFailure] and [effectiveMismatchCount] directly; with [suppressLiveAttestation] no real record (so
     * no E2EE peer) can ever land while the verdict still says OK — the fail-closed refusal's forcing state. Only
     * UiTestSeeding sets it, only on a debuggable build launched with the uitesting flag: there is no path where a
     * real session reports a state it did not compute.
     */
    data class UiTestSeed(val state: AttestationState, val hardFailure: Boolean = false, val mismatchCount: Int = 0, val suppressLiveAttestation: Boolean = false) {
        companion object {
            /** UITEST_SEED_ATTESTATION's values, as iOS reads them (degraded and verifyingPending keep their own doors here). */
            fun of(raw: String?): UiTestSeed? = when (raw) {
                "ok" -> UiTestSeed(AttestationState.OK)
                "verifying" -> UiTestSeed(AttestationState.VERIFYING)
                "hardFailure" -> UiTestSeed(AttestationState.DEGRADED, hardFailure = true)
                "none" -> UiTestSeed(AttestationState.NONE)
                "mismatch" -> UiTestSeed(AttestationState.OK, mismatchCount = 1)
                "e2eeUnavailable" -> UiTestSeed(AttestationState.OK, suppressLiveAttestation = true)
                else -> null
            }
        }
    }
    var uiTestSeed: UiTestSeed? = null
        set(value) { field = value; if (value?.suppressLiveAttestation == true) { attestationJob?.cancel(); clearDerivedState(resetCounts = true) }; touch() }

    /** See refreshAttestation: the seeded-degraded door for captures and UI tests. */
    var uiTestSeededDegraded = false
        set(value) { field = value; touch() }
    /**
     * DEBUG seeding only: the session as a fresh process has it. The app's AppState is the process's (one engine), so
     * a seeded journey would otherwise inherit the last journey's verdicts, flags and attested context — and with the
     * same context the next refresh keeps them ("not end-to-end encrypted" carried into the next near.ai journey).
     */
    fun uiTestReset() {
        attestationJob?.cancel(); attestationJob = null
        clearDerivedState(resetCounts = true)
        attestationFetchFailed = false; directHostMissing = false; noConfidentialEndpoint = false; attestationAttemptedAt = null
        lastRequestUsedE2EE = null; lastE2EEFailReason = null; attestedContext = null
        uiTestSeededDegraded = false; uiTestPlantsPendingVerifier = false; uiTestSeed = null
        touch()
    }

    /**
     * UITEST_SEED_ATTESTATION=verifyingPending (iOS f2fb40b): no fetch, and a TLS verifier handle that never answers is
     * planted on the attested session — the state a provider switch must clear. NVIDIA, 2026-09-12: a cancelled near.ai
     * probe left behind read as "verification still running" on a plain-TLS provider. Only UiTestSeeding sets it, only
     * on a debuggable build.
     */
    var uiTestPlantsPendingVerifier = false
        // Setting it also drops what a refresh already started: AppState's init refreshes the saved setup before
        // seeding, and on a fast network a near.ai record from the previous journey landed first (Pixel, 2026-10-02).
        set(value) { field = value; if (value) { attestationJob?.cancel(); clearDerivedState(resetCounts = true) } }

    /**
     * true when the active model is classified attestable but near.ai serves
     * no confidential endpoint for it (absent from the /endpoints directory —
     * e.g. a retired model like deepseek-v3.2). It can't be attested, so rather
     * than stalling on "verifying" then failing closed, it's treated as a plain
     * non-attestable model (state NONE, no lock, no send block).
     */
    var noConfidentialEndpoint = false
        set(value) { field = value; touch() }

    /** Whether the most recent completed request used application-layer E2EE. null = no request yet. */
    var lastRequestUsedE2EE: Boolean? = null
        set(value) { field = value; touch() }
    /** Brief reason when [lastRequestUsedE2EE] is false (e.g. "Server rejected key (HTTP 421)"). */
    var lastE2EEFailReason: String? = null
        set(value) { field = value; touch() }

    /** Responses in this session whose TEE signature was verified. */
    var verifiedResponseCount = 0
        set(value) { field = value; touch() }
    /** Responses in this session with a TEE signature mismatch (even after re-fetch). */
    var mismatchedResponseCount = 0
        set(value) { field = value; touch() }
    /** Responses accepted via gateway trust only — the signer was not individually attested, so NOT counted as verified. */
    var gatewayTrustResponseCount = 0
        set(value) { field = value; touch() }
    /** Messages (sent + received) in this session that used E2EE. */
    var e2eeMessageCount = 0
        set(value) { field = value; touch() }

    /** Image-provenance result for the active attestation's manifest. null = not yet checked. A definitive Incomplete degrades. */
    var imageProvenance: ManifestProvenance? = null
        set(value) { field = value; touch() }
    /**
     * No image could be checked: the record's provenance pass settled inconclusive (GitHub 403/429, the network) with no
     * earlier verdict for this record to keep. The seal is fine — the chip and the send are unchanged; the trust screen
     * says so (trust-provenance-unchecked, the maintainer 2026-11-06).
     */
    var provenanceUnchecked: Boolean = false
        set(value) { field = value; touch() }
    /** What [imageProvenance] was computed for: an inconclusive re-check keeps it only for the same record (T2). */
    private var provenanceKey: String? = null
    /** Verdict of the model-layer (inner) compose hash check (null = not attempted). HASH_MISMATCH degrades; FETCH_FAILED is advisory. */
    var modelLayerVerification: ModelLayerVerification? = null
        set(value) { field = value; touch() }

    /**
     * The running recipe (inner compose) failed its on-device hash check against
     * the signed action log — the launched config is NOT what was pinned. An
     * adversarial integrity break, distinct from a transient fetch failure.
     * Fail-closed: this degrades the session (see [attestationState]).
     */
    val modelComposeIntegrityFailed: Boolean get() = modelLayerVerification == ModelLayerVerification.HASH_MISMATCH
    /**
     * The hash-verified model-layer (inner) compose YAML text itself — the
     * document compose-manager actually launched inside the model enclave. In
     * production THIS is the document that names the engine + E2EE proxy; the
     * outer `gpuNodeComposeManifest` is the management harness only. Ephemeral.
     */
    var modelLayerManifest: String? = null
        set(value) { field = value; touch() }
    /** The pinned model artifact parsed from that hash-verified YAML (null = not parsed / not available). Ephemeral. */
    var modelArtifact: ModelArtifact? = null
        set(value) { field = value; touch() }


    /** Real DCAP verification (dcap-qvl) outcome for the active record's quotes. null = not yet checked; a hard failure degrades. */
    var dcapVerification: RecordDcapVerification? = null
        set(value) { field = value; touch() }

    /**
     * The 1.1.0 near.ai trust scope over the GPU node's report (NearTrustScope, pm 2026-11-04): null without one. (a) or
     * (b) mismatch is a hard break and degrades like a DCAP hard failure; not bound / absent is soft, shown, never a
     * degrade. Kept for the record and DCAP verdict it was computed from: the getters below read it on every frame.
     */
    val nearScope: NearTrustScope?
        get() {
            nearScopeOverride?.let { return it }
            val att = attestation ?: return null
            val dcap = dcapVerification
            nearScopeCache?.let { (a, d, v) -> if (a === att && d === dcap) return v }
            return computeNearScope(att, dcap).also { nearScopeCache = Triple(att, dcap, it) }
        }
    private var nearScopeCache: Triple<AttestationRecord, RecordDcapVerification?, NearTrustScope?>? = null
    /** Tests only: a scope as the send-gate vectors state it, in place of one computed from a report (iOS sets trustScope). */
    internal var nearScopeOverride: NearTrustScope? = null

    /** The running revisions' files, hash-checked, for the chat path and the answering proxy; reset with the record. */
    var composeBodies: Map<DeployLog.Source, String> = emptyMap()
        set(value) { field = value; nearScopeCache = null; listedScopeCache = null; touch() }

    /** (c)'s fetches for the current record, by (commit, file); reset with the record. */
    var composeFileFetches: Map<Pair<String, String>, ComposeFileFetch> = emptyMap()
        set(value) { field = value; nearScopeCache = null; listedScopeCache = null; touch() }
    private var composeFilesJob: Job? = null
    /** The DCAP verdict is being published and (c) is about to start from it: pending across that step, so no read between counts it settled. */
    private var composeFilesStarting = false
    /**
     * (c) still fetching its pairs and their bodies: a check still running, as iOS counts trustScopeTask until TrustScope.run
     * has them (pm 2026-11-07, parity). A send in this window waits or asks, and a mark made in it does not settle.
     */
    private val composeFilesPending: Boolean get() = composeFilesStarting || composeFilesJob?.isActive == true

    /** The read enclave's scope and each listed enclave's whose action log (a) has bound: only those name anything as running. */
    private fun boundScopes(): List<NearTrustScope> =
        (listOfNotNull(nearScope) + listedNearScopes.orEmpty()).filter { it.deployLogBinding == DeployLogBinding.Bound }

    /** [boundScopes] with each one's place among the read scope and the listed ones: the key of its log's [FetchBudget]. */
    private fun boundScopesIndexed(): List<IndexedValue<NearTrustScope>> =
        (listOfNotNull(nearScope) + listedNearScopes.orEmpty()).withIndex().filter { it.value.deployLogBinding == DeployLogBinding.Bound }

    /** Fetches (c)'s pairs once (a) has bound the log, for the read enclave and every listed one (A4): before that nothing from it is named as running. */
    private fun verifyComposeFiles() {
        composeFilesJob?.cancel()
        val firstScopes = boundScopesIndexed().ifEmpty { return }
        val att = attestation
        composeFilesJob = scope.launch {
            // One budget per action log (iOS 9d21baba, 1.1.1 audit cut review item 3: each listed enclave's TrustScope.run fetched its
            // whole-file sources, pairs and bodies uncapped, so N listed enclaves multiplied the keyless raw.githubusercontent.com
            // fetches; 200 is about twice the 99 files the largest live log names). The three stages below draw on the log's one budget.
            // Over it a file is couldn't-check, never a pass and never cached. This session's fetched map is shared by every log (a file at a commit
            // is one file), so a log whose budget is spent leaves a file another log can still fetch; what no log could read is marked Failed at the end.
            val budgets = HashMap<Int, FetchBudget>()
            val overBudget = LinkedHashSet<Pair<String, String>>()
            suspend fun fetch(logAt: Int, commit: String, file: String): Boolean {
                if ((commit to file) in composeFileFetches) return true
                val budget = budgets.getOrPut(logAt) { FetchBudget() }
                val got = ProvenanceService.default.fetchComposeFile(commit, file, budget)
                if (!isActive || attestation !== att) return false
                if (got == ComposeFileFetch.Failed && budget.spent()) { overBudget += commit to file; return true }
                composeFileFetches = composeFileFetches + ((commit to file) to got)
                return true
            }
            // Audit's rule A (2026-11-06): every newest whole file first — the walk reads its service names from them,
            // and the whole log is walked — then the reported pairs, newest first, up to the cap; the rest "not fetched".
            for ((at, sc) in firstScopes) for (src in sc.wholeFileSources.distinct()) if (!fetch(at, src.commit, src.file)) return@launch
            val walked = boundScopesIndexed().ifEmpty { return@launch }
            for ((at, sc) in walked) for ((commit, file) in ComposeFiles.toFetch(sc.runningServices).distinct()) if (!fetch(at, commit, file)) return@launch
            // The chat path and the answering proxy read the running revisions' bodies (iOS b3d90fac, 4d2a41e3), the read enclave's and every bound listed
            // one's (A5: a list entry carries no host, so each resolves its path against the host the app connects to, att.gpuNodeHost)
            val bodies = HashMap<DeployLog.Source, String>()
            var any = false
            for ((at, sc) in boundScopesIndexed()) for (src in sc.runningServices.mapNotNull(ChatPath::sourceOf).distinct()) {
                any = true
                if (src !in bodies) ProvenanceService.default.fetchComposeBody(src, budgets.getOrPut(at) { FetchBudget() })?.let { bodies[src] = it }
                if (!isActive || attestation !== att) return@launch
            }
            // A file no log had budget left for: couldn't-check, so it reads inconclusive, not as still running.
            composeFileFetches = composeFileFetches + overBudget.filter { it !in composeFileFetches }.associateWith { ComposeFileFetch.Failed }
            if (any) composeBodies = bodies
        }.also { it.invokeOnCompletion { touch() } }   // the end of the pending window is a change: the gate and settleDue re-read
    }

    private fun computeNearScope(att: AttestationRecord, dcap: RecordDcapVerification?): NearTrustScope? {
        val report = att.gpuNodeReport ?: return null
        val nonce = att.gpuNonce ?: return null
        return NearTrustScope.fromDcap(report, nonce, att.gpuIntelQuote, dcap?.gpu, dcap?.manager, composeFileFetches, composeBodies, att.gpuNodeHost)
    }

    /**
     * The read enclave's trust scope on each enclave near.ai listed, in list order (iOS listedTrustScopes, 1.1.1 audit A4): every
     * listed enclave holds the sealed key. Null until the DCAP pass has answered for every listed quote and its compose manager's,
     * and when there is no list. Kept for the record and DCAP verdict it was computed from, like [nearScope].
     */
    val listedNearScopes: List<NearTrustScope>?
        get() {
            val att = attestation ?: return null
            val dcap = dcapVerification ?: return null
            val listed = att.modelCandidates?.takeIf { it.enclaves.isNotEmpty() } ?: return null
            if (dcap.listed.size != listed.enclaves.size || dcap.listedManagers.size != listed.enclaves.size) return null
            listedScopeCache?.let { (a, d, v) -> if (a === att && d === dcap) return v }
            return ModelCandidates.trustScopes(listed, dcap, composeFileFetches, composeBodies, att.gpuNodeHost).also { listedScopeCache = Triple(att, dcap, it) }
        }
    private var listedScopeCache: Triple<AttestationRecord, RecordDcapVerification, List<NearTrustScope>>? = null

    /** A listed enclave's action log, boot log or running file contradicts its hardware: evidence, as the read one's (A4). */
    private val listedScopeBroken: Boolean get() = listedNearScopes?.any { it.severity == NearTrustScope.Severity.HARD } == true

    /** A listed enclave sent no compose-manager quote or no boot log: couldn't check, soft, never clean (A4). */
    private val listedScopeEvidenceAbsent: Boolean get() = listedNearScopes?.any { it.listedEvidenceAbsent } == true

    private val nearScopeBroken: Boolean get() = nearScope?.severity == NearTrustScope.Severity.HARD
    /** NVIDIA NRAS outcome for the record's GPU evidence. null = not yet checked or no GPU evidence; Failed degrades. */
    var gpuAttestation: NrasVerification? = null
        set(value) { field = value; touch() }
    /** TLS-attestation outcome. null = not yet checked; Failed degrades; NotPerformed is neutral. */
    var tlsAttestation: TlsAttestation? = null
        set(value) { field = value; touch() }

    private var provenanceJob: Job? = null
    /**
     * False from the moment a provenance pass starts until it has written its
     * result (or given up as inconclusive). While false with a job alive, a
     * session that is otherwise sealed reads VERIFYING, not OK — so the header
     * never goes green and then orange (1102bc8: green at 1.4 s, orange at 4.5 s).
     */
    var provenanceSettled = true
        private set(value) { field = value; touch() }
    /** Identifies the pass that owns [provenanceSettled]: a cancelled pass finishing late must not mark a newer one settled. */
    private var provenanceRun = Any()
    private var dcapJob: Job? = null
    private var nrasJob: Job? = null
    private var tlsJob: Job? = null
    internal var attestationJob: Job? = null

    /**
     * The (provider, model) the current attestation-derived state belongs to.
     * A change means a real switch → every derived field must be reset so the
     * previous model's name/images/verdict can't bleed into the new selection.
     */
    internal var attestedContext: String? = null

    init {
        // a tier list that lands from anywhere (the catalogue, the heal, the keyless load below) re-reads the verdict
        val me = java.lang.ref.WeakReference(this)
        NearAIModelCatalog.onTiersRecorded { me.get()?.let { s -> s.scope.launch { s.touch() } } }
        refreshAttestation()
    }

    // MARK: Verdict deadline (teemoon-ios VerdictDeadline.swift @ 83b8d527, the tier half)
    // The model: teemoon-ios formal/TeemoonTrust.tla, TierTimeout with fairness (liveness: TeemoonTrust_tier.cfg; safety:
    // TeemoonTrust_tier_safety.cfg); MAPPING's TierResolve row is the keyless fetch. The maintainer 2026-11-14: the 20 s caps the
    // tier ONLY — every other leg keeps its own bounded timeout (iOS's commit also caps them; not ported, by that call).

    /** The model whose tier is being watched, and the one still unknown at the deadline (can't check, soft). */
    private var tierModel: String? = null
    private var tierGaveUpOn: String? = null
    private var tierLoadJob: kotlinx.coroutines.Job? = null
    private var tierTimerJob: kotlinx.coroutines.Job? = null

    /**
     * near.ai's tier was still unknown at [TIER_DEADLINE_MS]: can't check, never "verifying…" forever. Soft — out of
     * [verdictsPending], the send asks — and it ends the unpublished-image exemption (TLC found a given-up tier beside
     * an unpublished image waved through, iOS CouldntCheckAsks).
     */
    val tierGaveUp: Boolean
        get() {
            val provider = activeProviderSource() ?: return false
            if (ProviderCapability.ATTESTATION !in provider.capabilities) return false
            return tierGaveUpOn == provider.model && NearAIModelCatalog.confidentiality(provider.model) == NearAIModelCatalog.Confidentiality.UNKNOWN
        }

    /**
     * Loads near.ai's tier list for an unknown tier and starts its deadline. Independent of a key, the heal and UI-test
     * seeding: without it the tier stays unknown and the chat reads "verifying…" forever (the 1.1.0 regression).
     */
    private fun watchTier(provider: Provider) {
        if (ProviderCapability.ATTESTATION !in provider.capabilities) return
        val model = provider.model
        if (NearAIModelCatalog.confidentiality(model) != NearAIModelCatalog.Confidentiality.UNKNOWN) return
        if (tierModel != model) {
            tierLoadJob?.cancel(); tierTimerJob?.cancel(); tierLoadJob = null; tierTimerJob = null
            tierGaveUpOn = null; tierModel = model
        }
        val load = services.fetchTiers
        if (tierLoadJob == null && load != null) tierLoadJob = scope.launch {
            for (attempt in 0..TIER_RETRY_DELAYS_MS.size) {
                if (attempt > 0) delay(TIER_RETRY_DELAYS_MS[attempt - 1])
                if (runCatching { load() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; false }) { touch(); break }
                warn("[tier] near.ai tier list attempt ${attempt + 1} failed")
            }
            if (tierModel == model) tierLoadJob = null
        }
        // Once given up, a re-verify reloads the list but the chip does not go back to "verifying…".
        val deadline = services.tierDeadlineMs ?: return
        if (tierTimerJob != null || tierGaveUpOn == model) return
        tierTimerJob = scope.launch {
            delay(deadline)
            if (tierModel == model && NearAIModelCatalog.confidentiality(model) == NearAIModelCatalog.Confidentiality.UNKNOWN) {
                warn("[tier] $model still unknown after the deadline — can't check")
                tierGaveUpOn = model; touch()
            }
            tierTimerJob = null
        }
    }

    // MARK: Request lifecycle

    /** The provider this session is currently attesting. */
    val activeProvider: Provider? get() = activeProviderSource()

    /** Key the TLS verifier sends to the model's direct host. */
    fun credential(forProvider: Provider): String = credentialFor(forProvider)

    // MARK: TEE context (ConfidentialSession.swift 178–203)

    /** Whether a request can be sealed right now: an attesting provider, a gated record, and a usable peer. */
    val canSeal: Boolean get() = currentPeerExists()

    /** The context for one attested request — the only place the derivation lives. */
    fun currentTeeContext(): TeeContext? = currentTeeContext(apiKey = null)

    private fun currentTeeContext(apiKey: String?): TeeContext? {
        val provider = activeProviderSource() ?: return null
        if (ProviderCapability.ATTESTATION !in provider.capabilities) return null
        val record = attestation ?: return null
        // near.ai only: the transport arms the model through this when it seals a request (iOS 6c364e33)
        return TeeContext.from(provider, apiKey ?: credentialFor(provider), record, warn)?.also { if (provider.isNearAI) it.sealedModels = sealedModels }
    }

    /**
     * Whether the current context carries an E2EE peer: the same derivation, without the API key. The peer comes from
     * the record alone, and the chip, the alert and the composer ask this on every recomposition — each ask decrypted
     * the key from the Android Keystore on the main thread (1.5 s across one chat open, verification and a send on the
     * Pixel 10 Pro, 2026-11-07). Only a request reads the key.
     */
    private fun currentPeerExists(): Boolean {
        // TeeContext.from's own conditions, and the model key's conversion, without making the peer: the chip, the alert
        // and the composer read canSeal on every recomposition, and each read drew a fresh random client key (E2EE.kt).
        // A SEND still derives its own peer in prepareTurn (pm 2026-11-14: one peer per send, never per record).
        val provider = activeProviderSource() ?: return false
        if (ProviderCapability.ATTESTATION !in provider.capabilities || provider.openAIBaseUrl == null) return false
        val key = attestation?.modelEd25519PubKey ?: return false
        return E2EEPeer.keyUsable(key)
    }

    /**
     * One turn: refresh a stale record, wait for the E2EE key if this provider
     * attests, derive the context, and open the bookkeeping. The view model
     * must not write E2EE fields itself.
     */
    suspend fun prepareTurn(place: Provider? = null): TeeContext? {
        // The turn was composed for [place]; a switch since then must not open a request on the provider now selected
        // (T3, 2026-11-06: the gate read the new provider while the turn went to the old one). No context: an
        // attested place then refuses before the wire (ChatViewModel.mustRefuseUnsealedSend).
        if (place != null && !ownsTurn(place)) { warn("[turn] ${place.id} is no longer selected — opening nothing"); return null }
        refreshAttestationIfStale()
        val provider = activeProviderSource()
        if (provider != null && ProviderCapability.ATTESTATION in provider.capabilities) {
            e2eeKey(waitingUpToMs = 15_000)
            // A verdict still in flight reads as neutral in `attestationState`,
            // so a send in that window went out on an unsettled gate — on the
            // phone, every other message to a host whose TLS check fails
            // (iOS, 2026-09-09). Settle first; the caller re-reads `sendPolicy`.
            settleVerifiers(waitingUpToMs = verifierSettleTimeoutMs)
        }
        val context = currentTeeContext()
        beginRequest(expectingE2EE = context?.e2eePeer != null)
        return context
    }

    /** How long [prepareTurn] holds a send for the DCAP, GPU and TLS verdicts of a refresh that is still running. Tests shorten it. */
    var verifierSettleTimeoutMs: Long = 15_000

    /**
     * The first record has not arrived: the only state in which a silent
     * verifier is news. Once a record is in, VERIFYING is the provenance
     * sweep, and a slow sweep is not an unreachable verifier.
     */
    val awaitsFirstRecord: Boolean get() = attestationState == AttestationState.VERIFYING && attestation == null

    /**
     * What a send waits for in [prepareTurn] (S30, the maintainer 2026-11-07: "the user feels like the e2ee is theater if
     * status show verifying and the message still goes out"; iOS 38647a07). HARDWARE — a verdict, a stale record's
     * refetch, the near.ai tier — asks "still checking near.ai's hardware" at the settle timeout; PROVENANCE alone asks
     * its own question. formal/ NothingPendingAtSend.
     */
    enum class PendingKind { HARDWARE, PROVENANCE }

    val pendingKind: PendingKind? get() = if (verdictsPending) PendingKind.HARDWARE else if (provenancePending) PendingKind.PROVENANCE else null

    /**
     * Image provenance in flight for the current record. Never drop it from [sendPending]: the chip reads verifying
     * until it answers (formal/ ChipNeverVerifyingAtSend).
     */
    val provenancePending: Boolean
        get() {
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return false
            return imageProvenance == null && !provenanceSettled && provenanceJob != null
        }

    /** The current attestation fetch has not finished; read by [staleRefetchPending]. */
    var attestationFetchInFlight = false
        private set(value) { field = value; touch() }
    private var attestationAttempt = 0

    /**
     * A record past [ATTESTATION_MAX_AGE_SECONDS] whose refetch is in flight: a send now would seal to the old key while
     * the new record's checks are about to run — the Pixel's 15:07:30 send, which went out at once and left the chip on
     * "verifying" for its whole reply.
     */
    val staleRefetchPending: Boolean
        get() {
            if (!attestationFetchInFlight || attestationJob?.isCancelled != false) return false
            val record = attestation ?: return false
            return services.now().epochSecond - record.fetchedAt.epochSecond > ATTESTATION_MAX_AGE_SECONDS
        }

    /** Every pending kind: the gate, the marks and the chip read this, never [verdictsPending] alone. */
    val sendPending: Boolean get() = pendingKind != null

    /**
     * The provenance lookup ended inconclusive (T2b: GitHub or Docker Hub unreachable or rate-limited, no verdict) and
     * nothing else degrades: the chip keeps its look and the send asks "couldn't confirm near.ai's software" (S30).
     */
    val provenanceUncheckedAsks: Boolean
        get() = provenanceUnchecked && imageProvenance == null && attestation != null &&
            attestationState != AttestationState.DEGRADED && attestationState != AttestationState.NONE

    /**
     * What the chip reads; the gate reads [attestationState]. Once "send anyway" answered a pending ask for this record
     * a send may leave while it runs, so the chip must not read verifying (formal/ ChipNeverVerifyingAtSend): hardware
     * pending reads "encrypted · hardware not confirmed", provenance alone the T2b look.
     */
    val chipState: AttestationState
        get() {
            val state = attestationState
            if (state != AttestationState.VERIFYING) return state
            val kind = pendingKind ?: return state
            if (!hasConfirmed(pending = true)) return state
            return if (kind == PendingKind.HARDWARE) AttestationState.DEGRADED else AttestationState.OK
        }

    /**
     * The title's "encrypted · hardware not confirmed" (trust-caption-sealed): a soft state that asks, or a pending one
     * answered with "send anyway", whose send is still sealed and whose last request didn't go out plain.
     */
    val sealedButHardwareUnconfirmed: Boolean
        get() {
            val asksAboutHardware = sendPolicy == TrustSendPolicy.CONFIRM && !provenanceUncheckedAsks
            val answeredWhileChecking = chipState == AttestationState.DEGRADED && attestationState == AttestationState.VERIFYING
            return (asksAboutHardware || answeredWhileChecking) && softButSealed && lastRequestUsedE2EE != false
        }

    /**
     * The hardware kind of pending: a DCAP, GPU (NRAS), TLS or trust-scope (compose files) verdict not in yet, a stale
     * record's refetch, or the near.ai tier still unknown (S30). Provenance is [provenancePending].
     */
    val verdictsPending: Boolean
        get() {
            // Only an attested send has verdicts to wait for. A plain-TLS provider
            // (NVIDIA, iOS 2026-09-12) was refused with "verification is still
            // running" because a verifier cancelled on the previous near.ai
            // session still counted here.
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return false
            return (dcapJob != null && dcapVerification == null) ||
                (nrasJob != null && gpuAttestation == null) ||
                (tlsJob != null && tlsAttestation == null) ||
                composeFilesPending ||
                staleRefetchPending ||
                (NearAIModelCatalog.confidentiality(provider.model) == NearAIModelCatalog.Confidentiality.UNKNOWN && !tierGaveUp)
        }

    /** Intel's DCAP verdict has been asked and has not answered (an enclave's seal waits on it: trust-pending-state). */
    val dcapPending: Boolean get() = dcapJob != null && dcapVerification == null

    /** The TLS check has been asked and has not answered (the transport row shows it as checking: trust-pending-state Q2). */
    val tlsPending: Boolean get() = tlsJob != null && tlsAttestation == null

    /**
     * Waits until no hard-block-capable verdict is pending, or [waitingUpToMs].
     * Counted in polls rather than read off a wall clock, so a virtual-time test
     * waits the same bounded number of steps a phone does.
     */
    suspend fun settleVerifiers(waitingUpToMs: Long) {
        repeat((waitingUpToMs / SETTLE_POLL_MS).toInt().coerceAtLeast(1)) {
            if (!sendPending) return
            delay(SETTLE_POLL_MS)
        }
    }

    /**
     * Nothing was sent: a refusal before the wire. Must not read as an
     * unsealed request — `beginRequest(expectingE2EE = false)` followed by an
     * outcome did, and once the key arrived `degradeIsHardFailure` called it
     * plaintext-went-out, a hard block only a provider switch cleared.
     */
    fun abandonTurn(reason: String?) {
        lastRequestUsedE2EE = null
        lastE2EEFailReason = reason
    }

    /**
     * Optimistically records that a request is starting. [expectingE2EE]
     * reflects whether an E2EE peer was available at send time; the actual
     * outcome arrives via [recordRequestOutcome].
     */
    fun beginRequest(expectingE2EE: Boolean) {
        lastRequestUsedE2EE = expectingE2EE
        lastE2EEFailReason = null
    }

    /** Whether [place] — the provider and model a turn was sent to — is still the one selected. */
    fun ownsTurn(place: Provider): Boolean = activeProviderSource()?.let { it.id == place.id && it.model == place.model } == true

    /** What a failed request reports back: the HTTP status, if any, and its user-facing line. */
    data class RequestError(val httpStatus: Int?, val userMessage: String)

    /**
     * Records the actual outcome of a completed request: whether E2EE was
     * active on the wire ([isE2EEActive], null when no request completed), the
     * session message count, and a human-readable reason when E2EE failed.
     */
    fun recordRequestOutcome(isE2EEActive: Boolean?, error: RequestError?) {
        if (isE2EEActive != null) {
            lastRequestUsedE2EE = isE2EEActive
            // one sent + one received; a reply sent while its record was still being checked counts only once that
            // record passes, and never once it failed (SentBeforeConfirmed.countsAsE2EE)
            val key = attestation?.let(::recordKeyOf)
            if (isE2EEActive) when {
                key != null && key in failedRecords -> Unit
                key != null && key in awaitingSettle -> heldE2EE[key] = (heldE2EE[key] ?: 0) + 2
                else -> e2eeMessageCount += 2
            }
        }
        if (lastRequestUsedE2EE != true && error != null) {
            lastE2EEFailReason = error.httpStatus?.let { "the server rejected the request (http $it)." } ?: error.userMessage
        }
    }

    /** Records the outcome of one response's signature verification in the session history counters. */
    fun recordVerification(verification: ResponseVerification) {
        when (verification) {
            is ResponseVerification.Verified -> verifiedResponseCount += 1
            is ResponseVerification.Unverified -> when (verification.reason) {
                is ResponseVerification.UnverifiedReason.GatewayTrustOnly -> gatewayTrustResponseCount += 1
                is ResponseVerification.UnverifiedReason.SignatureMismatch -> mismatchedResponseCount += 1
                is ResponseVerification.UnverifiedReason.ContentMismatch -> mismatchedResponseCount += 1
                else -> Unit
            }
        }
    }

    /**
     * Close the turn. Outcome and (optional) signature check are recorded here
     * so the view-model does not increment counters.
     */
    fun finishTurn(isE2EEActive: Boolean?, error: RequestError?, verification: ResponseVerification?, place: Provider? = null) {
        // A turn that ends after a switch books nothing on the provider now selected: B never earned A's "E2EE used",
        // its message count or its verified responses (T3, 2026-11-06).
        if (place != null && !ownsTurn(place)) { warn("[turn] ${place.id} finished after a switch — not booked"); return }
        recordRequestOutcome(isE2EEActive, error)
        verification?.let { recordVerification(it) }
    }

    // MARK: Awaitable operations

    /**
     * The model's Ed25519 E2EE key, waiting up to [timeoutMs] for the in-flight
     * attestation fetch (which retries with backoff — the model TEE may be
     * cold-starting). Returns null if the key is still unavailable.
     */
    suspend fun e2eeKey(waitingUpToMs: Long): ByteArray? {
        attestation?.modelEd25519PubKey?.let { return it }
        // Poll, never `attestationJob?.join()`: a hung fetch would outlast the deadline (iOS ebc9bdb; found here).
        val deadline = services.now().toEpochMilli() + waitingUpToMs
        while (services.now().toEpochMilli() < deadline) {
            attestation?.modelEd25519PubKey?.let { return it }
            delay(250)
        }
        return attestation?.modelEd25519PubKey
    }

    /**
     * Waits (up to [waitingUpToMs]) until the current refresh produced a record
     * or failed, so callers can react to the outcome instead of polling fields.
     */
    suspend fun attestationOutcome(waitingUpToMs: Long) {
        val deadline = services.now().toEpochMilli() + waitingUpToMs
        while (services.now().toEpochMilli() < deadline) {
            if (attestation != null || attestationFetchFailed) return
            delay(250)
        }
    }

    // MARK: Attestation lifecycle (ConfidentialSession+Refresh.swift)

    /**
     * Cancels any in-flight fetch and starts a new one if the active provider
     * supports attestation. When [keepExisting] is true, the current record
     * stays visible while the fresh one loads (the "verify now" button, so the
     * sheet doesn't flash to a loading state).
     */
    fun refreshAttestation(keepExisting: Boolean = false) {
        // Previews and capture harnesses must never touch the network: the
        // attestation pipeline (gateway + racing model fetch, Intel PCS,
        // GitHub, NVIDIA) would hang the sandbox, and a near.ai outage during a
        // tour run stamped "(refresh failed)" onto the verified-ladder screenshot.
        if (services.skipLiveAttestation) return
        // The DEBUG door iOS has (UITEST_SEED_ATTESTATION=degraded): no fetch, the record stays empty and the fetch
        // reads as failed, so the state is DEGRADED (soft) and the first send asks. Only UiTestSeeding sets it, only
        // on a debuggable build.
        if (uiTestSeededDegraded) { attestationJob?.cancel(); attestation = null; attestationFetchFailed = true; touch(); return }
        // e2eeUnavailable: no live fetch, so no Ed25519 key and no E2EE peer, while the seeded verdict answers OK
        if (uiTestSeed?.suppressLiveAttestation == true) { attestationJob?.cancel(); touch(); return }
        if (uiTestPlantsPendingVerifier) {
            if (tlsJob == null && activeProviderSource()?.let { ProviderCapability.ATTESTATION in it.capabilities } == true)
                tlsJob = scope.launch { delay(600_000) }
            touch(); return
        }
        attestationJob?.cancel()
        attestationFetchInFlight = false
        attestationAttempt += 1
        attestationFetchFailed = false
        // One refresh's answer, not the session's: a later refresh that fails must read DEGRADED, not NONE (T5).
        noConfidentialEndpoint = false
        // A genuine provider *or model* switch must reset every piece of
        // attestation-derived state — otherwise the previous model's name,
        // images, drift and verdict bleed into the new selection. This clear
        // fires on a context change REGARDLESS of keepExisting: keepExisting
        // only preserves state for the SAME context (re-verify, staleness,
        // foreground refreshes). Gating it on !keepExisting was a trap — the
        // first keepExisting refresh after a switch (the re-verify button, the
        // pre-send staleness check) recorded the new context without clearing,
        // so no later refresh ever saw a change, and the old model's state
        // stuck permanently (observed: GLM-5.1 → 5.2 still showing 5.1).
        val provider = activeProviderSource()
        provider?.let(::watchTier)
        val context = provider?.let { "${it.id}|${it.model}" }
        warn("[refresh] context ${attestedContext ?: "nil"} -> ${context ?: "nil"} keepExisting=$keepExisting")
        if (context != attestedContext) clearDerivedState(resetCounts = true)
        attestedContext = context
        if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) {
            attestation = null
            return
        }
        val base = provider.openAIBaseUrl ?: run { attestation = null; return }
        val provID = provider.id
        val apiKey = credentialFor(provider)
        directHostMissing = false
        attestationAttemptedAt = services.now()
        val attempt = attestationAttempt
        attestationFetchInFlight = true
        attestationJob = scope.launch {
          try {
            // Adopt results only while this job still speaks for the CURRENT
            // (provider, model): a cancelled-but-completing fetch from the
            // previous selection must never write its record back over the
            // fresh state (the second half of the switch-staleness bug — the
            // context clear alone can't stop an in-flight task that is already
            // past its last cancellation checkpoint).
            try {
                // near.ai's live /endpoints directory is the only host map:
                // without the model's direct host the model-enclave manifest is
                // never fetched and the E2EE key cannot bind to the node.
                val gpuNodeUrl = services.directBase(provider.model)
                if (!isActive || attestedContext != context) return@launch
                if (gpuNodeUrl == null) {
                    // See [outcomeWhenNoDirectHost].
                    // NONE — "ordinary model, nothing to verify" — is honest ONLY
                    // for a tier that claims no encryption: proxied, or attested
                    // third-party, neither of which has a confidential endpoint by
                    // design.
                    //
                    // An OWN-FLEET model with no host is unexplained: a cold
                    // directory, a failed fetch, or a gap in the published map.
                    // All three mean "couldn't check", and NONE there sends in the
                    // clear under a row that says end-to-end encrypted. Degrade
                    // instead, so the send asks first.
                    val outcome = outcomeWhenNoDirectHost(provider.model)
                    if (!isActive || attestedContext != context) return@launch
                    if (outcome == MissingHostOutcome.DEGRADED) {
                        warn("[refresh] no direct host for own-fleet model=${provider.model} — degraded, not NONE")
                        attestation = null
                        attestationFetchFailed = true
                        directHostMissing = true
                        return@launch
                    }
                    noConfidentialEndpoint = true
                    attestation = null
                    return@launch
                }
                noConfidentialEndpoint = false
                val fetch = services.fetchRecord
                    ?: throw IllegalStateException("AttestationService is not ported — no record can be fetched; failing closed")
                warn("[refresh] fetching model=${provider.model} gpuNode=$gpuNodeUrl")
                val record = fetch(base, apiKey, provider.model, provID, gpuNodeUrl)
                if (!isActive || attestedContext != context || uiTestSeededDegraded) {
                    warn("Discarding stale attestation result (context switched mid-fetch)")
                    return@launch
                }
                warn("[refresh] adopting record model=${record.model ?: "nil"} composePath=${record.modelComposePath ?: "nil"}")
                adopt(record, provider, gpuNodeUrl)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("Attestation fetch failed: $e")
                if (!isActive || attestedContext != context) return@launch
                // Keep the cached record for E2EE — a stale key is better than plaintext.
                if (attestation?.providerID == provID) warn("Keeping cached attestation")
                attestationFetchFailed = true
            }
          } finally { if (attestationAttempt == attempt) attestationFetchInFlight = false }
        }
    }

    /**
     * Resets all attestation-derived display state on a provider/model switch
     * so nothing from the previous selection bleeds into the new one. Cancels
     * the in-flight verify jobs too. Counts are per-context, so they reset on a
     * real switch but not on an in-place re-verify (keepExisting).
     */
    fun clearDerivedState(resetCounts: Boolean) {
        provenanceJob?.cancel()
        // Cancel AND drop the handles: a cancelled verifier never answers, and
        // a handle with no answer reads as a verdict still pending.
        dcapJob?.cancel(); dcapJob = null
        composeFilesJob?.cancel(); composeFilesJob = null; composeFileFetches = emptyMap(); composeBodies = emptyMap()
        nrasJob?.cancel(); nrasJob = null
        tlsJob?.cancel(); tlsJob = null
        attestation = null
        noConfidentialEndpoint = false
        directHostMissing = false
        modelArtifact = null
        imageProvenance = null
        provenanceUnchecked = false
        modelLayerVerification = null
        modelLayerManifest = null
        dcapVerification = null
        gpuAttestation = null
        tlsAttestation = null
        lastRequestUsedE2EE = null
        lastE2EEFailReason = null
        if (resetCounts) {
            verifiedResponseCount = 0
            mismatchedResponseCount = 0
            gatewayTrustResponseCount = 0
        }
    }

    /**
     * Refetches attestation if the current record is older than
     * [ATTESTATION_MAX_AGE_SECONDS] or if the Ed25519 key is missing (e.g. the
     * model TEE was cold-starting during the initial fetch). Called before each
     * generation to keep the Ed25519 key fresh.
     */
    fun refreshAttestationIfStale() {
        val record = attestation ?: run {
            // A failed fetch is retried, at most once a minute: a directory
            // that lagged the catalogue, or a server that was down, must not
            // stay degraded until a provider switch (iOS b2aeee9).
            val last = attestationAttemptedAt
            if (attestationFetchFailed && (last == null || services.now().epochSecond - last.epochSecond > ATTESTATION_RETRY_INTERVAL_SECONDS))
                refreshAttestation()
            return
        }
        val age = services.now().epochSecond - record.fetchedAt.epochSecond
        val keyMissing = record.modelEd25519PubKey == null
        if (age > ATTESTATION_MAX_AGE_SECONDS || keyMissing) {
            warn("Attestation refetch: ${if (keyMissing) "Ed25519 key missing" else "stale (${age}s old)"}")
            refreshAttestation(keepExisting = true)
        }
    }

    // MARK: The four post-fetch checks (ConfidentialSession+Verifiers.swift)

    /**
     * Verifies image provenance for the record's manifests (async, off the
     * attestation fetch). Fail-closed: any fetch/verify failure yields an
     * Incomplete result, which degrades the security state. Skipped when there
     * is no manifest to check.
     */
    fun verifyImageProvenance(record: AttestationRecord) {
        provenanceJob?.cancel()
        // A transient pass keeps this, for the same record only; only evidence replaces it. A re-verify that landed
        // on another node showed the old node's digests (T2, 2026-11-06; iOS provenanceKey).
        val key = provenanceKey(record)
        val previous = if (provenanceKey == key) imageProvenance else null
        imageProvenance = null
        provenanceUnchecked = false
        provenanceKey = key
        modelLayerVerification = null
        modelLayerManifest = null
        modelArtifact = null
        provenanceSettled = true
        // Both attested container sets: the gateway CVM's manifest and the
        // model node's own manifest (the enclave that reads plaintext).
        val manifests = listOf("gateway" to (record.composeManifest ?: ""), "model" to (record.gpuNodeComposeManifest ?: ""))
            .filter { it.second.isNotEmpty() }
        if (manifests.isEmpty() && record.modelComposeCommit == null) return
        val verifyManifests = services.verifyManifests ?: return   // ProvenanceService not ported: stays pending
        provenanceSettled = false
        val run = Any()
        provenanceRun = run
        provenanceJob = scope.launch {
          try {
            val all = ArrayList(manifests)
            var layerVerification: ModelLayerVerification? = null
            // The inference layer: fetch the model YAML at the attested
            // commit/path and hash-check it against the pinned value — extending
            // per-image provenance one level down. The three outcomes are
            // distinct: a HashMismatch is fail-closed (degrades via
            // modelComposeIntegrityFailed); a FetchFailed is transient (advisory
            // only, never a tamper accusation).
            val path = record.modelComposePath; val commit = record.modelComposeCommit; val sha = record.modelFileHash
            val fetchLayer = services.fetchModelLayerManifest
            if (path != null && commit != null && sha != null && fetchLayer != null) {
                when (val fetched = fetchLayer(path, commit, sha)) {
                    is ModelLayerFetch.Verified -> {
                        layerVerification = ModelLayerVerification.VERIFIED
                        // Scope a COMBINED node's compose to the REQUESTED model for
                        // display: near.ai reuses one node for several models, but the
                        // E2EE proxy routes YOUR request only to YOUR model's server,
                        // so the co-located servers never receive your plaintext.
                        // Hash verification already passed on the FULL attested inner;
                        // the scoped view is a subset of that same document.
                        val scopedInner = services.scopeManifest(fetched.yaml, record.model)
                        val artifact = services.parseArtifact(scopedInner, record.model)
                        // The manifest is ALWAYS kept: identity was already established
                        // by the verified served model_name, so the compose is only
                        // DISPLAY enrichment. If the selected artifact doesn't serve the
                        // requested model, adopt NO artifact (never the wrong model —
                        // the original DeepSeek-shows-Qwen bug) but STILL keep the
                        // manifest so the panel stays complete.
                        all.add("model" to scopedInner)
                        if (isActive) {
                            modelLayerManifest = scopedInner
                            modelArtifact = if (artifact != null && !NearAIModelCatalog.differentVendor(artifact.servedName, record.model)) artifact else null
                        }
                    }
                    is ModelLayerFetch.HashMismatch -> {
                        // Adversarial: the recipe on disk is NOT what the action log
                        // pinned. Do NOT add it to the provenance set — the session
                        // fails closed via modelComposeIntegrityFailed, so the
                        // outer-only provenance can't read as a benign AllVerified.
                        layerVerification = ModelLayerVerification.HASH_MISMATCH
                        warn("[provenance] INTEGRITY BREAK — inner compose hash mismatch (pinned ${fetched.expected}, got ${fetched.got}) — degrading fail-closed")
                    }
                    ModelLayerFetch.FetchFailed -> layerVerification = ModelLayerVerification.FETCH_FAILED
                }
                // Publish the recipe verdict IMMEDIATELY (before the slow per-image
                // sweep below). A detected hash mismatch must degrade fail-closed the
                // instant it's known — not render green for the seconds the sweep
                // takes, and never be dropped if this job is cancelled mid-sweep.
                if (isActive) modelLayerVerification = layerVerification
            }
            val result = verifyManifests(all)
            if (!isActive) return@launch
            modelLayerVerification = layerVerification
            if (result.isInconclusive) {
                // Rate limit / network trouble only — no negative evidence. Keep the
                // last verdict instead of degrading or dropping back to pending (iOS
                // fbedb79: a rate-limited re-sweep blanked a verified sheet); the
                // next refresh retries.
                warn("[provenance] inconclusive (transient fetch errors only) — keeping this record's last verdict")
                imageProvenance = previous
                provenanceUnchecked = previous == null
                return@launch
            }
            // A pass over no near.ai image vouches for nothing: read it as ProvenanceService's own fail-closed answer
            // for a manifest with none (pm, T2 2026-11-06).
            imageProvenance = if (result is ManifestProvenance.AllVerified && result.verified.isEmpty())
                ManifestProvenance.Incomplete(emptyList(), emptyList(), result.thirdParty) else result
          } finally {
            if (provenanceRun === run) provenanceSettled = true
          }
        }
    }

    /** The manifests and the model layer a provenance verdict is about. Another node gives another key (iOS provenanceKey). */
    internal fun provenanceKey(record: AttestationRecord): String =
        AttestationRecord.sha256Hex(listOf(record.composeManifest ?: "", record.gpuNodeComposeManifest ?: "", record.modelComposePath ?: "",
            record.modelComposeCommit ?: "", record.modelFileHash ?: "").joinToString("\u001F"))

    /**
     * Never split this with a suspension: [verdictsPending] only sees a launched job, so a record without its
     * verifiers would let a send through (iOS ebc9bdb).
     */
    fun adopt(record: AttestationRecord, provider: Provider, directBase: String? = null) {
        attestation = record
        verifyImageProvenance(record)
        verifyDcap(record)
        verifyGpu(record)
        verifyTls(provider, directBase)
    }

    /** Runs real DCAP verification over the record's quotes. Fail-closed: a hard failure degrades via [attestationState]. */
    fun verifyDcap(record: AttestationRecord) {
        dcapJob?.cancel(); dcapJob = null   // as verifyGpu: a cancelled job left behind reads as pending forever
        dcapVerification = null
        composeFilesJob?.cancel(); composeFileFetches = emptyMap(); composeBodies = emptyMap()
        val service = services.verifyDcap ?: return
        dcapJob = scope.launch {
            // A verifier that throws still answers (pm sweep 2026-11-06): no verdict read pending forever. Fail-closed,
            // as an unreachable collateral already is.
            val result = try { service(record) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // our bug is not evidence: couldn't check, soft (near-send-gate-rule reverses trust-verifier-error's "hard")
                // 1.1.1 audit integ read, item 5 (iOS ConfidentialSession+Verifiers.swift @ 01a337a4: DCAPService.verify never throws, a quote it can't
                // check is unchecked): EVERY quote of the record is unchecked, the listed enclaves' too, so the listed state reads "couldn't finish"
                // at once and never "checking" until the verdict deadline.
                warn("[dcap] verifier threw: $e"); couldntCheckEveryQuote(record)
            }
            if (!isActive) return@launch
            composeFilesStarting = true
            try {
                dcapVerification = result
                if (result.hasHardFailure) warn("[dcap] hard failure — degrading session")
                // the manager quote's verdict is what (a) waited on: a bound log's compose files can be checked now
                verifyComposeFiles()
            } finally { composeFilesStarting = false }
            touch()
        }
    }

    private fun couldntCheckEveryQuote(record: AttestationRecord): RecordDcapVerification {
        val unchecked = DcapVerification.Unchecked(VERIFIER_ERROR)
        val enclaves = record.modelCandidates?.enclaves ?: emptyList()
        return RecordDcapVerification(
            gateway = unchecked,
            model = unchecked.takeIf { record.modelIntelQuote?.isNotEmpty() == true },
            gpu = unchecked.takeIf { record.gpuIntelQuote?.isNotEmpty() == true },
            listed = enclaves.map { unchecked },
            listedManagers = enclaves.map { e ->
                val cm = e.report?.get("compose_manager_attestation") as? kotlinx.serialization.json.JsonObject
                unchecked.takeIf { (cm?.get("quote") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { q -> q.isString } != null }
            })
    }

    /** Verifies the record's GPU evidence against NVIDIA NRAS. Skipped when there is no nvidia_payload; fail-closed once evidence exists. */
    fun verifyGpu(record: AttestationRecord) {
        // cancel AND drop the handle (F2, iOS's TLA+ model): a record with no GPU evidence returns below, and a kept
        // cancelled job never answers — verdictsPending read it as NVIDIA still checking, "verifying" forever
        nrasJob?.cancel(); nrasJob = null
        gpuAttestation = null
        // A model node that answered with no nvidia_payload couldn't be checked (S37 B4): soft, never verified, about this record
        val payload = record.nvidiaPayload?.takeIf { it.isNotEmpty() }
        val modelAbsent = payload == null && record.modelIntelQuote?.isNotEmpty() == true
        // Every enclave near.ai lists holds the sealed key, so each one's GPU evidence is checked like the model leg's (1.1.1 audit A3).
        val listed = record.modelCandidates?.enclaves?.map { it.nvidiaPayload } ?: emptyList()
        val listNonce = record.modelCandidates?.nonce
        if (payload == null && listed.isEmpty()) {
            if (modelAbsent) gpuAttestation = NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT)
            return
        }
        val service = services.verifyNras ?: return
        val nonce = record.modelNonce
        nrasJob = scope.launch {
            // A throw is not NVIDIA's verdict: inconclusive (soft), never pending forever.
            suspend fun check(p: String, n: String?) = try { service(p, n) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                warn("[nras] verifier threw: $e"); NrasVerification.Inconclusive(VERIFIER_ERROR)
            }
            // In parallel, read back in input order (model leg first): the verdict is the worst, the reason shown stays stable.
            val results = coroutineScope {
                val model = payload?.let { async { check(it, nonce) } }
                val others = listed.map { p -> async {
                    if (p.isNullOrEmpty()) NrasVerification.ListedInconclusive(NrasVerification.EVIDENCE_ABSENT)
                    else check(p, listNonce).let { verdict -> when (verdict) {
                        is NrasVerification.Failed -> NrasVerification.ListedFailed(verdict.reason)
                        is NrasVerification.Inconclusive -> NrasVerification.ListedInconclusive(verdict.reason)
                        else -> verdict
                    } } // A10: names the listed enclave, never the read one; NVIDIA's own reason rides along for the expert row
                } }
                listOfNotNull(model?.await()) + others.awaitAll()
            }
            if (!isActive) return@launch
            gpuAttestation = NrasVerification.worst((if (modelAbsent) listOf(NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT)) else emptyList()) + results)
        }
    }

    /**
     * Verifies that HTTPS terminates inside the model TEE. Runs against the
     * model's authoritative direct host; when the model has no direct host, TLS
     * attestation isn't applicable (NotPerformed). Fail-closed: Failed degrades.
     */
    fun verifyTls(provider: Provider, resolvedDirectBase: String? = null) {
        tlsJob?.cancel(); tlsJob = null   // as verifyGpu: a cancelled job left behind reads as pending forever
        tlsAttestation = null
        val service = services.verifyTls ?: return
        val model = provider.model
        tlsJob = scope.launch {
            // The host the record was fetched from, when there is one (T4, 2026-11-06): asking the directory a second
            // time could answer "no host", and NotPerformed hid the TLS section and let the chip go green unchecked.
            val directBase = resolvedDirectBase ?: services.directBase(model)
            if (!isActive) return@launch
            if (directBase == null) { tlsAttestation = TlsAttestation.NotPerformed; return@launch }
            // No credential is passed: the probe is deliberately unauthenticated —
            // it rides an any-trust connection whose peer is only verified AFTER the fact.
            // A throw is not a fingerprint mismatch: inconclusive (soft), never pending forever.
            val result = try { service(directBase) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                warn("[tls] verifier threw: $e"); TlsAttestation.Inconclusive(VERIFIER_ERROR)
            }
            if (!isActive) return@launch
            tlsAttestation = result
            if (result is TlsAttestation.Failed) warn("[tls] failed — ${result.reason}")
            // Inconclusive degrades the verdict too ("attestation unreachable"); it deserves a line as much as a failure.
            if (result is TlsAttestation.Inconclusive) warn("[tls] inconclusive — ${result.reason}")
        }
    }

    // MARK: What the session means (ConfidentialSession+Verdict.swift)

    /** Title / ladder mismatch count. */
    val effectiveMismatchCount: Int get() = uiTestSeed?.mismatchCount?.takeIf { it > 0 } ?: mismatchedResponseCount

    /**
     * Combined security state derived from attestation + E2EE status.
     *
     * NO PLATFORM GUARD HERE, DELIBERATELY. This whole computation once sat
     * inside `#if os(iOS)` with an `#else return .none`, so on macOS every
     * fail-closed check below did not run and every provider reported "nothing
     * to attest" while the UI looked like it had checked. Nothing in here is
     * platform-specific: it reads model state and returns an enum. An app
     * whose stated value is verified confidential inference must not silently
     * answer NONE on a platform it ships.
     */
    val attestationState: AttestationState
        get() {
            uiTestSeed?.let { return it.state }
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return AttestationState.NONE
            // near.ai serves no confidential endpoint for this model — it can't be
            // attested, so present it as a plain non-attestable model rather than
            // spinning on "verifying" forever.
            if (noConfidentialEndpoint) return AttestationState.NONE
            val attestation = attestation ?: return if (attestationFetchFailed) AttestationState.DEGRADED else AttestationState.VERIFYING
            // Fail-closed: if the manifest we were shown does not match what the
            // enclave actually booted, the code identity is broken regardless of E2EE.
            if (attestation.codeIdentityVerified == false) return AttestationState.DEGRADED
            // Fail-closed: the running recipe (inner compose) did not match the hash
            // the signed action log pinned. A transient fetch failure deliberately
            // does NOT reach here, so a GitHub blip never masquerades as tamper.
            if (modelComposeIntegrityFailed) return AttestationState.DEGRADED
            // Fail-closed: an image that did not trace to a pinned near.ai workflow.
            // (A pending/null result does not degrade — provenance is still in
            // flight.) Signature / identity / digest failures degrade. A clean
            // GitHub 404 (unpublished image) does not — E2EE can still be intact.
            // The chip stays off OK via unpublishedOnlyButE2EEIntact, not this gate.
            val provenance = imageProvenance
            if (provenance is ManifestProvenance.Incomplete && !provenance.isUnpublishedOnly) return AttestationState.DEGRADED
            // Fail-closed: real DCAP verification is the authoritative quote verdict.
            // A hard failure breaks the hardware root of trust. Out-of-date TCB is
            // flagged in the sheet, not degraded (matching near.ai's own verifier).
            if (dcapVerification?.hasHardFailure == true) return AttestationState.DEGRADED
            // Fail-closed: the action log or the boot log does not bind to the model's hardware (NearTrustScope (a)/(b)).
            if (nearScopeBroken || listedScopeBroken) return AttestationState.DEGRADED
            // Fail-closed: a quote that does not echo our request nonce may be a replay.
            if (attestation.nonceEchoed == false) return AttestationState.DEGRADED
            // C3a: a listed enclave holding the same key failed a check.
            if (attestation.candidatesBroken) return AttestationState.DEGRADED
            // Fail-closed: GPU evidence that didn't verify — whether NVIDIA rejected
            // it (hard) or NRAS was unreachable (inconclusive) — breaks the GPU half
            // of the trust chain either way. Both degrade; only a genuine rejection
            // is HARD (see degradeIsHardFailure). null = in flight.
            if (gpuAttestation?.isHardFailure == true || gpuAttestation is NrasVerification.Inconclusive || gpuAttestation is NrasVerification.ListedInconclusive) return AttestationState.DEGRADED
            // Fail-closed: TLS attestation that didn't verify — a fingerprint mismatch
            // (hard, possible MITM) or an unreachable check (inconclusive). NotPerformed
            // (no direct host) is neutral; null = in flight.
            if (tlsAttestation?.isUnverified == true) return AttestationState.DEGRADED
            // Intel couldn't be asked about a quote: soft, the send asks (near-send-gate-rule, iOS couldntCheckHardware).
            if (couldntCheckHardware) return AttestationState.DEGRADED
            // near.ai's tier list never came: can't check, soft, the send asks (formal/ TierTimeout, iOS 83b8d527).
            if (tierGaveUp) return AttestationState.DEGRADED
            // C3a: the list of enclaves holding the key never came: can't check, soft, the send asks.
            if (attestation.modelCandidatesListFailed || listedScopeEvidenceAbsent) return AttestationState.DEGRADED
            // Fail-closed: a present-but-malformed key is a broken E2EE promise, not
            // "no E2EE expected". Both e2eeDegradedReason and e2eeBindingIntact
            // require 32 bytes; without this gate a wrong-length key with a verified
            // model quote read as OK (green chip) because e2eeKeyBoundToModelTEE
            // returns null — not false — when the length is wrong.
            // A record past its age whose refetch failed is no longer vouched for: soft, never green forever
            // (trust-stale-record, the maintainer 2026-11-06).
            if (recordStaleUnconfirmed) return AttestationState.DEGRADED
            val key = attestation.modelEd25519PubKey
            if (key != null && key.size != 32) return AttestationState.DEGRADED
            if (key != null) {
                // After a request: reflect actual result.
                if (lastRequestUsedE2EE == false) return AttestationState.DEGRADED
                // E2EE key must be cryptographically bound to a verified model TEE.
                val mqv = attestation.modelQuoteVerification
                if (mqv != null) {
                    if (!mqv.isVerified) return AttestationState.DEGRADED
                    if (attestation.e2eeKeyBoundToModelTEE == false) return AttestationState.DEGRADED
                    // A DCAP, NRAS or TLS verdict asked for and not answered is not a pass (T1, 2026-11-06): OK said
                    // "confirmed by Intel and NVIDIA" before either had answered. Same send gate: prepareTurn settles.
                    // Before the unpublished-image case too, which asks nothing at the gate (iOS d3ca003b).
                    if (verdictsPending) return AttestationState.VERIFYING
                    // Sealed, but a digest has no published attestation — not OK.
                    if (provenance?.isUnpublishedOnly == true) return AttestationState.DEGRADED
                    // Sealed, and provenance is still being fetched: not green yet; the send waits for it (S30).
                    if (provenance == null && !provenanceSettled && provenanceJob != null) return AttestationState.VERIFYING
                    // The near.ai tier is still unknown: it could resolve to third-party and the send go plain, so never
                    // "verified" yet (iOS ae1430ef; chip-verifying-until-tier-known, the maintainer 2026-11-07). verdictsPending
                    // holds the send (S30). A belt: no state reaches it with the tier UNKNOWN — still unknown, verdictsPending
                    // returned VERIFYING above; given up, tierGaveUp returned DEGRADED earlier — so it is an equivalent mutant
                    // (audit, 05c07fa), kept as iOS keeps it; the invariant is held by TierDeadlineTest.
                    if (activeProviderSource()?.let { NearAIModelCatalog.confidentiality(it.model) } == NearAIModelCatalog.Confidentiality.UNKNOWN)
                        return AttestationState.VERIFYING
                    return AttestationState.OK
                }
                // No model attestation available — can't confirm E2EE key provenance.
                return AttestationState.DEGRADED
            }
            // Attestation available but no Ed25519 key — TEE verified, no E2EE possible.
            return AttestationState.DEGRADED
        }

    /**
     * Human-readable reason why E2EE is not available. Non-null only when the
     * provider supports attestation but E2EE cannot be established. Mirrors
     * every degrade condition in [attestationState] so the alert names the
     * actual cause — an unexplained block gets misattributed to whatever flag
     * happens to be visible (e.g. an out-of-date TCB, which deliberately does
     * NOT degrade).
     */
    val e2eeDegradedReason: String?
        get() {
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return null
            val attestation = attestation ?: return when {
                directHostMissing -> "near.ai lists no confidential host for this model yet."
                attestationFetchFailed -> "couldn't reach the attestation server."
                else -> null // still loading
            }
            // 1.1.1 audit integ read item 8 (iOS ConfidentialSession+Verdict.swift @ 01a337a4: recordIsStaleAndUnrefreshable, right after the
            // missing-attestation guard): a record past its age whose refetch failed is named before any other cause. attestationState and
            // sendPolicy still read recordStaleUnconfirmed where they did; only the reason's place moved.
            if (recordStaleUnconfirmed) return "couldn't reach the attestation server."
            dcapVerification?.takeIf { it.hasHardFailure }?.let { dcap ->
                // a listed enclave's rejection names the listed enclave, never the read one (A10)
                val readLegFailed = listOf(dcap.gateway, dcap.model, dcap.gpu).any { it?.isHardFailure == true }
                return if (readLegFailed) "the hardware quote failed intel dcap verification." else ListedEnclaveReason.ENCLAVE_FAILED
            }
            if (attestation.codeIdentityVerified == false) return "the running code doesn't match the attested manifest."
            nearScope?.hardBreakReason?.let { return it }
            listedNearScopes.orEmpty().firstNotNullOfOrNull { it.listedHardBreakReason }?.let { return it }
            // before (a) binds the log it may not be called signed (near-trust-scope v4)
            if (modelComposeIntegrityFailed) return if (nearScope?.deployLogBinding == DeployLogBinding.Bound) NearTrustScope.RECIPE_UNVERIFIED else NearTrustScope.RECIPE_UNVERIFIED_UNBOUND
            if (unpublishedOnlyButE2EEIntact) return "an image has no published attestation. the send is still sealed."
            if (imageProvenance is ManifestProvenance.Incomplete) return "an image couldn't be traced to near.ai's published source."
            if (attestation.nonceEchoed == false) return "the attestation didn't echo this session's nonce (possible replay)."
            attestation.candidatesReason?.let { return it }
            if (couldntCheckHardware) {
                // Only listed quotes unchecked: name the listed enclaves, never the read one's Intel line (iOS 38850f79).
                val readLegUnchecked = dcapVerification?.let { d -> listOf(d.gateway, d.model, d.gpu).any { it?.isUnchecked == true } } == true ||
                    nearScope?.deployLogBinding == DeployLogBinding.Unchecked || listedNearScopes?.any { it.deployLogBinding == DeployLogBinding.Unchecked } == true
                if (!readLegUnchecked) return KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED)
                // our verifier broke: our bug, not the hardware's (trust-hardware-fail-lines), and not an outage either
                if (dcapVerification?.uncheckedReason == VERIFIER_ERROR) return VERIFIER_BROKE
                return INTEL_UNREACHABLE
            }
            // 1.1.1 audit integ read, item 3 (iOS ConfidentialSession+Verdict.swift @ 01a337a4 e2eeDegradedReason): iOS's order, so the text and
            // the confirmation key's reason half agree when a listed GPU verdict and a read-leg fault coincide: teemoon's own fault on the GPU leg,
            // then on the TLS leg, then no GPU evidence sent, then the listed verdicts, then the GPU's own, then the TLS leg's own.
            // teemoon's own fault is never called an outage (pm, 2026-11-06): the approved fault line, as the row says
            val gpu = gpuAttestation; val tls = tlsAttestation
            if (gpu is NrasVerification.Inconclusive && gpu.reason == NrasVerification.TEEMOON_FAULT) return VERIFIER_ERROR
            // A1: the probe is of the model node's certificate, never the chat's connection (iOS 123d0b28)
            if (tls is TlsAttestation.Inconclusive && tls.reason == NrasVerification.TEEMOON_FAULT) return VERIFIER_ERROR
            // no GPU evidence was sent (S37 B4): nothing was unreachable, so never "(service unreachable)"
            if (gpu is NrasVerification.Inconclusive && gpu.reason == NrasVerification.EVIDENCE_ABSENT) return NrasVerification.EVIDENCE_ABSENT
            // a listed enclave's NVIDIA rejection reads the listed enclave's line (A10); the read enclave's own keeps its own
            if (gpu is NrasVerification.ListedFailed) return ListedEnclaveReason.ENCLAVE_FAILED
            if (gpu is NrasVerification.ListedInconclusive) return KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED)
            if (gpu != null && !gpu.isVerified) return if (gpu.isHardFailure) "nvidia gpu attestation failed verification."
                else "nvidia gpu attestation couldn't be completed (service unreachable)."
            if (tls != null && tls.isUnverified) return if (tls.isHardFailure) "the model node's tls certificate doesn't match its attestation."
                else "teemoon couldn't check the model node's tls certificate (unreachable)."
            if (lastRequestUsedE2EE == false) lastE2EEFailReason?.let { return it }
            val key = attestation.modelEd25519PubKey
            if (key == null || key.size != 32) return "the model's encryption key is unavailable."
            // a key with no quote the parser could read is unproven, evidence (iOS release/1.1.1-patch e2f62bcc, a pick of 05a4d9a0): the same line as an unbound key
            val mqv = attestation.modelQuoteVerification ?: return "the encryption key isn't bound to verified hardware."
            if (!mqv.isVerified) return "the model's hardware attestation failed verification."
            if (attestation.e2eeKeyBoundToModelTEE == false) return "the encryption key isn't bound to verified hardware."
            // LAST: GPU/TLS hard failures and a missing key name themselves first (pm 2026-11-14, iOS verify-deadline)
            if (attestation.modelCandidatesListFailed) return ListedEnclaveReason.LIST_FAILED
            if (listedScopeEvidenceAbsent) return NrasVerification.EVIDENCE_ABSENT
            if (tierGaveUp) return TIER_REASON
            return null
        }

    /** near.ai's enclave list failed, it is the cause the send names, and nothing is in flight: the ask offers re-verify (1.1.1 audit A8, iOS beb43b71). */
    val enclaveListFailedAsks: Boolean
        get() {
            val refetching = attestationFetchInFlight && attestationJob?.isCancelled == false
            if (attestation?.modelCandidatesListFailed != true || verdictsPending || refetching) return false
            // softButSealed + the reason keep a worse soft cause (Intel unreachable, a missing key) from being hidden
            return softButSealed && e2eeDegradedReason == ListedEnclaveReason.LIST_FAILED
        }

    /** The record is older than [ATTESTATION_MAX_AGE_SECONDS] and the refetch that should replace it failed. */
    val recordStaleUnconfirmed: Boolean
        get() {
            val record = attestation ?: return false
            return attestationFetchFailed && services.now().epochSecond - record.fetchedAt.epochSecond > ATTESTATION_MAX_AGE_SECONDS
        }

    /**
     * True when the ONLY blocking failure is image provenance while E2EE itself
     * is fully established — the key is bound to verified hardware, so a send
     * would still be sealed end-to-end; the residual risk is an unverified
     * image inside the enclave boundary, not plaintext on the wire. The
     * confirmation alert uses this to say the truth ("still encrypted — code
     * unverified") instead of the misleading "send without encryption".
     */
    val provenanceBlockedButE2EEIntact: Boolean
        // an unpublished-only image is not a provenance block: it exempts only itself (sendgate 0.5.11, iOS 3f5d91a3).
        // Counted as one, an unpublished image beside a stale record offered "send without encryption" over a sealed send.
        get() = (imageProvenance as? ManifestProvenance.Incomplete)?.let { !it.isUnpublishedOnly } == true && e2eeBindingIntact

    /** Clean GitHub 404s only, with the model key bound to verified hardware. Send stays sealed and ungated; the session is not fully verified. */
    val unpublishedOnlyButE2EEIntact: Boolean
        get() = imageProvenance?.isUnpublishedOnly == true && e2eeBindingIntact && !tierGaveUp && attestation?.modelCandidatesListFailed != true && !listedScopeEvidenceAbsent

    /**
     * Key bound to a verified model TEE; GPU/TLS not in an unverified state.
     * Internal, not private: the attestation sheet's hero copy needs the same
     * signal ([AttestationSummary.e2eeIntact]) so a degrade whose send is still
     * sealed does not announce "E2EE is unavailable".
     */
    val e2eeBindingIntact: Boolean
        get() {
            val att = attestation ?: return false
            if (dcapVerification?.hasHardFailure == true) return false
            // not hard is not verified: a quote Intel couldn't check binds nothing (near-send-gate-rule)
            if (couldntCheckHardware) return false
            if (nearScopeBroken || listedScopeBroken) return false
            // the model recipe's hash mismatch is evidence (degradeIsHardFailure counts it): never an intact binding.
            // Shipped 1.0.6 missed it here, so an unpublished image beside it sent unasked (audit's F1 addendum).
            if (modelComposeIntegrityFailed) return false
            if (att.codeIdentityVerified == false) return false
            if (att.nonceEchoed == false) return false
            if (att.candidatesBroken) return false
            val key = att.modelEd25519PubKey ?: return false
            if (key.size != 32) return false
            if (att.modelQuoteVerification?.isVerified != true) return false
            if (att.e2eeKeyBoundToModelTEE == false) return false
            if (gpuAttestation?.isHardFailure == true || gpuAttestation is NrasVerification.Inconclusive || gpuAttestation is NrasVerification.ListedInconclusive) return false
            if (tlsAttestation?.isUnverified == true) return false
            return true
        }

    /**
     * Whether the current degrade is a HARD, adversarial integrity break
     * (tamper, replay, MITM, an unbound/unverified key) as opposed to a
     * transient/operational condition (verifier unreachable, an image still
     * tracing to source). Drives the red-vs-orange hero tier: a hard break must
     * render RED, never as a benign advisory. False whenever the session isn't
     * degraded, or the only cause is soft.
     */
    val degradeIsHardFailure: Boolean
        get() {
            uiTestSeed?.let { return it.hardFailure }
            if (attestationState != AttestationState.DEGRADED) return false
            val att = attestation ?: return false
            if (att.codeIdentityVerified == false) return true
            if (modelComposeIntegrityFailed) return true
            if (dcapVerification?.hasHardFailure == true) return true
            if (nearScopeBroken || listedScopeBroken) return true
            if (att.nonceEchoed == false) return true
            if (att.candidatesBroken) return true
            // HARD only for a genuine negative verdict (NVIDIA rejection / nonce
            // replay; TLS fingerprint mismatch). An unreachable NRAS/TLS check is
            // Inconclusive → soft: it still degrades, but must never be branded
            // tamper/MITM with a no-bypass block.
            if (gpuAttestation?.isHardFailure == true) return true
            if (tlsAttestation?.isHardFailure == true) return true
            if (att.modelEd25519PubKey != null) {
                // Plaintext went out unsealed, or the key isn't bound to a verified
                // model TEE — both are hard breaks of the E2EE guarantee.
                if (lastRequestUsedE2EE == false) return true
                // A key no readable quote proves is unproven: whoever answered chose it. Never "still checking" (1.1.0 audit LOW, iOS release/1.1.1-patch e2f62bcc, a pick of 05a4d9a0).
                val mqv = att.modelQuoteVerification ?: return true
                if (!mqv.isVerified) return true
                if (att.e2eeKeyBoundToModelTEE == false) return true
            }
            // Remaining degrades — provenance Incomplete with E2EE intact, no
            // Ed25519 key, or model attestation simply unavailable — are advisory.
            return false
        }

    /**
     * THE send gate, canonical. ALLOW sends; CONFIRM requires the user to see
     * the degrade and choose; BLOCK refuses outright. Every entry point that
     * can start a generation must consult THIS property; a second derivation
     * is exactly how the Siri/Shortcuts intent bypassed the gate on iOS.
     */
    val sendPolicy: TrustSendPolicy
        get() {
            // Item 1 (iOS d2d70d45): the capability guard is exactly where a demoted model falls out of the gate, so this comes first.
            if (sealedModelWentPlain) return TrustSendPolicy.CONFIRM
            val policy = TrustVerdict.sendPolicy(requiresConfirmation = requiresE2EEConfirmation, hardFailure = degradeIsHardFailure)
            // S30: an inconclusive provenance lookup asks before the send (T2b), once per record
            return if (policy == TrustSendPolicy.ALLOW && provenanceUncheckedAsks) TrustSendPolicy.CONFIRM else policy
        }

    /** DCAP couldn't fetch or read Intel's collateral for a quote, or (a) couldn't for the compose manager's (iOS). */
    val couldntCheckHardware: Boolean
        get() = dcapVerification?.hasUnchecked == true || nearScope?.deployLogBinding == DeployLogBinding.Unchecked
            || listedNearScopes?.any { it.deployLogBinding == DeployLogBinding.Unchecked } == true

    /**
     * A soft state in which the send would still go out sealed — the same peer the send seals to — and not the
     * provenance-only case: the "still checking near.ai's hardware" confirm, never "send without encryption"
     * (near-send-gate-rule, the maintainer 2026-11-06). The predicate is the send's own, not [e2eeBindingIntact].
     */
    val softButSealed: Boolean
        get() = !degradeIsHardFailure && !provenanceBlockedButE2EEIntact && currentPeerExists()

    /**
     * The sent-before-confirmed mark for a reply sent sealed NOW (MessageEntity.sentBeforeConfirmed, the maintainer approved
     * 2026-11-06): "pending|<recordKey>" while anything is still pending for this record ([sendPending], S30), else null.
     * recordKey = "<fetchedAt epoch ms>|<gatewayNonce ?: gpuNonce, lowercase hex>". Resolved per record later (phase 3).
     */
    fun sentBeforeConfirmedMark(sealed: Boolean): String? {
        if (!sealed || !sendPending) return null
        val key = recordKeyOf(attestation ?: return null)
        awaitingSettle += key
        return "pending|$key"
    }

    private fun recordKeyOf(att: AttestationRecord) = "${att.fetchedAt.toEpochMilli()}|${(att.gatewayNonce ?: att.gpuNonce ?: "").lowercase()}"

    /**
     * reply-machine.json sentBeforeConfirmedResolution, the trust side: a record a reply was sent on while its hard
     * verdicts were pending says how they settled — "pass", "hardFail", or "unsettled" (an outage, or the record was
     * replaced before they answered: the mark stays pending). The app rewrites the stored marks (SentBeforeConfirmed).
     */
    var onRecordSettled: ((recordKey: String, outcome: String) -> Unit)? = null

    private fun settleDue() {
        if (awaitingSettle.isEmpty()) return
        val current = attestation?.let(::recordKeyOf)
        // Losing the attestation capability (an unknown tier resolving to third-party) is not a verdict (F5, iOS 0e339959):
        // verdictsPending reads false then with the checks still running, and the policy reads allow.
        val attests = activeProviderSource()?.let { ProviderCapability.ATTESTATION in it.capabilities } == true
        for (key in awaitingSettle.toList()) {
            // the record's own verdicts, whatever provider is selected now: a switch away is not a pass
            val outstanding = (dcapJob != null && dcapVerification == null) || (nrasJob != null && gpuAttestation == null) || (tlsJob != null && tlsAttestation == null) || composeFilesPending ||
                // S30: the pending window ends only when nothing is pending (iOS: settles on sendPending)
                (attests && sendPending)
            if (key == current && attests && outstanding) continue
            awaitingSettle -= key
            // iOS settleSentBeforeConfirmed: hard is degradeIsHardFailure — every hard check, trust scope included (it read
            // only DCAP, NVIDIA and TLS, so a broken scope cleared the mark); a pass is the policy reading allow; anything
            // else (an outage, a soft state) leaves the mark pending.
            val outcome = if (key != current || !attests) "unsettled" else when {
                degradeIsHardFailure -> "hardFail"
                sendPolicy == TrustSendPolicy.ALLOW -> "pass"
                else -> "unsettled"
            }
            val held = heldE2EE.remove(key) ?: 0
            if (outcome == "pass") e2eeMessageCount += held
            if (outcome == "hardFail") failedRecords += key
            onRecordSettled?.invoke(key, outcome)
        }
    }

    /** (record, reason) pairs the user has already said "send anyway" to. */
    internal val confirmedDegrades = HashSet<String>()   // internal: TierDowngradeTest plants the post-flip key

    /** One confirm per (record, reason): the record is its fetch time and nonce; a pending confirm is its own reason. */
    fun confirmationKey(pending: Boolean): String? {
        val att = attestation ?: return null
        val record = "${att.fetchedAt.toEpochMilli()}|${att.gatewayNonce ?: att.gpuNonce ?: ""}"
        // F4 strict (iOS b3d00c3a, sendgate.json 0.5.15; the maintainer 2026-11-07): a couldn't-check already known (policy
        // confirm) is what the ask answers, even while other checks run — one answer covers the record. "pending" only when
        // nothing is known yet.
        // S30: the pending reason names its kind, so a hardware answer never covers provenance and back (sendgate.json 0.6.0)
        val pendingReason = if (pendingKind == PendingKind.PROVENANCE) "provenancePending" else "pending"
        val reason = if (pending && sendPolicy != TrustSendPolicy.CONFIRM) pendingReason
            else e2eeDegradedReason ?: (if (provenanceUncheckedAsks) "provenanceUnchecked" else "")
        return "$record|$reason"
    }
    fun hasConfirmed(pending: Boolean): Boolean {
        // 1.1.1 audit B1 read (iOS 217dc4cb): the tier-downgrade ask is answered only by its own "send without encryption"
        // (SealedModels.accept). A tier flip clears neither the record nor confirmedDegrades, so an earlier "send anyway" on the
        // held record must never count; what kept it shut was only that the key's reason half moves.
        if (sealedModelWentPlain) return false
        return confirmationKey(pending)?.let { it in confirmedDegrades } ?: false
    }
    fun rememberConfirmation(pending: Boolean, downgradeAskShown: Boolean = false) {   // false by default: only the downgrade ask's own answer says true
        // "send without encryption" to a downgrade: the consent is the id's removal, asked once per downgrade (item 1).
        // 1.1.1 audit cut review item 1: the answer carries WHICH ask was shown. The tier can flip to plain while a soft,
        // list-failed or still-checking ask is open, and that ask's "send anyway" is not this consent: nothing is remembered,
        // the model stays armed, and the downgrade ask is what the next send shows.
        if (sealedModelWentPlain) { if (downgradeAskShown) activeProvider?.let { sealedModels.accept(it.model); touch() }; return }
        confirmationKey(pending)?.let { confirmedDegrades += it; touch() }
    }

    /** The policy the composer asks with: a confirm already given for this record and reason doesn't ask again. */
    val sendPolicyAfterConfirmations: TrustSendPolicy
        get() = if (sendPolicy == TrustSendPolicy.CONFIRM && hasConfirmed(pending = false)) TrustSendPolicy.ALLOW else sendPolicy

    /** Whether sending a message requires user confirmation because E2EE is expected but cannot be established. */
    val requiresE2EEConfirmation: Boolean
        get() {
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return false
            // Unpublished image + sealed E2EE: no modal. The ladder still names the
            // digest. Shopping for another CVM's report would be a false verify.
            // an unpublished image exempts only itself (sendgate 0.5.11): any hard break beside it still blocks
            if (unpublishedOnlyButE2EEIntact && !recordStaleUnconfirmed && !degradeIsHardFailure) return false
            return attestationState == AttestationState.DEGRADED
        }

    /** What it means when near.ai publishes no direct host for the model. */
    enum class MissingHostOutcome { DEGRADED, ORDINARY }

    companion object {
        private const val SETTLE_POLL_MS = 100L
        /**
         * How long an unknown near.ai tier may hold the verdict (the maintainer 2026-11-14, "Yes", the same on both apps): past it
         * the state settles to the soft degrade, where the send asks, instead of "verifying" forever.
         */
        const val TIER_DEADLINE_MS = 20_000L
        /** The keyless tier list's retries after its first try (iOS VerdictDeadline.tierRetryDelays). */
        private val TIER_RETRY_DELAYS_MS = listOf(2_000L, 4_000L)
        /** The degrade reason, and the ask's body, when the tier list never came (iOS VerdictDeadline.tierReason; the maintainer approved the verify card 2026-11-14). */
        const val TIER_REASON = "near.ai's model list didn't load, so teemoon couldn't confirm this model runs on its own hardware."

        /** Maximum age before the Ed25519 key is considered stale and refetched. */
        const val ATTESTATION_MAX_AGE_SECONDS: Long = 600 // 10 minutes
        /**
         * A verifier that threw (trust-verifier-error, the maintainer 2026-11-06): its reason line. The raw exception goes to
         * the log only — it is our failure, not evidence, and it names nothing the reader can check.
         */
        const val VERIFIER_ERROR = "this check failed inside teemoon, so it proves nothing."
        /** The hero's cause when the DCAP verifier threw (+ "sending is blocked until it re-verifies."), the maintainer 2026-11-06. */
        const val VERIFIER_BROKE = "a hardware check broke inside teemoon."
        /** near-send-gate-words (the maintainer 2026-11-06): the cause when Intel couldn't be asked about a quote. */
        const val INTEL_UNREACHABLE = "intel's hardware check couldn't be completed (service unreachable)."
        const val ATTESTATION_RETRY_INTERVAL_SECONDS: Long = 60

        /**
         * ORDINARY is honest only for a tier that claims no encryption. An
         * own-fleet model with no host is unexplained — cold directory, failed
         * fetch, or a gap in the published map — and all three are "couldn't
         * check", which must not read as "nothing to encrypt".
         *
         * An id the guess does not know is UNKNOWN (iOS 9862a35), and it degrades
         * too. That is the safe direction: it asks.
         */
        fun outcomeWhenNoDirectHost(model: String): MissingHostOutcome = when (NearAIModelCatalog.confidentiality(model)) {
            NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.Confidentiality.UNKNOWN -> MissingHostOutcome.DEGRADED
            NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY, NearAIModelCatalog.Confidentiality.PROXIED -> MissingHostOutcome.ORDINARY
        }
    }
}
