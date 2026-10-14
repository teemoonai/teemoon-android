// ports: teemoon/Confidential/TLSAttestationVerifier.swift @ v1.0.1 (the TLSAttestation value type only)
package ai.teemoon.confidential

/** TLS-attestation outcome — whether HTTPS terminates in the model TEE. */
sealed interface TlsAttestation {
    /** Connection provably terminates inside the attested TEE. */
    data object Verified : TlsAttestation
    /**
     * A genuine NEGATIVE binding — the live certificate does NOT match the
     * attested fingerprint (possible MITM). Adversarial: a HARD failure.
     */
    data class Failed(override val reason: String) : TlsAttestation
    /**
     * The check couldn't be completed — request failed, no certificate,
     * missing/unparseable fields. Fail-closed (still degrades) but NOT
     * adversarial: a network blip must never be branded interception.
     */
    data class Inconclusive(override val reason: String) : TlsAttestation
    /** Not attempted (no direct host to verify against). */
    data object NotPerformed : TlsAttestation

    val isVerified: Boolean get() = this is Verified
    /** True only for a genuine fingerprint mismatch — drives the RED hard tier. */
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
