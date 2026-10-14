// ports: teemoon/Confidential/NRASService.swift @ v1.0.1 (the NRASVerification value type only)
package ai.teemoon.confidential

/** NVIDIA NRAS outcome for a record's GPU evidence. */
sealed interface NrasVerification {
    /** NRAS verdict is PASS and the evidence nonce matches our request nonce. */
    data object Verified : NrasVerification
    /**
     * A genuine NEGATIVE verdict — NVIDIA rejected the evidence, or the nonce
     * didn't echo (possible replay). Adversarial: this is a HARD failure.
     */
    data class Failed(val reason: String) : NrasVerification
    /**
     * The check couldn't be completed — NRAS unreachable, an unparseable/
     * unrecognized response, no recorded nonce. Fail-closed (still degrades),
     * but NOT adversarial: a network blip must never be branded tamper.
     */
    data class Inconclusive(val reason: String) : NrasVerification

    val isVerified: Boolean get() = this is Verified
    /** True only for a genuine negative verdict — drives the RED hard-failure tier. */
    val isHardFailure: Boolean get() = this is Failed
}
