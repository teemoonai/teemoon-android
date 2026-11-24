// ports: teemoon/Views/Chat/TrustLadderView.swift @ 39ba0919 (the summary takes deadlinePassed = session.tierGaveUp)
// ports: teemoon/Views/Chat/ChatView.swift @ beb43b71 (1.1.1 audit A8: the ask is built with session.enclaveListFailedAsks)
// ports: teemoon/Views/Chat/TrustLadderView+Hero.swift @ 37de10d9 (1.1.1 audit A9: the LEAKS-demotion comment shrinks)
// ports: teemoon/Views/Chat/TrustLadderView+None.swift @ 37de10d9 (1.1.1 audit A9: the no-encryption row)
// ports: teemoon/Views/Chat/TrustLadderView.swift @ v1.0.4 (summary / verdict / modelName / quant / isPaused derivations),
//        teemoon/Views/Chat/TrustLadderView+Hero.swift @ v1.0.1 (heroColor, metaLine), @ 8bfe33cb, b3d90fac (modelNodeAudit), @ c5e329f2 (modelNodeAudit over the listed enclaves, 1.1.1 audit A5),
//        teemoon/Confidential/TrustVerdict.swift @ c5e329f2 (ModelNodeAudit.rollup, listedState, worst),
//        teemoon/Views/Chat/TrustLadderView+None.swift @ v1.0.4 (the three not-attestable screens' copy),
//        teemoon/Views/Chat/E2EETitleBlock.swift @ v1.0.1 (the chip: TrustVerdict.chipMode + chipCaption),
//        teemoon/Views/Chat/ChatView.swift @ v1.0.4 (e2eeAlertTitle; the message and buttons are SendChoices.kt @ f600e4e),
//        teemoon/Views/PlatformChrome.swift @ v1.0.1 (DeviceNoun)
//
// SendPrep / prepareSend used to be duplicated here as well as in
// chat/SendPrep.kt. The app called THIS copy, so chat's — the mapped one, the
// one with the tests — was dead, and two wiring bugs lived undetected behind a
// green suite (2026-09 port review, B1, B2, B4). iOS keeps exactly one
// (ChatViewModel.swift:32-34 says so). This copy is gone; use ai.teemoon.chat.SendPrep.
//
// The state holder behind the trust chip, the send gate and the trust screen.
// Pure Kotlin, no Android imports: it reads the session and the provider and
// yields plain values the composables render. It authors NO trust language —
// every sentence is a TrustVerdict / AttestationSummary value, or the
// not-attestable copy ported verbatim from the iOS screens.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationState
import ai.teemoon.confidential.tierDowngradeAsk
import ai.teemoon.confidential.AttestationSummary
import ai.teemoon.confidential.AuditIndex
import ai.teemoon.confidential.MeasuredConfig
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.confidential.StepState
import ai.teemoon.confidential.AuditIndexStamp
import ai.teemoon.confidential.EnclaveGroup
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.EnclaveGrouping
import ai.teemoon.confidential.EverydayAuditState
import ai.teemoon.confidential.ImageEntry
import ai.teemoon.confidential.TrustChipMode
import ai.teemoon.confidential.TrustVerdict
import ai.teemoon.confidential.VerificationSection
import ai.teemoon.confidential.TrustSendPolicy
import ai.teemoon.confidential.selfVerifyScript
import ai.teemoon.confidential.verificationRun
import ai.teemoon.confidential.everydayAuditState
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.Provider

/** The machine, named — for boundary claims about what leaves it, and possessive ("your phone couldn't confirm…"). */
object DeviceNoun {
    const val BOUNDARY = "your phone"
    const val SUBJECT = "your phone"
}

/** Tint semantics only — the composable maps these to colours. */
enum class TrustTone { OK, VERIFYING, ADVISORY, FAILED, NEUTRAL }

/**
 * THE CHIP CARRIES A WORD, NOT ONLY A COLOUR. Colour alone fails for roughly
 * a tenth of users and for every screenshot pasted into a bug report, so the
 * state is always legible as text — the caption is TrustVerdict's.
 */
/** [sealed]: a soft state in which the send stays sealed — the caption's lock is closed (orange), not open (trust-caption-sealed). */
data class TrustChipModel(val label: String, val tone: TrustTone, val mode: TrustChipMode, val sealed: Boolean = false) {
    companion object {
        fun from(mode: TrustChipMode, state: AttestationState, unpublishedButSealed: Boolean, softButSealed: Boolean = false): TrustChipModel {
            val tone = when (mode) {
                TrustChipMode.VERIFIED -> TrustTone.OK
                TrustChipMode.VERIFYING -> TrustTone.VERIFYING
                // Amber, not red: something is weaker than promised, nothing is broken.
                TrustChipMode.SOFT_DEGRADE -> TrustTone.ADVISORY
                TrustChipMode.HARD_BLOCK, TrustChipMode.MISMATCH -> TrustTone.FAILED
                // NONE means the provider cannot be attested at all — an absence, not a failure; it must not borrow failure's red.
                TrustChipMode.ON_DEVICE, TrustChipMode.SELF_HOSTED, TrustChipMode.NONE -> TrustTone.NEUTRAL
            }
            val sealed = mode == TrustChipMode.SOFT_DEGRADE && softButSealed
            return TrustChipModel(TrustVerdict.chipCaption(mode, state, unpublishedButSealed, sealed), tone, mode, sealed)
        }
    }
}

