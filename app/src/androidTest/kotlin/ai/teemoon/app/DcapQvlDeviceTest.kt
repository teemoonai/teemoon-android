package ai.teemoon.app

import ai.teemoon.confidential.AndroidDcapQvl
import ai.teemoon.confidential.CollateralHttpClient
import ai.teemoon.confidential.CollateralHttpException
import ai.teemoon.confidential.DcapTcbStatus
import ai.teemoon.confidential.DcapVerification
import ai.teemoon.confidential.DcapVerifier
import ai.teemoon.confidential.IntelPcsCollateralProvider
import ai.teemoon.confidential.TdxQuoteVerifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The kit's quotes and collateral classes against the REAL dcap-qvl engine —
 * its native library exists for Android ABIs only, so this is where the
 * verdicts iOS recorded are proved on Android: verified with the recorded
 * TCB status at now_secs, failed two years on, failed with a flipped MRTD.
 */
@RunWith(AndroidJUnit4::class)
class DcapQvlDeviceTest {
    private fun doc(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream("/$name.json")!!.reader().readText()).jsonObject["vectors"]!!.jsonObject
    private val quotes = doc("quotes"); private val collateral = doc("collateral")
    private val real = quotes["quotes"]!!.jsonArray[0].jsonObject
    private val quoteHex = real["quoteHex"]!!.jsonPrimitive.content
    private val nowSecs = real["nowSecs"]!!.jsonPrimitive.long

    private val http = CollateralHttpClient { url ->
        val r = collateral["responses"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["url"]!!.jsonPrimitive.content == url }
            ?: throw CollateralHttpException(404, "stub", url)
        Base64.getDecoder().decode(r["bodyB64"]!!.jsonPrimitive.content) to r["headers"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    private fun verifier() = DcapVerifier(IntelPcsCollateralProvider(http), AndroidDcapQvl)

    @Test fun realQuote_verifiesWithTheRecordedTcbStatus() = runBlocking {
        val v = verifier().verify(quoteHex, nowSecs) as DcapVerification.Verified
        assertEquals(real["tcbStatus"]!!.jsonPrimitive.content, v.tcbStatus.rawValue)
        assertEquals(real["dcapMrConfigIdHex"]!!.jsonPrimitive.content, v.mrConfigIdHex.lowercase())
        assertEquals(real["dcapReportDataHex"]!!.jsonPrimitive.content, v.reportDataHex.lowercase())
        assertTrue(v.tcbStatus == DcapTcbStatus.OUT_OF_DATE || v.tcbStatus == DcapTcbStatus.UP_TO_DATE)
    }

    @Test fun everyRecordedNegativeFails() = runBlocking {
        for (n in quotes["negatives"]!!.jsonArray.map { it.jsonObject }) {
            val v = verifier().verify(n["quoteHex"]!!.jsonPrimitive.content, n["nowSecs"]!!.jsonPrimitive.long)
            assertEquals(n["note"]!!.jsonPrimitive.content, "failed", if (v is DcapVerification.Verified) "verified" else "failed")
        }
    }

    /** The on-device parser's PKIX path to the pinned Intel root also holds on Android's certificate stack. */
    @Test fun parserVerifiesOnDevice() {
        assertTrue(TdxQuoteVerifier.verify(quoteHex).isVerified)
    }
}
