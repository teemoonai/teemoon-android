// ports: teemoonTests/AttestationLegIndependenceTests.swift @ v1.0.4 (both suites, whole: "Attestation legs are independent"
//        and "Attestation leg retry taxonomy"). Until 2026-09-23 only the key-orphan vector was ported and the rest waived as
//        "covered by the existing retry tests" — three of them were not: an undecodable gateway 200 threw away the model key,
//        a GPU-only record was refused, and a GPU 401 body decoded into empty "evidence".
package ai.teemoon.confidential

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * AttestationService.fetch gathers three independent claims — the gateway's own quote, the model node's Ed25519 key +
 * quote, the GPU node's evidence. These pin that one leg's failure never discards another leg's success, and what each
 * class of gateway failure costs in requests.
 *
 * Incident (iOS, 2026-09-04, the key-orphan incident): with the near.ai API key orphaned, the gateway answered HTTP 401
 * `{"error":{…}}`. The direct completions host had already served a valid Ed25519 key, but fetch() decoded the 401 body
 * as a Report, threw, and the key went with it — "End-to-end encryption could not be established".
 */

/** Which of fetch()'s three legs a request belongs to, read off its URL. */
private enum class Leg {
    GATEWAY, MODEL, GPU;
    companion object {
        fun of(url: String): Leg = when {
            url.contains(Fixture.GPU_HOST) -> GPU
            url.contains("signing_algo=ed25519") -> MODEL
            else -> GATEWAY
        }
    }
}

/** The routed HTTP seam: every request is logged by leg, and [route] answers it. */
private class Routed(private val route: (Leg, String) -> Pair<Int, String>) {
    val requests = ArrayList<String>()
    fun count(leg: Leg) = synchronized(requests) { requests.count { Leg.of(it) == leg } }
    fun nonces(leg: Leg) = synchronized(requests) { requests.filter { Leg.of(it) == leg }.mapNotNull { Regex("[?&]nonce=([^&]+)").find(it)?.groupValues?.get(1) } }
    val http: AttestationHttp = { url, _ ->
        synchronized(requests) { requests += url }
        val (status, body) = route(Leg.of(url), url)
        status to body.encodeToByteArray()
    }
}

/** One leg that answers 503 [failuresBeforeSuccess] times, then [success]. Every other leg gets the same 503s. */
private fun flaky(leg: Leg, failuresBeforeSuccess: Int, success: String): Routed {
    lateinit var r: Routed
    r = Routed { l, _ -> if (l == leg && r.count(leg) > failuresBeforeSuccess) 200 to success else 503 to Fixture.NOT_JSON }
    return r
}

private object Fixture {
    const val NEAR_AI = "https://cloud-api.near.ai/v1"
    const val GPU_HOST = "gpu-node.example"
    const val GPU_NODE = "https://$GPU_HOST/v1"
    const val MODEL = "zai-org/GLM-5.1-FP8"
    /** The model leg resolves one direct source, as a shipped direct host does on iOS. */
    val directBase: suspend (String) -> String? = { "https://glm-5-1.completions.near.ai/v1" }

    val KEY_HEX = "ab".repeat(32)

    /** The incident's gateway answer, verbatim. */
    const val MISSING_AUTHORIZATION = """{"error":{"message":"Missing authorization header"}}"""
    const val NOT_JSON = "<html><body>502 Bad Gateway</body></html>"

    const val GATEWAY_REPORT = """{"gateway_attestation":{"intel_quote":"","signing_address":"0xgateway","info":{"compose_hash":"hash-compose","os_image_hash":"hash-os","tcb_info":{"mrtd":"hash-mrtd","app_compose":"services: {}"}}}}"""
    val ED25519_REPORT = """{"signing_public_key":"$KEY_HEX","nvidia_payload":"{\"arch\":\"HOPPER\"}","info":{"os_image_hash":"hash-model-os"}}"""
    const val GPU_REPORT = """{"signing_address":"0xgpu","nvidia_payload":"{\"arch\":\"AMPERE\"}","info":{"compose_hash":"gpu-compose"}}"""
}

private suspend fun fetch(r: Routed, model: String = Fixture.MODEL, gpuNode: String? = null, apiKey: String = "sk", providerID: String = "id") =
    AttestationService.fetch(Fixture.NEAR_AI, apiKey, model, providerID, gpuNode, http = r.http, directBase = Fixture.directBase)

class AttestationLegIndependenceTest {

