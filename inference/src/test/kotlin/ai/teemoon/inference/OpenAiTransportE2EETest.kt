// ports: teemoonTests/E2EESecurityExploitTests.swift @ v1.0.1 — "Exploit: Plaintext fallback fails closed", the two
//        HTTPTransport tests (the ChatViewModel refusal tests are in :app). + android-only: a full sealed round trip,
//        a decrypt failure killing the stream, and a plain provider sending plaintext with no E2EE headers.
package ai.teemoon.inference

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.E2EEEnvelope
import ai.teemoon.confidential.E2EEError
import ai.teemoon.confidential.E2EEPeer
import ai.teemoon.confidential.E2EEStreamCodec
import ai.teemoon.confidential.Ed25519ToX25519
import ai.teemoon.confidential.TeeContext
import ai.teemoon.confidential.hexToBytesOrNull
import ai.teemoon.confidential.toHex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.io.IOException

class OpenAiTransportE2EETest {
    /** A model key pair from the JDK's Ed25519: the raw public key and seed are the last 32 bytes of the SPKI / PKCS#8 encodings. */
    private class ModelKey {
        private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val edPub: ByteArray = kp.public.encoded.takeLast(32).toByteArray()
        val x25519Priv: ByteArray = Ed25519ToX25519.privateKey(kp.private.encoded.takeLast(32).toByteArray())
    }

    private fun record(key: ByteArray?) = AttestationRecord(composeHash = "test", mrtd = "test", osImageHash = "test", intelQuote = "", composeManifest = null, gpuArch = null,
        gpuNodeComposeHash = null, modelFileHash = null, signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = key,
        quoteVerification = null, gpuQuoteVerification = null, modelQuoteVerification = null, fetchedAt = Instant.now(), providerID = "p")
    private fun context(peer: E2EEPeer?, key: ByteArray?) = TeeContext("https://cloud-api.near.ai/v1", "z-ai/glm-5.2", "k", record(key), null, peer)

    /** Fails the fail-closed contract if any request is ever issued: every request that reaches the wire is counted and answered with an error. */
    private class Tripwire : Interceptor {
        var requestCount = 0
        override fun intercept(chain: Interceptor.Chain): Response { requestCount++; throw IOException("tripwire: a request reached the wire") }
    }
    private fun client(i: Interceptor) = OkHttpClient.Builder().addInterceptor(i).build()
    private fun jsonResponse(chain: Interceptor.Chain, body: String, code: Int = 200) = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("ok")
        .header("Content-Type", "application/json").body(body.toResponseBody("application/json".toMediaType())).build()
    private fun requestBody(chain: Interceptor.Chain): String = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()

