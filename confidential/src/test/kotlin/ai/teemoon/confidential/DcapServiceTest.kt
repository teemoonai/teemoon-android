// ports: teemoonTests/DCAPServiceTests.swift @ v1.0.1 — the engine is a fake that answers what the kit recorded dcap-qvl
//        answering at the snapshot's now_secs; the real engine is exercised on the device (DcapQvlDeviceTest)
package ai.teemoon.confidential

import ai.teemoon.confidential.KitFixtures.s
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DcapServiceTest {
    /** What dcap-qvl answered for the real quote at now_secs (from the kit); anything else is outside the collateral window. */
    private val kitEngine = DcapQvlEngine { _, _, now ->
        if (now != KitFixtures.nowSecs) throw IllegalStateException("collateral expired at $now")
        DcapVerifiedQuote(KitFixtures.realQuote.s("tcbStatus")!!, KitFixtures.realQuote.s("dcapMrConfigIdHex")!!, KitFixtures.realQuote.s("dcapReportDataHex")!!)
    }
    private fun record(quoteHex: String = KitFixtures.quoteHex, parse: TdxVerificationResult? = null) = AttestationRecord(
        composeHash = "", mrtd = "", osImageHash = "", intelQuote = quoteHex, composeManifest = null, gpuArch = null, gpuNodeComposeHash = null,
        modelFileHash = null, signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = null,
        quoteVerification = parse, gpuQuoteVerification = null, modelQuoteVerification = null,
        fetchedAt = Instant.ofEpochSecond(KitFixtures.nowSecs), providerID = "p")
    private fun service() = DcapService(IntelPcsCollateralProvider(KitFixtures.StubHttp()), kitEngine)

    @Test fun realQuote_verifiesThroughService_withCrossCheck() = runTest {
        val result = service().verify(record(parse = TdxQuoteVerifier.verify(KitFixtures.quoteHex)))
        val gw = result.gateway as DcapVerification.Verified
        assertTrue(gw.tcbStatus == DcapTcbStatus.OUT_OF_DATE || gw.tcbStatus == DcapTcbStatus.UP_TO_DATE)
        assertTrue(gw.mrConfigIdHex.startsWith("01"))   // MRCONFIGID carries "01" + compose_hash
        assertEquals(128, gw.reportDataHex.length)
        assertNull(result.model); assertNull(result.gpu); assertFalse(result.hasHardFailure); assertTrue(result.allPresentVerified)
    }

    @Test fun crossCheckMismatch_failsTheQuote() = runTest {
        val real = TdxQuoteVerifier.verify(KitFixtures.quoteHex); val m = real.measurements
        val mismatch = TdxVerificationResult(TdxMeasurements(m.mrtd, m.mrSeam, ByteArray(48), m.mrOwner, m.rtmr0, m.rtmr1, m.rtmr2, m.rtmr3, m.reportData), true, true, null)
        val result = service().verify(record(parse = mismatch))
        val gw = result.gateway as DcapVerification.Failed
        assertTrue(gw.reason.contains("cross-check")); assertTrue(result.hasHardFailure)
    }

    @Test fun hardFailurePolicy_matchesPlan() {
        val outOfDate = DcapVerification.Verified(DcapTcbStatus.OUT_OF_DATE, "01", ""); assertFalse(outOfDate.isHardFailure)
        val revoked = DcapVerification.Verified(DcapTcbStatus.REVOKED, "01", ""); assertTrue(revoked.isHardFailure)
        assertTrue(DcapVerification.Failed("no collateral").isHardFailure)
        var r = RecordDcapVerification(gateway = outOfDate); assertFalse(r.hasHardFailure); assertEquals(DcapTcbStatus.OUT_OF_DATE, r.worstTcbStatus)
        r = r.copy(model = revoked); assertTrue(r.hasHardFailure); assertEquals(DcapTcbStatus.REVOKED, r.worstTcbStatus)
    }

    @Test fun emptyQuotes_produceNoOutcomes() = runTest {
        val result = service().verify(record(quoteHex = ""))
        assertNull(result.gateway); assertNull(result.model); assertNull(result.gpu); assertFalse(result.hasHardFailure); assertFalse(result.allPresentVerified)
    }

    /** The verification time defaults to the record's fetch time, so fresh collateral is judged inside its own window. */
    @Test fun nowDefaultsToTheRecordFetchTime() = runTest {
        val late = service().verify(record(), nowSecs = KitFixtures.nowSecs + 2 * 365 * 86400)
        assertTrue(late.gateway is DcapVerification.Failed)
        assertTrue(service().verify(record()).gateway is DcapVerification.Verified)
    }
}