    /**
     * The incident: gateway 401s (auth is the gateway's problem alone), the direct host serves the key. The key must
     * survive, and nothing the gateway did not serve may appear as evidence.
     */
    @Test fun gateway401DoesNotDiscardTheModelKey() = runTest {
        // Mirror the incident: everything on the gateway host is 401, including the model leg's gateway source; only direct hosts answer.
        val r = Routed { _, url -> if (url.startsWith(Fixture.NEAR_AI)) 401 to Fixture.MISSING_AUTHORIZATION else 200 to Fixture.ED25519_REPORT }
        val record = fetch(r, apiKey = "", providerID = "p-1")
        assertEquals(Fixture.KEY_HEX, record.modelEd25519PubKey?.toHex())
        assertNotNull(record.modelNonce)
        assertEquals("hash-model-os", record.modelOSImageHash)
        assertEquals("HOPPER", record.gpuArch)
        assertEquals("p-1", record.providerID)
        assertEquals(Fixture.MODEL, record.model)

        assertNull(record.signingAddress); assertNull(record.gatewayNonce); assertNull(record.quoteVerification)
        assertEquals("", record.intelQuote); assertEquals("", record.composeHash); assertEquals("", record.mrtd); assertEquals("", record.osImageHash)
        assertNull(record.composeManifest); assertNull(record.gpuSigningAddress)

        assertEquals("a 401 cannot succeed on retry", 1, r.count(Leg.GATEWAY))
    }

    @Test fun undecodableGatewayBodyLeavesGatewayFieldsEmpty() = runTest {
        val r = Routed { leg, _ -> if (leg == Leg.MODEL) 200 to Fixture.ED25519_REPORT else 200 to Fixture.NOT_JSON }
        val record = fetch(r)
        assertEquals(Fixture.KEY_HEX, record.modelEd25519PubKey?.toHex())
        assertNull(record.signingAddress); assertNull(record.gatewayNonce); assertNull(record.quoteVerification)
        assertEquals("", record.intelQuote)
        assertEquals("a malformed body is not transient", 1, r.count(Leg.GATEWAY))
    }

    @Test fun gpuEvidenceAloneIsARecord() = runTest {
        val r = Routed { leg, _ -> if (leg == Leg.GPU) 200 to Fixture.GPU_REPORT else 404 to Fixture.MISSING_AUTHORIZATION }
        val record = fetch(r, model = "", gpuNode = Fixture.GPU_NODE)
        assertEquals("0xgpu", record.gpuSigningAddress)
        assertEquals("AMPERE", record.gpuArch)
        assertEquals("gpu-compose", record.gpuNodeComposeHash)
        assertNotNull(record.gpuNonce)
        assertNull(record.signingAddress); assertNull(record.quoteVerification); assertNull(record.modelEd25519PubKey)
        assertEquals(1, r.count(Leg.GATEWAY))
    }

    @Test fun totalBlackoutThrowsAfterOneGatewayRequestFor4xx() = runTest {
        val r = Routed { _, _ -> 401 to Fixture.MISSING_AUTHORIZATION }
        try { fetch(r, model = "", apiKey = ""); fail("fetch returned a record with no evidence from any leg") } catch (e: IOException) {
            assertTrue(e.message!!.contains("no evidence"))
        }
        assertEquals(1, r.count(Leg.GATEWAY))
    }

    /** 503 twice, then the real report: the third attempt must win (3 s of virtual backoff). */
    @Test fun gateway5xxIsRetriedAndRecovers() = runTest {
        val r = flaky(Leg.GATEWAY, failuresBeforeSuccess = 2, success = Fixture.GATEWAY_REPORT)
        val record = fetch(r, model = "")
        assertEquals("0xgateway", record.signingAddress)
        assertEquals("hash-compose", record.composeHash)
        assertEquals(3, r.count(Leg.GATEWAY))
    }

    /** A complete fetch: every field lands, and the gateway nonce the record reports is the one actually sent on the wire. */
    @Test fun allLegsSucceedFieldForField() = runTest {
        val r = Routed { leg, _ -> when (leg) {
            Leg.GATEWAY -> 200 to Fixture.GATEWAY_REPORT
            Leg.MODEL -> 200 to Fixture.ED25519_REPORT
            Leg.GPU -> 200 to Fixture.GPU_REPORT
        } }
        val record = fetch(r, gpuNode = Fixture.GPU_NODE)
        assertEquals("hash-compose", record.composeHash); assertEquals("hash-mrtd", record.mrtd); assertEquals("hash-os", record.osImageHash)
        assertEquals("services: {}", record.composeManifest); assertEquals("0xgateway", record.signingAddress); assertEquals("", record.intelQuote)
        assertNull("an empty quote verifies to nothing, not to a failure", record.quoteVerification)
        assertEquals(Fixture.KEY_HEX, record.modelEd25519PubKey?.toHex())
        assertEquals("hash-model-os", record.modelOSImageHash)
        assertEquals("the model node's own arch wins over the GPU node fetch", "HOPPER", record.gpuArch)
        assertEquals("0xgpu", record.gpuSigningAddress); assertEquals("gpu-compose", record.gpuNodeComposeHash)
        assertNotNull(record.modelNonce); assertNotNull(record.gpuNonce)
        val sent = r.nonces(Leg.GATEWAY)
        assertEquals(1, sent.size)
        assertEquals(sent.first(), record.gatewayNonce)
    }
}

