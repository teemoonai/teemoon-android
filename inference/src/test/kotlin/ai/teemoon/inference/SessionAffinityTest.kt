// ports: teemoonTests/SessionAffinityTests.swift @ v1.1.0
//
// A chat's routing id survives a relaunch (Fireworks glm-5p3 / -flash, 2026-10-19: turn-2 cache hits 7/12 with the
// same id, 3/12 with a fresh one, which is what a relaunch sent before). Each provider gets only its own documented key.
package ai.teemoon.inference

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAffinityTest {
    @After fun reset() { SessionAffinity.keyedMac = null; SessionAffinity.resetForTests() }

    @Test fun stablePerThreadAndDistinctAcrossThreads() {
        val a = "thread-a-1234"; val b = "thread-b-5678"
        assertEquals(SessionAffinity.id(a), SessionAffinity.id(a))
        assertNotEquals(SessionAffinity.id(a), SessionAffinity.id(b))
        assertFalse("the thread id never shows", SessionAffinity.id(a).contains(a))
    }

    /** Derived, not stored: the same secret gives the same id after a relaunch. */
    @Test fun theIdIsAKeyedHashOfTheThread() {
        val thread = "t-1770000000000"
        val secret = ByteArray(32) { 7 }; val other = ByteArray(32) { 9 }
        val id = SessionAffinity.derive(thread, secret)
        assertEquals(id, SessionAffinity.derive(thread, secret))
        assertNotEquals("another install cannot reproduce it", id, SessionAffinity.derive(thread, other))
        assertNotEquals(id, SessionAffinity.derive("t-1770000000001", secret))
        assertTrue(id.length == 32 && id.all { it in "0123456789abcdef" })
    }

    /** The app's path: the installed keyed MAC (the Keystore's, in the app) gives the derived id, the same after a "relaunch". */
    @Test fun theInstalledSecretGivesTheDerivedIdAcrossARelaunch() {
        val secret = ByteArray(32) { 3 }
        SessionAffinity.keyedMac = { msg -> javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256")) }.doFinal(msg) }
        val first = SessionAffinity.id("T-ABC")
        SessionAffinity.resetForTests()   // a relaunch: the in-memory ids are gone, the secret is not
        assertEquals(first, SessionAffinity.id("T-ABC"))
        assertEquals("lowercase thread id, first 16 bytes as hex", SessionAffinity.derive("t-abc", secret), first)
    }

    @Test fun eachProviderGetsItsOwnDocumentedKey() {
        assertEquals("x-session-affinity", SessionAffinity.header("https://api.fireworks.ai/inference/v1/chat/completions"))
        assertEquals("x-grok-conv-id", SessionAffinity.header("https://api.x.ai/v1/chat/completions"))
        assertEquals("x-session-id", SessionAffinity.header("https://openrouter.ai/api/v1/chat/completions"))
        for (url in listOf("https://cloud-api.near.ai/v1/chat/completions", "https://integrate.api.nvidia.com/v1/chat/completions",
            "http://192.168.1.2:11434/v1/chat/completions", "https://api.openai.com/v1/chat/completions")) assertNull(url, SessionAffinity.header(url))
    }

    @Test fun theRequestCarriesItWhereDocumentedAndNowhereElse() = runBlocking {
        val cases = listOf("https://api.x.ai/v1/chat/completions" to "x-grok-conv-id", "https://openrouter.ai/api/v1/chat/completions" to "x-session-id",
            "https://api.fireworks.ai/inference/v1/chat/completions" to "x-session-affinity", "https://integrate.api.nvidia.com/v1/chat/completions" to null)
        for ((url, header) in cases) {
            var sent: okhttp3.Headers? = null; var body = ""
            val capture = Interceptor { chain ->
                sent = chain.request().headers; body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").header("Content-Type", "text/event-stream")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
            }
            OpenAiTransport(url, "m", apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(), affinityID = "abc123")
                .runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
            for (known in listOf("x-session-affinity", "x-grok-conv-id", "x-session-id"))
                assertEquals("$url $known", if (known == header) "abc123" else null, sent!![known])
            assertFalse("a header, never the body", body.contains("abc123"))
        }
    }
}
