// ports: teemoon/Confidential/DCAPService.swift @ e86b13cd (1.1.1 audit A4 read: dcap-qvl's answer memoised by quote for one record's pass);
//        teemoon/Confidential/DCAPVerifier.swift @ v1.0.6 (DCAPVerifier; the value types are in DcapVerification.kt)
//        teemoon/Confidential/DCAPService.swift @ ba0842d1 (DCAPService; 1.1.1 audit A1: every listed enclave's quote goes through verifyQuote)
//
// Real Intel TDX quote verification via Phala's dcap-qvl (the exact library
// near.ai's own verifier uses), through the vendored Android binding. This
// performs the full DCAP verification the hand-rolled TdxQuoteVerifier does
// NOT: Quoting-Enclave report binding and TCB-status evaluation. The
// hand-rolled parser is kept as a display parser and a cross-check tripwire.
//
// The engine is a seam: the binding's native library exists for Android ABIs
// only, so JVM tests drive DcapVerifier with a fake engine, and the kit's
// quotes class is proved against the real engine on the managed device.
package ai.teemoon.confidential

import kotlinx.coroutines.withContext

/** One verified quote as dcap-qvl reports it. */
data class DcapVerifiedQuote(val status: String, val mrConfigIdHex: String, val reportDataHex: String)

/** dcap-qvl's verify(). Throws with the library's reason on failure. */
fun interface DcapQvlEngine {
    fun verify(rawQuote: ByteArray, collateralJson: ByteArray, nowSecs: Long): DcapVerifiedQuote
}

/** The vendored com.phala.dcapqvl binding. Android only — its .so ships per ABI. */
object AndroidDcapQvl : DcapQvlEngine {
    override fun verify(rawQuote: ByteArray, collateralJson: ByteArray, nowSecs: Long): DcapVerifiedQuote {
        val report = com.phala.dcapqvl.verify(rawQuote, collateralJson, nowSecs.toULong())
        val (mrConfig, reportData) = when (val r = report.report) {
            is com.phala.dcapqvl.Report.Td10 -> r.report.mrConfigId.toHex() to r.report.reportData.toHex()
            is com.phala.dcapqvl.Report.Td15 -> r.report.base.mrConfigId.toHex() to r.report.base.reportData.toHex()
            is com.phala.dcapqvl.Report.Sgx -> "" to r.report.reportData.toHex()
        }
        return DcapVerifiedQuote(report.status, mrConfig, reportData)
    }
}

class DcapVerifier(
    private val collateral: CollateralProvider,
    private val engine: DcapQvlEngine = AndroidDcapQvl,
    private val log: (String) -> Unit = {},
) {
    /** Verifies a hex-encoded TDX quote at [nowSecs] (the attestation's fetch time / a trusted time). */
    /** Main-safe: the parse and the native check run on [VerifyWork], never the caller's thread. */
    suspend fun verify(quoteHex: String, nowSecs: Long): DcapVerification = withContext(VerifyWork.dispatcher) { verifyHere(quoteHex, nowSecs) }

    private suspend fun verifyHere(quoteHex: String, nowSecs: Long): DcapVerification {
        val rawQuote = quoteHex.removePrefix("0x").hexToBytesOrNull() ?: return DcapVerification.Failed("quote hex undecodable")
        // A cancel stays a cancel: `catch (e: Exception)` turned it into Failed — a hard verdict — and the check ran
        // on (pm sweep 2026-11-06).
        val collateralJson = try { collateral.collateralJson(rawQuote) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) {
            log("[dcap] collateral unavailable: $e")
            return collateralUnavailable(e)
        }
        return try {
            val report = engine.verify(rawQuote, collateralJson, nowSecs)
            log("[dcap] verified — tcb=${report.status}")
            DcapVerification.Verified(DcapTcbStatus.fromRaw(report.status), report.mrConfigIdHex, report.reportDataHex)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("[dcap] verification failed: $e")
            DcapVerification.Failed(e.message ?: e.toString())
        }
    }

    companion object {
        /**
         * What a quote's verdict is when its collateral couldn't be had. Only an outage or an unreadable PCS answer is
         * "couldn't check" (soft); a quote that can't be parsed, one missing its PCK chain, and anything unrecognised
         * are evidence and stay hard (near-send-gate-rule, the maintainer 2026-11-06; iOS DCAPVerifier.couldntCheck).
         */
        fun collateralUnavailable(e: Exception): DcapVerification =
            if (couldntCheck(e)) DcapVerification.Unchecked("collateral: ${e.message ?: e}") else DcapVerification.Failed("collateral: ${e.message ?: e}")

        internal fun couldntCheck(e: Exception): Boolean = when (e) {
            is java.io.IOException -> true
            // the maintainer 2026-11-06: a 404/400/422 on a request built from the quote's own fields (the TCB info by FMSPC, the
            // PCK CRL by CA) is evidence against the quote — Intel doesn't know this platform, or the quote named it
            // wrong. Everything else — those statuses on what Intel serves everyone (QE identity, the root CRL), no
            // response, 401/403/408/429, 5xx — is outside our control: an outage, never a block. Aligned with iOS's
            // sendgate.json status list when it lands.
            is CollateralHttpException -> !(e.status in setOf(404, 400, 422) && quoteFieldsRequest(e.path))
            is CollateralException.MissingHeader, is CollateralException.MalformedResponse -> true
            else -> false   // QuoteMissingPckChain, QuoteUnreadable, anything unknown
        }

        /** The collateral requests built from the quote's own fields: the TCB info by FMSPC and the PCK CRL by CA. */
        private fun quoteFieldsRequest(path: String) = path.endsWith("/tcb") || path.endsWith("/pckcrl")
    }
}

