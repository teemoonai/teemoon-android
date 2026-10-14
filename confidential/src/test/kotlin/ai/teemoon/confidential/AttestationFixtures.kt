package ai.teemoon.confidential

import java.time.Instant

/** Shared builders for the attestation suites — the Swift test helpers, once. */
internal object AttestationFixtures {
    /** A verification result with a chosen report_data (all else zeroed). */
    fun verification(reportData: ByteArray, mrConfigId: ByteArray = ByteArray(48), signatureValid: Boolean = true, certChainValid: Boolean = true, certChainError: String? = null) =
        TdxVerificationResult(TdxMeasurements.stub(reportData, mrConfigId), signatureValid, certChainValid, certChainError)

    /**
     * Clones a record with selected overrides, keeping only the sixteen fields
     * the Swift helper passes — the optional vars reset to their defaults, as
     * on iOS. [fetchedAt] defaults to now so timestampText hits "just now".
     */
    fun clone(base: AttestationRecord, modelQuoteVerification: TdxVerificationResult? = base.modelQuoteVerification, fetchedAt: Instant = Instant.now()) =
        AttestationRecord(
            composeHash = base.composeHash, mrtd = base.mrtd, osImageHash = base.osImageHash,
            intelQuote = base.intelQuote, composeManifest = base.composeManifest, gpuArch = base.gpuArch,
            gpuNodeComposeHash = base.gpuNodeComposeHash, modelFileHash = base.modelFileHash,
            signingAddress = base.signingAddress, gpuSigningAddress = base.gpuSigningAddress,
            modelEd25519PubKey = base.modelEd25519PubKey, quoteVerification = base.quoteVerification,
            gpuQuoteVerification = base.gpuQuoteVerification, modelQuoteVerification = modelQuoteVerification,
            fetchedAt = fetchedAt, providerID = base.providerID,
        )

    fun ref(image: String, digestByte: Char = 'a') = ImageRef(image = image, digest = digestByte.toString().repeat(64))
    fun fetchFailed(detail: String = "HTTP 404") = ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed(detail))
}
