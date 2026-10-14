// ports: teemoonTests/ResponseSignatureTests.swift @ v1.0.1 (Keccak256 and the ContentBinding shape; the ecrecover suite and
//        the production-exchange binding tests are byte-locked by crypto.json where the kit carries them — the exchange itself
//        (nearai_signed_response.json) is not vendored, so those four run over synthetic exchanges here)
//        teemoonTests/TEESignatureVerifierTests.swift @ v1.0.1
package ai.teemoon.confidential

import ai.teemoon.confidential.ResponseVerification.UnverifiedReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.io.IOException

class TeeSignatureTest {
    @Test fun keccakStandardVectors() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Keccak256.hash(ByteArray(0)).toHex())
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", Keccak256.hash("abc".toByteArray()).toHex())
        assertEquals("4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15", Keccak256.hash("The quick brown fox jumps over the lazy dog".toByteArray()).toHex())
        val input = "a".repeat(300).toByteArray(); assertEquals(32, Keccak256.hash(input).size); assertTrue(Keccak256.hash(input).contentEquals(Keccak256.hash(input)))
    }

    @Test fun malformedSignaturesReturnNullWithoutCrashing() {
        for (filler in listOf("00", "ff", "aa", "7f")) EthereumSignature.recoverAddress("x", "0x" + filler.repeat(64) + "1b")
        assertNull(EthereumSignature.recoverAddress("x", "0xdead")); assertNull(EthereumSignature.recoverAddress("x", "zz"))
        assertNull(EthereumSignature.recoverAddress("x", "0x" + "11".repeat(64) + "63"))
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()

    @Test fun contentBinding_bindsAndDetectsTampering() {
        val body = """{"model":"m","messages":[]}"""; val lines = listOf("data: {}", "data: [DONE]", "")
        val response = lines.joinToString("") { it + "\n" }
        val text = "m:${sha(body)}:${sha(response)}"
        val bound = ContentBinding.check(text, SignedExchange(listOf(body.toByteArray()), response))!!
        assertTrue(bound.isBound)
        assertFalse(ContentBinding.check(text, SignedExchange(listOf(body.toByteArray()), "$response "))!!.responseHashMatches)
        assertFalse(ContentBinding.check(text, SignedExchange(listOf("$body ".toByteArray()), response))!!.requestHashMatches)
        assertTrue(ContentBinding.check(text, SignedExchange(listOf("something else".toByteArray(), body.toByteArray()), response))!!.requestHashMatches)   // under E2EE both bodies are offered
        // Dropping the blank line after [DONE] is the live "didn't check out" bug.
        assertFalse(ContentBinding.check(text, SignedExchange(listOf(body.toByteArray()), lines.dropLast(1).joinToString("") { it + "\n" }))!!.responseHashMatches)
    }

    @Test fun contentBinding_parsesTwoPartText_andRejectsMalformed() {
        val ex = SignedExchange(listOf("req".toByteArray()), "resp")
        val b = ContentBinding.check("${sha("req")}:0000", ex)!!
        assertTrue(b.requestHashMatches); assertFalse(b.responseHashMatches)
        assertNull(ContentBinding.check("onlyonepart", ex)); assertNull(ContentBinding.check("a:b:c:d", ex))
    }

    @Test fun teeMessageSignature_decodes() {
        val sig = Json.decodeFromString(TEEMessageSignature.serializer(), """{"text":"abc123:def456","signature":"0xdeadbeef","signing_address":"0x4a8b","signing_algo":"ecdsa"}""")
        assertEquals("abc123:def456", sig.text); assertEquals("0x4a8b", sig.signingAddress); assertEquals("ecdsa", sig.signingAlgo)
        assertTrue(runCatching { Json.decodeFromString(TEEMessageSignature.serializer(), """{"text":"abc:def","signature":"0xdeadbeef"}""") }.isFailure)
    }

    // MARK: - The verifier over seams

    private fun body(text: String, signature: String, address: String, algo: String = "ecdsa") =
        """{"text":"$text","signature":"$signature","signing_address":"$address","signing_algo":"$algo"}""".toByteArray()

    @Test fun returnsUnavailableWhenNoTrustedAddresses() = runTest {
        for (pair in listOf(null to null, "" to "")) {
            val r = TEESignatureVerifier.verify("test-123", pair.first, pair.second, false, null, fetchSignature = { error("must not fetch") }, sleepMs = {})
            assertEquals(ResponseVerification.Unverified(UnverifiedReason.SignatureUnavailable), r)
        }
    }

    @Test fun a404TwiceIsUnavailable_andTheSecondAttemptWaits() = runTest {
        val sleeps = ArrayList<Long>(); var calls = 0
        val r = TEESignatureVerifier.verify("id", "0xabc", null, false, null, fetchSignature = { calls++; 404 to ByteArray(0) }, sleepMs = { sleeps.add(it) })
        assertEquals(ResponseVerification.Unverified(UnverifiedReason.SignatureUnavailable), r); assertEquals(2, calls); assertEquals(listOf(2_000L, 4_000L), sleeps)
    }

    /** The signer is RECOVERED from the signature; a forged response quoting a trusted address does not pass. */
    @Test fun forgedSignatureQuotingATrustedAddress_isRejected() = runTest {
        val r = TEESignatureVerifier.verify("id", "0x4a8b3f2e9d1c7a5e2b0f8d3c6e1a4b7f2e9d1c7a", null, false, null,
            fetchSignature = { 200 to body("fake_req:fake_resp", "0x" + "11".repeat(64) + "1b", "0x4a8b3f2e9d1c7a5e2b0f8d3c6e1a4b7f2e9d1c7a") }, sleepMs = {})
        assertTrue(r is ResponseVerification.Unverified && (r.reason as? UnverifiedReason.SignatureMismatch)?.got == "unrecoverable signature" || r is ResponseVerification.Unverified && r.reason is UnverifiedReason.SignatureMismatch)
    }

    @Test fun contentMismatch_isDecisiveBeforeAnySigner() = runTest {
        val r = TEESignatureVerifier.verify("id", "0xabc", null, true, SignedExchange(listOf("req".toByteArray()), "resp"),
            fetchSignature = { 200 to body("${sha("other")}:${sha("resp")}", "0x00", "0xabc") }, sleepMs = {})
        assertEquals("signed request hash does not match observed content", ((r as ResponseVerification.Unverified).reason as UnverifiedReason.ContentMismatch).detail)
    }

    /** A non-ECDSA algo falls back to the asserted address; an unattested signer through a verified gateway is gateway-trust-only, never verified. */
    @Test fun nonEcdsaAssertedAddress_andGatewayTrustOnly() = runTest {
        val ok = TEESignatureVerifier.verify("id", "0xABC", null, false, null, fetchSignature = { 200 to body("a:b", "0x", "0xabc", algo = "other") }, sleepMs = {})
        assertEquals(ResponseVerification.Verified("0xabc"), ok)
        val gw = TEESignatureVerifier.verify("id", "0xabc", null, true, null, fetchSignature = { 200 to body("a:b", "0x", "0xdef", algo = "other") }, sleepMs = {})
        assertEquals(ResponseVerification.Unverified(UnverifiedReason.GatewayTrustOnly("0xdef")), gw)
        val rotated = TEESignatureVerifier.verify("id", "0xabc", null, false, null, fetchSignature = { 200 to body("a:b", "0x", "0xdef", algo = "other") }, refetchSigningAddress = { listOf(null, "0xDEF") }, sleepMs = {})
        assertEquals(ResponseVerification.Verified("0xdef"), rotated)
        val mismatch = TEESignatureVerifier.verify("id", "0xabc", null, false, null, fetchSignature = { 200 to body("a:b", "0x", "0xdef", algo = "other") }, sleepMs = {})
        assertEquals(ResponseVerification.Unverified(UnverifiedReason.SignatureMismatch("0xabc", "0xdef")), mismatch)
    }

    @Test fun fetchFailure_isReported() = runTest {
        val r = TEESignatureVerifier.verify("id", "0xabc", null, false, null, fetchSignature = { throw IOException("down") }, sleepMs = {})
        assertTrue((r as ResponseVerification.Unverified).reason is UnverifiedReason.FetchFailed)
    }
}