/**
 * Runs real DCAP verification over every TDX quote in an attestation record —
 * gateway, model TEE, and direct GPU node. This is the authoritative quote
 * verdict; if the hand-rolled parser's MRCONFIGID/report_data ever disagrees
 * with dcap-qvl's, the quote is treated as failed and the disagreement logged
 * loudly. Out-of-date TCB is flagged, not hard; failed, or any TCB
 * status outside the allow-list (UpToDate, OutOfDate), is hard.
 */
class DcapService(collateral: CollateralProvider, engine: DcapQvlEngine = AndroidDcapQvl, private val log: (String) -> Unit = {}) {
    private val verifier = DcapVerifier(collateral, engine, log)

    /** [nowSecs] defaults to the record's fetch time — collateral is fetched fresh, so that time is inside its validity window. */
    /** Main-safe: teemoon's own quote checks and dcap-qvl run on [VerifyWork]. */
    suspend fun verify(record: AttestationRecord, nowSecs: Long? = null): RecordDcapVerification = withContext(VerifyWork.dispatcher) { verifyHere(record, nowSecs) }

    private suspend fun verifyHere(record: AttestationRecord, nowSecs: Long? = null): RecordDcapVerification {
        val now = nowSecs ?: record.fetchedAt.epochSecond
        // dcap-qvl's answer per quote for this record (iOS DCAPService.forOneRecord, e86b13cd): a quote two legs carry, the read enclave being one of the
        // listed, goes to Intel once. teemoon's own checks, the advertised report_data and the cross-check still run per caller on that answer.
        val answers = HashMap<String, DcapVerification>()
        val listed = ArrayList<DcapVerification>()
        for ((i, enclave) in (record.modelCandidates?.enclaves ?: emptyList()).withIndex()) {
            // A listed enclave with no quote is evidence, never skipped (ModelCandidates.check says so too). Each holds the sealed
            // key, so each quote goes through the model leg's own path and the same gates read it (audit 1.1.1 A1).
            val quote = enclave.quoteHex
            if (quote.isNullOrEmpty()) { listed.add(DcapVerification.Failed("listed enclave carries no quote")); continue }
            listed.add(verifyQuote(answers, quote, "listed ${i + 1}", runCatching { TdxQuoteVerifier.verify(quote) }.getOrNull(), now, enclave.advertisedReportData)
                ?: DcapVerification.Failed("listed enclave carries no quote"))
        }
        // Each listed enclave's compose-manager quote, in the same pass (1.1.1 audit A4; iOS e86b13cd: each quote of a record goes to Intel once)
        val listedManagers = (record.modelCandidates?.enclaves ?: emptyList()).mapIndexed { i, enclave ->
            val cm = enclave.report?.get("compose_manager_attestation") as? kotlinx.serialization.json.JsonObject
            val quote = (cm?.get("quote") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            verifyQuote(answers, quote, "listed ${i + 1} compose-manager", null, now)
        }
        return RecordDcapVerification(
            listed = listed, listedManagers = listedManagers,
            gateway = verifyQuote(answers, record.intelQuote, "gateway", record.quoteVerification, now, record.gatewayAdvertisedReportData),
            model = verifyQuote(answers, record.modelIntelQuote, "model", record.modelQuoteVerification, now, record.modelAdvertisedReportData),
            gpu = verifyQuote(answers, record.gpuIntelQuote, "gpu", record.gpuQuoteVerification, now, record.gpuAdvertisedReportData),
            manager = verifyQuote(answers, record.managerQuote, "compose-manager", null, now))
    }

    private suspend fun verifyQuote(answers: MutableMap<String, DcapVerification>, quoteHex: String?, label: String, crossCheck: TdxVerificationResult?, nowSecs: Long, advertised: String? = null): DcapVerification? {
        if (quoteHex.isNullOrEmpty()) return null
        // teemoon's own evidence first (S37 B1/B2): it must not wait on Intel answering, nor lean on dcap-qvl's policy
        QuoteChecks.failure(quoteHex, advertised)?.let { why -> log("[dcap] $label quote failed teemoon's own check: $why"); return DcapVerification.Failed(why) }
        val outcome = answers.getOrPut(quoteHex) { verifier.verify(quoteHex, nowSecs) }
        val verified = outcome as? DcapVerification.Verified ?: run { log("[dcap] $label quote failed: $outcome"); return outcome }
        if (!QuoteChecks.advertisedMatches(advertised, verified.reportDataHex)) {
            log("[dcap] $label advertised report_data is not the verified quote's")
            return DcapVerification.Failed(QuoteChecks.REPORT_DATA_DISAGREES)
        }
        // Cross-check tripwire: the Kotlin parser and dcap-qvl must agree on the measurements. A disagreement means one of them
        // is wrong about security-critical bytes — fail the quote and say so.
        crossCheck?.measurements?.let { m ->
            if (m.mrConfigId.toHex() != verified.mrConfigIdHex.lowercase() || m.reportData.toHex() != verified.reportDataHex.lowercase()) {
                log("[dcap] $label CROSS-CHECK MISMATCH — parser and dcap-qvl disagree on measurements")
                return DcapVerification.Failed("measurement cross-check mismatch between verifiers")
            }
        }
        log("[dcap] $label quote verified — tcb=${verified.tcbStatus.rawValue}")
        return outcome
    }
}
