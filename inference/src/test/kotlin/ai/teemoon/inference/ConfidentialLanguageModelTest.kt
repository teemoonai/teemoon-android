// ports: teemoonTests/ConfidentialLanguageModelTests.swift @ v1.0.1 (plainStreaming_accumulatesContent,
//        toolRound_executesAndChainsFollowUp, nonStreamingJSONResponse_isHandled, httpError_drainsBodyIntoLLMError).
//        The two E2EE cases — the sealed round trip and a decrypt failure failing the stream — were ported earlier as
//        OpenAiTransportE2EETest's sealedRoundTrip_… and decryptFailure_failsTheStream_neverRendersCiphertext.
//        ConfidentialLanguageModel is iOS's LanguageModel adapter over the engine; here the same path is
//        GenerationEngine over OpenAiTransport, with an OkHttp interceptor playing the server (StubServer's role).
package ai.teemoon.inference

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfidentialLanguageModelTest {
    /** Scripted exchanges: the Nth request gets the Nth answer; every request body is kept for inspection. */
    private class Script(private vararg val answers: Triple<Int, String, String>) : Interceptor {
        val bodies = ArrayList<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val (code, type, body) = answers[minOf(bodies.size - 1, answers.size - 1)]
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()
        }
    }
    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
    private fun content(text: String) = """{"choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(text)}},"finish_reason":null}]}"""
    private fun transport(script: Script) = OpenAiTransport("https://api.example.com/v1/chat/completions", "stub-model", apiKey = "k",
        client = OkHttpClient.Builder().addInterceptor(script).build(), providerName = "Stub")
    private class Collect : StreamCallbacks { val texts = ArrayList<String>(); override fun onText(text: String) { texts += text } }

    /** Deltas accumulate into the one answer, and the partials the UI draws only ever grow. */
    @Test fun plainStreamingAccumulatesContent() = runBlocking {
        val script = Script(Triple(200, "text/event-stream", sse(content("Hel"), content("lo, "), content("world"))))
        val events = Collect()
        val answer = GenerationEngine(transport(script), events = events).run(emptyList(), "hi")
        assertEquals("Hello, world", answer)
        assertTrue("partials must grow, never shrink", events.texts.zipWithNext().all { (a, b) -> b.startsWith(a) })
    }

    /** A structured tool call runs the tool, and its result rides the FOLLOW-UP request, whose answer is the reply. */
    @Test fun aToolRoundExecutesAndChainsTheFollowUp() = runBlocking {
        val call = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"x\"}"}}]},"finish_reason":null}]}"""
        val script = Script(
            Triple(200, "text/event-stream", sse(call, """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")),
            Triple(200, "text/event-stream", sse(content("the answer is 42"))),
        )
        var ran = ""
        val tool = object : Tool {
            override val name = "lookup"; override val description = "looks things up"
            override val parametersSchema = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""
            override suspend fun execute(argumentsJson: String): String { ran = argumentsJson; return "RESULT-SENTINEL" }
        }
        val answer = GenerationEngine(transport(script), tools = listOf(tool), events = Collect()).run(emptyList(), "look it up")
        assertEquals("the answer is 42", answer)
        assertTrue("the tool ran with the model's arguments", ran.contains("\"q\""))
        assertEquals("one follow-up request after the call", 2, script.bodies.size)
        assertTrue("the follow-up carries the tool's result", script.bodies[1].contains("RESULT-SENTINEL"))
    }

    /** A server that answers with one JSON body instead of a stream still yields the reply, and its usage count. */
    @Test fun aNonStreamingJsonResponseIsHandled() = runBlocking {
        val body = """{"id":"chatcmpl-nonstream","choices":[{"index":0,"message":{"role":"assistant","content":"full response"},"finish_reason":"stop"}],"usage":{"completion_tokens":7}}"""
        val result = transport(Script(Triple(200, "application/json", body))).runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
        assertEquals("full response", result.content)
        assertEquals(7, result.completionTokens)
    }

    /** An HTTP error's body is drained into the error: the user reads the provider's own words, and the status. */
    @Test fun anHttpErrorDrainsItsBodyIntoTheError() = runBlocking {
        val script = Script(Triple(401, "application/json", """{"error": {"message": "invalid api key"}}"""))
        try {
            transport(script).runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
            fail("a 401 must throw")
        } catch (e: LlmError) {
            assertEquals(401, e.httpStatus)
            assertTrue("the provider's message reaches the user: ${e.userMessage}", e.userMessage.contains("invalid api key"))
        }
    }
}