/** One row of a not-attestable screen: a check or a minus, and its sentence. */
data class NoneRow(val positive: Boolean, val title: String, val detail: String)

/** The not-attestable screens' content, as data — the iOS copy, verbatim. */
/** [heroSymbol] is the SF Symbol name TrustLadderView+None draws at 40pt: lock.iphone / lock.laptopcomputer in teeVerified, lock.open in secondary. */
data class NoneScreen(val heroTitle: String, val heroLead: String, val rows: List<NoneRow>, val footnote: String? = null, val offersEncryptedModels: Boolean = false, val heroSymbol: String = "lock.open")

/** Everything the chip, the gate and the trust screen render, read once from the session. */
data class TrustSnapshot(
    val state: AttestationState,
    val summary: AttestationSummary,
    val verdict: TrustVerdict,
    val chip: TrustChipModel,
    /** Whether sending is actually blocked pending re-verification — the REAL gate, nothing else. */
    val isPaused: Boolean,
    val modelName: String,
    val providerDisplayName: String,
    val heroTone: TrustTone,
    val metaLine: String,
    /** Non-null for the on-device / self-hosted / not-attestable screens. */
    val none: NoneScreen?,
    val alertTitle: String,
    val alertMessage: String,
    /** The send option appears ONLY for soft degrades, with an honest label per mode. */
    val bypassLabel: String?,
    val e2eeDegradedReason: String?,
    /** The send question's actions in order, the way out last (E2EESendChoice.choices). */
    val sendChoices: List<E2EESendChoice> = emptyList(),
    /** The proof rung: near.ai's documented checks over this session, plus teemoon's own — VerificationRun.kt, as data. */
    val proof: List<VerificationSection> = emptyList(),
    /** The expert chain (TrustChain.expert); the everyday chain is derived from the verdict's claims. */
    val expertNodes: List<ChainNode> = emptyList(),
    /** The exportable attestation report (AttestationSummary.shareText). */
    val reportText: String = "",
    /** The self-verify python script embedding this session's attested values, or null when there is no record. */
    val selfVerifyScript: String? = null,
    /** The expert rung's evidence sections (TrustLadderView+Sections), as data. */
    val expert: ExpertSections = ExpertSections(),
    /** What the composer asks with: a confirm already given for this record and reason doesn't ask again (near-send-gate-rule). */
    val composerPolicy: TrustSendPolicy = verdict.sendPolicy,
    /** Sending is blocked, or the record is stale and unconfirmed: the everyday chain shows nothing green (TrustChain.everyday). */
    val chainHalted: Boolean = false,
    /** The ask this snapshot shows is the tier-downgrade ask: the only ask whose "send anyway" is the downgrade consent (1.1.1 cut review item 1). */
    val askIsTierDowngrade: Boolean = false,
)

/** One evidence row: "intel tdx quote · done · detail". */
data class EvidenceItem(val label: String, val state: StepState, val detail: String)

/**
 * TrustLadderView+Sections, as data: known code (the enclave groups and the
 * audit-index stamp), genuine hardware, sealed transport, disclosed limits,
 * and what you still trust at each rung. The views only lay these out.
 */
