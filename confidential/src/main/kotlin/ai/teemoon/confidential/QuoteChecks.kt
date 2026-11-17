// ports: teemoon/Confidential/QuoteChecks.swift @ d5e40fee
package ai.teemoon.confidential

/**
 * teemoon's own checks on a TDX quote (near.ai inference-sdk ff591054 policies; S37, the maintainer 2026-11-08). DcapService
 * runs them before Intel is asked, so their verdict never waits on PCS or on dcap-qvl's policy.
 */
object QuoteChecks {
    /** dcap-qvl's own words for a debug TD (verify.rs:970): the DCAP row reads the same whichever check caught it. */
    const val DEBUG_TD = "Debug mode is enabled"
    /** The parser/dcap-qvl tripwire's words, reused: two readings of one quote's report_data disagree. */
    const val REPORT_DATA_DISAGREES = "measurement cross-check mismatch between verifiers"

    /** TDATTRIBUTES byte 0 is TUD and its bit 0 is DEBUG. Do not widen this to other bits: dcap-qvl owns those. */
    fun debugEnabled(tdAttributes: ByteArray): Boolean {
        val tud = tdAttributes.firstOrNull() ?: return true   // an unreadable field is never read as off
        return tud.toInt() and 0x01 != 0
    }

    /** A report_data the report advertises must be the quote's own. Absent is nothing to compare. */
    fun advertisedMatches(advertised: String?, quoteReportDataHex: String): Boolean =
        advertised == null || advertised.lowercase() == quoteReportDataHex.lowercase()

    /** dstack's MRCONFIGID: 0x01 ‖ sha256(app_compose), zero-padded. */
    fun mrConfigCommits(mrConfigId: ByteArray, composeSha256Hex: String): Boolean =
        mrConfigId.toHex().lowercase().startsWith("01" + composeSha256Hex.lowercase())

    /** Evidence in the quote's own bytes; null when there is none or it doesn't parse (dcap-qvl then fails it). */
    fun failure(quoteHex: String, advertisedReportData: String? = null): String? {
        val quote = runCatching { QuoteParser.parseHex(quoteHex) }.getOrNull() ?: return null
        if (debugEnabled(quote.body.tdAttributes)) return DEBUG_TD
        if (!advertisedMatches(advertisedReportData, quote.body.reportData.toHex())) return REPORT_DATA_DISAGREES
        return null
    }
}