    /** THE transport guarantee: when sealing fails, runTurn throws and NOTHING is sent. */
    @Test fun runTurn_encryptionFailure_throwsAndNothingTouchesTheWire() {
        // A LOW-ORDER model key injected past the conversion guard: X25519 agreement against u = 0 fails inside the seal.
        val lowOrderU = ByteArray(32)
        val peer = E2EEPeer.bypassingConversion(lowOrderU, lowOrderU.toHex())
        val tripwire = Tripwire()
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k", client = client(tripwire), teeContext = context(peer, lowOrderU))
        val thrown = runCatching { runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "my private data")), false, emptyList()) {} } }.exceptionOrNull()
        assertTrue("sealing failure must throw, not fall back to plaintext: $thrown", thrown is LlmError)
        assertTrue((thrown as LlmError).underlyingError is E2EEError)
        assertEquals("fail-closed means the request NEVER touches the wire", 0, tripwire.requestCount)
    }

    /** The other failure mode: an unparseable body passes through unchanged, the codec reports null — and the transport turns that into a HARD failure with no E2EE headers. */
    @Test fun unchangedBody_becomesAHardFailure_notAPlaintextSend() {
        val peer = E2EEPeer(ModelKey().edPub)
        val plaintextBody = "not json at all".toByteArray()
        assertTrue(peer.encryptRequestBody(plaintextBody).contentEquals(plaintextBody))
        val codec = E2EEStreamCodec(peer)
        assertNull(codec.encryptedBodyIfChanged(plaintextBody))
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k")
        val thrown = runCatching { transport.e2eeSealedBody(plaintextBody, codec) }.exceptionOrNull()
        assertTrue((thrown as? LlmError)?.underlyingError is E2EEError)
    }

    /** The whole seal: every field goes out encrypted under the E2EE headers, and the model's sealed reply comes back as plaintext content. */
    @Test fun sealedRoundTrip_encryptsTheRequestAndDecryptsTheReply() {
        val model = ModelKey()
        val peer = E2EEPeer(model.edPub)
        var seen: String? = null; var clientKeyHex: String? = null; var encryptAll: String? = null
        val server = Interceptor { chain ->
            seen = requestBody(chain); clientKeyHex = chain.request().header("X-Client-Pub-Key"); encryptAll = chain.request().header("X-Encrypt-All-Fields")
            val clientX = Ed25519ToX25519.publicKey(clientKeyHex!!.hexToBytesOrNull()!!)
            val sealedReply = E2EEEnvelope.seal("hello, sealed".toByteArray(), clientX).toHex()
            jsonResponse(chain, """{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"$sealedReply"},"finish_reason":"stop"}],"usage":{"completion_tokens":3}}""")
        }
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k", client = client(server), teeContext = context(peer, model.edPub))
        val partials = ArrayList<String>()
        val result = runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "my private data")), false, emptyList()) { partials.add(it) } }
        assertEquals("true", encryptAll)
        val wireContent = Json.parseToJsonElement(seen!!).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertFalse("the plaintext must not be on the wire", seen.contains("my private data"))
        assertEquals("my private data", String(E2EEEnvelope.open(wireContent.hexToBytesOrNull()!!, model.x25519Priv)))
        assertEquals("hello, sealed", result.content); assertEquals(listOf("hello, sealed"), partials)
        assertTrue(result.report.isE2eeActive); assertTrue(result.report.sealedBodyJson!!.contains(wireContent)); assertEquals(3, result.completionTokens)
        assertTrue(transport.lastRawTranscript.contains("chatcmpl-1"))
    }

    /** A reply that does not decrypt kills the turn: the ciphertext is never rendered, not even for a beat. */
    @Test fun decryptFailure_failsTheStream_neverRendersCiphertext() {
        val model = ModelKey()
        val server = Interceptor { chain -> jsonResponse(chain, """{"id":"c","choices":[{"message":{"role":"assistant","content":"zz not hex"},"finish_reason":"stop"}]}""") }
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k", client = client(server), teeContext = context(E2EEPeer(model.edPub), model.edPub))
        val partials = ArrayList<String>()
        val thrown = runCatching { runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "x")), false, emptyList()) { partials.add(it) } } }.exceptionOrNull()
        assertTrue((thrown as? LlmError)?.underlyingError is E2EEError); assertTrue(partials.isEmpty())
    }

    /** Non-attested providers made no E2EE promise — they keep sending plaintext, with no E2EE headers, and stream normally. */
    @Test fun plainProvider_sendsPlaintextWithoutE2EEHeaders() {
        var seen: String? = null; var clientKey: String? = null
        val server = Interceptor { chain ->
            seen = requestBody(chain); clientKey = chain.request().header("X-Client-Pub-Key")
            val sse = "data: {\"id\":\"c2\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").header("Content-Type", "text/event-stream")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }
        val transport = OpenAiTransport("http://127.0.0.1:1/v1/chat/completions", "fake", client = client(server))
        val result = runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "plain text")), false, emptyList()) {} }
        assertTrue(seen!!.contains("plain text")); assertNull(clientKey); assertFalse(result.report.isE2eeActive)
        assertEquals("hi", result.content); assertNull(result.report.verify)
        // The signed form: every received line + "\n", the [DONE] event's blank line included.
        assertEquals("data: {\"id\":\"c2\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n", transport.lastRawTranscript)
    }
}
