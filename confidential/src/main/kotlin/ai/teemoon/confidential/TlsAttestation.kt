// ports: teemoon/Confidential/TLSAttestationVerifier.swift @ a09537fe (the TLSAttestation value type only)
package ai.teemoon.confidential

/** TLS-attestation outcome — whether HTTPS terminates in the model TEE. */
sealed interface TlsAttestation {
    /** Connection provably terminates inside the attested TEE. */
    data object Verified : TlsAttestation
    /** The live certificate doesn't match the attested binding, or near.ai's evidence is malformed. HARD: blocks. */
    data class Failed(override val reason: String) : TlsAttestation
    /**
     * An outage, or teemoon's own fault. Malformed evidence is [Failed]
     * (near-gate-nvidia-tls-split). Fail-closed (still degrades) but NOT
     * adversarial: a network blip must never be branded interception.
     */
    data class Inconclusive(override val reason: String) : TlsAttestation
    /** Not attempted (no direct host to verify against). */
    data object NotPerformed : TlsAttestation

    val isVerified: Boolean get() = this is Verified
    /** Drives the red, blocking tier. [Inconclusive] is soft (confirm-to-proceed), never hard. */
    val isHardFailure: Boolean get() = this is Failed
    /** Attempted and did not verify (either hard or inconclusive) — both degrade. */
    val isUnverified: Boolean get() = this is Failed || this is Inconclusive
    val reason: String?
        get() = when (this) {
            is Failed -> reason
            is Inconclusive -> reason
            else -> null
        }
}
