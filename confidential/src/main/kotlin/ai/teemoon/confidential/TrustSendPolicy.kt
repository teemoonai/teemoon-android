// ports: teemoon/Confidential/TrustVerdict.swift @ v1.0.4 (TrustSendPolicy; the verdict is TrustVerdict.kt)
package ai.teemoon.confidential

/**
 * Send policy for an attested provider. Soft degrade asks; hard break refuses.
 *
 * ONE canonical gate. iOS learned this from the Siri/Shortcuts intent, which
 * used to send with no policy check at all: an attestation record merely
 * EXISTING was enough, even unverified, and with no key the send went out in
 * plaintext. Every send path — the compose bar, a message retry, an intent —
 * consults this same value. See [TrustVerdict.sendPolicy] for how it is made.
 */
enum class TrustSendPolicy {
    ALLOW,
    CONFIRM,
    BLOCK,
}