data class ExpertSections(
    val knownCodeIntro: String = "",
    val enclaveGroups: List<EnclaveGroup> = emptyList(),
    /** Shown in place of groups while provenance resolves, or when none exists. */
    val knownCodeEmpty: String? = null,
    val auditStamp: AuditIndexStamp.Stamp? = null,
    val hardware: List<EvidenceItem> = emptyList(),
    val hardwareSubtitle: String = "",
    val transport: EvidenceItem? = null,
    val limits: List<Pair<String, String>> = emptyList(),
    val residualEveryday: String = "",
    /** TrustLadderView+Sections.trustRow(icon, text): the SF symbol name beside each residual-trust line. */
    val residualExpert: List<Pair<String, String>> = emptyList(),
    /** Trust scope for the model enclave's record (near-trust-scope's rows); null when the record has none. */
    val scope: ai.teemoon.confidential.NearTrustScope? = null,
) {
    companion object {
        /**
         * A1 (the maintainer 2026-11-08, approved on the review page; iOS 123d0b28): the TLS card is teemoon's separate probe
         * of the model node's host. The chat goes through near.ai's gateway, which isn't pinned: never say it is.
         */
        const val TRANSPORT_TITLE = "model node's tls key"
        const val TRANSPORT_SUBTITLE = "teemoon's own probe, separate from your chat. your messages go through near.ai's gateway over ordinary tls, sealed end-to-end to the model's key."
        const val TRANSPORT_LABEL = "model node's certificate"
        const val LIMITS_SUBTITLE = "what this build does not do — named, not hidden."

        fun make(session: ConfidentialSession, summary: AttestationSummary, modelName: String, providerName: String, audit: AuditIndex = AuditIndex.shared, nowMs: Long = System.currentTimeMillis()): ExpertSections {
            // until (a) binds the action log, nothing on the screen calls it signed (near-trust-scope, the maintainer 2026-11-06)
            val groups = EnclaveGrouping(session.attestation, session.imageProvenance, session.modelLayerManifest, session.modelLayerVerification, audit,
                actionLogBound = session.nearScope?.deployLogBinding == ai.teemoon.confidential.DeployLogBinding.Bound,
                hardwarePending = session.dcapPending).groups()
            val hardware = ArrayList<EvidenceItem>()
            if (session.attestation != null) {
                summary.dcapState?.let { hardware += EvidenceItem("intel tdx quote", it, summary.dcapDetail) }
                if (summary.showGPURow) hardware += EvidenceItem((session.attestation?.gpuModelName ?: "nvidia gpu").lowercase(), summary.nrasState ?: StepState.PENDING, summary.gpuRowDetail)
            }
            val transport = when (val tls = session.tlsAttestation) {
                // asked and not answered: shown as checking (trust-pending-state Q2, the maintainer 2026-11-06), words already shipped
                null -> if (session.tlsPending) EvidenceItem(TRANSPORT_LABEL, StepState.PENDING, "checking the model node's certificate…") else null
                TlsAttestation.NotPerformed -> null   // no direct host — not applicable
                TlsAttestation.Verified -> EvidenceItem(TRANSPORT_LABEL, StepState.DONE, "teemoon's probe of the model node's host saw a certificate whose key is bound into that node's attestation (`report_data`). your chat doesn't use this connection: it goes through near.ai's gateway, which isn't pinned.")
                is TlsAttestation.Failed -> EvidenceItem(TRANSPORT_LABEL, StepState.STUCK, "probe of the model node: ${tls.reason}")
                // couldn't complete is the orange "couldn't check"; only a mismatch is red (trust-unchecked-glyph)
                // teemoon's own fault reads the approved line alone (trust-verifier-error: nothing added), as the NVIDIA row does
                is TlsAttestation.Inconclusive -> EvidenceItem(TRANSPORT_LABEL, StepState.UNCHECKED,
                    if (tls.reason == ai.teemoon.confidential.NrasVerification.TEEMOON_FAULT) tls.reason else "probe of the model node: ${tls.reason} — could not complete")
            }
            val limits = arrayListOf(
                "gateway domain — standard WebPKI only" to "the completions.near.ai domain is not certificate-pinned; a mis-issued public cert would be accepted.",
                "no oblivious-HTTP relay" to "requests are not routed through a third-party OHTTP relay, so $providerName's gateway can correlate requests to your source address.",
                "metadata visible" to "$providerName still sees that you chat, when, and how much — just not what you say.")
            session.modelLayerManifest?.takeIf { MeasuredConfig.lacksEgressConfinement(it) }?.let {
                limits += "no network egress restriction" to "nothing at the network layer prevents these containers from connecting out — the attested compose declares no internal-only network and no egress allowlist. confinement rests on their audited code behavior."
            }
            return ExpertSections(
                knownCodeIntro = "running $modelName's pinned, published build — its operator can't swap in a version that logs your chats.",
                enclaveGroups = groups,
                knownCodeEmpty = if (groups.isNotEmpty()) null else if (session.attestationState == AttestationState.VERIFYING) "resolving image provenance…" else "no verified image set available.",
                scope = session.nearScope,
                auditStamp = AuditIndexStamp.make(hasIndex = audit.index != null, updated = audit.index?.updated, loadedAtMs = audit.loadedAtMs, nowMs = nowMs),
                hardware = hardware,
                hardwareSubtitle = "real, tamper-resistant chips — vouched for by Intel and NVIDIA themselves, not by $providerName.",
                transport = transport, limits = limits,
                residualEveryday = "you're still trusting that the published open-source code is honest, and that Intel's and NVIDIA's sealed chips really protect your data the way they promise.",
                residualExpert = listOf(
                    "eye" to "the model reads your messages in the clear **inside** the enclave — and no one outside it ($providerName included) can.",
                    "chevron.left.forwardslash.chevron.right" to "$providerName's open-source code is benign — it's public, so anyone can audit it.",
                    "cpu" to "Intel's and NVIDIA's hardware roots and their attestation services."))
        }
    }
}

