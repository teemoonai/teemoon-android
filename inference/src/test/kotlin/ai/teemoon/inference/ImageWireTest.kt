// spec: IMAGES.md draft 2 §4 (teemoon-ios fa3c46c): a message with images is sent as an OpenAI content array — the text
// part first, then each image as a data: URL — with keys sorted like every body, so the prompt cache still matches a
// chat's next send (an image's bytes never change once stored).
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImageWireTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun body(history: List<Map<String, Any>>): String = runBlocking {
        var sent: String? = null
        val capture = Interceptor { chain -> sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(); throw IOException("offline") }
        val p = Provider(id = "f", name = "Fireworks", endpoint = "https://api.fireworks.ai/inference/v1", model = "accounts/fireworks/models/qwen-vl", requiresAPIKey = true)
        val t = OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = p.name)
        runCatching { t.runTurn(history, false, emptyList()) {} }
        sent!!
    }

    @Test fun aMessageWithImagesIsAContentArrayTextFirst() {
        val m = mapOf("role" to "user", "content" to wireContent("what is this?", listOf(WireImage("image/jpeg", jpeg), WireImage("image/png", byteArrayOf(9)))))
        val parts = Json.parseToJsonElement(body(listOf(m))).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(listOf("text", "image_url", "image_url"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("what is this?", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,/9j/AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun keysAreSortedInsideThePartsAndTheBytesAreStable() {
        val m = mapOf("role" to "user", "content" to wireContent("", listOf(WireImage("image/jpeg", jpeg))))
        val a = body(listOf(m)); val b = body(listOf(m))
        assertEquals("two sends of the same image are the same bytes", a, b)
        assertTrue(a, a.contains("""{"image_url":{"url":"data:image/jpeg;base64,/9j/AQID"},"type":"image_url"}"""))
        assertTrue("an empty text adds no text part", !a.contains(""""type":"text""""))
    }

    @Test fun withoutImagesTheContentIsTheStringItWas() {
        assertEquals("hello", wireContent("hello", emptyList()))
    }

    /**
     * A home Ollama machine (4ed2a69: a home model that reads images gets them) is sent the same body, over the same
     * transport: Ollama's OpenAI-compatible /v1/chat/completions takes image_url data URLs. Pinned because no test sent
     * one before (pm, 2026-11-12): plain http on the LAN, no key and so no Authorization header, the text then the image.
     */
    @Test fun aHomeOllamaMachineGetsTheImageInTheSameBody() = runBlocking {
        var sent: okhttp3.Request? = null; var bodyText: String? = null
        val capture = Interceptor { chain -> sent = chain.request(); bodyText = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(); throw IOException("offline") }
        val p = Provider(id = "h", name = "Mac", endpoint = "http://192.168.1.20:11434/v1", model = "gemma4:e4b", requiresAPIKey = false)
        val t = OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = null, client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = p.name)
        val m = mapOf("role" to "user", "content" to wireContent("what shape is this?", listOf(WireImage("image/jpeg", jpeg))))
        runCatching { t.runTurn(listOf(m), false, emptyList()) {} }
        assertEquals("http://192.168.1.20:11434/v1/chat/completions", sent!!.url.toString())
        assertEquals("no key, no Authorization header", null, sent.header("Authorization"))
        val body = Json.parseToJsonElement(bodyText!!).jsonObject
        assertEquals("gemma4:e4b", body["model"]!!.jsonPrimitive.content)
        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"] as JsonArray
        assertEquals(listOf("text", "image_url"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("data:image/jpeg;base64,/9j/AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
}
