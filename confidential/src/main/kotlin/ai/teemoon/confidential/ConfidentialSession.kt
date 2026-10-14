// ports: teemoon/Confidential/ConfidentialSession.swift @ v1.0.1
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.1
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4 (a sealed session reads VERIFYING until provenance settles)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4 (provenanceSettled, with a run token)
//        teemoon/Confidential/ConfidentialSession.swift @ v1.0.4 (verdictsPending / settleVerifiers / abandonTurn; prepareTurn settles)
//        teemoon/Confidential/ConfidentialSession.swift @ v1.0.4 (verdictsPending is attested-only)
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.4 (clearDerivedState drops the verifier handles)
//        teemoon/Confidential/ConfidentialSession+Refresh.swift @ v1.0.4 (directHostMissing; a failed fetch retried once a minute)
//        teemoon/Confidential/ConfidentialSession+Verdict.swift @ v1.0.4 (e2eeDegradedReason names the directory)
//        teemoon/Confidential/ConfidentialSession+Verifiers.swift @ v1.0.4 (an inconclusive pass keeps the last verdict)
//        TEEContext / prepareTurn / finishTurn / canSeal are here since the E2EE row landed; NOT here:
//        the Xcode-preview / -DesignTour process flags. The UITEST_SEED_ATTESTATION door IS here (`uiTestSeededDegraded`,
//        the "degraded" case, and `uiTestPlantsPendingVerifier` for "verifyingPending", f2fb40b), reachable from UiTestSeeding on a
//        debuggable build.
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.CoroutineScope
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
        set(value) { attestationStored = value; touch() }
    /** Backing storage; the public accessor applies the read gate. */
    private var attestationStored: AttestationRecord? = null

    /**
     * Bumps on every mutation of the session's observable state — iOS is
     * `@Observable`; Compose collects this and re-reads the snapshot. The
     * value is a counter, never the state itself.
     */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private fun touch() { _changes.value += 1 }

    /** Set to true when the attestation fetch fails, so the UI can stop showing a spinner. */
    var attestationFetchFailed = false
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
        refreshAttestation()
    }

    // MARK: Request lifecycle

    /** The provider this session is currently attesting. */
    val activeProvider: Provider? get() = activeProviderSource()

    /** Key the TLS verifier sends to the model's direct host. */
    fun credential(forProvider: Provider): String = credentialFor(forProvider)

    // MARK: TEE context (ConfidentialSession.swift 178–203)

    /** Whether a request can be sealed right now: an attesting provider, a gated record, and a usable peer. */
    val canSeal: Boolean get() = currentTeeContext()?.e2eePeer != null

    /** The context for one attested request — the only place the derivation lives. */
    fun currentTeeContext(): TeeContext? {
        val provider = activeProviderSource() ?: return null
        if (ProviderCapability.ATTESTATION !in provider.capabilities) return null
        val record = attestation ?: return null
        return TeeContext.from(provider, credentialFor(provider), record, warn)
    }

    /**
     * One turn: refresh a stale record, wait for the E2EE key if this provider
     * attests, derive the context, and open the bookkeeping. The view model
     * must not write E2EE fields itself.
     */
    suspend fun prepareTurn(): TeeContext? {
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
     * A hard-block-capable verdict has been asked for and has not answered:
     * DCAP, GPU (NRAS) or TLS. Provenance is not counted — its failures are
     * soft, and it is neutral while in flight by design.
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
                (tlsJob != null && tlsAttestation == null)
        }

    /**
     * Waits until no hard-block-capable verdict is pending, or [waitingUpToMs].
     * Counted in polls rather than read off a wall clock, so a virtual-time test
     * waits the same bounded number of steps a phone does.
     */
    suspend fun settleVerifiers(waitingUpToMs: Long) {
        repeat((waitingUpToMs / SETTLE_POLL_MS).toInt().coerceAtLeast(1)) {
            if (!verdictsPending) return
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
            if (isE2EEActive) e2eeMessageCount += 2  // one sent + one received
        }
        if (lastRequestUsedE2EE != true && error != null) {
            lastE2EEFailReason = error.httpStatus?.let { "Server rejected request (HTTP $it)" } ?: error.userMessage
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
    fun finishTurn(isE2EEActive: Boolean?, error: RequestError?, verification: ResponseVerification?) {
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
        val deadline = services.now().toEpochMilli() + waitingUpToMs
        attestationJob?.join()
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
        attestationJob?.join()
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
        attestationFetchFailed = false
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
        attestationJob = scope.launch {
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
                attestation = record
                verifyImageProvenance(record)
                verifyDcap(record)
                verifyGpu(record)
                verifyTls(provider)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("Attestation fetch failed: $e")
                if (!isActive || attestedContext != context) return@launch
                // Keep the cached record for E2EE — a stale key is better than plaintext.
                if (attestation?.providerID == provID) warn("Keeping cached attestation")
                attestationFetchFailed = true
            }
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
        nrasJob?.cancel(); nrasJob = null
        tlsJob?.cancel(); tlsJob = null
        attestation = null
        noConfidentialEndpoint = false
        directHostMissing = false
        modelArtifact = null
        imageProvenance = null
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
        // A transient pass keeps this; only evidence replaces it.
        val previous = imageProvenance
        imageProvenance = null
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
                warn("[provenance] inconclusive (transient fetch errors only) — keeping the last verdict")
                imageProvenance = previous
                return@launch
            }
            imageProvenance = result
          } finally {
            if (provenanceRun === run) provenanceSettled = true
          }
        }
    }

    /** Runs real DCAP verification over the record's quotes. Fail-closed: a hard failure degrades via [attestationState]. */
    fun verifyDcap(record: AttestationRecord) {
        dcapJob?.cancel()
        dcapVerification = null
        val service = services.verifyDcap ?: return
        dcapJob = scope.launch {
            val result = service(record)
            if (!isActive) return@launch
            dcapVerification = result
            if (result.hasHardFailure) warn("[dcap] hard failure — degrading session")
        }
    }

    /** Verifies the record's GPU evidence against NVIDIA NRAS. Skipped when there is no nvidia_payload; fail-closed once evidence exists. */
    fun verifyGpu(record: AttestationRecord) {
        nrasJob?.cancel()
        gpuAttestation = null
        val payload = record.nvidiaPayload?.takeIf { it.isNotEmpty() } ?: return
        val service = services.verifyNras ?: return
        val nonce = record.modelNonce
        nrasJob = scope.launch {
            val result = service(payload, nonce)
            if (!isActive) return@launch
            gpuAttestation = result
        }
    }

    /**
     * Verifies that HTTPS terminates inside the model TEE. Runs against the
     * model's authoritative direct host; when the model has no direct host, TLS
     * attestation isn't applicable (NotPerformed). Fail-closed: Failed degrades.
     */
    fun verifyTls(provider: Provider) {
        tlsJob?.cancel()
        tlsAttestation = null
        val service = services.verifyTls ?: return
        val model = provider.model
        tlsJob = scope.launch {
            val directBase = services.directBase(model) ?: run { tlsAttestation = TlsAttestation.NotPerformed; return@launch }
            // No credential is passed: the probe is deliberately unauthenticated —
            // it rides an any-trust connection whose peer is only verified AFTER the fact.
            val result = service(directBase)
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
            // Fail-closed: a quote that does not echo our request nonce may be a replay.
            if (attestation.nonceEchoed == false) return AttestationState.DEGRADED
            // Fail-closed: GPU evidence that didn't verify — whether NVIDIA rejected
            // it (hard) or NRAS was unreachable (inconclusive) — breaks the GPU half
            // of the trust chain either way. Both degrade; only a genuine rejection
            // is HARD (see degradeIsHardFailure). null = in flight.
            if (gpuAttestation is NrasVerification.Failed || gpuAttestation is NrasVerification.Inconclusive) return AttestationState.DEGRADED
            // Fail-closed: TLS attestation that didn't verify — a fingerprint mismatch
            // (hard, possible MITM) or an unreachable check (inconclusive). NotPerformed
            // (no direct host) is neutral; null = in flight.
            if (tlsAttestation?.isUnverified == true) return AttestationState.DEGRADED
            // Fail-closed: a present-but-malformed key is a broken E2EE promise, not
            // "no E2EE expected". Both e2eeDegradedReason and e2eeBindingIntact
            // require 32 bytes; without this gate a wrong-length key with a verified
            // model quote read as OK (green chip) because e2eeKeyBoundToModelTEE
            // returns null — not false — when the length is wrong.
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
                    // Sealed, but a digest has no published attestation — not OK.
                    if (provenance?.isUnpublishedOnly == true) return AttestationState.DEGRADED
                    // Sealed, and provenance is still being fetched: not green yet.
                    // Every hard verdict above already had its say; only the
                    // green-or-orange decision waits. The send gate is unchanged —
                    // VERIFYING allows exactly as OK did here.
                    if (provenance == null && !provenanceSettled && provenanceJob != null) return AttestationState.VERIFYING
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
                attestationFetchFailed -> "Could not reach the attestation server."
                else -> null // still loading
            }
            if (dcapVerification?.hasHardFailure == true) return "The hardware quote failed Intel DCAP verification."
            if (attestation.codeIdentityVerified == false) return "The running code does not match the attested manifest."
            if (modelComposeIntegrityFailed) return "The running recipe doesn’t match the signed action log — the launched configuration is unverified."
            if (unpublishedOnlyButE2EEIntact) return "An image has no published attestation — the send is still sealed."
            if (imageProvenance is ManifestProvenance.Incomplete) return "An image could not be traced to near.ai's published source."
            if (attestation.nonceEchoed == false) return "The attestation did not echo this session's nonce (possible replay)."
            gpuAttestation?.let { gpu ->
                if (gpu is NrasVerification.Failed) return "NVIDIA GPU attestation failed verification."
                if (gpu is NrasVerification.Inconclusive) return "NVIDIA GPU attestation couldn't be completed (service unreachable)."
            }
            tlsAttestation?.let { tls ->
                if (tls.isUnverified) return if (tls.isHardFailure) "The connection could not be verified to end inside the enclave (possible interception)."
                else "The connection to the enclave couldn't be verified (attestation unreachable)."
            }
            if (lastRequestUsedE2EE == false) lastE2EEFailReason?.let { return it }
            val key = attestation.modelEd25519PubKey
            if (key == null || key.size != 32) return "The model's encryption key is unavailable."
            val mqv = attestation.modelQuoteVerification ?: return "The model's hardware attestation is unavailable."
            if (!mqv.isVerified) return "The model's hardware attestation failed verification."
            if (attestation.e2eeKeyBoundToModelTEE == false) return "The encryption key is not bound to verified hardware."
            return null
        }

    /**
     * True when the ONLY blocking failure is image provenance while E2EE itself
     * is fully established — the key is bound to verified hardware, so a send
     * would still be sealed end-to-end; the residual risk is an unverified
     * image inside the enclave boundary, not plaintext on the wire. The
     * confirmation alert uses this to say the truth ("still encrypted — code
     * unverified") instead of the misleading "send unencrypted".
     */
    val provenanceBlockedButE2EEIntact: Boolean
        get() = imageProvenance is ManifestProvenance.Incomplete && e2eeBindingIntact

    /** Clean GitHub 404s only, with the model key bound to verified hardware. Send stays sealed and ungated; the session is not fully verified. */
    val unpublishedOnlyButE2EEIntact: Boolean
        get() = imageProvenance?.isUnpublishedOnly == true && e2eeBindingIntact

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
            if (att.codeIdentityVerified == false) return false
            if (att.nonceEchoed == false) return false
            val key = att.modelEd25519PubKey ?: return false
            if (key.size != 32) return false
            if (att.modelQuoteVerification?.isVerified != true) return false
            if (att.e2eeKeyBoundToModelTEE == false) return false
            if (gpuAttestation is NrasVerification.Failed || gpuAttestation is NrasVerification.Inconclusive) return false
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
            if (att.nonceEchoed == false) return true
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
                val mqv = att.modelQuoteVerification
                if (mqv != null) {
                    if (!mqv.isVerified) return true
                    if (att.e2eeKeyBoundToModelTEE == false) return true
                }
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
        get() = TrustVerdict.sendPolicy(requiresConfirmation = requiresE2EEConfirmation, hardFailure = degradeIsHardFailure)

    /** Whether sending a message requires user confirmation because E2EE is expected but cannot be established. */
    val requiresE2EEConfirmation: Boolean
        get() {
            val provider = activeProviderSource()
            if (provider == null || ProviderCapability.ATTESTATION !in provider.capabilities) return false
            // Unpublished image + sealed E2EE: no modal. The ladder still names the
            // digest. Shopping for another CVM's report would be a false verify.
            if (unpublishedOnlyButE2EEIntact) return false
            return attestationState == AttestationState.DEGRADED
        }

    /** What it means when near.ai publishes no direct host for the model. */
    enum class MissingHostOutcome { DEGRADED, ORDINARY }

    companion object {
        private const val SETTLE_POLL_MS = 100L

        /** Maximum age before the Ed25519 key is considered stale and refetched. */
        const val ATTESTATION_MAX_AGE_SECONDS: Long = 600 // 10 minutes
        const val ATTESTATION_RETRY_INTERVAL_SECONDS: Long = 60

        /**
         * ORDINARY is honest only for a tier that claims no encryption. An
         * own-fleet model with no host is unexplained — cold directory, failed
         * fetch, or a gap in the published map — and all three are "couldn't
         * check", which must not read as "nothing to encrypt".
         *
         * The id the guess does not know classifies as own fleet, so an unknown
         * model degrades too. That is the safe direction: it asks.
         */
        fun outcomeWhenNoDirectHost(model: String): MissingHostOutcome =
            if (NearAIModelCatalog.confidentiality(model) == NearAIModelCatalog.Confidentiality.TEE_OWN)
                MissingHostOutcome.DEGRADED else MissingHostOutcome.ORDINARY
    }
}
