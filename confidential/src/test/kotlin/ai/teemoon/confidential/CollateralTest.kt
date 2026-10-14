// ports: teemoonTests/CollateralServiceTests.swift @ v1.0.1 — over the kit's collateral class (dcap-qvl's own verdicts on
//        the assembled JSON run on the device: see DcapQvlDeviceTest in :app)
package ai.teemoon.confidential

import ai.teemoon.confidential.KitFixtures.s
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollateralTest {
    private val rawQuote = KitFixtures.quoteHex.hexToBytes()

    /** Every assembled field equals the one iOS handed dcap-qvl — byte-exact, including tcb_info and qe_identity. */
    @Test fun assembledCollateralMatchesTheKitFieldForField() = runTest {
        val fields = IntelPcsCollateralProvider(KitFixtures.StubHttp()).collateralFields(rawQuote)
        assertEquals(KitFixtures.assembledFields.keys, fields.keys)
        for ((k, v) in KitFixtures.assembledFields) assertEquals(k, v, fields[k])
        val tcb = fields["tcb_info"]!!; assertTrue(tcb.startsWith("{") && tcb.endsWith("}"))
    }

    @Test fun extractionsAreByteExact() {
        for (e in KitFixtures.collateral["extractions"]!!.jsonArray.map { it.jsonObject }) {
            val body = KitFixtures.StubHttp().responses[e.s("url")!!]!!.first
            assertEquals(e.s("key"), e.s("rawObject"), IntelPcsCollateralProvider.rawJsonObject(e.s("key")!!, body))
        }
        for (v in KitFixtures.collateral["rawJSONObject"]!!.jsonArray.map { it.jsonObject })
            assertEquals(v.s("note"), v.s("rawObject"), IntelPcsCollateralProvider.rawJsonObject(v.s("key")!!, v.s("body")!!.toByteArray()))
    }

    @Test fun crlDistributionPoint_extractsIntelURL() {
        val qeUrl = KitFixtures.StubHttp().responses.keys.first { it.contains("qe/identity") }
        val pem = URLDecoder.decode(KitFixtures.StubHttp().responses[qeUrl]!!.second["SGX-Enclave-Identity-Issuer-Chain"]!!.replace("+", "%2B"), "UTF-8")
        val root = IntelPcsCollateralProvider.pemCertificatesDer(pem).last()
        assertEquals(KitFixtures.collateral.s("crlDistributionPointFromQEIssuerRoot"), IntelPcsCollateralProvider.crlDistributionPointUri(root))
    }

    /** Same-FMSPC quotes reuse the cached per-FMSPC collateral: no new PCS GETs on the second assembly. */
    @Test fun sameFmspcQuotes_fetchPerFmspcCollateralOnce() = runTest {
        val http = KitFixtures.StubHttp(); val provider = IntelPcsCollateralProvider(http)
        provider.collateralJson(rawQuote); val afterFirst = http.count
        provider.collateralJson(rawQuote)
        assertTrue(afterFirst > 0); assertEquals(afterFirst, http.count)
    }

    @Test fun unavailableCollateral_failsClosedWithTheReason() = runTest {
        val verifier = DcapVerifier(IntelPcsCollateralProvider(http = CollateralHttpClient { url -> throw CollateralHttpException(503, URI(url).host, URI(url).path) }), engine = { _, _, _ -> error("must not verify without collateral") })
        val r = verifier.verify(KitFixtures.quoteHex, KitFixtures.nowSecs) as DcapVerification.Failed
        assertTrue(r.reason, r.reason.contains("collateral") && r.reason.contains("503"))
        assertNull((DcapVerifier(IntelPcsCollateralProvider(KitFixtures.StubHttp()), engine = { _, _, _ -> error("x") }).verify("zz", 0) as? DcapVerification.Verified))
    }
}
