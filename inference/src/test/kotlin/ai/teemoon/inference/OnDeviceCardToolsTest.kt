// ports: teemoonTests/OnDeviceCardToolsTests.swift @ ca3d155
//
// the maintainer, 2026-10-22: Gemma 4 E2B's card said "tools-offered: 1" but showed no tool. The on-device card's request body
// carries the tools in the cloud card's shape.
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OnDeviceCardToolsTest {
    private val messages: List<Map<String, Any>> = listOf(mapOf("role" to "system", "content" to "persona"), mapOf("role" to "user", "content" to "What's the weather in Paris?"))

    @Test fun aTurnWithSearchShowsTheToolAsTheCloudSendsIt() = runBlocking {
        val tool = BraveWebSearchTool(apiKey = "b")
        val shown = Json.parseToJsonElement(LiteRTTransport.prettyRequest(messages, listOf(tool))!!).jsonObject
        assertEquals(setOf("messages", "tools"), shown.keys)
        val tools = shown["tools"]!!.jsonArray
        assertEquals("one per tool offered, as tools-offered counts them", 1, tools.size)
        val fn = tools[0].jsonObject["function"]!!.jsonObject
        assertEquals("function", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("web_search", fn["name"]!!.jsonPrimitive.content)
        assertTrue(fn["description"]!!.jsonPrimitive.content.isNotEmpty())
        assertNotNull((fn["parameters"] as JsonObject)["properties"])

        // The same JSON a cloud request carries for the same tool.
        var sent: String? = null
        val capture = Interceptor { chain -> sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(); throw IOException("offline") }
        val p = Provider(id = "p", name = "p", endpoint = "https://api.fireworks.ai/inference/v1", model = "m")
        runCatching { OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = p.name)
            .runTurn(messages, true, listOf(tool)) {} }
        val wire = Json.parseToJsonElement(sent!!).jsonObject["tools"] as JsonArray
        assertEquals(Json.encodeToString(JsonArray.serializer(), wire), Json.encodeToString(JsonArray.serializer(), tools))
    }

    @Test fun aTurnWithoutToolsShowsNoToolsKey() {
        val card = LiteRTTransport.prettyRequest(messages, emptyList())!!
        assertEquals("unchanged from before", LiteRTTransport.prettyMessages(messages), card)
        assertFalse(card.contains("\"tools\""))
    }

    @Test fun theMessagesAreUnchanged() {
        val shown = Json.parseToJsonElement(LiteRTTransport.prettyRequest(messages, listOf(BraveWebSearchTool(apiKey = "")))!!).jsonObject
        assertEquals(Json.parseToJsonElement(LiteRTTransport.prettyMessages(messages)!!), shown["messages"])
    }
}
