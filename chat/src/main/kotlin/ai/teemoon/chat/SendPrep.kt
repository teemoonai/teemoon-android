// ports: teemoon/Chat/ChatViewModel.swift @ v1.0.4 (SendPrep, prepareSend, isPromptEmpty, Arrival —
//        the pure send gate. NOT here: generate/retry/sendMessage are the view model and
//        ChatGeneration; sendPolicy(session:) forwards to ConfidentialSession.sendPolicy;
//        mustRefuseUnsealedSend is here too, since the E2EE row landed)
package ai.teemoon.chat

import ai.teemoon.confidential.TrustSendPolicy

/**
 * Why send cannot proceed. Trust allow/confirm/block is [TrustSendPolicy];
 * this adds no-provider and still-downloading. The view presents; it does
 * not decide. See SendPrepTest.
 *
 * A message RETRY must pass the same gate as a fresh send — the message row
 * must not be a hole that resends the whole thread into a compromised or
 * unverified enclave. Retry passes `requirePrompt = false`, nothing else
 * differs.
 */
enum class SendPrep {
    BLOCKED_EMPTY_PROMPT,
    BLOCKED_NO_PROVIDER,
    /**
     * A cloud setup with no key. Since 1.0.2 one cannot be SAVED without a key
     * and deleting a setup removes its key, so this is a legacy record or a lost
     * key — and either way the send must be stopped here rather than at the wire,
     * where it would surface as an authentication failure the user cannot act on.
     */
    BLOCKED_NO_KEY,
    BLOCKED_DOWNLOADING,
    CONFIRM_E2EE,
    BLOCKED_E2EE,
    READY;

    companion object {
        /** Whitespace-only counts as empty, as on iOS (`whitespacesAndNewlines`). */
        fun isPromptEmpty(prompt: String): Boolean = prompt.isBlank()

        /**
         * The order is the contract: an empty prompt is a no-op before anything
         * else is consulted, a missing provider is reported before trust, a
         * missing KEY before a download, and a download in flight before trust —
         * each is a condition the user can fix without being shown an E2EE alert
         * about a provider that is not even going to be dialled.
         *
         * `hasKey` defaults to true so a keyless provider — on-device, Brave —
         * is unaffected without every call site having to say so.
         */
        fun prepare(
            prompt: String,
            hasProvider: Boolean,
            isDownloading: Boolean,
            trust: TrustSendPolicy,
            requirePrompt: Boolean = true,
            hasKey: Boolean = true,
        ): SendPrep {
            if (requirePrompt && isPromptEmpty(prompt)) return BLOCKED_EMPTY_PROMPT
            if (!hasProvider) return BLOCKED_NO_PROVIDER
            if (!hasKey) return BLOCKED_NO_KEY
            if (isDownloading) return BLOCKED_DOWNLOADING
            return when (trust) {
                TrustSendPolicy.ALLOW -> READY
                TrustSendPolicy.CONFIRM -> CONFIRM_E2EE
                TrustSendPolicy.BLOCK -> BLOCKED_E2EE
            }
        }
    }
}

/**
 * Weights still arriving on the phone or a home pull. Copy is data — the
 * alert renders [alertMessage] verbatim and composes nothing. See SendPrepTest.
 */
data class Arrival(
    val name: String,
    val fraction: Double?,
    val kind: Kind,
) {
    enum class Kind { PHONE, HOME, HOME_MANIFEST }

    val alertMessage: String
        get() {
            if (fraction != null) {
                return "$name is ${(fraction * 100).toInt()}% downloaded. it'll answer as soon as the rest lands — " +
                    "you can leave this message here and send it then."
            }
            if (kind == Kind.HOME_MANIFEST) {
                return "$name is still downloading onto that machine. it'll answer as soon as it lands — " +
                    "you can leave this message here and send it then."
            }
            return "$name isn't downloaded. pick it again in where to restart the download, or choose something else to send now."
        }
}
