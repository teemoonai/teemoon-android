// ports: teemoon/Confidential/AttestationState.swift @ v1.0.1
package ai.teemoon.confidential

/**
 * The four-way session state the chip and sheet share. Interpretation lives
 * on ConfidentialSession; everyday sentences live on [TrustVerdict].
 */
enum class AttestationState(
    /** Plain caption used when the chip is not in a special mode. */
    val caption: String,
) {
    /** TEE attested and E2EE encryption active. */
    OK("end-to-end encrypted"),
    /** TEE attested but E2EE failed or unavailable (plaintext inside enclave). */
    DEGRADED("not end-to-end encrypted"),
    /** Attestation is being fetched — not yet verified. */
    VERIFYING("verifying…"),
    /** Provider doesn't support TEE — no indicator shown. */
    NONE(""),
}
