// ports: teemoon/Views/Chat/TrustLadderView.swift @ v1.0.4 (the timer; iOS has no test of it — these are Android's)
package ai.teemoon.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTimeoutTest {
    /** No first record for 8 s: the verifier is unreachable — and not a moment before. */
    @Test fun aSilentVerifierTimesOutAfterEightSeconds() = runTest {
        var timedOut = false
        launch { VerifierTimeout.run({ true }) { timedOut = it } }
        advanceTimeBy(VerifierTimeout.AFTER_MS - 1); runCurrent()
        assertFalse(timedOut)
        advanceTimeBy(2); runCurrent()
        assertTrue(timedOut)
    }

    /** The record lands during the wait: the slow part was never the verifier. */
    @Test fun aRecordArrivingInTimeNeverTimesOut() = runTest {
        var timedOut = false; var awaits = true
        launch { VerifierTimeout.run({ awaits }) { timedOut = it } }
        advanceTimeBy(3_000); awaits = false
        advanceTimeBy(VerifierTimeout.AFTER_MS); runCurrent()
        assertFalse(timedOut)
    }

    /** A record already in (VERIFYING is the provenance sweep): the timer does not arm, and a stale flag clears. */
    @Test fun withARecordInTheTimerDoesNotArmAndClearsAStaleFlag() = runTest {
        var timedOut = true
        launch { VerifierTimeout.run({ false }) { timedOut = it } }
        advanceTimeBy(VerifierTimeout.AFTER_MS * 2); runCurrent()
        assertFalse(timedOut)
    }
}
