// ports: teemoonTests/TierDowngradeTests.swift @ 6c364e33 (SealedArmingTests: error, stop, plain, no-key; the intent test is
//        ConfidentialSessionArmingTest in :confidential: Android has no Shortcuts path, a send is prepareTurn -> currentTeeContext -> transport)
//
// 1.1.1 audit B1 read: a near.ai model is armed when its sealed request reaches the wire, not when a reply ends. Each test runs the
// real transport (OpenAiTransport.runTurn) against an interceptor; no network. The last test pins that arming changes no byte.
package ai.teemoon.inference

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.E2EEEnvelope
import ai.teemoon.confidential.E2EEPeer
import ai.teemoon.confidential.Ed25519ToX25519
import ai.teemoon.confidential.SealedModels
import ai.teemoon.confidential.TeeContext
import ai.teemoon.confidential.hexToBytesOrNull
import ai.teemoon.confidential.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.time.Instant

class SealedArmingTest {
    private val model = "z-ai/arming-test"

    private class ModelKey {
        private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val edPub: ByteArray = kp.public.encoded.takeLast(32).toByteArray()
        val x25519Priv: ByteArray = Ed25519ToX25519.privateKey(kp.private.encoded.takeLast(32).toByteArray())
    }

    private fun record(key: ByteArray?) = AttestationRecord(composeHash = "test", mrtd = "test", osImageHash = "test", intelQuote = "", composeManifest = null, gpuArch = null,
        gpuNodeComposeHash = null, modelFileHash = null, signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = key,
        quoteVerification = null, gpuQuoteVerification = null, modelQuoteVerification = null, fetchedAt = Instant.now(), providerID = "p")

    /** A near.ai context carrying [sealed] (what currentTeeContext builds for near.ai); [peer] null is a record with no usable key. */
    private fun context(peer: E2EEPeer?, key: ByteArray?, sealed: SealedModels?) =
        TeeContext("https://cloud-api.near.ai/v1", model, "k", record(key), null, peer).also { it.sealedModels = sealed }

    private fun transport(i: Interceptor, ctx: TeeContext?) = OpenAiTransport("https://cloud-api.near.ai/v1/chat/completions", model, "k",
        client = OkHttpClient.Builder().addInterceptor(i).build(), teeContext = ctx)

    private val hello = listOf(mapOf<String, Any>("role" to "user", "content" to "my private data"))
    private fun send(t: OpenAiTransport) = runCatching { runBlocking { t.runTurn(hello, false, emptyList()) {} } }
    private fun fail(chain: Interceptor.Chain): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("boom")
        .header("Content-Type", "application/json").body("""{"error":"boom"}""".toResponseBody("application/json".toMediaType())).build()

    @Test fun aSealedSendThatErrorsBeforeAReplyArms() {
        val key = ModelKey(); val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        val result = send(transport({ fail(it) }, context(E2EEPeer(key.edPub), key.edPub, sealed)))
        assertTrue("no reply came", result.isFailure)
        assertEquals(setOf(model), sealed.ids)
    }

    /** Armed as the request is handed to the network: by the time the interceptor sees it, before any reply can exist. */
    @Test fun theModelIsArmedBeforeTheRequestReachesTheServer() {
        val key = ModelKey(); val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        var armedAtTheWire: Set<String>? = null
        send(transport({ armedAtTheWire = sealed.ids; throw IOException("no reply") }, context(E2EEPeer(key.edPub), key.edPub, sealed)))
        assertEquals(setOf(model), armedAtTheWire)
    }

    @Test fun aSealedSendStoppedBeforeAReplyArms() {
        val key = ModelKey(); val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        val atWire = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val t = transport({ atWire.complete(Unit); release.await(10, java.util.concurrent.TimeUnit.SECONDS); throw IOException("cancelled") }, context(E2EEPeer(key.edPub), key.edPub, sealed))
        runBlocking {
            val turn = async(Dispatchers.Default) { runCatching { t.runTurn(hello, false, emptyList()) {} } }
            withTimeout(10_000) { atWire.await() }
            turn.cancelAndJoin()   // the user's stop
            release.countDown()
        }
        assertEquals(setOf(model), sealed.ids)
    }

    @Test fun aPlainSendNeverArms() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        // no context at all, as a plain-TLS near.ai model gets; and the store sits nowhere a plain request can reach it
        send(transport({ fail(it) }, null))
        assertTrue(sealed.ids.isEmpty())
    }

    /** An attested model whose record carries no key: the context exists, the request is not sealed, nothing arms. */
    @Test fun anUnsealedContextNeverArms() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore())
        var requests = 0
        send(transport({ requests++; fail(it) }, context(null, null, sealed)))
        assertEquals("the plain request went out", 1, requests)
        assertTrue(sealed.ids.isEmpty())
    }

    /**
     * No request bytes change: the same peer and messages, with and without a SealedModels on the context, put the same headers on
     * the wire and the same plaintext inside the seal (the ciphertext itself is fresh per request, so it is opened with the model's key).
     */
    /** The request with every sealed string opened by the model's key, rendered in the body's own key order. */
    private fun opened(e: kotlinx.serialization.json.JsonElement, key: ModelKey): String = when (e) {
        is kotlinx.serialization.json.JsonObject -> e.entries.joinToString(",", "{", "}") { (k, v) -> "$k:${opened(v, key)}" }
        is kotlinx.serialization.json.JsonArray -> e.joinToString(",", "[", "]") { opened(it, key) }
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) (e.content.hexToBytesOrNull()?.let { runCatching { String(E2EEEnvelope.open(it, key.x25519Priv)) }.getOrNull() } ?: e.content) else e.content
    }

    @Test fun armingChangesNoByteOfTheRequest() {
        val key = ModelKey(); val peer = E2EEPeer(key.edPub)
        class Seen(var headers: Map<String, String> = emptyMap(), var plaintext: String = "")
        fun capture(sealed: SealedModels?): Seen {
            val seen = Seen()
            send(transport({ chain ->
                val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                seen.headers = chain.request().headers.toMultimap().mapValues { it.value.joinToString(",") }.toSortedMap()
                seen.plaintext = opened(Json.parseToJsonElement(body), key)
                throw IOException("no reply")
            }, context(peer, key.edPub, sealed)))
            return seen
        }
        val without = capture(null); val with = capture(SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        assertTrue("something was sealed", with.plaintext.contains("my private data"))
        assertEquals(without.headers, with.headers)
        assertEquals(without.plaintext, with.plaintext)
        assertTrue("sealed request headers present", with.headers.containsKey("x-client-pub-key"))
    }
}
