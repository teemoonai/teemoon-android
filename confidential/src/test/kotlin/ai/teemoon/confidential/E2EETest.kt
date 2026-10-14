// ports: teemoonTests/E2EETests.swift @ v1.0.1 (Hex → covered by Hex.kt's own contract; KATs → CryptoKitVectorsTest;
//        isE2EEActive propagation → GenerationReport, an :inference type). Randomness-is-the-property tests kept as Kotlin-self tests.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519

object E2EETestSupport {
    fun seed() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    /** A model keypair: Ed25519 seed → its public key and its X25519 private half. */
    class Model { val seed = seed(); val edPub = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }; val xPriv = Ed25519ToX25519.privateKey(seed) }
    fun x25519Key() = seed().let { Ed25519ToX25519.privateKey(it) }
    fun open(hex: String, priv: ByteArray) = String(E2EEEnvelope.open(hex.hexToBytes(), priv), Charsets.UTF_8)
    fun json(body: JsonObject) = Json.encodeToString(JsonObject.serializer(), body).toByteArray()
    fun parse(bytes: ByteArray) = Json.parseToJsonElement(String(bytes)).jsonObject
}

class XChaChaPolyTest {
    private fun key() = E2EETestSupport.seed()

    @Test fun roundTrip() {
        val k = key(); val plaintext = "Hello, TEE world!".toByteArray()
        val box = XChaChaPoly.seal(plaintext, k)
        assertTrue(XChaChaPoly.open(XChaChaPoly.SealedBox.fromCombined(box.combined), k).contentEquals(plaintext))
    }

    @Test fun wrongKey_fails() {
        val box = XChaChaPoly.seal("secret".toByteArray(), key())
        assertTrue(runCatching { XChaChaPoly.open(box, key()) }.isFailure)
    }

    /** A 32-BYTE plaintext: with one byte, two keystreams collide 1 in 256 and the test cried wolf. The nonce assertion is the invariant. */
    @Test fun freshNonceEverySeal() {
        val k = key(); val plaintext = ByteArray(32) { 0xA5.toByte() }
        val b1 = XChaChaPoly.seal(plaintext, k); val b2 = XChaChaPoly.seal(plaintext, k)
        assertFalse(b1.nonce.bytes.contentEquals(b2.nonce.bytes)); assertFalse(b1.ciphertext.contentEquals(b2.ciphertext))
    }

    @Test fun combinedTooShort_throws() { assertTrue(runCatching { XChaChaPoly.SealedBox.fromCombined(ByteArray(24 + 15)) }.exceptionOrNull() is XChaChaPoly.Error.IncorrectCombinedSize) }
    @Test fun nonceWrongLength_throws() { assertTrue(runCatching { XChaChaPoly.Nonce.of(ByteArray(12)) }.exceptionOrNull() is XChaChaPoly.Error.IncorrectNonceSize) }
}

class KeyConversionTest {
    @Test fun seedConversion_producesValidKey() {
        val x = Ed25519ToX25519.privateKey(E2EETestSupport.seed()); val peer = E2EETestSupport.x25519Key()
        val shared = ByteArray(32); assertTrue(X25519.calculateAgreement(x, 0, Ed25519ToX25519.x25519PublicKey(peer), 0, shared, 0))
    }

    /** Both routes must yield the same X25519 public key. */
    @Test fun pubKeyConversion_matchesTheScalarRoute() {
        val m = E2EETestSupport.Model()
        assertEquals(Ed25519ToX25519.x25519PublicKey(m.xPriv).toHex(), Ed25519ToX25519.publicKey(m.edPub).toHex())
    }

    @Test fun invalidLength_throws() { assertTrue(runCatching { Ed25519ToX25519.publicKey(ByteArray(16)) }.exceptionOrNull() is E2EEError.InvalidKeyLength) }
}

class WireFormatTest {
    @Test fun encrypt_producesCorrectLayout() {
        val recipient = E2EETestSupport.x25519Key(); val plaintext = "test message".toByteArray()
        assertEquals(32 + 24 + plaintext.size + 16, E2EEEnvelope.seal(plaintext, Ed25519ToX25519.x25519PublicKey(recipient)).size)
    }
    @Test fun encrypt_decrypt_roundTrip() {
        val recipient = E2EETestSupport.x25519Key(); val plaintext = "The quick brown fox jumps over the lazy dog".toByteArray()
        assertTrue(E2EEEnvelope.open(E2EEEnvelope.seal(plaintext, Ed25519ToX25519.x25519PublicKey(recipient)), recipient).contentEquals(plaintext))
    }
    @Test fun decrypt_shortData_fails() { assertTrue(runCatching { E2EEEnvelope.open(ByteArray(50), E2EETestSupport.x25519Key()) }.exceptionOrNull() is E2EEError.DecryptionFailed) }
}

