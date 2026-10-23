// ports: teemoonTests/SignatureVerdictTests.swift @ 97b2ca9
//
// TEESignatureVerifier.evaluate against two real captures, vendored byte-identical from iOS (teemoonTests/Fixtures):
// nearai_signed_response.json (a provider_tee ecdsa signature with the model prefix) and
// nearai_gateway_signed_response.json (2026-10-19: a plaintext request, answered with signature_kind=gateway in both
// ecdsa and ed25519).
//
// Before 1.0.6 the verifier returned Verified for any non-ecdsa signature on the server-asserted address, counted the
// gateway's key as a model signer, and accepted a "key rotation" address read from an unverified report. Each of those
// has a negative test here.
package ai.teemoon.confidential

import ai.teemoon.confidential.ResponseVerification.UnverifiedReason
import java.security.SecureRandom
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SignatureVerdictTest {

    // MARK: Fixtures

    private class ModelCapture(val bodyJson: String, val lines: List<String>, val sig: Map<String, String>) {
        val exchange get() = SignedExchange(listOf(bodyJson.toByteArray()), lines.joinToString("") { it + "\n" })
    }

    private class GatewayCapture(val bodyJson: String, val responseText: String, val gatewayEcdsaAddress: String, val gatewayEd25519Key: String,
                                 val sigEcdsa: Map<String, String>, val sigEd25519: Map<String, String>) {
        val exchange get() = SignedExchange(listOf(bodyJson.toByteArray()), responseText)
    }

    private fun doc(name: String) = Json.parseToJsonElement(SignatureVerdictTest::class.java.getResourceAsStream("/fixtures/$name")!!.reader().readText()).jsonObject
    private fun JsonObject.strings(key: String) = this[key]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun model() = doc("nearai_signed_response.json").let { ModelCapture(it.str("body_json"), it["lines"]!!.jsonArray.map { l -> l.jsonPrimitive.content }, it.strings("sig")) }
    private fun gateway() = doc("nearai_gateway_signed_response.json").let {
        GatewayCapture(it.str("body_json"), it.str("response_text"), it.str("gateway_ecdsa_address"), it.str("gateway_ed25519_key"), it.strings("sig_ecdsa"), it.strings("sig_ed25519"))
    }

    /** A signature from [fields] with [overrides] applied (a null value removes the field). */
    private fun signature(fields: Map<String, String>, overrides: Map<String, String?> = emptyMap()): TEEMessageSignature {
        val f = fields.toMutableMap(); overrides.forEach { (k, v) -> if (v == null) f.remove(k) else f[k] = v }
        return Json.decodeFromString(TEEMessageSignature.serializer(), JsonObject(f.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun evaluate(sig: TEEMessageSignature, att: AttestationRecord, exchange: SignedExchange) = TEESignatureVerifier.evaluate(sig, att, exchange)
    private fun reasonOf(v: ResponseVerification) = (v as? ResponseVerification.Unverified)?.reason

    companion object {
        private fun hex(s: String) = s.removePrefix("0x").hexToBytesOrNull() ?: ByteArray(0)

        /** report_data carrying [prefix] (an address or key) in its first bytes. */
        private fun reportData(prefix: ByteArray) = ByteArray(64).also { prefix.copyInto(it) }

        fun record(gateway: String? = null, gatewayBound: Boolean = true, gpu: String? = null, gpuBound: Boolean = true,
                   modelKey: ByteArray? = null, modelKeyBound: Boolean = true) = AttestationRecord(
            composeHash = "", mrtd = "", osImageHash = "", intelQuote = "", composeManifest = null, gpuArch = null,
            gpuNodeComposeHash = null, modelFileHash = null,
            signingAddress = gateway, gpuSigningAddress = gpu, modelEd25519PubKey = modelKey,
            quoteVerification = gateway?.let { AttestationFixtures.verification(reportData(if (gatewayBound) hex(it) else ByteArray(20))) },
            gpuQuoteVerification = gpu?.let { AttestationFixtures.verification(reportData(if (gpuBound) hex(it) else ByteArray(20))) },
            modelQuoteVerification = modelKey?.let { AttestationFixtures.verification(reportData(if (modelKeyBound) it else ByteArray(32))) },
            fetchedAt = Instant.now(), providerID = "test",
        )
    }

    // MARK: provider_tee (model node)

    @Test fun modelSignatureByBoundKeyIsVerified() {
        val m = model()
        val v = evaluate(signature(m.sig), record(gpu = m.sig["signing_address"]), m.exchange)
        assertEquals(ResponseVerification.Verified(m.sig["signing_address"]!!.lowercase()), v)
    }

    @Test fun modelSignatureByUnboundKeyIsNotVerified() {
        val m = model()
        val v = evaluate(signature(m.sig), record(gpu = m.sig["signing_address"], gpuBound = false), m.exchange)
        assertTrue("expected signerUnattested, got $v", reasonOf(v) is UnverifiedReason.SignerUnattested)
    }

    @Test fun tamperedSignatureIsRejected() {
        val m = model()
        val s = m.sig["signature"]!!.toCharArray(); s[10] = if (s[10] == 'a') 'b' else 'a'
        val v = evaluate(signature(m.sig, mapOf("signature" to String(s))), record(gpu = m.sig["signing_address"]), m.exchange)
        assertFalse("a tampered signature verified", v is ResponseVerification.Verified)
    }

    @Test fun modelSignatureWithoutPrefixIsRejected() {
        val m = model()
        val unprefixed = m.sig["text"]!!.split(":").takeLast(2).joinToString(":")
        val v = evaluate(signature(m.sig, mapOf("text" to unprefixed)), record(gpu = m.sig["signing_address"]), m.exchange)
        val reason = reasonOf(v) as? UnverifiedReason.ContentMismatch ?: return fail("expected contentMismatch, got $v")
        assertTrue(reason.detail, reason.detail.contains("prefix"))
    }

    @Test fun gatewayKindOverModelTextIsRejected() {
        val m = model()
        val v = evaluate(signature(m.sig, mapOf("signature_kind" to "gateway")), record(gateway = m.sig["signing_address"]), m.exchange)
        assertTrue("expected contentMismatch, got $v", reasonOf(v) is UnverifiedReason.ContentMismatch)
    }

    /** The removed rotation path: a signer that matches no bound key is a mismatch even when the gateway quote verified. */
    @Test fun signerOutsideTheAttestationIsAMismatch() {
        val m = model()
        val other = "0x" + "11".repeat(20)
        val v = evaluate(signature(m.sig), record(gateway = other, gpu = other), m.exchange)
        assertTrue("expected signatureMismatch, got $v", reasonOf(v) is UnverifiedReason.SignatureMismatch)
    }

    @Test fun unknownAlgorithmOnATrustedAddressIsRejected() {
        val m = model()
        for (algo in listOf("rsa", "", "none")) {
            val v = evaluate(signature(m.sig, mapOf("signing_algo" to algo)), record(gpu = m.sig["signing_address"]), m.exchange)
            assertTrue("$algo: expected signatureMismatch, got $v", reasonOf(v) is UnverifiedReason.SignatureMismatch)
        }
    }

    @Test fun unknownSignatureKindIsRejected() {
        val m = model()
        val v = evaluate(signature(m.sig, mapOf("signature_kind" to "proxy")), record(gpu = m.sig["signing_address"]), m.exchange)
        assertTrue("expected signatureMismatch, got $v", reasonOf(v) is UnverifiedReason.SignatureMismatch)
    }

    // MARK: gateway

    @Test fun gatewaySignatureIsGatewayTrustOnly() {
        val g = gateway()
        val v = evaluate(signature(g.sigEcdsa), record(gateway = g.gatewayEcdsaAddress), g.exchange)
        assertEquals(ResponseVerification.Unverified(UnverifiedReason.GatewayTrustOnly(g.gatewayEcdsaAddress.lowercase())), v)
    }

    /** Pre-1.0.6 this was Verified: the gateway address sat in the trusted set. */
    @Test fun gatewaySignerIsNeverVerifiedEvenAsAModelKey() {
        val g = gateway()
        val v = evaluate(signature(g.sigEcdsa), record(gateway = g.gatewayEcdsaAddress, gpu = g.gatewayEcdsaAddress), g.exchange)
        assertFalse("a gateway-kind signature verified", v is ResponseVerification.Verified)
    }

    @Test fun gatewaySignatureByUnboundKeyIsAMismatch() {
        val g = gateway()
        val v = evaluate(signature(g.sigEcdsa), record(gateway = g.gatewayEcdsaAddress, gatewayBound = false), g.exchange)
        assertTrue("expected signatureMismatch, got $v", reasonOf(v) is UnverifiedReason.SignatureMismatch)
    }

    @Test fun gatewaySignatureWithModelPrefixIsRejected() {
        val g = gateway()
        val v = evaluate(signature(g.sigEcdsa, mapOf("text" to "z-ai/glm-5.3-flash:" + g.sigEcdsa["text"])), record(gateway = g.gatewayEcdsaAddress), g.exchange)
        assertTrue("expected contentMismatch, got $v", reasonOf(v) is UnverifiedReason.ContentMismatch)
    }

    // MARK: ed25519

    @Test fun realEd25519SignatureChecksAgainstItsKey() {
        val g = gateway()
        val key = hex(g.gatewayEd25519Key)
        assertTrue(Ed25519Signature.isValid(g.sigEd25519["text"]!!, g.sigEd25519["signature"]!!, key))
        assertFalse(Ed25519Signature.isValid(g.sigEd25519["text"]!! + "x", g.sigEd25519["signature"]!!, key))
    }

    /** The gateway's Ed25519 key is not attested, so a valid one is still rejected. */
    @Test fun gatewayEd25519IsRejected() {
        val g = gateway()
        val v = evaluate(signature(g.sigEd25519), record(gateway = g.gatewayEcdsaAddress, modelKey = hex(g.gatewayEd25519Key)), g.exchange)
        assertTrue("expected signatureMismatch, got $v", reasonOf(v) is UnverifiedReason.SignatureMismatch)
    }

    @Test fun modelEd25519ByBoundKeyIsVerifiedAndUnboundIsNot() {
        val m = model()
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey().encoded
        val text = m.sig["text"]!!
        val signed = Ed25519Signer().apply { init(true, priv); text.toByteArray().let { update(it, 0, it.size) } }.generateSignature()
        val sig = signature(m.sig, mapOf("signing_algo" to "ed25519", "signing_address" to pub.toHex(), "signature" to signed.toHex()))
        val bound = evaluate(sig, record(modelKey = pub), m.exchange)
        assertTrue("expected verified, got $bound", bound is ResponseVerification.Verified)
        val unbound = evaluate(sig, record(modelKey = pub, modelKeyBound = false), m.exchange)
        assertFalse("an unbound model key verified", unbound is ResponseVerification.Verified)
        val forged = evaluate(signature(m.sig, mapOf("signing_algo" to "ed25519", "signing_address" to pub.toHex(), "signature" to "00".repeat(64))), record(modelKey = pub), m.exchange)
        assertFalse("a zero ed25519 signature verified on the asserted key", forged is ResponseVerification.Verified)
    }
}
