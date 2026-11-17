// ports: teemoon/Confidential/NRASService.swift @ ee5d1b51 (the NRASVerification value type only)
package ai.teemoon.confidential

/** NVIDIA NRAS outcome for a record's GPU evidence. */
sealed interface NrasVerification {
    /** NRAS verdict is PASS and the evidence nonce matches our request nonce. */
    data object Verified : NrasVerification
    /**
     * NVIDIA rejected the evidence, the nonce didn't echo, or the evidence or
     * NVIDIA's answer is malformed (near-gate-nvidia-tls-split). HARD: blocks.
     */
    data class Failed(val reason: String) : NrasVerification
    /**
     * An outage, or teemoon's own fault ([TEEMOON_FAULT]). Soft: asks, never
     * blocks — a network blip must never be branded tamper.
     */
    data class Inconclusive(val reason: String) : NrasVerification

    val isVerified: Boolean get() = this is Verified
    /** Drives the red, blocking tier. [Inconclusive] is soft (confirm-to-proceed), never hard. */
    val isHardFailure: Boolean get() = this is Failed

    companion object {
        /** trust-verifier-error's approved line, for a check that failed inside teemoon. One string with VERIFIER_ERROR: no new words. */
        const val TEEMOON_FAULT = ConfidentialSession.VERIFIER_ERROR
        /** A near.ai model node that answered without GPU evidence (gpu_evidence_required, S37 B4): couldn't check, never verified. */
        const val EVIDENCE_ABSENT = "the model's hardware attestation is unavailable."
    }
}
