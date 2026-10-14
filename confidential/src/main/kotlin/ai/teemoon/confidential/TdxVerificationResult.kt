// ports: Packages/TDXQuoteVerifier/Sources/TDXQuoteVerifier/TDXQuote.swift @ v1.0.1 (TDXMeasurements)
//        Packages/TDXQuoteVerifier/Sources/TDXQuoteVerifier/TDXQuoteVerifier.swift @ v1.0.1
//        (TDXVerificationResult; the parser and verifier are TdxQuote.kt)
package ai.teemoon.confidential

/** Extracted measurements for comparison against expected values. */
class TdxMeasurements(
    val mrtd: ByteArray,
    val mrSeam: ByteArray,
    val mrConfigId: ByteArray,
    val mrOwner: ByteArray,
    val rtmr0: ByteArray,
    val rtmr1: ByteArray,
    val rtmr2: ByteArray,
    val rtmr3: ByteArray,
    val reportData: ByteArray,
) {
    companion object {
        /** All-zero measurements except the two the bindings read — for previews and tests. */
        fun stub(reportData: ByteArray, mrConfigId: ByteArray = ByteArray(48)): TdxMeasurements {
            val zero48 = ByteArray(48)
            return TdxMeasurements(zero48, zero48, mrConfigId, zero48, zero48, zero48, zero48, zero48, reportData)
        }
    }
}

/** What the on-device TDX quote check produced for one quote. */
class TdxVerificationResult(
    val measurements: TdxMeasurements,
    /** Whether the ECDSA signature over the quote is valid. */
    val signatureValid: Boolean,
    /** Whether the PCK cert chain is valid and chains to Intel's Root CA. */
    val certChainValid: Boolean,
    /** Human-readable description of any verification failure. */
    val certChainError: String?,
    /** The parsed quote, when this result came from the parser (null for stubs and stored values). */
    val quote: TdxQuote? = null,
) {
    /** True only if both signature and cert chain are cryptographically verified. */
    val isVerified: Boolean get() = signatureValid && certChainValid
}