/** "what you still trust" — the residual trust the proofs cannot remove. iOS VerificationRunView copy, verbatim. */
val RESIDUAL_TRUST: List<String> = listOf(
    "you trust that the model reads your messages in the clear inside the enclave — and that no one outside it (near.ai included) can.",
    "you trust that near.ai’s open-source code is benign — it’s public, so anyone can read it.",
    "you trust Intel’s and NVIDIA’s hardware roots and their attestation services.",
)
const val CLOUD_VERIFIER_URL = "https://github.com/nearai/nearai-cloud-verifier"

/**
 * The plain-language primer, reached from a banner above the verification run.
 *
 * DATA, not composed language (CONTRIBUTING.md: trust copy is data). It makes no claim about THIS session — the run below it does that,
 * and every sentence there traces to a check. This says how the mechanism works in general, which is why it is a
 * constant and why its [url] is the vendor's own documentation rather than anything this app derives.
 *
 * Three short beats with bolded key phrases, not one paragraph. From the iOS design review: the sheet is aimed at
 * the most anxious, least expert reader, and the first version handed exactly that reader a dense wall of text at
 * exactly their moment of anxiety. Keep the beats separate and keep them short.
 */
data class GlossaryEntry(val id: String, val title: String, val definition: String, val url: String) {
    /** The paragraphs, as the sheet renders them — one beat per block, `**…**` spans bold. */
    val beats: List<String> get() = definition.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        val primer = GlossaryEntry(
            id = "primer",
            title = "how teemoon proves your privacy",
            definition =
                "your messages are processed inside a **sealed region of the chip** (a TEE) whose memory the machine’s " +
                "operator can’t read from outside. what the software inside does with your messages is what the published " +
                "reviews check." +
                "\n\n" +
                "before each conversation, teemoon checks **Intel- and NVIDIA-signed proofs** that the hardware is " +
                "genuine, that the images its launch config names are near.ai’s published source, and that your connection is " +
                "encrypted all the way into that sealed hardware." +
                "\n\n" +
                "it runs the same checks near.ai documents — automatically, on your live data — and **shows you the " +
                "signed values** so you can re-verify everything yourself.",
            url = "https://docs.near.ai/cloud/verification/",
        )
    }
}

/**
 * The expert card on a not-attestable screen: WHY there is nothing to attest, in the vocabulary someone would use
 * to check it. TrustLadderView+None, verbatim.
 *
 * Three absences, each naming the thing that is missing rather than the consequence — the everyday rows above
 * already say what it means for the reader. Data, not composed: this is the same fact the send gate reaches its
 * verdict from, spelled out.
 */
object NoneReasons {
    val ROWS: List<Pair<String, String>> = listOf(
        "network" to "no confidential endpoint — this model has no entry in near.ai's `/endpoints` directory, so there's no sealed host to reach.",
        "cpu" to "no enclave quote — without a direct host there's no Intel TDX / NVIDIA GPU quote to verify.",
        "transport" to "transport only — the connection is TLS to the gateway/proxy, not end-to-end encrypted to a model key.",
    )
}

/**
 * Screen-reader names for controls whose visible label is too short to identify them.
 *
 * iOS sets these with `.accessibilityLabel`, which REPLACES the visible text for VoiceOver. Compose's default is the
 * visible text, which is right almost everywhere and wrong exactly here: a screen with three buttons all reading
 * "copy" announces "copy, copy, copy", and "cancel" beside a progress number announces as a dialog's cancel. The
 * visible text stays short because the layout needs it to; the announced name says which one it is.
 *
 * Data rather than literals at the call sites so the copy audit can trace them to iOS, and so the same phrase is
 * not re-typed differently on the second screen that needs it.
 */
object A11yLabels {
    const val RE_RUN_VERIFICATION = "re-run verification"
    const val CANCEL_DOWNLOAD = "cancel download"
    const val CLOSE_SETTINGS = "close settings"
    /** The dimmed area behind a sheet — VoiceOver's name for UIKit's dimming view. Unlabelled, TalkBack read a full-screen "button" on every sheet (I7, 2026-10-04). */
    const val DISMISS_SHEET = "dismiss"
    const val COPY_KEY = "copy key"
    const val SHOW_KEY = "show key"
    const val HIDE_KEY = "hide key"
    const val COPY_DEBUG_INFO = "copy debug info"
    const val COPY_MODEL_ID = "copy model id"
}

/** The banner that opens [GlossaryEntry.primer]. Two lines: the question a first-time reader is actually asking, then what they get. */
object PrimerBanner {
    const val TITLE = "new to this? how teemoon proves your privacy"
    const val SUBTITLE = "a plain-language walkthrough"
    /** The glossary sheet's one link out, to the vendor's documentation. */
    const val LEARN_MORE = "learn more"
}

