// ports: teemoon/Confidential/TEESignatureVerifier.swift @ v1.1.0 (the ResponseVerification value type only —
//        the verifier is TeeSignature.kt)
package ai.teemoon.confidential

/**
 * The result of verifying one response against the attested signing key.
 *
 * Either the response is [Verified] against the attested key, or it is
 * [Unverified] with the reason. There is deliberately no way to express
 * "verified, sort of" — UI that claims per-response cryptographic
 * verification can only do so from [Verified].
 */
sealed interface ResponseVerification {
    /** Signing address matches the attestation — response is TEE-authentic. */
    data class Verified(val signingAddress: String) : ResponseVerification

    /** Per-response verification did not succeed; the reason says what guarantee (if any) remains. */
    data class Unverified(val reason: UnverifiedReason) : ResponseVerification

    sealed interface UnverifiedReason {
        /**
         * A valid `gateway`-kind signature by the attested, quote-bound
         * gateway key. Proves the gateway relayed these bytes, not that the
         * model wrote them; rendered distinctly, never as verified.
         */
        data class GatewayTrustOnly(val signingAddress: String) : UnverifiedReason

        /**
         * A valid model-node signature whose key is not bound to a quote
         * (no GPU quote, or report_data does not carry the address).
         */
        data class SignerUnattested(val signingAddress: String) : UnverifiedReason

        /** Signing address mismatch — possible tampering or wrong attestation. */
        data class SignatureMismatch(val expected: String, val got: String) : UnverifiedReason

        /**
         * The signed request/response hashes do not match the content teemoon
         * actually sent/received — possible tampering in transit.
         */
        data class ContentMismatch(val detail: String) : UnverifiedReason

        /** Signature not yet available (TEE stores signatures asynchronously). */
        object SignatureUnavailable : UnverifiedReason

        /** Network or parse error while fetching the signature. */
        data class FetchFailed(val error: Throwable) : UnverifiedReason
    }
}
