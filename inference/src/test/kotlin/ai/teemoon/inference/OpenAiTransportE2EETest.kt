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

    /**
     * android-only (iOS ad960c7's retry, under E2EE): when near.ai refuses the effort field, the turn is asked again
     * RE-SEALED from the plaintext — new ciphertext (a fresh envelope per field), never the first request's sealed
     * bytes. The client key header is the session's, so it is the same on both.
     */
    @Test fun anEffortRetryIsSealedAfreshNotResent() {
        ReasoningEffort.forgetRefusals()
        val model = ModelKey()
        val peer = E2EEPeer(model.edPub)
        val bodies = ArrayList<String>(); val clientKeys = ArrayList<String>()
        val server = Interceptor { chain ->
            val body = requestBody(chain).also { bodies += it }; val key = chain.request().header("X-Client-Pub-Key")!!.also { clientKeys += it }
            if (body.contains("reasoning_effort")) jsonResponse(chain, """{"error":"Invalid reasoning effort."}""", code = 400)
            else {
                val sealedReply = E2EEEnvelope.seal("ok".toByteArray(), Ed25519ToX25519.publicKey(key.hexToBytesOrNull()!!)).toHex()
                jsonResponse(chain, """{"id":"chatcmpl-2","choices":[{"message":{"role":"assistant","content":"$sealedReply"},"finish_reason":"stop"}]}""")
            }
        }
        val near = ai.teemoon.providers.Provider(id = "n", name = "near.ai", endpoint = "https://cloud-api.near.ai/v1", model = "z-ai/glm-5.2", requiresAPIKey = true)
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k", client = client(server), teeContext = context(peer, model.edPub),
            effort = ReasoningEffort.For(ReasoningEffort.FAST, near))
        val result = runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "my private data")), false, emptyList()) {} }
        assertEquals("ok", result.content)
        assertEquals(2, bodies.size)
        fun wire(b: String) = Json.parseToJsonElement(b).jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("the retry's ciphertext is new", wire(bodies[0]) != wire(bodies[1]))
        // the response key is the session's (E2EEPeer), not per request: the retry answers to the same client key
        assertEquals(clientKeys[0], clientKeys[1])
        for (b in bodies) {
            assertFalse("never plaintext on the wire", b.contains("my private data"))
            assertEquals("my private data", String(E2EEEnvelope.open(wire(b).hexToBytesOrNull()!!, model.x25519Priv)))
        }
        assertFalse(bodies[1].contains("reasoning_effort"))
        ReasoningEffort.forgetRefusals()
    }

    // audit 2026-11-06 (proxy 0be4ee8f): under E2EE v2 near.ai's proxy returns reply fields in plaintext only when the
    // request asks for one. conformance/e2ee-request-keys.json (kit 0.5.10, iOS d623299c, sha256 08947008…1e35) names them;
    // 0.5.10 adds return_prompt_token_ids (alone it returns the whole conversation's prompt token ids in plaintext at v2,
    // sglang 3eccc307 serving_chat.py:2047) and return_meta_info. `stop` stays allowed: it echoes only the user's own string.
    private val requestKeys = Json.parseToJsonElement(javaClass.getResourceAsStream("/e2ee-request-keys.json")!!.reader().readText()).jsonObject
    private val forbidden = requestKeys.getValue("forbiddenKeys").jsonArray.map { it.jsonPrimitive.content }.toSet()
    private val pathSuffix = requestKeys.getValue("allowedPathSuffix").jsonPrimitive.content
    private fun keysAtAnyDepth(e: kotlinx.serialization.json.JsonElement): Set<String> = when (e) {
        is kotlinx.serialization.json.JsonObject -> e.keys + e.values.flatMap { keysAtAnyDepth(it) }
        is kotlinx.serialization.json.JsonArray -> e.flatMap { keysAtAnyDepth(it) }.toSet()
        else -> emptySet()
    }
    /** A sealed near.ai turn's wire requests, (path, body), each answered with a sealed "ok". */
    private fun sealedTurn(endpoint: String, extras: Map<String, String>, tools: Boolean = false): Pair<List<Pair<String, String>>, Throwable?> {
        val model = ModelKey(); val peer = E2EEPeer(model.edPub)
        val wire = ArrayList<Pair<String, String>>()
        val server = Interceptor { chain ->
            wire += chain.request().url.encodedPath to requestBody(chain)
            val sealedReply = E2EEEnvelope.seal("ok".toByteArray(), Ed25519ToX25519.publicKey(chain.request().header("X-Client-Pub-Key")!!.hexToBytesOrNull()!!)).toHex()
            jsonResponse(chain, """{"id":"chatcmpl-3","choices":[{"message":{"role":"assistant","content":"$sealedReply"},"finish_reason":"stop"}]}""")
        }
        val transport = OpenAiTransport(endpoint, "z-ai/glm-5.2", "k", client = client(server), teeContext = context(peer, model.edPub), extraParams = extras,
            effort = ReasoningEffort.For(ReasoningEffort.FAST, ai.teemoon.providers.Provider(id = "n", name = "near.ai", endpoint = "https://cloud-api.near.ai/v1", model = "z-ai/glm-5.2", requiresAPIKey = true)))
        val tool = object : Tool {
            override val name = "web_search"; override val description = "search the web"
            override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
            override suspend fun execute(argumentsJson: String) = ""
        }
        val thrown = runCatching { runBlocking { transport.runTurn(listOf(mapOf("role" to "user", "content" to "my private data")), tools, if (tools) listOf(tool) else emptyList()) {} } }.exceptionOrNull()
        return wire to thrown
    }

    /** iOS E2EERequestKeysTests.noSealedRequestCarriesAPlaintextReplyField: what teemoon itself builds never asks. */
    @Test fun noSealedRequestCarriesAPlaintextReplyField() {
        assertEquals(7, forbidden.size)
        assertFalse("stop stays allowed", "stop" in forbidden)
        for (tools in listOf(false, true)) {
            val (wire, thrown) = sealedTurn("https://cloud-api.near.ai/v1/chat/completions", emptyMap(), tools)
            assertNull(thrown); assertTrue(wire.isNotEmpty())
            for ((path, body) in wire) {
                assertTrue(path, path.endsWith(pathSuffix))
                assertEquals(emptySet<String>(), keysAtAnyDepth(Json.parseToJsonElement(body)).intersect(forbidden))
            }
        }
    }

    /**
     * iOS E2EERequestKeysTests.aPlacesExtraParametersNeverAskForPlaintext: a place's extra parameters carrying one of the
     * vector's keys, or a sealed turn aimed anywhere but chat completions, are refused before the wire with the existing
     * "couldn't be encrypted, so it wasn't sent" error. Latent: no screen or import sets extras (pm 2026-11-06).
     */
    @Test fun aPlacesExtraParametersNeverAskForPlaintext() {
        val vectors = requestKeys.getValue("extraParamsVectors").jsonObject
        assertEquals("refused before the wire", vectors.getValue("expected").jsonPrimitive.content)
        for (key in vectors.getValue("keys").jsonArray.map { it.jsonPrimitive.content }) {
            val (wire, thrown) = sealedTurn("https://cloud-api.near.ai/v1/chat/completions", mapOf(key to "true"))
            assertTrue("$key: $thrown", thrown is LlmError)
            assertEquals(key, emptyList<Pair<String, String>>(), wire)
        }
        val (wire, thrown) = sealedTurn("https://cloud-api.near.ai/v1/completions", emptyMap())
        assertTrue("$thrown", thrown is LlmError)
        assertEquals(emptyList<Pair<String, String>>(), wire)
        assertEquals("the existing error, no new words", "the request couldn't be encrypted, so it wasn't sent. try again. if it keeps happening, re-verify from the lock icon.", (thrown as LlmError).message)
        // at any depth: a key nested inside the body is refused too
        val nested = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"extra":{"return_token_ids":true}}""".toByteArray()
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.2", "k")
        assertTrue(runCatching { transport.e2eeSealedBody(nested, E2EEStreamCodec(E2EEPeer(ModelKey().edPub))) }.exceptionOrNull() is LlmError)
        // the code's list covers the kit's
        assertTrue(OpenAiTransport.PLAINTEXT_REPLY_KEYS.containsAll(forbidden + vectors.getValue("keys").jsonArray.map { it.jsonPrimitive.content }))
    }

    /** pm 2026-11-06: no preset carries a key that would ask near.ai for a reply field in the clear. */
    @Test fun noPresetCarriesAPlaintextReplyKey() {
        val keys = forbidden + requestKeys.getValue("extraParamsVectors").jsonObject.getValue("keys").jsonArray.map { it.jsonPrimitive.content }
        for (p in ai.teemoon.providers.Provider.presets) assertEquals(p.name, emptySet<String>(), p.extraParams.keys.intersect(keys))
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

    /**
     * iOS PromptCachePrefixTests.sealingTakesTheSortedPlaintextAndLeavesStrings (904de68): the sort is on the plaintext,
     * in makeBody, before e2eeSealedBody; sealed fields go out as plain ciphertext strings, in the same message order,
     * whatever the sort did.
     */
    @Test fun sealingTakesTheSortedPlaintextAndLeavesStrings() {
        var plaintext: ByteArray? = null
        val capture = Interceptor { chain -> plaintext = Buffer().also { chain.request().body!!.writeTo(it) }.readByteArray(); throw IOException("offline") }
        val transport = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", "z-ai/glm-5.3-flash", "k", client = client(capture))
        runCatching { runBlocking { transport.runTurn(listOf(mapOf("role" to "system", "content" to "persona"), mapOf("role" to "user", "content" to "my private question")), true, listOf(BraveWebSearchTool(apiKey = "b"))) {} } }
        val plain = Json.parseToJsonElement(String(plaintext!!, Charsets.UTF_8)).jsonObject
        assertEquals("the plaintext is sorted before it is sealed", plain.keys.sorted(), plain.keys.toList())
        val sealed = E2EEStreamCodec(E2EEPeer(ModelKey().edPub)).encryptedBodyIfChanged(plaintext)!!
        val json = Json.parseToJsonElement(String(sealed, Charsets.UTF_8)).jsonObject
        val messages = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        for (m in messages) {
            val content = m["content"] as? kotlinx.serialization.json.JsonPrimitive
            assertTrue("a sealed content is one ciphertext string", content != null && content.isString)
            assertFalse(content!!.content.contains("private"))
        }
        val fn = json["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject
        assertTrue("the schema is sealed whole, as a string", (fn["parameters"] as? kotlinx.serialization.json.JsonPrimitive)?.isString == true)
        assertFalse(String(sealed, Charsets.UTF_8).contains("my private question"))
    }
}
