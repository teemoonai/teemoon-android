// conformance: crypto.json (kit 0.2.0, §7 item 1) — the byte-locked acceptance for XChaChaPoly, Ed25519ToX25519,
//              Keccak256, EthereumSignature and E2EEEnvelope. Vendored from teemoon-ios; never edited here.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.math.ec.rfc7748.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoKitVectorsTest {
    private val doc: JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream("/crypto.json")!!.reader().readText()).jsonObject
    private val v = doc["vectors"]!!.jsonObject
    private fun JsonObject.s(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    @Test fun kitIsTheOneThePortsMapPins() { assertEquals("crypto", doc.s("fixtureClass")); assertEquals("teemoon-ios", doc.s("sourceOfTruth")) }

    @Test fun hchacha20MatchesTheDraft() {
        for (x in v["hchacha20"]!!.jsonArray.map { it.jsonObject })
            assertEquals(x.s("note"), x.s("output"), XChaChaPoly.hchacha20(x.s("key")!!.hexToBytes(), x.s("input")!!.hexToBytes()).toHex())
    }

    @Test fun aeadMatchesTheDraft() {
        for (x in v["xchachaAEAD"]!!.jsonArray.map { it.jsonObject }) {
            val box = XChaChaPoly.seal(x.s("plaintext")!!.hexToBytes(), x.s("key")!!.hexToBytes(), XChaChaPoly.Nonce.of(x.s("nonce")!!.hexToBytes()), x.s("aad")!!.hexToBytes())
            assertEquals(x.s("ciphertext"), box.ciphertext.toHex()); assertEquals(x.s("tag"), box.tag.toHex())
            assertEquals(x.s("plaintext"), XChaChaPoly.open(box, x.s("key")!!.hexToBytes(), x.s("aad")!!.hexToBytes()).toHex())
        }
    }

    /** Both directions for the same seed must agree with CryptoKit: RFC 8032 scalar derivation and the RFC 7748 birational map. */
    @Test fun ed25519ToX25519MatchesCryptoKitBothWays() {
        for (x in v["ed25519ToX25519"]!!.jsonArray.map { it.jsonObject }) {
            val priv = Ed25519ToX25519.privateKey(x.s("ed25519Seed")!!.hexToBytes())
            assertEquals(x.s("x25519PrivateKey"), priv.toHex())
            assertEquals(x.s("x25519PublicKeyFromPrivate"), Ed25519ToX25519.x25519PublicKey(priv).toHex())
            assertEquals(x.s("x25519PublicKeyFromEdPublic"), Ed25519ToX25519.publicKey(x.s("ed25519PublicKey")!!.hexToBytes()).toHex())
            assertEquals(x.s("x25519PublicKeyFromPrivate"), x.s("x25519PublicKeyFromEdPublic"))
        }
    }

    @Test fun keccakMatchesTheEthereumVectors() {
        for (x in v["keccak256"]!!.jsonArray.map { it.jsonObject })
            assertEquals(x.s("note"), x.s("digest"), Keccak256.hash(x.s("messageHex")!!.hexToBytes()).toHex())
    }

    /** The production near.ai signature recovers its signer; tampered text never does; malformed inputs are null, never a crash. */
    @Test fun ecrecoverMatchesProduction() {
        for (x in v["ecrecover"]!!.jsonArray.map { it.jsonObject }) {
            val got = EthereumSignature.recoverAddress(x.s("text")!!, x.s("signature")!!)
            assertEquals(x.s("note"), x.s("expectedAddress"), got)
            x.s("personalSignDigest")?.let { assertEquals(it, EthereumSignature.personalSignDigest(x.s("text")!!).toHex()) }
        }
        val production = v["ecrecover"]!!.jsonArray.map { it.jsonObject }.first { it.s("note")!!.startsWith("production") }
        assertTrue(production.s("expectedAddress")!!.startsWith("0x"))
    }

    /** The full near.ai v2 envelope under fixed keys and nonce opens under production, and its key derivation is the recorded one. */
    @Test fun envelopeOpensUnderProduction() {
        for (x in v["e2eeEnvelope"]!!.jsonArray.map { it.jsonObject }) {
            val recipientPriv = x.s("recipientX25519PrivateKey")!!.hexToBytes()
            assertEquals(x.s("recipientX25519PublicKey"), Ed25519ToX25519.x25519PublicKey(recipientPriv).toHex())
            assertEquals(x.s("ephemeralX25519PublicKey"), Ed25519ToX25519.x25519PublicKey(x.s("ephemeralX25519PrivateKey")!!.hexToBytes()).toHex())
            val opened = E2EEEnvelope.open(x.s("wireFormat")!!.hexToBytes(), recipientPriv)
            assertEquals(x.s("plaintextUTF8"), String(opened, Charsets.UTF_8))
            // The derived key, recomputed from the recorded shared secret inputs.
            val shared = ByteArray(32); X25519.calculateAgreement(x.s("ephemeralX25519PrivateKey")!!.hexToBytes(), 0, x.s("recipientX25519PublicKey")!!.hexToBytes(), 0, shared, 0)
            assertEquals(x.s("derivedKey"), E2EEEnvelope.deriveKey(shared).toHex())
            assertEquals(x.s("nonce"), x.s("wireFormat")!!.substring(64, 64 + 48))
        }
    }

    @Test fun unknownVectorClassesAreNotSilentlyIgnored() {
        assertEquals(setOf("hchacha20", "xchachaAEAD", "ed25519ToX25519", "keccak256", "ecrecover", "e2eeEnvelope"), v.keys)
        assertNull(v["contentBinding"])   // owed by iOS (§7 item 1 lists it); ContentBindingTest covers the shape synthetically until then
    }
}
