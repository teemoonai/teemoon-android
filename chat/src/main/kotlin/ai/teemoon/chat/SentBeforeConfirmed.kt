// ports: teemoon/Confidential/SentBeforeConfirmed.swift @ a27f0621
//
// MessageEntity.sentBeforeConfirmed: a sealed send that left before its attestation record was confirmed. The reply
// machine writes it once; only [settle] rewrites it, when that record's verdict settles (reply-machine.json
// sentBeforeConfirmedResolution). The mark words live with the view that shows them (ReplyMarkerCopy).
package ai.teemoon.chat

object SentBeforeConfirmed {
    enum class Outcome(val rawValue: String) { PASS("pass"), HARD_FAIL("hardFail"), UNSETTLED("unsettled") }

    /** Mark ids; their words are decided elsewhere. */
    enum class Mark(val rawValue: String) {
        SENT_WHILE_CHECKING("mark.sentWhileChecking"), CHECK_FAILED_AFTER_SEND("mark.checkFailedAfterSend")
    }

    /** `<fetchedAt, epoch ms>|<nonce>`, the record a send went out on. */
    fun recordKey(fetchedAtMs: Long, nonce: String) = "$fetchedAtMs|$nonce"

    fun pending(recordKey: String) = "pending|$recordKey"
    fun failed(recordKey: String) = "failed|$recordKey"

    /** The value after [recordKey]'s verdict settles. Rows of other records, null and failed rows never change. */
    fun resolved(value: String?, recordKey: String, outcome: Outcome): String? {
        if (value != pending(recordKey)) return value
        return when (outcome) {
            Outcome.PASS -> null
            Outcome.HARD_FAIL -> failed(recordKey)
            Outcome.UNSETTLED -> value
        }
    }

    fun mark(value: String?): Mark? = when {
        value == null -> null
        value.startsWith("pending|") -> Mark.SENT_WHILE_CHECKING
        value.startsWith("failed|") -> Mark.CHECK_FAILED_AFTER_SEND
        else -> null
    }

    /** The report and counters count a message as end-to-end encrypted only when this is null. */
    fun countsAsE2EE(value: String?) = value == null
}
