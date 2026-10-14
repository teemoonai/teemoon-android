// ports: teemoonTests/GPUNodeRoutingTests.swift @ v1.0.1 (9), teemoonTests/DirectHostDerivationTests.swift @ v1.0.1 (3),
//        teemoonTests/HTTPSeamTests.swift @ v1.0.1 ("Attestation fetch is offline-testable"; the Brave and provider-key suites are :inference/:providers),
//        teemoonTests/GuestOSProvenanceTests.swift @ v1.0.1 (the three os_image_hash wire-shape tests)
//        + android-only fetch-path tests over the http seam (ProductLiveEndpointTests / E2EELiveSmokeTests are live-only)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestationServiceTest {
    private val modelOSHash = "9b69bb1698bacbb6985409a2c272bcb892e09cdcea63d5399c6768b67d3ff677"
    private val gatewayBody = """{"gateway_attestation":{"intel_quote":"","signing_address":"0xabc","info":{"compose_hash":"hash-compose","os_image_hash":"hash-os","tcb_info":{"mrtd":"hash-mrtd","app_compose":"services: {}"}}}}"""
    private fun modelBody(name: String = "zai-org/GLM-5.1-FP8", key: String = "40".repeat(32)) = """{"model_name":"$name","signing_public_key":"$key","info":{"os_image_hash":"$modelOSHash"}}"""

    /** A stub that answers by URL prefix; records every call. */
    private class Stub(private val answers: (url: String) -> Pair<Int, String>?) {
        val calls = ArrayList<String>()
        val http: AttestationHttp = { url, _ -> calls.add(url); (answers(url) ?: throw IOException("no route for $url")).let { it.first to it.second.toByteArray() } }
    }
    private val noDirectory: suspend (String) -> String? = { null }

    // MARK: - GPUNodeRouting

    @Test fun routingRule() {
        val f = AttestationService::gpuReportServesExpectedModel
        assertFalse(f("z-ai/glm-5.2", "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", "glm-5-2.completions.near.ai"))   // the live misroute
        assertTrue(f("zai-org/GLM-5.1-FP8", "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", "glm-5-1.completions.near.ai"))
        assertTrue(f("ZAI-ORG/glm-5.1-fp8", "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", null))
        assertTrue(f("z-ai/glm-5.2", "zai-org/GLM-5.2-FP8", "glm-5-2.completions.near.ai", "glm-5-2.completions.near.ai"))   // directory alias on the same host
        assertFalse(f("z-ai/glm-5.2", "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", "glm-5-2.completions.near.ai"))
        // THE hole: a fallback host's own model must not authenticate itself.
        assertFalse(f("zai-org/GLM-5.1-FP8", "zai-org/GLM-5.2-FP8", "glm-5-2.completions.near.ai", "glm-5-1.completions.near.ai"))
        assertFalse(f("someone/other-model", "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", null))
        assertTrue(f(null, "zai-org/GLM-5.1-FP8", "glm-5-1.completions.near.ai", null))
        assertTrue(f("z-ai/glm-5.2", "", "glm-5-2.completions.near.ai", "glm-5-2.completions.near.ai"))
    }

    // MARK: - DirectHostDerivation

    @Test fun derivesKnownNearAISlugs() {
        for ((model, slug) in listOf("zai-org/GLM-5.1-FP8" to "glm-5-1", "zai-org/GLM-5-FP8" to "glm-5", "Qwen/Qwen3.5-122B-A10B" to "qwen35-122b", "deepseek-ai/DeepSeek-V3.1" to "deepseek-v31", "openai/gpt-oss-120b" to "gpt-oss-120b"))
            assertTrue("$model → ${AttestationService.derivedDirectHostSlugs(model)}", slug in AttestationService.derivedDirectHostSlugs(model))
        val slugs = AttestationService.derivedDirectHostSlugs("zai-org/GLM-5.1-FP8")
        assertTrue(slugs.size <= 3); for (s in slugs) { assertTrue(s.all { it.isLetterOrDigit() || it == '-' }); assertTrue(s.isNotEmpty()) }
        assertTrue("gpt-oss-120b" in AttestationService.derivedDirectHostSlugs("gpt-oss-120b"))
    }

    // MARK: - Attestation fetch is offline-testable

    @Test fun plantedGatewayJSONBecomesARecord() = runTest {
        val stub = Stub { if (it.contains("cloud-api.near.ai/v1/attestation/report?signing_algo=ecdsa")) 200 to gatewayBody else null }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "sk-test", "", "id", http = stub.http, directBase = noDirectory)
        assertEquals("hash-compose", record.composeHash); assertEquals("hash-os", record.osImageHash); assertEquals("hash-mrtd", record.mrtd)
        assertEquals("0xabc", record.signingAddress); assertEquals("id", record.providerID); assertEquals("services: {}", record.composeManifest)
        assertNotNull(record.gatewayNonce); assertEquals(64, record.gatewayNonce!!.length)
        assertTrue(stub.calls.single().contains("nonce=${record.gatewayNonce}"))
    }

    // MARK: - os_image_hash, both wire shapes

    @Test fun directHostFlatReport_yieldsModelOSImageHash() {
        assertEquals(modelOSHash, AttestationService.modelOSImageHash("""{"model_name":"zai-org/GLM-5.1-FP8","signing_public_key":"40ef","info":{"compose_hash":"0fccab4eb7ff","os_image_hash":"$modelOSHash"}}""".toByteArray()))
    }
    @Test fun gatewayWithModelEnvelope_yieldsModelOSImageHash() {
        assertEquals(modelOSHash, AttestationService.modelOSImageHash("""{"model_attestations":[{"model_name":"zai-org/GLM-5.1-FP8","signing_public_key":"40ef","info":{"os_image_hash":"$modelOSHash"}}]}""".toByteArray()))
    }
    @Test fun reportWithoutInfo_yieldsNull() {
        assertNull(AttestationService.modelOSImageHash("""{"model_name":"zai-org/GLM-5.1-FP8","signing_public_key":"40ef"}""".toByteArray()))
    }

    // MARK: - Android-only: the fetch paths over the seam

    /** The direct host wins the race; the gateway's ?model= fallback is cancelled; the winner's nonce is the record's model nonce. */
    @Test fun modelAttestation_directHostWinsAndGatewayIsNotNeeded() = runTest {
        val model = "zai-org/GLM-5.1-FP8"
        val stub = Stub { url -> when {
            url.startsWith("https://cloud-api.near.ai/v1/attestation/report?signing_algo=ecdsa") -> 200 to gatewayBody
            url.startsWith("https://glm-5-1.completions.near.ai/v1/attestation/report?signing_algo=ed25519") -> 200 to modelBody()
            url.contains("model=") -> 503 to "slow gateway"
            else -> null
        } }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", model, "id", http = stub.http, directBase = { "https://glm-5-1.completions.near.ai/v1" })
        assertEquals("40".repeat(32), record.modelEd25519PubKey!!.toHex()); assertEquals(modelOSHash, record.modelOSImageHash); assertEquals(model, record.model)
        assertNotNull(record.modelNonce); assertTrue(stub.calls.any { it.contains("glm-5-1.completions") && it.contains("nonce=${record.modelNonce}") })
    }

    /** A host serving a DIFFERENT model is rejected outright — E2EE must never bind to the wrong key — and the gateway fallback supplies the key. */
    @Test fun modelAttestation_wrongHostIsRejected_gatewayFallbackWins() = runTest {
        val stub = Stub { url -> when {
            url.startsWith("https://cloud-api.near.ai/v1/attestation/report?signing_algo=ecdsa") -> 200 to gatewayBody
            url.startsWith("https://glm-5-1.completions.near.ai") -> 200 to modelBody(name = "z-ai/glm-5.2", key = "51".repeat(32))
            url.contains("model=") -> 200 to modelBody(key = "42".repeat(32))
            else -> null
        } }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "zai-org/GLM-5.1-FP8", "id", http = stub.http, directBase = { "https://glm-5-1.completions.near.ai/v1" })
        assertEquals("42".repeat(32), record.modelEd25519PubKey!!.toHex())
    }

    /** The gateway retries three times with backoff; when it still fails but the model attestation succeeded, a partial record carries the key. */
    @Test fun gatewayFailure_returnsPartialRecordWithTheKey() = runTest {
        val stub = Stub { url -> when {
            url.contains("signing_algo=ecdsa") -> 503 to "down"
            url.contains("signing_algo=ed25519") -> 200 to modelBody()
            else -> null
        } }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "zai-org/GLM-5.1-FP8", "id", http = stub.http, directBase = noDirectory)
        assertEquals(3, stub.calls.count { it.contains("signing_algo=ecdsa") })
        assertEquals("", record.composeHash); assertEquals("40".repeat(32), record.modelEd25519PubKey!!.toHex()); assertEquals("zai-org/GLM-5.1-FP8", record.model)
    }

    /**
     * THE KEY-ORPHAN INCIDENT (iOS, 2026-09-04), as its own vector.
     *
     * The test above fails the gateway with a 503 — a server error, which every fallback notices. The incident was
     * not that. With the near.ai API key orphaned the gateway ANSWERED, HTTP 401, with a well-formed
     * `{"error":{…}}` body. The direct completions host had already served a valid Ed25519 key. On iOS the partial
     * fallback keyed on a THROWN transport error, so an answered 401 walked straight past it: fetch decoded the
     * error body as a Report, threw on the missing field, and the key went with it — the user saw "end-to-end
     * encryption could not be established" while holding a perfectly good key.
     *
     * An answered-but-wrong response is a different shape from an unanswered one, and only this asserts it.
     * Ported from teemoonTests/AttestationLegIndependenceTests.swift, which is that incident's regression suite.
     */
    @Test fun anAnsweredGatewayErrorStillKeepsTheModelKey() = runTest {
        val stub = Stub { url -> when {
            // ANSWERED, not refused: a 401 whose body parses as JSON but is not a Report.
            url.contains("signing_algo=ecdsa") -> 401 to """{"error":{"code":"invalid_api_key","message":"API key not found"}}"""
            url.contains("signing_algo=ed25519") -> 200 to modelBody()
            else -> null
        } }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "zai-org/GLM-5.1-FP8", "id", http = stub.http, directBase = noDirectory)
        assertEquals("the model leg's key must survive the gateway leg's answered failure",
            "40".repeat(32), record.modelEd25519PubKey!!.toHex())
        assertEquals("", record.composeHash)
    }

    @Test fun gatewayFailure_withoutAKey_throws() = runTest {
        val stub = Stub { _ -> 503 to "down" }
        assertTrue(runCatching { AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "", "id", http = stub.http, directBase = noDirectory) }.isFailure)
    }

    /** A slow model attestation must not block the gateway record: after the bounded wait the gateway record returns with the model rows empty. */
    @Test fun slowModelAttestation_isBoundedAndTheGatewayRecordReturns() = runTest {
        val stub = Stub { url -> when {
            url.contains("signing_algo=ecdsa") -> 200 to gatewayBody
            else -> null   // model sources: unreachable → they retry with backoff for far longer than the bound
        } }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "zai-org/GLM-5.1-FP8", "id", http = stub.http, directBase = noDirectory, modelWaitMs = 15_000)
        assertEquals("hash-compose", record.composeHash); assertNull(record.modelEd25519PubKey)
    }

    /** GPU node: a misrouted node is rejected and the retry re-rolls; the signing address falls back to report_data when the field is absent. */
    @Test fun gpuNode_misrouteIsRejectedThenAcceptedOnRetry() = runTest {
        var gpuCalls = 0
        val actions = """{"actions":[{"action":"compose_up","file":"prod/A.yaml","commit":"c1","file_sha256":"s1","timestamp":"2026-07-12T00:00:00Z"},{"action":"compose_up","file":"prod/B.yaml","commit":"c2","tag":"v1","file_sha256":"s2","timestamp":"2026-07-16T00:00:00Z"}]}"""
        val stub = Stub { url -> when {
            url.contains("signing_algo=ecdsa") && url.startsWith("https://cloud-api") -> 200 to gatewayBody
            url.contains("signing_algo=ed25519") -> 200 to modelBody()
            url.startsWith("https://glm-5-1.completions.near.ai") -> { gpuCalls++
                if (gpuCalls == 1) 200 to """{"model_name":"z-ai/glm-5.2","signing_address":"0xwrong"}"""
                else 200 to """{"model_name":"zai-org/GLM-5.1-FP8","signing_address":"0xgpu","nvidia_payload":"{\"arch\":\"HOPPER\"}","info":{"compose_hash":"gpu-hash","tcb_info":{"app_compose":"services: {}"}},"compose_manager_attestation":$actions}""" }
            else -> null
        } }
        val hosts: suspend (String) -> String? = { m -> if (m.contains("5.2")) "https://glm-5-2.completions.near.ai/v1" else "https://glm-5-1.completions.near.ai/v1" }
        val record = AttestationService.fetch("https://cloud-api.near.ai/v1", "k", "zai-org/GLM-5.1-FP8", "id", gpuNodeUrl = "https://glm-5-1.completions.near.ai/v1", http = stub.http, directBase = hosts)
        assertEquals(2, gpuCalls)
        assertEquals("0xgpu", record.gpuSigningAddress); assertEquals("HOPPER", record.gpuArch); assertEquals("gpu-hash", record.gpuNodeComposeHash)
        assertEquals("s2", record.modelFileHash); assertEquals("prod/B.yaml", record.modelComposePath); assertEquals("v1", record.modelComposeTag)
        assertEquals("2026-07-16T00:00:00Z", record.modelDeployedAt); assertEquals("2026-07-12T00:00:00Z", record.modelPreviouslyDeployedAt)
        assertEquals("config deployed 2026-07-16 · previous 2026-07-12 · per the signed action log", record.deploymentCaption)
    }

    @Test fun fetchSigningAddress_readsTheFieldOrReportData() = runTest {
        assertEquals("0xabc", AttestationService.fetchSigningAddress("https://n/v1", "k", http = { _, _ -> 200 to """{"signing_address":"0xabc"}""".toByteArray() }))
        assertNull(AttestationService.fetchSigningAddress("https://n/v1", "k", http = { _, _ -> 200 to """{"intel_quote":"zz"}""".toByteArray() }))
        assertNull(AttestationService.fetchSigningAddress("https://n/v1", "k", http = { _, _ -> throw IOException("down") }))
    }

    @Test fun teeContext_derivesThePeerOnlyFromAUsableKey() {
        val provider = Provider.nearAI
        val model = E2EETestSupport.Model()
        val rec = AttestationRecord.preview.copy(modelEd25519PubKey = model.edPub)
        val ctx = TeeContext.from(provider, "k", rec)!!
        assertNotNull(ctx.e2eePeer); assertEquals(provider.model, ctx.model); assertEquals(provider.openAIBaseUrl, ctx.baseUrl)
        assertNull(TeeContext.from(provider, "k", rec.copy(modelEd25519PubKey = ByteArray(32)))!!.e2eePeer)   // low-order → no peer, fail closed
        assertNull(TeeContext.from(provider, "k", rec.copy(modelEd25519PubKey = null))!!.e2eePeer)
    }
}