fun trustSnapshot(session: ConfidentialSession, provider: Provider?, timedOut: Boolean = false, audit: AuditIndex? = null): TrustSnapshot {
    val state = session.attestationState
    val nodeAudit = modelNodeAudit(session, audit)
    val summary = AttestationSummary(
        attestation = session.attestation, state = state, timedOut = timedOut, provider = provider,
        lastRequestUsedE2EE = session.lastRequestUsedE2EE, lastE2EEFailReason = session.lastE2EEFailReason,
        verifiedResponseCount = session.verifiedResponseCount, mismatchedResponseCount = session.effectiveMismatchCount,
        gatewayTrustResponseCount = session.gatewayTrustResponseCount, attestationFetchFailed = session.attestationFetchFailed,
        imageProvenance = session.imageProvenance, dcapVerification = session.dcapVerification, nrasVerification = session.gpuAttestation, listedTrustScopes = session.listedNearScopes,
        tlsAttestation = session.tlsAttestation, modelLayerVerification = session.modelLayerVerification,
        degradeIsHardFailure = session.degradeIsHardFailure, e2eeIntact = session.e2eeBindingIntact, provenanceUnchecked = session.provenanceUnchecked, deadlinePassed = session.tierGaveUp,
        auditState = nodeAudit.state, leaksExposure = nodeAudit.leaksExposure)
    val modelName = modelDisplayName(session, provider)
    val providerName = provider?.name ?: "this provider"
    val isOnDevice = provider?.isLocal == true
    val isSelfHosted = provider?.isSelfHosted == true
    val isPaused = session.requiresE2EEConfirmation
    val auditState = nodeAudit.state
    val verdict = TrustVerdict.make(TrustVerdict.Input(
        attestationState = state, requiresConfirmation = isPaused, degradeIsHardFailure = session.degradeIsHardFailure,
        mismatchedResponseCount = session.effectiveMismatchCount, modelName = modelName, quant = session.modelArtifact?.quant,
        providerDisplayName = providerName, isLocal = isOnDevice, isSelfHosted = isSelfHosted,
        e2eeKeyBound = summary.e2eeKeyBound, provenanceUnchecked = session.provenanceUnchecked, hardwareChecksPending = summary.hardwareChecksPending, listedEnclaves = summary.listedEnclaves, provenanceState = summary.provenanceState, provenanceDetail = summary.provenanceDetail,
        responseSigState = summary.responseSigState, hasSigningAddress = session.attestation?.signingAddress != null,
        quantDrift = session.modelArtifact?.quantDrift == true, auditState = auditState,
        deviceBoundary = DeviceNoun.BOUNDARY, deviceSubject = DeviceNoun.SUBJECT,
        e2eeDegradedReason = session.e2eeDegradedReason, unpublishedButSealed = session.unpublishedOnlyButE2EEIntact,
        leaksExposure = nodeAudit.leaksExposure))
    // The chip reads chipState, the gate attestationState (S30): once "send anyway" answered a pending ask a send may
    // leave while checks run, so the chip must not read verifying then (formal/ ChipNeverVerifyingAtSend).
    val chipState = session.chipState
    val chipMode = if (chipState == state) verdict.chipMode else TrustVerdict.chipMode(TrustVerdict.Input(
        attestationState = chipState, requiresConfirmation = isPaused, degradeIsHardFailure = session.degradeIsHardFailure,
        mismatchedResponseCount = session.effectiveMismatchCount, modelName = modelName, quant = session.modelArtifact?.quant,
        providerDisplayName = providerName, isLocal = isOnDevice, isSelfHosted = isSelfHosted))
    val chip = TrustChipModel.from(chipMode, chipState, session.unpublishedOnlyButE2EEIntact, softButSealed = !session.degradeIsHardFailure && session.softButSealed)
    // A LEAKS review or unchecked code demotes green to orange, never red (the seal still passes); `incomplete` doesn't.
    val heroTone = if (summary.headerSeverity == AttestationSummary.HeaderSeverity.OK && auditState == EverydayAuditState.LEAKS) TrustTone.ADVISORY
    else when (summary.headerSeverity) {
        AttestationSummary.HeaderSeverity.OK -> TrustTone.OK
        AttestationSummary.HeaderSeverity.ADVISORY -> TrustTone.ADVISORY
        AttestationSummary.HeaderSeverity.FAILED -> TrustTone.FAILED
        AttestationSummary.HeaderSeverity.NEUTRAL -> TrustTone.NEUTRAL
    }
    // Under "sending paused" a "verified just now" stamp contradicts the verdict — the record was fetched, but verification did not pass.
    val metaLine = TrustChain.metaLine(state, isPaused, summary.timestampText(), session.attestation)
    val expertNodes = TrustChain.expert(session.attestation, state, summary, session.modelArtifact, modelName)
    val none = if (state != AttestationState.NONE) null else noneScreen(session, provider, modelName, providerName, isOnDevice, isSelfHosted)
    val hard = session.degradeIsHardFailure
    val provenanceOnly = session.provenanceBlockedButE2EEIntact
    // A soft state in which the send would still go out sealed asks "still checking near.ai's hardware", never
    // "end-to-end encryption unavailable · send without encryption" — with a peer present that sent sealed anyway
    // (near-send-gate-rule, the maintainer 2026-11-06; the predicate is the send's own: ConfidentialSession.softButSealed).
    // An inconclusive provenance lookup asks "couldn't confirm near.ai's software" (S30). The question is SendChoices.kt's E2EEAsk.
    // Item 1 (iOS d2d70d45): the tier-downgrade question comes first, before any other.
    val ask = session.tierDowngradeAsk?.let(E2EEAsk::tierDowngrade) ?: E2EEAsk.make(hardFailure = hard, pendingAsk = null, provenanceUnchecked = session.provenanceUncheckedAsks,
        softButSealed = session.softButSealed, provenanceBlocked = provenanceOnly, cause = session.e2eeDegradedReason, tierGaveUp = session.tierGaveUp,
        enclaveListFailed = session.enclaveListFailedAsks)
    val alertTitle = ask.title
    val sendChoices = ask.choices
    val alertMessage = ask.message
    // When E2EE is intact and only provenance failed, a send is still sealed end-to-end, so "send without encryption" would be false and scarier than the truth.
    val bypassLabel = sendChoices.firstOrNull { it is E2EESendChoice.SendAnyway }?.label
    return TrustSnapshot(state, summary, verdict, chip, isPaused, modelName, providerName, heroTone, metaLine, none, alertTitle, alertMessage, bypassLabel, session.e2eeDegradedReason, sendChoices,
        proof = if (state == AttestationState.NONE) emptyList() else summary.verificationRun, expertNodes = expertNodes,
        reportText = summary.shareText(session.e2eeMessageCount, session.verifiedResponseCount, session.mismatchedResponseCount, session.gatewayTrustResponseCount),
        selfVerifyScript = summary.selfVerifyScript,
        expert = if (state == AttestationState.NONE) ExpertSections() else ExpertSections.make(session, summary, modelName, providerName, audit ?: AuditIndex.shared),
        composerPolicy = session.sendPolicyAfterConfirmations,
        chainHalted = verdict.sendPolicy == TrustSendPolicy.BLOCK || session.recordStaleUnconfirmed,
        askIsTierDowngrade = session.tierDowngradeAsk != null)
}

