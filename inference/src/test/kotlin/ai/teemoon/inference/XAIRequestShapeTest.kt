// ports: teemoonTests/XAIRequestShapeTests.swift @ 94b8500
//
// the maintainer, 2026-10-19: Grok must never search server-side — xAI's search is a plaintext exfiltration path we cannot
// audit; web search is Brave, run by the app. So an xAI request carries no `search_parameters`, no xAI server tools,
// and only our own function tools.
package ai.teemoon.inference

import ai.teemoon.providers.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XAIRequestShapeTest {
    @Test fun aGrokTurnWithSearchOnAsksForNoServerSideSearch() = runBlocking {
        ReasoningEffort.forgetRefusals()
        val bodies = ArrayList<String>()
        val capture = Interceptor { chain ->
            bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .header("Content-Type", "text/event-stream").body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }
        val xai = Provider(id = "x", name = "xAI", endpoint = "https://api.x.ai/v1", model = "grok-4.7", requiresAPIKey = true)
        val transport = OpenAiTransport(xai.chatCompletionsUrl, xai.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(),
            providerName = xai.name, effort = ReasoningEffort.For(ReasoningEffort.FAST, xai))
        runCatching { transport.runTurn(listOf(mapOf("role" to "user", "content" to "news today")), true, listOf(BraveWebSearchTool(apiKey = "b"))) {} }
        val body = Json.parseToJsonElement(bodies.first()) as JsonObject
        // Android also sends `tool_choice: "auto"` with its tools (OpenAiTransport.makeBody); otherwise iOS's exact key set
        assertTrue("${body.keys.sorted()}", body.keys.all { it in setOf("model", "messages", "stream", "stream_options", "tools", "tool_choice", "reasoning_effort") })
        assertNull(body["search_parameters"])
        val tools = body["tools"] as JsonArray
        assertTrue(tools.isNotEmpty())
        for (t in tools) assertEquals("no xAI server-side tool (web_search / x_search)", "function", ((t as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals(listOf("web_search"), tools.map { (((it as JsonObject)["function"] as JsonObject)["name"] as JsonPrimitive).content })
    }
}
