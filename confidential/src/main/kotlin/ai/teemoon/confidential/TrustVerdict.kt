// ports: teemoon/Confidential/TrustVerdict.swift @ 86be40f6 (the maintainer 2026-11-20: the reviewed hero is 'encrypted to <model>'s enclave key · <quant>')
// ports: teemoon/Confidential/TrustVerdict.swift @ e9e2e747 (design review: sealedStatus, the sealed row's mark follows the listed enclaves)
// ports: teemoon/Confidential/TrustVerdict.swift @ 37de10d9 (1.1.1 audit A9, card trust-copy-sweep-111 v4: heroTitle, heroSubtitle and the sealed row name the enclave key, no 'only it can read')
// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (everything but TrustSendPolicy, which is its own file)
// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (heroTitle / heroSubtitle: a reviewed leak outranks a provenance gap)
// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (chipMode: a mismatch stays on the chip through a provenance sweep)
// ports: teemoon/Confidential/TrustVerdict.swift @ a6f11ac7 (1.1.1 audit A7: Input.listedEnclaves); before that @ 5b56b7b4 (key-custody copy: KeyCustodyCopy.kt makes the sealed / signed sentences)
// ports: teemoon/Confidential/TrustVerdict.swift @ 8bfe33cb (trust-leaks-wording: AuditedSealText, heroTitle, heroSubtitle, auditClaim)
package ai.teemoon.confidential

/**
 * Worst-verdict rollup for the model node (trust-audit-claim, the maintainer 2026-11-06; iOS's rules). Any `leaks` wins.
 * Green only when every relevant page is covered, its class is known, and it is private or a qualified pass; a
 * compromisable page gives the caveat; a missing or unknown class (null) is a gap, never dropped.
 */
enum class EverydayAuditState { LEAKS, REVIEWED, REVIEWED_WITH_CAVEAT, INCOMPLETE }

/** trust-leaks-wording (the maintainer 2026-11-06): "only you … can read" is claimed only over a reviewed build. */
object AuditedSealText {
    const val NOT_REVIEWED = "sealed to the model's hardware on the way in. not everything that handles it inside has been reviewed — see below."
    const val LEAKS_LEAD = "sealed to the model's hardware on the way in. a review of this exact build found:"
}

/** running-manager-unreviewed (the maintainer, 2026-11-06): an unreviewed running manager means not everything is reviewed; a leak stays a leak. */
fun withRunningManager(state: EverydayAuditState?, managerUnreviewed: Boolean): EverydayAuditState? =
    if (managerUnreviewed && (state == EverydayAuditState.REVIEWED || state == EverydayAuditState.REVIEWED_WITH_CAVEAT)) EverydayAuditState.INCOMPLETE else state

fun everydayAuditState(classes: List<AuditVerdictClass?>, allTouchersCovered: Boolean): EverydayAuditState {
    if (AuditVerdictClass.LEAKS in classes) return EverydayAuditState.LEAKS
    if (!allTouchersCovered || classes.isEmpty() || classes.any { it == null || it == AuditVerdictClass.INCONCLUSIVE }) return EverydayAuditState.INCOMPLETE
    return if (AuditVerdictClass.COMPROMISABLE in classes) EverydayAuditState.REVIEWED_WITH_CAVEAT else EverydayAuditState.REVIEWED
}

/** One everyday chain claim. The view maps this onto its chain node. */
data class TrustClaim(
    val id: String,
    val status: Status,
    val title: String,
    val detail: String,
    val jumpTitle: String? = null,
    val jumpTarget: String? = null,
    val jumpAtEveryday: Boolean = false,
) {
    enum class Status { ORIGIN, OK, ALERT, INFO, FAIL, PENDING }
}

/**
 * Title-block / chip display mode. Richer than [AttestationState] so a red
 * integrity break never collapses into the same orange as a benign degrade.
 */
enum class TrustChipMode {
    VERIFIED, VERIFYING, SOFT_DEGRADE, HARD_BLOCK, MISMATCH,
    ON_DEVICE, SELF_HOSTED, NONE,
}

/**
 * The everyday security sentences — hero, title-block caption, send policy,
 * and the five-node chain — as a plain value. The trust ladder, the E2EE title
 * block and the chat view model read this. They do not author the claims.
 *
 * Inputs are plain values (never ConfidentialSession) so the quotes a
 * reviewer will copy can be unit-tested.
 */