/**
 * Short display name for the model. Prefers the *pinned* artifact's clean name
 * (from the hash-verified model-layer YAML) over the provider's served alias,
 * which can misstate the model — and only while that record passes the read
 * gate. A downloaded model prints the catalog's own name, never its repo id.
 */
fun modelDisplayName(session: ConfidentialSession, provider: Provider?): String {
    if (session.attestation != null) session.modelArtifact?.baseModelName?.takeIf { it.isNotEmpty() }?.let { return it }
    provider?.localModelID?.let { id -> LocalModelCatalog.model(id)?.let { return it.displayName } }
    val raw = provider?.model ?: "the model"
    return raw.split('/').lastOrNull { it.isNotEmpty() } ?: raw
}

/** The model enclave's everyday audit state, and the exposure summary of the first leaking page (iOS ModelNodeAudit, 8bfe33cb). */
data class ModelNodeAudit(val state: EverydayAuditState?, val leaksExposure: String?)

/**
 * The everyday audit rollup over the rows whose code can read the unlocked message: plaintext touchers, the in-enclave
 * engine, the guest OS. Never read by the send gate.
 */
fun modelNodeAudit(session: ConfidentialSession, audit: AuditIndex?): ModelNodeAudit {
    val none = ModelNodeAudit(null, null)
    if (session.attestation == null || audit == null) return none
    val group = EnclaveGrouping(session.attestation, session.imageProvenance, session.modelLayerManifest, session.modelLayerVerification, audit)
        .groups().firstOrNull { it.primary } ?: return none
    // the pages that judge THIS chat: its path's revisions and images, a sibling's recipe leak dropped (iOS b3d90fac)
    val scope = session.nearScope
    return modelNodeAuditOver(group, hasAttestation = true, chatPath = scope?.chatPath,
        runningSources = scope?.runningServices?.mapNotNull(ai.teemoon.confidential.ChatPath::sourceOf).orEmpty(), composeManager = scope?.composeManager,
        listed = session.attestation?.modelCandidates, listFailed = session.attestation?.modelCandidatesListFailed == true, listedScopes = session.listedNearScopes, audit = audit)
}

