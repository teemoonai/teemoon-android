package ai.teemoon.confidential

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The 1.1.1 audit's forged quote (teemoon-backups/audit-111/forge, AuditForgedQuoteTest): a listed enclave's real quote
 * with report_data rewritten to a key no enclave holds, header||body re-signed by a fresh P-256 key put in as the
 * attestation key, and the genuine Intel PCK chain left in place. At f2f859bc it read as verified and the listed check
 * came back Clean: the local verifier checked the chain and the quote signature but never that the PCK leaf vouches for
 * the attestation key. Intel's DCAP binds them through the QE report: the PCK leaf signs the QE report, and the QE
 * report's report_data is SHA-256(attestation key || QE auth data) followed by 32 zero bytes.
 */
class ForgedQuoteTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun quoteOf(e: JsonObject) = e.getValue("intel_quote").jsonPrimitive.content.removePrefix("0x").hexToBytes()

    @Test fun everyGenuineListedQuoteStillVerifies() {
        val c = LiveCandidates.load()
        c.list.forEachIndexed { i, e -> assertTrue("listed quote $i", TdxQuoteVerifier.verify(quoteOf(e)).isVerified) }
    }

    @Test fun aQuoteWhoseAttestationKeyThePckChainDoesNotVouchForIsNotVerified() {
        val c = LiveCandidates.load()
        val forged = ForgedQuote.forge(quoteOf(c.list[1]), ByteArray(32) { (it * 7 + 1).toByte() }, c.nonce.hexToBytes())
        val qv = TdxQuoteVerifier.verify(forged)
        // iOS folds the binding into signatureValid (ba0842d1), so the forger's own valid header||body signature no longer reads as one
        assertFalse("the forger's key is not the one the QE report binds", TdxQuoteVerifier.attestationKeyIsBoundToChain(qv.quote!!.signature))
        assertFalse(qv.signatureValid)
        assertTrue("the copied Intel chain is valid", qv.certChainValid)
        assertFalse("but the attestation key is not the one the QE report binds", qv.isVerified)
    }

    @Test fun aTamperedQeReportIsNotVerified() {
        val c = LiveCandidates.load()
        val q = quoteOf(c.list[0])
        // the QE report sits after the 4-byte size, 64-byte signature, 64-byte key and the 6-byte type-6 header
        val qeReport = 632 + 4 + 128 + 6
        q[qeReport + 320] = (q[qeReport + 320].toInt() xor 1).toByte()          // its report_data
        assertFalse(TdxQuoteVerifier.verify(q).isVerified)
    }

    @Test fun theForgedEntryAsAListedSiblingIsNotClean() {
        val c = LiveCandidates.load()
        val entry = c.list[1]
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val nonce = c.nonce.hexToBytes()
        val forged = JsonObject(entry + mapOf(
            "intel_quote" to JsonPrimitive(hex(ForgedQuote.forge(quoteOf(entry), key, nonce))), "signing_public_key" to JsonPrimitive(hex(key)),
            "report_data" to JsonPrimitive(hex(key + nonce))))
        assertNotEquals(ModelCandidates.Result.Clean, ModelCandidates.check(forged, key, c.nonce))
    }
}

/** The audit's forgery, shared with SendGateRuleTest's first-enclave case. */
internal object ForgedQuote {
    private fun fixed(v: java.math.BigInteger): ByteArray {
        val a = v.toByteArray(); val o = ByteArray(32); val n = minOf(32, a.size); System.arraycopy(a, a.size - n, o, 32 - n, n); return o
    }

    /** The audit's forgery, byte for byte in method: report_data = [modelKey] || nonce, re-signed by a key the forger holds. */
    fun forge(quote: ByteArray, modelKey: ByteArray, nonce: ByteArray): ByteArray {
        val q = quote.copyOf()
        System.arraycopy(modelKey, 0, q, 568, 32); System.arraycopy(nonce, 0, q, 600, 32)
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val der = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(q, 0, 632) }.sign()
        var p = 3; val rl = der[p].toInt(); val r = java.math.BigInteger(1, der.copyOfRange(p + 1, p + 1 + rl)); p += 1 + rl + 1
        val sl = der[p].toInt(); val s = java.math.BigInteger(1, der.copyOfRange(p + 1, p + 1 + sl))
        val w = (kp.public as ECPublicKey).w
        val sig = 632 + 4
        System.arraycopy(fixed(r), 0, q, sig, 32); System.arraycopy(fixed(s), 0, q, sig + 32, 32)
        System.arraycopy(fixed(w.affineX), 0, q, sig + 64, 32); System.arraycopy(fixed(w.affineY), 0, q, sig + 96, 32)
        return q
    }

}
