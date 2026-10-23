// ports: teemoon/Confidential/DCAPVerifier.swift @ 9722921 (DCAPVerification, DCAPTcbStatus — value types only)
//        teemoon/Confidential/DCAPService.swift @ 9722921 (RecordDCAPVerification and its rollups; the
//        service that runs dcap-qvl over a record is DcapService.kt)
package ai.teemoon.confidential

/** One quote's DCAP outcome. */
sealed interface DcapVerification {
    /** Quote verified; [tcbStatus] is Intel's TCB verdict ("UpToDate", "OutOfDate", …). */
    data class Verified(val tcbStatus: DcapTcbStatus, val mrConfigIdHex: String, val reportDataHex: String) : DcapVerification
    data class Failed(val reason: String) : DcapVerification

    val isVerified: Boolean get() = this is Verified

    /** Hard failure = quote didn't verify (incl. no collateral) or TCB outside the allow-list. */
    val isHardFailure: Boolean
        get() = when (this) {
            is Verified -> !tcbStatus.isAccepted
            is Failed -> true
        }
}

/**
 * Intel TCB status, mapped from dcap-qvl. Only [UP_TO_DATE] and [OUT_OF_DATE] are
 * accepted ([isAccepted]); `OutOfDateConfigurationNeeded` maps to
 * [CONFIGURATION_NEEDED] and fails with the rest.
 */
enum class DcapTcbStatus(val rawValue: String) {
    UP_TO_DATE("upToDate"),
    OUT_OF_DATE("outOfDate"),
    CONFIGURATION_NEEDED("configurationNeeded"),
    REVOKED("revoked"),
    OTHER("other");

    /** Whether this status should count as fully trusted (green) vs. degraded. */
    val isFullyTrusted: Boolean get() = this == UP_TO_DATE

    /** The SDK's default allow-list. Anything else fails the quote. */
    val isAccepted: Boolean get() = this == UP_TO_DATE || this == OUT_OF_DATE

    companion object {
        fun fromRaw(rawStatus: String): DcapTcbStatus {
            val s = rawStatus.lowercase().replace("_", "")
            return when {
                s == "uptodate" -> UP_TO_DATE
                s.contains("revoked") -> REVOKED
                s.contains("configuration") -> CONFIGURATION_NEEDED
                s.contains("outofdate") -> OUT_OF_DATE
                else -> OTHER
            }
        }
    }
}

/**
 * DCAP outcomes for each quote present in an attestation record.
 * null per slot = that quote wasn't in the record.
 *
 * TCB policy (near.ai inference-sdk's default allow-list): UpToDate and
 * OutOfDate pass; every other status, and a quote that fails DCAP or whose
 * collateral is unreachable, is a hard failure. OutOfDate is genuine
 * hardware behind on Intel updates and is flagged in the sheet.
 */
data class RecordDcapVerification(
    val gateway: DcapVerification? = null,
    val model: DcapVerification? = null,
    val gpu: DcapVerification? = null,
) {
    private val present: List<DcapVerification> get() = listOfNotNull(gateway, model, gpu)

    /**
     * True when any present quote hard-fails DCAP — verification failure
     * (incl. unavailable collateral) or a TCB status outside the allow-list.
     */
    val hasHardFailure: Boolean get() = present.any { it.isHardFailure }

    /** The least-trusted TCB status among verified quotes (for the sheet row). */
    val worstTcbStatus: DcapTcbStatus?
        get() {
            val statuses = present.mapNotNull { (it as? DcapVerification.Verified)?.tcbStatus }
            return when {
                DcapTcbStatus.REVOKED in statuses -> DcapTcbStatus.REVOKED
                DcapTcbStatus.OTHER in statuses -> DcapTcbStatus.OTHER
                DcapTcbStatus.OUT_OF_DATE in statuses -> DcapTcbStatus.OUT_OF_DATE
                DcapTcbStatus.CONFIGURATION_NEEDED in statuses -> DcapTcbStatus.CONFIGURATION_NEEDED
                statuses.isEmpty() -> null
                else -> DcapTcbStatus.UP_TO_DATE
            }
        }

    /** All present quotes verified (regardless of TCB status). */
    val allPresentVerified: Boolean get() = present.isNotEmpty() && present.all { it.isVerified }

    /**
     * The first hard-failure reason, tagged with which quote failed — surfaced
     * verbatim in the sheet so a DCAP failure is diagnosable without logs.
     */
    val failureReason: String?
        get() {
            for ((label, outcome) in listOf("gateway" to gateway, "model" to model, "gpu" to gpu)) {
                when (outcome) {
                    is DcapVerification.Failed -> return "$label quote — ${outcome.reason}"
                    is DcapVerification.Verified -> when {
                        outcome.tcbStatus == DcapTcbStatus.REVOKED -> return "$label quote — TCB revoked by Intel"
                        !outcome.tcbStatus.isAccepted ->
                            return "$label quote — TCB status ${outcome.tcbStatus.rawValue} is not accepted (only up to date or out of date)"
                    }
                    null -> Unit
                }
            }
            return null
        }
}