/** iOS ModelNodeAudit.state: the rollup over the model enclave [group], and the listed enclaves too (A5). Pure, so the rows can be tested on fixtures. */
internal fun modelNodeAuditOver(
    group: EnclaveGroup?, hasAttestation: Boolean, chatPath: ai.teemoon.confidential.ChatPath.Result?, runningSources: List<ai.teemoon.confidential.DeployLog.Source>,
    composeManager: ai.teemoon.confidential.ComposeManagerSeat?, listed: ai.teemoon.confidential.ModelCandidates.Listed? = null, listFailed: Boolean = false,
    listedScopes: List<ai.teemoon.confidential.NearTrustScope>? = null, audit: AuditIndex,
): ModelNodeAudit {
    val none = ModelNodeAudit(null, null)
    if (!hasAttestation || group == null) return none
    val relevant = group.images.filter { it.plaintextRole != null || it.kind == ImageEntry.Kind.LOCAL || it.kind == ImageEntry.Kind.GUEST_OS }
    if (relevant.isEmpty()) return none
    val all = mutableListOf(nodeAuditRollup(relevant.mapNotNull { it.auditURL } + listOfNotNull(group.configAuditURL, group.recipe?.auditURL),
        covered = relevant.all(::coversForEveryday), chatPath = chatPath, runningSources = runningSources, wholeHost = false, composeManager = composeManager, audit = audit))
    // A list that failed names no holder; a scope not in yet is not a review.
    if (listFailed) all += ModelNodeAudit(EverydayAuditState.INCOMPLETE, null)
    val enclaves = listed?.enclaves.orEmpty()
    if (enclaves.isNotEmpty()) {
        val scopes = listedScopes?.takeIf { it.size == enclaves.size }
        for ((i, enclave) in enclaves.withIndex()) all += listedAuditState(enclave, scopes?.get(i), audit)
    }
    return worstAudit(all)
}

/** The read enclave's computation, on one enclave's pages (iOS ModelNodeAudit.rollup, extracted in A5 so each listed enclave runs the same code). */
private fun nodeAuditRollup(
    base: List<String>, covered: Boolean, chatPath: ai.teemoon.confidential.ChatPath.Result?, runningSources: List<ai.teemoon.confidential.DeployLog.Source>,
    wholeHost: Boolean, composeManager: ai.teemoon.confidential.ComposeManagerSeat?, audit: AuditIndex,
): ModelNodeAudit {
    val urls = ai.teemoon.confidential.ModelNodeAuditPath.onPath(base, chatPath, runningSources, audit, wholeHost)
    // A week-old index is no index for the everyday claim; the expert stamp still shows its age (trust-audit-claim).
    if (!audit.isFreshForEveryday) return ModelNodeAudit(EverydayAuditState.INCOMPLETE, null)
    // a page with no class, or one this build doesn't know, stays in as a gap (null) — it used to be dropped
    val classes = urls.map { audit.verdictClass(it) }
    val managerUnreviewed = ai.teemoon.confidential.TrustScopeText.runningManagerUnreviewed(composeManager) { audit.imageAuditURL("nearaidev/compose-manager", "sha256:$it") }
    val state = ai.teemoon.confidential.withRunningManager(everydayAuditState(classes, allTouchersCovered = covered), managerUnreviewed)!!
    val exposure = if (state == EverydayAuditState.LEAKS)
        urls.firstOrNull { audit.verdictClass(it) == ai.teemoon.confidential.AuditVerdictClass.LEAKS }?.let(audit::exposureSummary) else null
    return ModelNodeAudit(state, exposure)
}

/**
 * One listed enclave, scoped by everything its host runs: its measured compose (only over a clean entry), its guest OS and every running revision must
 * have a review. An off-path LEAKS page still drops out (onPath). A null scope is pending: not a review.
 */
private fun listedAuditState(enclave: ai.teemoon.confidential.ModelCandidates.Enclave, scope: ai.teemoon.confidential.NearTrustScope?, audit: AuditIndex): ModelNodeAudit {
    if (scope == null) return ModelNodeAudit(EverydayAuditState.INCOMPLETE, null)
    val measured = if (enclave.result == ai.teemoon.confidential.ModelCandidates.Result.Clean) audit.measuredAuditURL(enclave.composeHash) else null
    val os = enclave.osImageHash?.let(audit::osAuditURL)
    val sources = scope.runningServices.mapNotNull(ai.teemoon.confidential.ChatPath::sourceOf)
    // a running row the log gave no commit or file for names no page that could review it: not covered
    val covered = measured != null && os != null && !scope.listedEvidenceAbsent &&
        scope.runningServices.all { r -> ai.teemoon.confidential.ChatPath.sourceOf(r)?.let { audit.manifestAuditURL(it.file, it.fileSha256) } != null }
    return nodeAuditRollup(listOfNotNull(measured, os), covered, scope.chatPath, sources, wholeHost = true, composeManager = scope.composeManager, audit = audit)
}

/** leaks > incomplete > reviewedWithCaveat > reviewed; the first leaking enclave's sentence. */
private fun worstAudit(all: List<ModelNodeAudit>): ModelNodeAudit {
    val order = listOf(EverydayAuditState.LEAKS, EverydayAuditState.INCOMPLETE, EverydayAuditState.REVIEWED_WITH_CAVEAT, EverydayAuditState.REVIEWED)
    val state = order.firstOrNull { s -> all.any { it.state == s } } ?: EverydayAuditState.INCOMPLETE
    return ModelNodeAudit(state, if (state == EverydayAuditState.LEAKS) all.firstNotNullOfOrNull { if (it.state == EverydayAuditState.LEAKS) it.leaksExposure else null } else null)
}

