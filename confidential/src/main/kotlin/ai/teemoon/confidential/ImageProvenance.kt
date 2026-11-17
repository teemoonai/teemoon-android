// ports: teemoon/Confidential/ImageProvenance.swift @ v1.0.1 (the ImageProvenance value type and its
//        failureReason table only; ProvenanceVerifier.kt is the Sigstore/Rekor half; identityNotThisBuild @ e03c78a5,
//        the outage's source @ cf8002d5)
package ai.teemoon.confidential

/** Whether one image digest traces to a pinned near.ai workflow. */
sealed interface ImageProvenance {
    /**
     * The image digest was signed by a pinned near.ai workflow. [sourceRef] and
     * [sourceCommit] come from the signed SLSA payload — attested build claims,
     * surfaced so the user can open the exact source; the digest match remains
     * the hard guarantee.
     */
    data class Verified(
        val repositoryURL: String,
        val workflowIdentity: String,
        val sourceRef: String?,
        val sourceCommit: String?,
        val transparencyLogChecked: Boolean,
    ) : ImageProvenance

    data class Unverified(val reason: Reason) : ImageProvenance

    sealed interface Reason {
        data class MalformedBundle(val detail: String) : Reason
        data object SignatureInvalid : Reason
        data class CertificateChainInvalid(val detail: String) : Reason
        data class IdentityNotTrusted(val got: String) : Reason
        /** Inside near.ai's org but not the image's own build (wrong repo, workflow or branch). Definitive, like [IdentityNotTrusted]. */
        data class IdentityNotThisBuild(val got: String) : Reason
        data class DigestMismatch(val expected: String, val got: String) : Reason
        /**
         * Definitive: the attestation was fetched and is absent/wrong (e.g.
         * HTTP 404 — no attestation exists for this digest). Fail-closed.
         */
        data class FetchFailed(val detail: String) : Reason
        /**
         * Transient: rate limit, network error, server error — no evidence
         * either way. Callers should treat the check as inconclusive rather
         * than failed.
         */
        data class FetchTransient(val detail: String, val from: TransientSource = TransientSource.GITHUB) : Reason
    }

    /** Only the wording follows the source; every source is equally inconclusive (T2b). */
    enum class TransientSource { GITHUB, DOCKER_HUB }

    val isVerified: Boolean get() = this is Verified

    /**
     * A short, plain-language reason this image is not verified — so the UI
     * can say *why* a specific image failed rather than only counting it.
     * null when verified.
     */
    val failureReason: String?
        get() = when (this) {
            is Verified -> null
            is Unverified -> when (reason) {
                is Reason.FetchFailed -> "no published attestation (GitHub 404)"
                is Reason.FetchTransient -> when (reason.from) {
                    TransientSource.GITHUB -> "couldn’t reach GitHub (rate-limited or offline)"
                    TransientSource.DOCKER_HUB -> "couldn’t reach Docker Hub (rate-limited or offline)"
                }
                is Reason.SignatureInvalid -> "signature didn’t verify"
                is Reason.CertificateChainInvalid -> "certificate didn’t chain to Sigstore’s roots"
                is Reason.IdentityNotTrusted -> "signed by an identity outside near.ai’s org"
                is Reason.IdentityNotThisBuild -> "signed inside near.ai, but not by this image’s build"
                is Reason.DigestMismatch -> "signed digest doesn’t match the running image"
                is Reason.MalformedBundle -> "attestation bundle was malformed"
            }
        }
}
