// ports: teemoon/Presentation/SendChoices.swift @ e0c15c3; E2EEAsk and the provenance questions @ 38647a07 (S30)
//
// The three-way questions, in order, the way out last. Shown as action sheets on iOS and bottom sheets on Android
// (UI audit, pm 2026-10-25; IosAlert draws three actions as a sheet); SendChoicesTest pins the order and the words.
package ai.teemoon.app

sealed interface E2EESendChoice {
    data object RetryVerification : E2EESendChoice
    data class SendAnyway(val stillEncrypted: Boolean) : E2EESendChoice
    /**
     * A plain row that keeps the draft. iOS 26.5 hid its role-.cancel button when the sheet became a popover, leaving
     * an unlabelled tap outside as the only safe exit (the maintainer via pm, 2026-10-30); both platforms now name the way out.
     */
    data object DontSend : E2EESendChoice
    /** The hardware isn't confirmed but the seal holds (near-send-gate-rule, the maintainer 2026-11-06; iOS sendSealedUnconfirmed). */
    data object SendSealedUnconfirmed : E2EESendChoice
    /** The still-checking question's way out: keeps the draft, sends nothing. */
    data object Wait : E2EESendChoice

    val label: String get() = when (this) {
        RetryVerification -> "retry verification"
        is SendAnyway -> if (stillEncrypted) "send anyway (still encrypted)" else "send unencrypted"
        DontSend -> "don't send"
        SendSealedUnconfirmed -> "send anyway"
        Wait -> "wait"
    }

    /** Red: the message leaves without the seal. The still-encrypted provenance-only send stays plain. */
    val isDestructive: Boolean get() = this == SendAnyway(stillEncrypted = false)

    companion object {
        /** A hard integrity break offers no way to send. */
        fun choices(hardFailure: Boolean, provenanceOnly: Boolean): List<E2EESendChoice> =
            if (hardFailure) listOf(RetryVerification, DontSend)
            else listOf(RetryVerification, SendAnyway(stillEncrypted = provenanceOnly), DontSend)

        /** near-send-gate-words (the maintainer 2026-11-06): a soft state, or a check still running, in which the send stays sealed. */
        val STILL_CHECKING: List<E2EESendChoice> = listOf(SendSealedUnconfirmed, Wait)
        const val STILL_CHECKING_TITLE = "still checking near.ai's hardware"
        const val STILL_CHECKING_MESSAGE = "some checks haven't finished. your message would still be sealed to the model's key, but the hardware behind that key isn't confirmed yet."

        /** the maintainer's text, exact. The degrade's cause is not repeated here: the trust sheet names it (TrustVerdict). */
        const val UNAVAILABLE_MESSAGE = "teemoon couldn't seal this message so only the model can read it. nothing has been sent."
        const val PROVENANCE_ONLY_MESSAGE = "your message would still be sealed to the model's verified hardware key — but one running image on this server couldn't be verified as published code. re-verifying may reach a different server of this model."

        /** [cause] is the session's degrade reason; only a hard break names it — and states the policy, since there is no bypass. */
        fun message(hardFailure: Boolean, provenanceOnly: Boolean, cause: String?): String {
            if (provenanceOnly) return PROVENANCE_ONLY_MESSAGE
            if (!hardFailure) return UNAVAILABLE_MESSAGE
            val named = cause ?: "end-to-end encryption couldn't be set up for this provider."
            return "$named sending is blocked until this re-verifies. this could mean the enclave was tampered with or the connection was intercepted."
        }
    }
}

/** The question the send gate shows, chosen in one place so the screen only lays it out (iOS E2EEAsk @ 38647a07). */
data class E2EEAsk(val title: String, val message: String, val choices: List<E2EESendChoice>) {
    companion object {
        /** Image provenance alone still running at the settle timeout (S30, the maintainer 2026-11-07, "The wording is good"). */
        const val PROVENANCE_PENDING_TITLE = "couldn't confirm near.ai's software yet"
        const val PROVENANCE_PENDING_MESSAGE = "your message would still be sealed to the model's verified hardware key, but teemoon hasn't yet confirmed that the software running on it is the published code."
        /** The provenance lookup ended without an answer (T2b): nothing to wait for, so no "wait". */
        const val PROVENANCE_UNCHECKED_TITLE = "couldn't confirm near.ai's software"
        const val PROVENANCE_UNCHECKED_MESSAGE = "your message would still be sealed to the model's verified hardware key, but teemoon couldn't reach GitHub or Docker Hub to confirm that the software running on it is the published code."

        /**
         * near.ai's tier list never came within the deadline (ConfidentialSession.tierGaveUp; iOS verify-deadline; the maintainer
         * approved the verify card 2026-11-14): nothing reloads it, so — as the provenance case — no "wait".
         */
        const val TIER_GAVE_UP_TITLE = "couldn't confirm near.ai's hardware"

        /** [pendingAsk]: the kind still pending when the settle timeout asked, null when no pending ask is up. */
        fun make(hardFailure: Boolean, pendingAsk: ai.teemoon.confidential.ConfidentialSession.PendingKind?, provenanceUnchecked: Boolean,
                 softButSealed: Boolean, provenanceBlocked: Boolean, cause: String?, tierGaveUp: Boolean = false): E2EEAsk {
            if (!hardFailure && pendingAsk == ai.teemoon.confidential.ConfidentialSession.PendingKind.PROVENANCE)
                return E2EEAsk(PROVENANCE_PENDING_TITLE, PROVENANCE_PENDING_MESSAGE, E2EESendChoice.STILL_CHECKING)
            if (!hardFailure && pendingAsk == null && provenanceUnchecked)
                return E2EEAsk(PROVENANCE_UNCHECKED_TITLE, PROVENANCE_UNCHECKED_MESSAGE, E2EESendChoice.choices(hardFailure = false, provenanceOnly = true))
            if (!hardFailure && pendingAsk == null && tierGaveUp)
                return E2EEAsk(TIER_GAVE_UP_TITLE, ai.teemoon.confidential.ConfidentialSession.TIER_REASON, listOf(E2EESendChoice.SendSealedUnconfirmed, E2EESendChoice.DontSend))
            if (!hardFailure && (pendingAsk != null || softButSealed))
                return E2EEAsk(E2EESendChoice.STILL_CHECKING_TITLE, E2EESendChoice.STILL_CHECKING_MESSAGE, E2EESendChoice.STILL_CHECKING)
            val title = if (hardFailure) "verification failed — sending blocked" else if (provenanceBlocked) "couldn't verify the running code" else "end-to-end encryption unavailable"
            return E2EEAsk(title, E2EESendChoice.message(hardFailure, provenanceBlocked, cause), E2EESendChoice.choices(hardFailure, provenanceBlocked))
        }
    }
}

enum class CellularDownloadChoice(val label: String) {
    DOWNLOAD_NOW("download now"), WAIT_FOR_WIFI("wait for wi-fi"), NOT_NOW("not now"),
}