/**
 * Whether an image's review counts as coverage for the everyday claim. A tag-pinned image's tag page reviews whatever the
 * tag named then, not the bytes running: never coverage (trust-audit-tag-pages, the maintainer 2026-11-06).
 */
internal fun coversForEveryday(entry: ImageEntry): Boolean = entry.auditURL != null && entry.kind != ImageEntry.Kind.TAG_PINNED

private fun noneScreen(session: ConfidentialSession, provider: Provider?, modelName: String, providerName: String, isOnDevice: Boolean, isSelfHosted: Boolean): NoneScreen {
    // On device FIRST, and it cannot be folded into the self-hosted branch: a local provider's endpoint has no host, so isSelfHosted is false for it.
    if (isOnDevice) return NoneScreen(heroSymbol = "lock.iphone", heroTitle = "nothing leaves this phone unless you turn on web search", heroLead =
        
        "$modelName runs on this device. your messages are answered here, so there is no server to trust and no network to protect them on. web search, if you turn it on, sends its queries to Brave.",
        rows = listOf(
            NoneRow(true, "your messages never leave the phone; with web search on, only its queries go to Brave", "the model is a file on this device and teemoon runs it here. with web search off, nothing about this chat is transmitted; with it on, only the search queries go to Brave."),
            NoneRow(true, "no operator to trust", "there is no company on the other end of this chat, because there is no other end. nobody is metering it and nobody could be asked to hand it over."),
            NoneRow(true, "works with the network off", "airplane mode changes nothing here — which is the plainest proof that nothing is leaving.")))
    if (isSelfHosted) {
        val tls = provider?.openAIBaseUrl?.lowercase()?.startsWith("https://") == true
        return NoneScreen(heroSymbol = "lock.laptopcomputer", heroTitle = "on your own machine", heroLead =
            "this model runs on hardware you control. teemoon talks to it directly — no company sits in the middle.",
            rows = listOf(
                NoneRow(true, "your messages don't leave your network", "teemoon sends them straight to your own server, over your local network or tailnet. no third party receives them."),
                NoneRow(true, "no operator to trust", "there is no provider here who could read your chats. the only party with access is you, on the machine serving the model."),
                if (tls) NoneRow(true, "encrypted in transit", "the connection to your server uses https, so the hop across your network is protected too.")
                else NoneRow(false, "not encrypted in transit", "the connection to your server is plain http. that is usually fine on a network you own, but anyone else on it could read the traffic."),
                NoneRow(false, "not cryptographically attested", "teemoon can't prove which binary is running, the way it can inside a sealed enclave. you control the machine, so you may not need that — but there is no proof here you could show someone else.")))
    }
    val lead = if (session.noConfidentialEndpoint) "$modelName isn't served inside a sealed enclave, so this chat can't be end-to-end encrypted."
    else "$providerName doesn't offer hardware attestation, so this chat can't be end-to-end encrypted to a sealed model."
    return NoneScreen("not end-to-end encrypted", lead,
        listOf(
            NoneRow(true, "still encrypted in transit", "standard HTTPS protects your messages on the way to $providerName — like any secure site."),
            NoneRow(false, "not end-to-end to a sealed model", "$providerName can read your messages — nothing seals them to the model's hardware."),
            NoneRow(false, "nothing to cryptographically verify", "there's no hardware attestation, so teemoon can't prove where this runs or who can see it.")),
        footnote = if (session.noConfidentialEndpoint) "near.ai runs many models inside sealed hardware — $modelName just isn't one of them. It's served as an ordinary model, not a confidential one." else null,
        // ALWAYS on this screen, and only on this screen.
        //
        // This was `provider?.endpoint?.contains("near.ai") == true` until 2026-09-21, which is half of iOS's
        // `nearAIProvider` — the active provider when it is near.ai — with its `else providers.first { near.ai }`
        // dropped. The effect was to hide the way out from exactly the person it is for: on Fireworks, on NVIDIA,
        // on OpenRouter, the screen explained that nothing could be verified and then offered nothing. Found by
        // running the build on a phone; no test could see it, because the CTA had no renderer until today.
        //
        // Broader than iOS's condition on purpose. iOS hides the button when no near.ai provider is configured,
        // because its only action is a model picker and there would be nothing to pick with. EncryptedModelCTA
        // resolves to AddNearAIKey in that case, which IS actionable — so there is never nothing to offer here.
        //
        // The on-device and self-hosted screens return above and never reach this, which is the important half of
        // the scoping: offering to move the user off the most private option teemoon has would be the one place
        // this button is wrong.
        offersEncryptedModels = true)
}
