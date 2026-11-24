// ports: teemoonTests/KeyCustodyListedStatesTests.swift @ 39ba0919 (aListedCheckStillMissingAtTheDeadlineReadsUnfinished)
// ports: teemoonTests/KeyCustodyListedStatesTests.swift @ e9e2e747 (design review: theSealedMarkFollowsTheListedEnclaves)
// ports: teemoonTests/KeyCustodyListedStatesTests.swift @ 1fe69e3d (1.1.1 audit A4: the scopes argument); @ c1eb3f4b (1.1.1 audit A7, read of a6f11ac7: theGPUVerdictIsAListedCheck, the gpu argument); before that @ a6f11ac7
//
// Audit 1.1.1 A7: "teemoon checked every enclave near.ai listed" only when the list was read and every entry passed,
// DCAP included. Card key-custody-listed-states v2 (approved 2026-11-19): five states, everyday = expert, no "chips".
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCustodyListedStatesTest {
    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")

    private fun record(results: List<ModelCandidates.Result>?, listFailed: Boolean = false) =
        AttestationRecord.preview.copy(modelCandidates = results?.let(ModelCandidatesTest::listedFor), modelCandidatesListFailed = listFailed)

    private fun dcap(listed: List<DcapVerification>) = RecordDcapVerification(model = upToDate, listed = listed)

    @Test fun everyStateFromWhatTeemoonKnows() {
        val clean = listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean)
        assertEquals(KeyCustodyCopy.Listed.CHECKED, KeyCustodyCopy.listed(record(clean), dcap(listOf(upToDate, upToDate)), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.LIST_FAILED, KeyCustodyCopy.listed(record(null, listFailed = true), dcap(emptyList()), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, KeyCustodyCopy.listed(record(clean), null, NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, KeyCustodyCopy.listed(record(null), dcap(emptyList()), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, KeyCustodyCopy.listed(record(clean), dcap(listOf(upToDate)), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(record(clean), dcap(listOf(upToDate, DcapVerification.Unchecked("pcs"))), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.FAILED, KeyCustodyCopy.listed(
            record(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Mismatch(ModelCandidates.Why.BOOT_LOG))), dcap(listOf(upToDate, upToDate)), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.FAILED, KeyCustodyCopy.listed(record(clean), dcap(listOf(upToDate, DcapVerification.Failed("x"))), NrasVerification.Verified, ListedNearScopeTest.clean(2)))
    }

    /** Audit read of a6f11ac7: the GPU verdict is one of the listed checks too (A3 covers every listed enclave). */
    @Test fun theGpuVerdictIsAListedCheck() {
        val r = record(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean)); val d = dcap(listOf(upToDate, upToDate))
        assertEquals(KeyCustodyCopy.Listed.FAILED, KeyCustodyCopy.listed(r, d, NrasVerification.Failed("NVIDIA rejected the evidence"), ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(r, d, NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT), ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(r, d, NrasVerification.Inconclusive("NRAS unreachable"), ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKING, KeyCustodyCopy.listed(r, d, null, ListedNearScopeTest.clean(2)))
        assertEquals(KeyCustodyCopy.Listed.CHECKED, KeyCustodyCopy.listed(r, d, NrasVerification.Verified, ListedNearScopeTest.clean(2)))
    }

    @Test fun aListThatDidNotLoadNeverReadsChecked() {
        val input = TrustVerdict.Input(attestationState = AttestationState.OK, e2eeKeyBound = true, listedEnclaves = KeyCustodyCopy.Listed.LIST_FAILED)
        val sealed = TrustVerdict.make(input).everydayClaims.first { it.id == "sealed" }
        assertTrue(sealed.detail, sealed.detail.endsWith("teemoon couldn't load near.ai's list of enclaves, so it couldn't check them."))
        assertFalse(sealed.detail, sealed.detail.contains("checked every"))
        assertTrue(KeyCustodyCopy.keyHeldEveryday(KeyCustodyCopy.Listed.LIST_FAILED).endsWith("teemoon couldn't load near.ai's list, so it couldn't check them."))
        assertFalse(KeyCustodyCopy.keyInQuoteExpert("m", KeyCustodyCopy.Listed.LIST_FAILED).contains("checked every"))
    }

    @Test fun theApprovedSentences() {
        assertEquals("teemoon checked every enclave near.ai listed.", KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.CHECKED))
        assertEquals("teemoon is checking the enclaves near.ai listed…", KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.CHECKING))
        assertEquals("teemoon couldn't finish checking the enclaves near.ai listed.", KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.UNFINISHED))
        assertEquals("one of the enclaves near.ai listed failed teemoon's check.", KeyCustodyCopy.listedSentence(KeyCustodyCopy.Listed.FAILED))
        assertEquals("teemoon checked each one near.ai listed.", KeyCustodyCopy.listedTail(KeyCustodyCopy.Listed.CHECKED))
        assertEquals("teemoon couldn't finish checking the ones near.ai listed.", KeyCustodyCopy.listedTail(KeyCustodyCopy.Listed.UNFINISHED))
        assertEquals("near.ai's secure enclaves hold the key", KeyCustodyCopy.keyHeldEverydayTitle)
    }

    @Test fun noKeyCustodyLineSaysChips() {
        for (s in KeyCustodyCopy.Listed.entries) {
            for (line in listOf(KeyCustodyCopy.sealedDetail("d", "m", s), KeyCustodyCopy.keyInQuoteExpert("m", s),
                KeyCustodyCopy.keyHeldEveryday(s), KeyCustodyCopy.keyHeldEverydayTitle)) {
                assertFalse(line, line.contains("chip"))
            }
        }
    }

    /** 1.1.1 audit A9-6 (iOS 37de10d9): the expert tail is "doesn't hold the key", not "can't decrypt". */
    @Test fun expertTailSaysTheGatewayDoesntHoldTheKey() {
        for (s in KeyCustodyCopy.Listed.entries) {
            val line = KeyCustodyCopy.keyInQuoteExpert("m", s)
            assertTrue(line, line.endsWith(" the gateway doesn't hold the key."))
            assertFalse(line, line.contains("can't decrypt"))
        }
    }

    /** iOS e9e2e747 (design review 2026-11-20): a green mark sat beside "teemoon is checking / couldn't load / couldn't finish ...". The sealed row takes [TrustVerdict.sealedStatus]. */
    @Test fun theSealedMarkFollowsTheListedEnclaves() {
        val expected = listOf(KeyCustodyCopy.Listed.CHECKED to TrustClaim.Status.OK, KeyCustodyCopy.Listed.CHECKING to TrustClaim.Status.PENDING,
            KeyCustodyCopy.Listed.LIST_FAILED to TrustClaim.Status.ALERT, KeyCustodyCopy.Listed.UNFINISHED to TrustClaim.Status.ALERT,
            KeyCustodyCopy.Listed.FAILED to TrustClaim.Status.FAIL)
        for ((listed, status) in expected) {
            assertEquals("$listed", status, TrustVerdict.sealedStatus(hardwareChecksPending = false, listed = listed))
            val row = TrustVerdict.make(TrustVerdict.Input(attestationState = AttestationState.OK, e2eeKeyBound = true, listedEnclaves = listed))
                .everydayClaims.first { it.id == "sealed" }
            assertEquals("sealed row, $listed", status, row.status)
        }
        assertEquals(TrustClaim.Status.PENDING, TrustVerdict.sealedStatus(hardwareChecksPending = true, listed = KeyCustodyCopy.Listed.CHECKED))
        assertEquals(TrustClaim.Status.FAIL, TrustVerdict.sealedStatus(hardwareChecksPending = true, listed = KeyCustodyCopy.Listed.FAILED))
    }

    /**
     * iOS 39ba0919 (A7 read): once the verdict deadline passed the send ask says the checks didn't finish, so a listed check
     * still unanswered must not read "teemoon is checking..." beside it.
     */
    @Test fun aListedCheckStillMissingAtTheDeadlineReadsUnfinished() {
        val r = record(listOf(ModelCandidates.Result.Clean, ModelCandidates.Result.Clean)); val d = dcap(listOf(upToDate, upToDate))
        val clean2 = ListedNearScopeTest.clean(2)
        val missing = listOf(
            listOf(null, NrasVerification.Verified, clean2), listOf(dcap(listOf(upToDate)), NrasVerification.Verified, clean2),
            listOf(d, null, clean2), listOf(d, NrasVerification.Verified, null), listOf(d, NrasVerification.Verified, ListedNearScopeTest.clean(1)))
        for (m in missing) {
            @Suppress("UNCHECKED_CAST")
            val (dc, gpu, scopes) = Triple(m[0] as RecordDcapVerification?, m[1] as NrasVerification?, m[2] as List<NearTrustScope>?)
            assertEquals(KeyCustodyCopy.Listed.CHECKING, KeyCustodyCopy.listed(r, dc, gpu, scopes))
            assertEquals("$m", KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(r, dc, gpu, scopes, deadlinePassed = true))
        }
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, KeyCustodyCopy.listed(record(null), null, null, null, deadlinePassed = true))
        // What arrived still decides: the deadline never turns a pass or a failure into "couldn't finish".
        assertEquals(KeyCustodyCopy.Listed.CHECKED, KeyCustodyCopy.listed(r, d, NrasVerification.Verified, clean2, deadlinePassed = true))
        assertEquals(KeyCustodyCopy.Listed.FAILED, KeyCustodyCopy.listed(r, d, NrasVerification.Failed("x"), null, deadlinePassed = true))
        assertEquals(KeyCustodyCopy.Listed.LIST_FAILED, KeyCustodyCopy.listed(record(null, listFailed = true), null, null, null, deadlinePassed = true))

        fun summary(passed: Boolean) = AttestationSummary(attestation = r, state = AttestationState.OK, timedOut = false, provider = null, lastRequestUsedE2EE = true,
            lastE2EEFailReason = null, verifiedResponseCount = 0, mismatchedResponseCount = 0, attestationFetchFailed = false,
            dcapVerification = d, nrasVerification = null, listedTrustScopes = clean2, deadlinePassed = passed)
        fun sealed(s: AttestationSummary) = TrustVerdict.make(TrustVerdict.Input(attestationState = AttestationState.OK, e2eeKeyBound = true, listedEnclaves = s.listedEnclaves))
            .everydayClaims.first { it.id == "sealed" }
        assertEquals(KeyCustodyCopy.Listed.CHECKING, summary(false).listedEnclaves)
        assertEquals(TrustClaim.Status.PENDING, sealed(summary(false)).status)
        assertEquals(KeyCustodyCopy.Listed.UNFINISHED, summary(true).listedEnclaves)
        val row = sealed(summary(true))
        assertEquals(TrustClaim.Status.ALERT, row.status)
        assertTrue(row.detail, row.detail.endsWith("teemoon couldn't finish checking the enclaves near.ai listed."))
        assertFalse(row.detail, row.detail.contains("is checking"))
    }
}