class E2EEPeerTest {
    @Test fun sessionEncryptDecrypt_roundTrip() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        assertEquals("Hello from the client!", E2EETestSupport.open(session.encrypt("Hello from the client!"), m.xPriv))
    }
    @Test fun sessionDecrypt_modelResponse() {
        val session = E2EEPeer(E2EETestSupport.Model().edPub)
        val wire = E2EEEnvelope.seal("Here is the model's response.".toByteArray(), session.agreementPublicKey)
        assertEquals("Here is the model's response.", session.decrypt(wire.toHex()))
    }
    @Test fun headers_containRequiredFields() {
        val h = E2EEPeer(E2EETestSupport.Model().edPub).headers
        assertEquals("ed25519", h["X-Signing-Algo"]); assertEquals("2", h["X-Encryption-Version"]); assertEquals(64, h["X-Client-Pub-Key"]!!.length); assertEquals(64, h["X-Model-Pub-Key"]!!.length)
        assertEquals("true", h["X-Encrypt-All-Fields"])
    }
    @Test fun sessionStoresModelPubKeyHex_forVerification() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        assertEquals(m.edPub.toHex(), session.modelPubKeyHex); assertEquals(m.edPub.toHex(), session.headers["X-Model-Pub-Key"])
    }
}

class RequestBodyEncryptionTest {
    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    @Test fun encryptRequestBody_replacesContent() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        val body = buildJsonObject { put("model", "test-model"); putJsonArray("messages") { add(msg("system", "You are helpful.")); add(msg("user", "What is 2+2?")) } }
        val result = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(body)))
        assertEquals("test-model", result["model"]!!.jsonPrimitive.content)
        val msgs = result["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, msgs.size); assertEquals("system", msgs[0]["role"]!!.jsonPrimitive.content)
        val encSystem = msgs[0]["content"]!!.jsonPrimitive.content; val encUser = msgs[1]["content"]!!.jsonPrimitive.content
        assertNotEquals("You are helpful.", encSystem); assertNotEquals("What is 2+2?", encUser)
        assertEquals("You are helpful.", E2EETestSupport.open(encSystem, m.xPriv)); assertEquals("What is 2+2?", E2EETestSupport.open(encUser, m.xPriv))
    }

    @Test fun encryptRequestBody_emptyContent_unchanged() {
        val session = E2EEPeer(E2EETestSupport.Model().edPub)
        val result = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(buildJsonObject { putJsonArray("messages") { add(msg("user", "")) } })))
        assertEquals("", result["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun encryptRequestBody_invalidJSON_returnsOriginal() {
        val garbage = "not json".toByteArray()
        assertTrue(E2EEPeer(E2EETestSupport.Model().edPub).encryptRequestBody(garbage).contentEquals(garbage))
    }

    @Test fun encryptRequestBody_toolResultsFollowUp() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        val body = buildJsonObject {
            put("model", "test-model"); put("stream", true)
            putJsonArray("messages") {
                add(msg("system", "You are helpful.")); add(msg("user", "What's the weather?"))
                addJsonObject { put("role", "assistant"); put("content", ""); putJsonArray("tool_calls") { addJsonObject { put("id", "call_1"); put("type", "function"); putJsonObject("function") { put("name", "brave_search"); put("arguments", "{\"query\":\"weather today\"}") } } } }
                addJsonObject { put("role", "tool"); put("tool_call_id", "call_1"); put("content", "SOURCE weather.com | https://weather.com\nSunny, 72F") }
            }
        }
        val msgs = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(body)))["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, msgs.size)
        assertNotEquals("You are helpful.", msgs[0]["content"]!!.jsonPrimitive.content)
        assertEquals("", msgs[2]["content"]!!.jsonPrimitive.content)   // assistant empty content stays empty
        assertEquals("SOURCE weather.com | https://weather.com\nSunny, 72F", E2EETestSupport.open(msgs[3]["content"]!!.jsonPrimitive.content, m.xPriv))
    }

    @Test fun encryptRequestBody_preservesStreamAndAllFields() {
        val session = E2EEPeer(E2EETestSupport.Model().edPub)
        val body = buildJsonObject { put("model", "zai-org/GLM-5-FP8"); put("stream", true); putJsonObject("stream_options") { put("include_usage", true) }; put("temperature", 0.7); put("max_tokens", 1024); putJsonArray("messages") { add(msg("user", "Test")) } }
        val r = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(body)))
        assertTrue(r["stream"]!!.jsonPrimitive.boolean); assertTrue(r["stream_options"] != null)
        assertEquals("zai-org/GLM-5-FP8", r["model"]!!.jsonPrimitive.content); assertEquals("0.7", r["temperature"]!!.jsonPrimitive.content); assertEquals("1024", r["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun encryptRequestBody_encryptsToolCallArgsNamesDefinitionsAndToolChoice() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        val body = buildJsonObject {
            put("model", "test-model")
            putJsonArray("messages") { add(msg("user", "Hello")); addJsonObject { put("role", "assistant"); put("content", ""); putJsonArray("tool_calls") { addJsonObject { put("id", "call_1"); put("type", "function"); putJsonObject("function") { put("name", "brave_search"); put("arguments", "{\"query\":\"weather today\"}") } } } } }
            putJsonArray("tools") { addJsonObject { put("type", "function"); putJsonObject("function") { put("name", "brave_search"); put("description", "Search the web"); putJsonObject("parameters") { put("type", "object"); putJsonObject("properties") { putJsonObject("query") { put("type", "string") } } } } } }
            putJsonObject("tool_choice") { put("type", "function"); putJsonObject("function") { put("name", "brave_search") } }
        }
        val r = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(body)))
        val fn = r["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("brave_search", E2EETestSupport.open(fn["name"]!!.jsonPrimitive.content, m.xPriv))
        assertEquals("{\"query\":\"weather today\"}", E2EETestSupport.open(fn["arguments"]!!.jsonPrimitive.content, m.xPriv))
        val tool = r["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("brave_search", E2EETestSupport.open(tool["name"]!!.jsonPrimitive.content, m.xPriv))
        assertEquals("Search the web", E2EETestSupport.open(tool["description"]!!.jsonPrimitive.content, m.xPriv))
        // The schema is sealed WHOLE, with sorted keys.
        assertEquals("""{"properties":{"query":{"type":"string"}},"type":"object"}""", E2EETestSupport.open(tool["parameters"]!!.jsonPrimitive.content, m.xPriv))
        assertEquals("brave_search", E2EETestSupport.open(r["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content, m.xPriv))
    }
}

class StreamingAndFollowUpTest {
    @Test fun streamingChunks_andBothFields_decryptIndependently() {
        val session = E2EEPeer(E2EETestSupport.Model().edPub)
        var accumulated = ""
        for (chunk in listOf("2+2", " equals", " 4.")) accumulated += session.decrypt(E2EEEnvelope.seal(chunk.toByteArray(), session.agreementPublicKey).toHex())
        assertEquals("2+2 equals 4.", accumulated)
        val reasoning = E2EEEnvelope.seal("The user is asking about X...".toByteArray(), session.agreementPublicKey)
        val content = E2EEEnvelope.seal("Here is my answer.".toByteArray(), session.agreementPublicKey)
        assertFalse(reasoning.contentEquals(content))
        assertEquals("The user is asking about X...", session.decrypt(reasoning.toHex())); assertEquals("Here is my answer.", session.decrypt(content.toHex()))
    }

    @Test fun fullE2EERoundtrip_streaming() {
        val m = E2EETestSupport.Model(); val session = E2EEPeer(m.edPub)
        val body = buildJsonObject { put("model", "test-model"); put("stream", true); putJsonArray("messages") { addJsonObject { put("role", "system"); put("content", "You are helpful.") }; addJsonObject { put("role", "user"); put("content", "What is 2+2?") } } }
        val enc = E2EETestSupport.parse(session.encryptRequestBody(E2EETestSupport.json(body)))
        assertTrue(enc["stream"]!!.jsonPrimitive.boolean)
        for (msg in enc["messages"]!!.jsonArray.map { it.jsonObject }) assertTrue(E2EETestSupport.open(msg["content"]!!.jsonPrimitive.content, m.xPriv).isNotEmpty())
        var accumulated = ""
        for (chunk in listOf("First search found 3 results.", "Second search refined the answer.", "Here is the final answer.")) accumulated += session.decrypt(E2EEEnvelope.seal(chunk.toByteArray(), session.agreementPublicKey).toHex())
        assertTrue(accumulated.endsWith("final answer."))
    }
}

class E2EEStreamCodecTest {
    /** `encryptRequestBody` still passes a body it cannot parse through unchanged; the codec reports that as null so the transport can refuse to send. */
    @Test fun unchangedBody_isReportedAsNull() {
        val peer = E2EEPeer(E2EETestSupport.Model().edPub); val plain = "not json at all".toByteArray()
        assertTrue(peer.encryptRequestBody(plain).contentEquals(plain))
        val codec = E2EEStreamCodec(peer)
        assertTrue(codec.encryptedBodyIfChanged(plain) == null)
        assertTrue(codec.encryptedBodyIfChanged(E2EETestSupport.json(buildJsonObject { putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "hi") } } })) != null)
        assertEquals(peer.headers, codec.headers)
    }
    @Test fun decryptField_returnsNullOnFailure() {
        val codec = E2EEStreamCodec(E2EEPeer(E2EETestSupport.Model().edPub))
        assertTrue(codec.decryptField("zz") == null); assertTrue(codec.decryptField(ByteArray(80).toHex()) == null)
    }
}
