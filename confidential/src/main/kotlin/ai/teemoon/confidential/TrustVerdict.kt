// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (everything but TrustSendPolicy, which is its own file)
// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (heroTitle / heroSubtitle: a reviewed leak outranks a provenance gap)
// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (chipMode: a mismatch stays on the chip through a provenance sweep)
package ai.teemoon.confidential

/**
 * Worst-verdict rollup for the model node. Any `leaks` wins; full coverage
 * with no leaks/inconclusive is reviewed; anything else is a gap.
 */
enum class EverydayAuditState { LEAKS, REVIEWED, INCOMPLETE }

fun everydayAuditState(classes: List<AuditVerdictClass>, allTouchersCovered: Boolean): EverydayAuditState {
    if (AuditVerdictClass.LEAKS in classes) return EverydayAuditState.LEAKS
    if (allTouchersCovered && classes.isNotEmpty() && AuditVerdictClass.INCONCLUSIVE !in classes) {
        return EverydayAuditState.REVIEWED
    }
    return EverydayAuditState.INCOMPLETE
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

        fun chipCaption(mode: TrustChipMode, state: AttestationState, unpublishedButSealed: Boolean = false): String =
            when (mode) {
                TrustChipMode.HARD_BLOCK -> "verification failed — sending blocked"
                TrustChipMode.MISMATCH -> "a reply didn’t check out"
                TrustChipMode.ON_DEVICE -> "on this device"
                TrustChipMode.SELF_HOSTED -> "on your own machine"
                TrustChipMode.VERIFIED -> state.caption
                TrustChipMode.VERIFYING -> state.caption
                TrustChipMode.SOFT_DEGRADE ->
                    if (unpublishedButSealed) "encrypted — image unpublished" else state.caption
                TrustChipMode.NONE -> state.caption
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
            if (input.auditState == EverydayAuditState.LEAKS) return "encrypted to $label — but its logs copy what you type"
            if (input.unpublishedButSealed) return "encrypted to $label — one image unpublished"
            return "encrypted to $label — only it can read this"
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
                return "the seal holds on the way in, but a review of this exact build found your messages are copied into its operator's monitoring logs — see the ladder below."
            }
            if (input.unpublishedButSealed) {
                return "your message is still sealed to ${input.modelName}'s hardware key. one running image has no published attestation — see the ladder."
            }
            return "only you and ${input.modelName}'s sealed hardware can read this chat."
        }

        private fun everydayClaims(input: Input): List<TrustClaim> {
            val verifying = input.attestationState == AttestationState.VERIFYING
            val model = input.modelName
            val nodes = ArrayList<TrustClaim>()

            nodes.add(TrustClaim("msg", TrustClaim.Status.ORIGIN, title = "your message", detail = "readable only here"))

            nodes.add(when (input.e2eeKeyBound) {
                true -> TrustClaim("sealed", TrustClaim.Status.OK,
                    title = "sealed so only $model can open it",
                    detail = "before anything leaves ${input.deviceBoundary} it's locked to a key that lives inside $model's sealed chip and never comes out — not ${input.providerDisplayName}, not the network, nobody else can open it.")
                false -> TrustClaim("sealed", TrustClaim.Status.FAIL,
                    title = "couldn't seal this chat",
                    detail = "${input.deviceSubject} couldn't confirm $model holds the key — so nothing has been sent.")
                null -> TrustClaim("sealed", TrustClaim.Status.PENDING,
                    title = "sealing this chat to $model",
                    detail = "locking it to a key only $model's sealed chip holds…")
            })

            nodes.add(when (input.provenanceState) {
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

            auditClaim(input.auditState)?.let { nodes.add(it) }

            if (input.quantDrift) {
                nodes.add(TrustClaim("drift", TrustClaim.Status.INFO,
                    title = "it's more compressed than the name says",
                    detail = "answers may be slightly lower quality than the full-size model — your privacy isn't affected."))
            }

            if (input.hasSigningAddress || verifying) {
                val signedDetail = "each answer carries a signature only $model's sealed chip can make, so you know it really came from the model."
                nodes.add(when (input.responseSigState) {
                    StepState.STUCK -> TrustClaim("signed", TrustClaim.Status.FAIL,
                        title = "a reply didn't come back signed",
                        detail = "one answer didn't carry a valid signature from $model's sealed chip.")
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

        private fun auditClaim(state: EverydayAuditState?): TrustClaim? = when (state) {
            null -> null
            EverydayAuditState.LEAKS -> TrustClaim("audit", TrustClaim.Status.ALERT,
                title = "this setup copies what you type into its operator's logs",
                detail = "a review of this exact build found your messages are copied somewhere others can read — near.ai's monitoring systems can see what you type here. The seal still protects your message on the way in; this is about what happens after it's unlocked.",
                jumpTitle = "see the proof", jumpTarget = "known code", jumpAtEveryday = true)
            EverydayAuditState.REVIEWED -> TrustClaim("audit", TrustClaim.Status.OK,
                title = "the code that can read your message was reviewed",
                detail = "teemoon read the software that handles your unlocked message — in this exact setup, none of it is set to copy your words anywhere else.",
                jumpTitle = "see the proof", jumpTarget = "known code", jumpAtEveryday = true)
            EverydayAuditState.INCOMPLETE -> TrustClaim("audit", TrustClaim.Status.INFO,
                title = "not everything here has been reviewed yet",
                detail = "some of the software that can see your unlocked message hasn't been reviewed — a gap in the checking, not an all-clear.")
        }
    }
}
