// ports: teemoon/Confidential/NRASService.swift @ 36d53c97 (1.1.1 audit A10: listedInconclusive(nvidia:), soft; worst-of order: hard > read inconclusive > listed inconclusive > verified)
// ports: teemoon/Confidential/NRASService.swift @ 49608f08 (1.1.1 audit A10: listedFailed(nvidia:), hard, reason is the A10 line)
// ports: teemoon/Confidential/NRASService.swift @ ceb05738 (the NRASVerification value type only; worst() is 1.1.1 audit A3), before that @ ee5d1b51
package ai.teemoon.confidential

/** NVIDIA NRAS outcome for a record's GPU evidence. */
sealed interface NrasVerification {
    /** NRAS verdict is PASS and the evidence nonce matches our request nonce. */
    data object Verified : NrasVerification
    /**
     * NVIDIA rejected the evidence, the nonce didn't echo, or the evidence or
     * NVIDIA's answer is malformed (near-gate-nvidia-tls-split). HARD: blocks.
     */
    data class Failed(override val reason: String) : NrasVerification
    /** A listed enclave's rejection (1.1.1 audit A10, iOS 49608f08): hard, reads the A10 line everywhere; only the expert GPU row adds NVIDIA's own. */
    data class ListedFailed(val nvidia: String) : NrasVerification {
        override val reason: String get() = ListedEnclaveReason.ENCLAVE_FAILED
    }
    /**
     * An outage, or teemoon's own fault ([TEEMOON_FAULT]). Soft: asks, never
     * blocks — a network blip must never be branded tamper.
     */
    data class Inconclusive(override val reason: String) : NrasVerification
    /** A listed enclave's couldn't-check (1.1.1 audit A10, iOS 36d53c97): soft, reads A7's unfinished line; only the expert GPU row adds NVIDIA's. */
    data class ListedInconclusive(val nvidia: String) : NrasVerification {
        override val reason: String get() = KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED)
    }

    /** The line this verdict reads in the reason line and the row; null when verified. */
    val reason: String? get() = null

    val isVerified: Boolean get() = this is Verified
    /** Drives the red, blocking tier. [Inconclusive] is soft (confirm-to-proceed), never hard. */
    val isHardFailure: Boolean get() = this is Failed || this is ListedFailed

    companion object {
        /**
         * The record's GPU verdict over the model leg and every listed enclave (1.1.1 audit A3): any failure, else any
         * couldn't-check, else verified; null with nothing to read. In input order, so the reason shown is stable.
         */
        fun worst(results: List<NrasVerification>): NrasVerification? =
            results.firstOrNull { it.isHardFailure } ?: results.firstOrNull { it is Inconclusive }
                // the read enclave's own couldn't-check beats a listed one's (iOS 36d53c97)
                ?: results.firstOrNull { it is ListedInconclusive } ?: if (results.isEmpty()) null else Verified
        /** trust-verifier-error's approved line, for a check that failed inside teemoon. One string with VERIFIER_ERROR: no new words. */
        const val TEEMOON_FAULT = ConfidentialSession.VERIFIER_ERROR
        /** A near.ai model node that answered without GPU evidence (gpu_evidence_required, S37 B4): couldn't check, never verified. */
        const val EVIDENCE_ABSENT = "the model's hardware attestation is unavailable."
    }
}