data class TrustVerdict(
    val sendPolicy: TrustSendPolicy,
    val heroTitle: String,
    val heroSubtitle: String,
    val chipMode: TrustChipMode,
    val chipCaption: String,
    val everydayClaims: List<TrustClaim>,
) {
    data class Input(
        val attestationState: AttestationState,
        val requiresConfirmation: Boolean = false,
        val degradeIsHardFailure: Boolean = false,
        val mismatchedResponseCount: Int = 0,
        val modelName: String = "",
        val quant: String? = null,
        val providerDisplayName: String = "",
        val isLocal: Boolean = false,
        val isSelfHosted: Boolean = false,
        val e2eeKeyBound: Boolean? = null,
        /** No image could be checked just now (trust-provenance-unchecked): the header says so; the chip doesn't change. */
        val provenanceUnchecked: Boolean = false,
        /** DCAP or NVIDIA still checking: the seal step reads checking, not proven (trust-pending-state Q4). */
        val hardwareChecksPending: Boolean = false,
        val listedEnclaves: KeyCustodyCopy.Listed = KeyCustodyCopy.Listed.CHECKING,
        val provenanceState: StepState? = null,
        val provenanceDetail: String = "",
        val responseSigState: StepState? = null,
        val hasSigningAddress: Boolean = false,
        val quantDrift: Boolean = false,
        val auditState: EverydayAuditState? = null,
        val deviceBoundary: String = "",
        val deviceSubject: String = "",
        val e2eeDegradedReason: String? = null,
        /** Clean GitHub 404s only, E2EE intact. Send is allowed; not fully verified. */
        val unpublishedButSealed: Boolean = false,
        /** A LEAKS review's own exposure summary (modelNodeAudit); null when the index has none. */
        val leaksExposure: String? = null,
    )

    companion object {
        fun sendPolicy(requiresConfirmation: Boolean, hardFailure: Boolean): TrustSendPolicy {
            if (!requiresConfirmation) return TrustSendPolicy.ALLOW
            return if (hardFailure) TrustSendPolicy.BLOCK else TrustSendPolicy.CONFIRM
        }

        fun chipMode(input: Input): TrustChipMode {
            if (input.degradeIsHardFailure) return TrustChipMode.HARD_BLOCK
            // A sealed session that is re-sweeping provenance reads VERIFYING (1102bc8);
            // a reply that failed its check must not vanish for the sweep.
            if (input.mismatchedResponseCount > 0 &&
                (input.attestationState == AttestationState.OK || input.attestationState == AttestationState.VERIFYING)) return TrustChipMode.MISMATCH
            return when (input.attestationState) {
                AttestationState.OK -> TrustChipMode.VERIFIED
                AttestationState.VERIFYING -> TrustChipMode.VERIFYING
                AttestationState.DEGRADED -> TrustChipMode.SOFT_DEGRADE
                AttestationState.NONE ->
                    if (input.isLocal) TrustChipMode.ON_DEVICE
                    else if (input.isSelfHosted) TrustChipMode.SELF_HOSTED
                    else TrustChipMode.NONE
            }
        }

        /** trust-caption-sealed (the maintainer 2026-11-06): a soft state in which the send would still go out sealed. */
        const val SEALED_UNCONFIRMED_CAPTION = "encrypted · hardware not confirmed"

        fun chipCaption(mode: TrustChipMode, state: AttestationState, unpublishedButSealed: Boolean = false, softButSealed: Boolean = false): String =
            when (mode) {
                TrustChipMode.HARD_BLOCK -> "verification failed — sending blocked"
                TrustChipMode.MISMATCH -> "a reply didn’t check out"
                TrustChipMode.ON_DEVICE -> "on this device"
                TrustChipMode.SELF_HOSTED -> "on your own machine"
                TrustChipMode.VERIFIED -> state.caption
                TrustChipMode.VERIFYING -> state.caption
                TrustChipMode.SOFT_DEGRADE ->
                    if (unpublishedButSealed) "encrypted — image unpublished" else if (softButSealed) SEALED_UNCONFIRMED_CAPTION else state.caption
                TrustChipMode.NONE -> state.caption
            }

        /**
         * The sealed step's mark, everyday row and expert "bound" node alike (iOS e9e2e747, design review 2026-11-20).
         * Never green beside a listed-enclaves sentence that says otherwise; ALERT is the ladder's soft couldn't-check.
         */
        fun sealedStatus(hardwareChecksPending: Boolean, listed: KeyCustodyCopy.Listed): TrustClaim.Status = when (listed) {
            KeyCustodyCopy.Listed.CHECKED -> if (hardwareChecksPending) TrustClaim.Status.PENDING else TrustClaim.Status.OK
            KeyCustodyCopy.Listed.CHECKING -> TrustClaim.Status.PENDING
            KeyCustodyCopy.Listed.LIST_FAILED, KeyCustodyCopy.Listed.UNFINISHED -> TrustClaim.Status.ALERT
            KeyCustodyCopy.Listed.FAILED -> TrustClaim.Status.FAIL
        }

        fun make(input: Input): TrustVerdict {
            val policy = sendPolicy(
                requiresConfirmation = input.requiresConfirmation,
                hardFailure = input.degradeIsHardFailure,
            )
            val mode = chipMode(input)
            return TrustVerdict(
                sendPolicy = policy,
                heroTitle = heroTitle(input, paused = input.requiresConfirmation),
                heroSubtitle = heroSubtitle(input, paused = input.requiresConfirmation),
                chipMode = mode,
                chipCaption = chipCaption(mode, input.attestationState, input.unpublishedButSealed),
                everydayClaims = everydayClaims(input),
            )
        }

        private fun heroTitle(input: Input, paused: Boolean): String {
            if (input.attestationState == AttestationState.VERIFYING) return "verifying…"
            if (paused) return if (input.degradeIsHardFailure) "sending blocked" else "sending paused"
            if (input.mismatchedResponseCount > 0) {
                return if (input.mismatchedResponseCount == 1) "one reply didn't check out"
                else "${input.mismatchedResponseCount} replies didn't check out"
            }
            val label = input.quant?.let { "${input.modelName} · $it" } ?: input.modelName
            // A reviewed leak outranks a provenance gap: "your words are copied"
            // is the stronger claim, and an unpublished image must not hide it.
            if (input.unpublishedButSealed && input.auditState != EverydayAuditState.LEAKS) return "encrypted to $label — one image unpublished"
            // The enclave-key tail only over a reviewed build whose code was checked (trust-leaks-wording; the
            // unchecked half is trust-provenance-unchecked) — otherwise the same words, the tail dropped
            if (input.auditState != EverydayAuditState.REVIEWED || input.provenanceUnchecked) return "encrypted to $label"
            // The possessive goes on the model, never the quant ("GLM-5.1 · AWQ's enclave key"; iOS 86be40f6, the maintainer 2026-11-20).
            return "encrypted to ${input.modelName}'s enclave key" + (input.quant?.let { " · $it" } ?: "")
        }

        private fun heroSubtitle(input: Input, paused: Boolean): String {
            if (input.attestationState == AttestationState.VERIFYING) {
                return "confirming this conversation runs inside ${input.modelName}'s sealed hardware."
            }
            if (paused) {
                val cause = input.e2eeDegradedReason ?: "end-to-end encryption isn't established."
                val tail = if (input.degradeIsHardFailure) "sending is blocked until it re-verifies."
                else "nothing is sent without your confirmation."
                return "$cause $tail"
            }
            if (input.mismatchedResponseCount > 0) {
                val phrase = if (input.mismatchedResponseCount == 1) "one reply" else "${input.mismatchedResponseCount} replies"
                return "your session is still sealed and encrypted to ${input.modelName}, but $phrase couldn't be verified — read below."
            }
            if (input.auditState == EverydayAuditState.LEAKS) {
                return input.leaksExposure?.let { "${AuditedSealText.LEAKS_LEAD} $it" } ?: AuditedSealText.NOT_REVIEWED
            }
            if (input.provenanceUnchecked) return PROVENANCE_UNCHECKED_HEADER
            if (input.unpublishedButSealed) {
                return "your message is still sealed to ${input.modelName}'s hardware key. one running image has no published attestation — see the ladder."
            }
            if (input.auditState != EverydayAuditState.REVIEWED) return AuditedSealText.NOT_REVIEWED
            return "sealed to ${input.modelName}'s enclave key. near.ai's key service decides which secure enclaves hold it."
        }

        private fun everydayClaims(input: Input): List<TrustClaim> {
            val verifying = input.attestationState == AttestationState.VERIFYING
            val model = input.modelName
            val nodes = ArrayList<TrustClaim>()

            nodes.add(TrustClaim("msg", TrustClaim.Status.ORIGIN, title = "your message", detail = "readable only here"))

            nodes.add(when (input.e2eeKeyBound) {
                true -> TrustClaim("sealed", sealedStatus(input.hardwareChecksPending, input.listedEnclaves),
                    title = "sealed to $model's enclave key",
                    detail = KeyCustodyCopy.sealedDetail(device = input.deviceBoundary, model = model, listed = input.listedEnclaves))
                false -> TrustClaim("sealed", TrustClaim.Status.FAIL,
                    title = "couldn't seal this chat",
                    detail = "${input.deviceSubject} couldn't confirm $model holds the key — so nothing has been sent.")
                null -> TrustClaim("sealed", TrustClaim.Status.PENDING,
                    title = "sealing this chat to $model",
                    detail = KeyCustodyCopy.sealingPending(model))
            })

            nodes.add(when (input.provenanceState) {
                // in place of the spinner: GitHub didn't answer, nothing was checked (trust-provenance-unchecked)
                StepState.UNCHECKED -> TrustClaim("identity", TrustClaim.Status.INFO, title = PROVENANCE_UNCHECKED_STEP, detail = "")
                StepState.STUCK -> TrustClaim("identity", TrustClaim.Status.FAIL,
                    title = "couldn't confirm it's the published $model",
                    detail = input.provenanceDetail)
                StepState.DONE -> TrustClaim("identity", TrustClaim.Status.OK,
                    title = "it's the real, published $model",
                    detail = "it's the pinned, published build — checked, so a swap would show up as a mismatch.")
                else -> TrustClaim("identity", TrustClaim.Status.PENDING,
                    title = "checking it's the real, published $model",
                    detail = "the model checks out — one code check is still finishing.")
            })

            auditClaim(input.auditState, input.leaksExposure)?.let { nodes.add(it) }

            if (input.quantDrift) {
                nodes.add(TrustClaim("drift", TrustClaim.Status.INFO,
                    title = "it's more compressed than the name says",
                    detail = "answers may be slightly lower quality than the full-size model — your privacy isn't affected."))
            }

            if (input.hasSigningAddress || verifying) {
                val signedDetail = KeyCustodyCopy.replySigned(model)
                nodes.add(when (input.responseSigState) {
                    StepState.STUCK -> TrustClaim("signed", TrustClaim.Status.FAIL,
                        title = "a reply didn't come back signed",
                        detail = KeyCustodyCopy.replySignatureFailed(model))
                    StepState.DONE -> TrustClaim("signed", TrustClaim.Status.OK,
                        title = "every reply comes back signed by $model",
                        detail = signedDetail)
                    else -> TrustClaim("signed", if (verifying) TrustClaim.Status.PENDING else TrustClaim.Status.OK,
                        title = if (verifying) "checking each reply's signature" else "every reply comes back signed by $model",
                        detail = if (verifying) "still confirming — not yet green." else signedDetail)
                })
            }

            return nodes
        }

        private fun auditClaim(state: EverydayAuditState?, leaksExposure: String?): TrustClaim? = when (state) {
            null -> null
            // The reviewer's own sentence; with none recorded, the not-reviewed words (trust-leaks-wording).
            EverydayAuditState.LEAKS -> TrustClaim("audit", TrustClaim.Status.ALERT,
                title = leaksExposure ?: "not everything here has been reviewed yet",
                // only the true sentence: "your messages are copied … near.ai's monitoring systems can see what you type" was
                // the blanket claim the entry removed, untrue where chat never passes the leaking service (pm 2026-11-06)
                detail = "The seal still protects your message on the way in; this is about what happens after it's unlocked.",
                jumpTitle = "see the proof", jumpTarget = "known code", jumpAtEveryday = true)
            EverydayAuditState.REVIEWED -> TrustClaim("audit", TrustClaim.Status.OK,
                title = "the code that can read your message was reviewed",
                detail = "teemoon read the software that handles your unlocked message — in this exact setup, none of it is set to copy your words anywhere else.",
                jumpTitle = "see the proof", jumpTarget = "known code", jumpAtEveryday = true)
            EverydayAuditState.REVIEWED_WITH_CAVEAT -> TrustClaim("audit", TrustClaim.Status.INFO,
                title = "the code that can read your message was reviewed, with a catch",
                detail = "teemoon's AI-assisted review found nothing set to copy your words in this exact setup, but someone with near.ai's operator access could change that.",
                jumpTitle = "see the proof", jumpTarget = "known code", jumpAtEveryday = true)
            EverydayAuditState.INCOMPLETE -> TrustClaim("audit", TrustClaim.Status.INFO,
                title = "not everything here has been reviewed yet",
                detail = "some of the software that can see your unlocked message hasn't been reviewed — a gap in the checking, not an all-clear.")
        }
    }
}
