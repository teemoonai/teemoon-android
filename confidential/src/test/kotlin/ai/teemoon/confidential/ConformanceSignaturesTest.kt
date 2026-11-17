// ports: teemoonTests/ConformanceVectorsSignatures.swift @ v1.1.0 (the consuming half: vectorsReproduceUnderProduction and
//        iosAgreesWithTheSDK as androidAgreesWithTheSDK; minting the vectors is iOS's, never here)
//
// Per-response signature vectors (conformance/vendor/signatures.json, kit 0.5.0), built in teemoon-ios from three real
// near.ai captures and mutations of them, each answered there by the production verifier. iOS's
// conformance/tools/sdk_verdicts.py replays the same cases through near.ai's own inference-sdk (pinned) into
// signatures.sdk.json. This suite holds Android's TEESignatureVerifier.evaluate to iOS's verdict on every case, and to
// the SDK's wherever the SDK can express the case (sdkApplies: the two key-to-quote binding cases are the SDK's
// attestation layer, not its signature check).
package ai.teemoon.confidential

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ConformanceSignaturesTest {
    private companion object {
        const val SDK_COMMIT = "4bccfd94549f6e5061821f90a5d8901d7299a8c0"
    }

    private fun resource(path: String) = Json.parseToJsonElement(ConformanceSignaturesTest::class.java.getResourceAsStream(path)!!.reader().readText()).jsonObject
    private val cases = resource("/signatures.json")["vectors"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun outcome(v: ResponseVerification) = when (v) {
        is ResponseVerification.Verified -> "verified"
        is ResponseVerification.Unverified -> when (v.reason) {
            is ResponseVerification.UnverifiedReason.GatewayTrustOnly -> "gatewayTrustOnly"
            is ResponseVerification.UnverifiedReason.SignerUnattested -> "signerUnattested"
            else -> "rejected"
        }
    }

    private fun evaluate(c: JsonObject): ResponseVerification {
        val sig = c["signature"]!!.jsonObject
        val fields = buildMap {
            put("text", sig.s("text")!!); put("signature", sig.s("signature")!!)
            put("signing_address", sig.s("signingAddress")!!); put("signing_algo", sig.s("signingAlgo")!!)
            sig.s("signatureKind")?.let { put("signature_kind", it) }
        }
        val signature = Json.decodeFromString(TEEMessageSignature.serializer(), JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString())
        val exchange = SignedExchange(c["requestBodiesB64"]!!.jsonArray.map { Base64.getDecoder().decode(it.jsonPrimitive.content) }, c.s("responseText")!!)
        val a = c["attestation"]!!.jsonObject
        fun bound(k: String) = a[k]?.jsonPrimitive?.booleanOrNull ?: true
        val record = SignatureVerdictTest.record(
            gateway = a.s("gatewayAddress"), gatewayBound = bound("gatewayBound"),
            gpu = a.s("gpuAddress"), gpuBound = bound("gpuBound"),
            modelKey = a.s("modelKey")?.hexToBytesOrNull(), modelKeyBound = bound("modelKeyBound"))
        return TEESignatureVerifier.evaluate(signature, record, exchange)
    }

    @Test fun vectorsReproduceUnderProduction() {
        assertEquals(14, cases.size)
        for (c in cases) assertEquals(c.s("note"), c.s("outcome"), outcome(evaluate(c)))
        assertEquals(2, cases.count { it.s("outcome") == "verified" })
        assertEquals(1, cases.count { it.s("outcome") == "gatewayTrustOnly" })
    }

    /** The SDK has no unattested-signer state; it either accepts a kind or rejects. */
    @Test fun androidAgreesWithTheSDK() {
        val doc = resource("/signatures.sdk.json")
        assertEquals(SDK_COMMIT, doc.s("sdkCommit"))
        val sdk = doc["verdicts"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val applicable = cases.filter { it["sdkApplies"]!!.jsonPrimitive.boolean }
        assertEquals("signatures.sdk.json is stale against signatures.json", applicable.size, sdk.size)
        for (c in applicable) assertEquals("${c.s("note")}: sdk vs android", sdk[c.s("note")], outcome(evaluate(c)))
    }

    /** The E2EE capture the first vectors were cut from, vendored with them: its signature is vector 0's. */
    @Test fun theE2eeCaptureIsTheFirstVector() {
        val capture = resource("/fixtures/nearai_e2ee_signed_response.json")
        val first = cases.first()["signature"]!!.jsonObject
        assertEquals(capture["sig"]!!.jsonObject.s("signature"), first.s("signature"))
        assertEquals(capture.s("model_key"), cases.first()["attestation"]!!.jsonObject.s("modelKey"))
    }
}