/**
 * What each class of answer costs a leg in requests. The three legs share one taxonomy: a thrown transport error or a
 * 5xx is retried, a 4xx is final.
 *
 * Incident, defect 3: the model leg classed only `status >= 500` as an error. A 401 body decoded into the all-optional
 * Ed25519Report, tripped the missing-key guard and was retried — 8 backed-off attempts, ~46 s, for a status that cannot
 * succeed on retry. The GPU leg was worse: its report is all-optional too, so a 401 body decoded into an EMPTY record
 * that counted as evidence.
 */
class AttestationLegRetryTaxonomyTest {

    @Test fun modelLeg4xxIsFinal() = runTest {
        for (status in listOf(401, 403, 404)) {
            val r = Routed { _, _ -> status to Fixture.MISSING_AUTHORIZATION }
            val result = AttestationService.fetchModelAttestation(Fixture.NEAR_AI, true, Fixture.MODEL, "", 8, "gateway", r.http)
            assertNull(result)
            assertEquals("HTTP $status cannot succeed on retry", 1, r.count(Leg.MODEL))
        }
    }

    /** 503 once, then the key: the second attempt wins. */
    @Test fun modelLeg5xxIsRetriedAndRecovers() = runTest {
        val r = flaky(Leg.MODEL, failuresBeforeSuccess = 1, success = Fixture.ED25519_REPORT)
        val result = AttestationService.fetchModelAttestation(Fixture.NEAR_AI, true, Fixture.MODEL, "sk", 8, "gateway", r.http)
        assertEquals(Fixture.KEY_HEX, result?.ed25519PubKey?.toHex())
        assertEquals(2, r.count(Leg.MODEL))
    }

    /** A 200 with no key yet is a TEE still starting: transient, so retried up to the budget. */
    @Test fun modelLeg200WithoutKeyStaysTransient() = runTest {
        val r = Routed { _, _ -> 200 to "{}" }
        val result = AttestationService.fetchModelAttestation(Fixture.NEAR_AI, true, Fixture.MODEL, "sk", 2, "gateway", r.http)
        assertNull(result)
        assertEquals(2, r.count(Leg.MODEL))
    }

    /** The incident's shape with a model set: every source of the model leg asks once and the whole fetch fails at once. */
    @Test fun blackoutWithModelLegAsksEachSourceOnce() = runTest {
        val r = Routed { _, _ -> 401 to Fixture.MISSING_AUTHORIZATION }
        val sources = AttestationService.directSources(Fixture.MODEL, Fixture.directBase).size + 1
        val started = testScheduler.currentTime
        try { fetch(r, apiKey = ""); fail("fetch returned a record with no evidence from any leg") } catch (_: IOException) {}
        assertEquals(1, r.count(Leg.GATEWAY))
        assertEquals("direct host(s) + gateway, once each", sources, r.count(Leg.MODEL))
        assertTrue(testScheduler.currentTime - started < 5_000)
    }

    /** A 401 body is not GPU evidence: nothing from that leg lands, and it is not retried. */
    @Test fun gpuLeg401IsNeitherEvidenceNorRetried() = runTest {
        val r = Routed { leg, _ -> if (leg == Leg.GPU) 401 to Fixture.MISSING_AUTHORIZATION else 200 to Fixture.GATEWAY_REPORT }
        val record = fetch(r, model = "", gpuNode = Fixture.GPU_NODE)
        assertEquals("0xgateway", record.signingAddress)
        assertNull("an empty decoded report must not count as a GPU answer", record.gpuNonce)
        assertNull(record.gpuSigningAddress); assertNull(record.gpuArch); assertNull(record.gpuNodeComposeHash)
        assertEquals(1, r.count(Leg.GPU))
    }

    /** GPU 503 once, then the real report. */
    @Test fun gpuLeg5xxIsRetriedAndRecovers() = runTest {
        val r = flaky(Leg.GPU, failuresBeforeSuccess = 1, success = Fixture.GPU_REPORT)
        val record = fetch(r, model = "", gpuNode = Fixture.GPU_NODE)
        assertEquals("0xgpu", record.gpuSigningAddress)
        assertEquals(2, r.count(Leg.GPU))
    }
}
