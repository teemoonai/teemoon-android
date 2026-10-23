// ports: teemoonTests/PromptCachePrefixTests.swift @ 904de68 (aRequestBodyIsTheSameBytesEveryTime, sortingNeverReordersAnArray;
//        sealingTakesTheSortedPlaintextAndLeavesStrings is OpenAiTransportE2EETest's, beside the E2EE fixtures)
//
// The maintainer, TestFlight 57: Fireworks GLM 5.3 Flash showed "cache 0%" on a chat's second send. iOS serialized the body from
// Swift dictionaries without sorted keys — a Dictionary's order is seeded per process — so the tools' key order changed
// between requests and the prompt the server rendered no longer shared a prefix. Live on iOS, two sends: 0 cached of
// ~870 twice unsorted, 768 of ~860 twice sorted (2026-10-22).
//
// Android's maps kept insertion order, so its bodies were already stable; it sorts the keys now as iOS does, which makes
// the order the conformance rule (pm, 2026-10-22) — "a chat request's body is a pure function of the turn" — rather
// than an accident of how each object was built. The rule's two-body prefix vector is ConformanceRequestsTest.
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

class PromptCachePrefixTest {
    @Test fun aRequestBodyIsTheSameBytesEveryTime() = runBlocking {
        ReasoningEffort.forgetRefusals()
        val bodies = ArrayList<String>()
        val capture = Interceptor { chain ->
            bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            throw IOException("offline")   // the body is all this needs, as iOS's protocol fails with notConnectedToInternet
        }
        val provider = Provider(id = "f", name = "Fireworks", endpoint = "https://api.fireworks.ai/inference/v1",
            model = "accounts/fireworks/models/glm-5p3-flash", requiresAPIKey = true)
        val messages = listOf(mapOf("role" to "system", "content" to "persona"), mapOf("role" to "user", "content" to "What is a tide pool?"))
        repeat(3) {
            // A fresh transport and tool each time, as each send builds them.
            val transport = OpenAiTransport(provider.chatCompletionsUrl, provider.model, apiKey = "k",
                client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = provider.name,
                effort = ReasoningEffort.For(ReasoningEffort.FAST, provider))
            runCatching { transport.runTurn(messages, true, listOf(BraveWebSearchTool(apiKey = "b"))) {} }
        }
        assertEquals(3, bodies.size)
        assertEquals("the same turn must serialize to the same bytes", 1, bodies.toSet().size)
        assertTrue("the tools are in the body", bodies.first().contains("\"web_search\""))
        fun sorted(e: JsonElement): Boolean = when (e) {
            is JsonObject -> e.keys.toList() == e.keys.sorted() && e.values.all(::sorted)
            is JsonArray -> e.all(::sorted)
            else -> true
        }
        assertTrue("keys are sorted, so a map's order never reaches the wire", sorted(Json.parseToJsonElement(bodies.first())))
    }

    /** Only object keys sort: messages, tools, tool_calls and content parts keep their order. */
    @Test fun sortingNeverReordersAnArray() = runBlocking {
        ReasoningEffort.forgetRefusals()
        var sent: String? = null
        val capture = Interceptor { chain -> sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(); throw IOException("offline") }
        val provider = Provider(id = "f", name = "Fireworks", endpoint = "https://api.fireworks.ai/inference/v1",
            model = "accounts/fireworks/models/glm-5p3-flash", requiresAPIKey = true)
        val messages: List<Map<String, Any>> = listOf(
            mapOf("role" to "system", "content" to "zeta persona"),
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "text", "text" to "zz first part"), mapOf("type" to "text", "text" to "aa second part"))),
            mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(
                mapOf("id" to "z-call", "type" to "function", "function" to mapOf("name" to "web_search", "arguments" to "{\"query\":\"z\"}")),
                mapOf("id" to "a-call", "type" to "function", "function" to mapOf("name" to "web_search", "arguments" to "{\"query\":\"a\"}")))),
            mapOf("role" to "tool", "tool_call_id" to "z-call", "content" to "z result"),
            mapOf("role" to "tool", "tool_call_id" to "a-call", "content" to "a result"),
            mapOf("role" to "user", "content" to "and then?"),
        )
        val transport = OpenAiTransport(provider.chatCompletionsUrl, provider.model, apiKey = "k",
            client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = provider.name,
            effort = ReasoningEffort.For(ReasoningEffort.FAST, provider))
        runCatching { transport.runTurn(messages, true, listOf(BraveWebSearchTool(apiKey = "b"), BraveWebSearchTool(apiKey = "b2"))) {} }
        val json = Json.parseToJsonElement(sent!!).jsonObject
        val out = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), out.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("zz first part", "aa second part"), out[1]["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertEquals(listOf("z-call", "a-call"), out[2]["tool_calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(listOf("z-call", "a-call"), out.subList(3, 5).map { it["tool_call_id"]!!.jsonPrimitive.content })
        val tools = json["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val schema = tools[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(listOf("query"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
