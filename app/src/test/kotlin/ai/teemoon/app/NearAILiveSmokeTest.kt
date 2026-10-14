// ports: teemoonTests/E2EELiveSmokeTests.swift @ v1.0.1 (attestedEncryptedRoundTrip) + android-only: a report of
//        every verifier the session runs, over the same record.
//
// LIVE. Costs one tiny request. Never part of the offline gate: it runs only
// when BOTH TEEMOON_LIVE=1 and NEAR_AI_API_KEY are set, and skips otherwise.
//
//     TEEMOON_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*NearAILiveSmokeTest*' -i
package ai.teemoon.app

import ai.teemoon.confidential.AttestationService
import ai.teemoon.confidential.NrasService
import ai.teemoon.confidential.ProvenanceService
import ai.teemoon.confidential.TeeContext
import ai.teemoon.confidential.TlsAttestationVerifier
import ai.teemoon.inference.GenerationEngine
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.OpenAiTransport
import ai.teemoon.inference.RequestResult
import ai.teemoon.inference.StreamCallbacks
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NearAILiveSmokeTest {

    /** No model call: the TLS-attestation probe against the flagship's direct host must verify (the certificate is captured from the live handshake). */
    @Test fun tlsAttestationOnTheDirectHost() = runBlocking {
        assumeTrue("set TEEMOON_LIVE=1 to run the live probe", System.getenv("TEEMOON_LIVE") == "1")
        NearAIModelCatalog.resetTierCache()
        val direct = Provider.nearAI.directGPUNodeUrl ?: error("no direct host for ${Provider.nearAI.model}")
        val outcome = TlsAttestationVerifier(log = { println("[live] $it") }).verify(direct)
        println("[live] TLS attestation on $direct: $outcome")
        assertTrue("expected Verified, got $outcome", outcome.isVerified)
    }

    @Test fun attestedEncryptedRoundTrip() = runBlocking {
        val apiKey = System.getenv("NEAR_AI_API_KEY")?.trim().orEmpty()
        assumeTrue("set TEEMOON_LIVE=1 and NEAR_AI_API_KEY to run the live smoke", System.getenv("TEEMOON_LIVE") == "1" && apiKey.isNotEmpty())
        val log: (String) -> Unit = { println("[live] $it") }

        // Keep the response tiny: extraParams flow into the request body (reasoning models spend budget on thinking first).
        // iOS caps at 512; GLM-5.2 spent all of that thinking on the first live run and the engine rightly withheld
        // the truncated scratchpad, so the Android smoke allows a little more room for the reasoning before "pong".
        val provider = Provider.nearAI.copy(extraParams = mapOf("max_tokens" to "2048"))
        val baseUrl = provider.openAIBaseUrl!!
        NearAIModelCatalog.resetTierCache()
        println("[live] model ${provider.model} attestable=${ProviderCapability.ATTESTATION in provider.capabilities} direct=${provider.directGPUNodeUrl}")

        // 1. Real attestation — must carry the model's Ed25519 key for E2EE.
        val record = AttestationService.fetch(baseUrl, apiKey, provider.model, provider.id, provider.directGPUNodeUrl, log = log)
        assertNotNull("attestation has no model Ed25519 key — E2EE unavailable server-side", record.modelEd25519PubKey)
        println("[live] gateway quote signature=${record.quoteVerification?.signatureValid} chain=${record.quoteVerification?.certChainValid} nonceEchoed=${record.nonceEchoed} codeIdentity=${record.codeIdentityVerified}")
        println("[live] model quote verified=${record.modelQuoteVerification?.isVerified} keyBound=${record.e2eeKeyBoundToModelTEE} gpuArch=${record.gpuArch} signer=${record.signingAddress} gpuSigner=${record.gpuSigningAddress}")
        assertTrue("the gateway quote must verify on device", record.quoteVerification?.isVerified == true)

        // 2. One encrypted completion through the production engine.
        val context = TeeContext.from(provider, apiKey, record, log)!!
        assertNotNull("no E2EE peer derived from the record", context.e2eePeer)
        val results = ArrayList<RequestResult>()
        val transport = OpenAiTransport(provider.chatCompletionsUrl, provider.model, apiKey, teeContext = context, extraParams = provider.extraParams, providerName = provider.name)
        val engine = GenerationEngine(
            transport = transport,
            events = object : StreamCallbacks {
                override fun onText(text: String) {}
                override fun onWarning(message: String) = log("warning: $message")
                override fun onSuccess(result: RequestResult) { results.add(result) }
            },
        )
        val text = try { engine.run(emptyList(), "Reply with exactly one word: pong") } catch (e: LlmError) {
            println("[live] request failed: HTTP ${e.httpStatus} body=${e.responseBody?.take(600)} sealed=${e.requestBodyJson?.take(200)}"); throw e
        }
        println("[live] response: ${text.take(80)}")
        assertTrue("empty response — decryption produced nothing", text.isNotEmpty())

        // 3. E2EE must have actually been active, and the verification outcome is reported honestly.
        val r = results.lastOrNull() ?: error("no RequestResult delivered")
        assertTrue("request went out in plaintext", r.isE2eeActive)
        println("[live] response signature: ${r.teeVerification}")
        // What the gateway answers for this id over the next half minute (a diagnostic, not an assertion).
        transport.lastChatCompletionId?.let { id ->
            val http = okhttp3.OkHttpClient()
            for (waitMs in listOf(0L, 5_000L, 10_000L, 15_000L)) {
                delay(waitMs)
                http.newCall(okhttp3.Request.Builder().url("$baseUrl/signature/$id").header("Authorization", "Bearer $apiKey").build()).execute().use { resp ->
                    println("[live] signature/$id after +${waitMs}ms: HTTP ${resp.code} ${resp.body.string().take(300)}")
                    if (resp.code == 200) return@let
                }
            }
        }

        // 4. The rest of the verifier fan-out, over the same record (the app runs these from the session).
        record.nvidiaPayload?.let { println("[live] NRAS: ${NrasService(log = log).verify(it, record.modelNonce)}") }
        provider.directGPUNodeUrl?.let { println("[live] TLS attestation: ${TlsAttestationVerifier(log = log).verify(it)}") }
        val manifests = listOf("gateway" to (record.composeManifest ?: ""), "model" to (record.gpuNodeComposeManifest ?: "")).filter { it.second.isNotEmpty() }
        if (manifests.isNotEmpty()) {
            val prov = ProvenanceService(log = log).verifyManifests(manifests)
            println("[live] provenance: ${prov::class.simpleName} verified=${(prov as? ai.teemoon.confidential.ManifestProvenance.AllVerified)?.verified?.size ?: (prov as? ai.teemoon.confidential.ManifestProvenance.Incomplete)?.verified?.size} failures=${(prov as? ai.teemoon.confidential.ManifestProvenance.Incomplete)?.failures?.map { it.ref.image + ": " + it.reason.failureReason }}")
        }
    }
}
