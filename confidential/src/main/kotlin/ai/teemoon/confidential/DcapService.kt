// ports: teemoon/Confidential/DCAPVerifier.swift @ v1.0.1 (DCAPVerifier; the value types are in DcapVerification.kt)
//        teemoon/Confidential/DCAPService.swift @ v1.0.1 (DCAPService)
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
    suspend fun verify(quoteHex: String, nowSecs: Long): DcapVerification {
        val rawQuote = quoteHex.removePrefix("0x").hexToBytesOrNull() ?: return DcapVerification.Failed("quote hex undecodable")
        val collateralJson = try { collateral.collateralJson(rawQuote) } catch (e: Exception) {
            // Fail-closed, but keep the specific reason for the UI/logs.
            log("[dcap] collateral unavailable: $e")
            return DcapVerification.Failed("collateral: ${e.message ?: e}")
        }
        return try {
            val report = engine.verify(rawQuote, collateralJson, nowSecs)
            log("[dcap] verified — tcb=${report.status}")
            DcapVerification.Verified(DcapTcbStatus.fromRaw(report.status), report.mrConfigIdHex, report.reportDataHex)
        } catch (e: Exception) {
            log("[dcap] verification failed: $e")
            DcapVerification.Failed(e.message ?: e.toString())
        }
    }
}

/**
 * Runs real DCAP verification over every TDX quote in an attestation record —
 * gateway, model TEE, and direct GPU node. This is the authoritative quote
 * verdict; if the hand-rolled parser's MRCONFIGID/report_data ever disagrees
 * with dcap-qvl's, the quote is treated as failed and the disagreement logged
 * loudly. Out-of-date TCB is flagged, not hard; failed or revoked is hard.
 */
class DcapService(collateral: CollateralProvider, engine: DcapQvlEngine = AndroidDcapQvl, private val log: (String) -> Unit = {}) {
    private val verifier = DcapVerifier(collateral, engine, log)

    /** [nowSecs] defaults to the record's fetch time — collateral is fetched fresh, so that time is inside its validity window. */
    suspend fun verify(record: AttestationRecord, nowSecs: Long? = null): RecordDcapVerification {
        val now = nowSecs ?: record.fetchedAt.epochSecond
        return RecordDcapVerification(
            gateway = verifyQuote(record.intelQuote, "gateway", record.quoteVerification, now),
            model = verifyQuote(record.modelIntelQuote, "model", record.modelQuoteVerification, now),
            gpu = verifyQuote(record.gpuIntelQuote, "gpu", record.gpuQuoteVerification, now))
    }

    private suspend fun verifyQuote(quoteHex: String?, label: String, crossCheck: TdxVerificationResult?, nowSecs: Long): DcapVerification? {
        if (quoteHex.isNullOrEmpty()) return null
        val outcome = verifier.verify(quoteHex, nowSecs)
        val verified = outcome as? DcapVerification.Verified ?: run { log("[dcap] $label quote failed: $outcome"); return outcome }
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
