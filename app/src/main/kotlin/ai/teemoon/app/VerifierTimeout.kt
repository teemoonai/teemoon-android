// ports: teemoon/Views/Chat/TrustLadderView.swift @ v1.0.4 (the `.task(id: session.attestationState)` timer and
//        the hero's reset on re-verify, TrustLadderView+Hero.swift @ v1.0.4)
package ai.teemoon.app

import kotlinx.coroutines.delay

/**
 * TrustLadderView's "can't reach verifier" timer. iOS re-runs it on every change of `attestationState`: it clears
 * `timedOut`, arms ONLY while the session awaits its first record, and after 8 s — if the first record still has not
 * come — says the verifier is unreachable. Once a record is in, VERIFYING is the provenance sweep, and a slow sweep is
 * not an unreachable verifier (ConfidentialSession.awaitsFirstRecord).
 *
 * Until 2026-10-02 Android never set the flag: every `timedOut` branch in AttestationSummary was unreachable, and a
 * session whose verifier never answered read "verifying" forever.
 */
object VerifierTimeout {
    const val AFTER_MS = 8_000L

    /** One run of the timer; cancelled by the caller when the state changes, as `.task(id:)` cancels. */
    suspend fun run(awaitsFirstRecord: () -> Boolean, setTimedOut: (Boolean) -> Unit) {
        setTimedOut(false)
        if (!awaitsFirstRecord()) return
        delay(AFTER_MS)
        if (awaitsFirstRecord()) setTimedOut(true)
    }
}
