// ports: teemoonTests/ReasoningEffortTests.swift @ 09dcb6e (per provider: ReasoningControl), teemoonTests/ThinkHarderTests.swift @ 7cd8c10
//        + Android-only: the transport's body with no effort is pinned byte-for-byte to the 1.0.5 shape, and a set
//        effort lands in that body, every round alike.
//
// Effort maps to each provider's own parameter, and only where it was seen
// to work (live probes 2026-10-19). Chat sends low by default (the maintainer,
// 2026-10-19, via pm's eval v2: 5 models × 3 levels × 24 objective tasks).
// null changes nothing at the transport: that send is byte-identical to 1.0.5.
package ai.teemoon.inference

import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.Provider
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortTest {
    @org.junit.Before fun forget() { ReasoningEffort.forgetRefusals(); ReasoningControl.forget() }
    private fun provider(endpoint: String) = Provider(id = "p", name = "p", endpoint = endpoint, model = "m", requiresAPIKey = true)
    private fun level(fields: Map<String, kotlinx.serialization.json.JsonElement>?) = (fields?.get("reasoning_effort") as? JsonPrimitive)?.content

    @Test fun chatSendsLowByDefault() = assertEquals(ReasoningEffort.FAST, ReasoningEffort.CHAT_DEFAULT)

    @Test fun fireworksNearAndXAIUseReasoningEffort() {
        for (url in listOf("https://api.fireworks.ai/inference/v1/chat/completions", "https://cloud-api.near.ai/v1/chat/completions",
                "https://api.x.ai/v1/chat/completions")) {
            val p = provider(url)
            assertEquals(url, "low", level(ReasoningEffort.FAST.bodyFields(p)))
            assertEquals(url, "high", level(ReasoningEffort.THINK_HARDER.bodyFields(p)))
        }
    }

    /** Only a model whose /models entry lists `reasoning` gets the field; the rest get nothing. */
    @Test fun openRouterNestsItWhereTheModelSaysItReasons() {
        val p = provider("https://openrouter.ai/api/v1/chat/completions")
        ReasoningControl.note(ReasoningControl.resolve(p, null, listOf("tools", "reasoning")), p)
        val fields = ReasoningEffort.THINK_HARDER.bodyFields(p)
        assertEquals("high", ((fields?.get("reasoning") as? JsonObject)?.get("effort") as? JsonPrimitive)?.content)
        assertNull(fields?.get("max_tokens"))
        ReasoningControl.note(ReasoningControl.resolve(p, null, listOf("tools")), p)
        assertNull(ReasoningEffort.THINK_HARDER.bodyFields(p))
        assertFalse(ReasoningEffort.canThinkHarder(ReasoningControl.current(p), p))
    }

    /**
     * LM Studio, llama.cpp, any custom endpoint: a server that may ignore the field gets none, and Think Harder is not
     * offered (it would only ask again).
     */
    @Test fun unverifiedServersAreLeftAloneAndOfferNoThinkHarder() {
        for ((url, kind) in listOf("http://192.168.1.20:1234/v1/chat/completions" to LocalServerKind.LM_STUDIO,
                "http://192.168.1.20:8080/v1/chat/completions" to null, "https://api.example.com/v1/chat/completions" to null)) {
            val p = provider(url)
            val control = ReasoningControl.resolve(p, kind, null)
            assertEquals(url, ReasoningControl.None, control)
            ReasoningControl.note(control, p)
            assertNull(url, ReasoningEffort.FAST.bodyFields(p))
            assertFalse(url, ReasoningEffort.canThinkHarder(control, p))
        }
    }

    /** Ollama honours reasoning_effort (measured 2026-10-22, gemma4:e4b on 0.40.6: low 396 tokens, high 668). */
    @Test fun ollamaGetsEffortWithoutACap() {
        val p = provider("http://192.168.1.20:11434/v1/chat/completions")
        val control = ReasoningControl.resolve(p, LocalServerKind.OLLAMA, null)
        assertEquals(ReasoningControl.Effort(null), control)
        ReasoningControl.note(control, p)
        assertEquals("low", level(ReasoningEffort.FAST.bodyFields(p)))
        val high = ReasoningEffort.THINK_HARDER.bodyFields(p)
        assertEquals("high", level(high)); assertNull(high?.get("max_tokens"))
        assertTrue(ReasoningEffort.canThinkHarder(control, p))
    }

    /** NVIDIA ignores reasoning_effort; enable_thinking switches it (measured 2026-10-22 on three Nemotrons). */
    @Test fun nvidiaSwitchesThinkingOffThenOn() {
        val p = provider("https://integrate.api.nvidia.com/v1/chat/completions")
        assertEquals(ReasoningControl.ThinkingSwitch, ReasoningControl.resolve(p, null, null))
        fun thinking(f: Map<String, kotlinx.serialization.json.JsonElement>?) = ((f?.get("chat_template_kwargs") as? JsonObject)?.get("enable_thinking") as? JsonPrimitive)?.content
        assertEquals("false", thinking(ReasoningEffort.FAST.bodyFields(p)))
        assertEquals("true", thinking(ReasoningEffort.THINK_HARDER.bodyFields(p)))
        assertNull(ReasoningEffort.THINK_HARDER.bodyFields(p)?.get("reasoning_effort"))
        assertTrue(ReasoningEffort.isRefusal(400, """{"error":"unexpected field chat_template_kwargs"}"""))
    }

    /** near.ai's default output was never measured, so its Think Harder keeps 8192; the others get none. */
    @Test fun onlyNearAICapsThinkHarder() {
        assertEquals(8192, (ReasoningEffort.THINK_HARDER.bodyFields(provider("https://cloud-api.near.ai/v1/chat/completions"))?.get("max_tokens") as? JsonPrimitive)?.content?.toInt())
        for (url in listOf("https://api.fireworks.ai/inference/v1/chat/completions", "https://api.x.ai/v1/chat/completions"))
            assertNull(url, ReasoningEffort.THINK_HARDER.bodyFields(provider(url))?.get("max_tokens"))
        assertNull(ReasoningEffort.FAST.bodyFields(provider("https://cloud-api.near.ai/v1/chat/completions"))?.get("max_tokens"))
    }

    @Test fun onDeviceAndBraveAnswers() {
        val local = provider("on-device").copy(localModelID = "litert-community/gemma-4-E2B-it-litert-lm")
        assertEquals(ReasoningControl.OnDevice, ReasoningControl.resolve(local, null, null))
        assertNull("on-device thinking is the runtime's switch, not a body", ReasoningEffort.THINK_HARDER.bodyFields(local))
        assertTrue(ReasoningEffort.canThinkHarder(ReasoningControl.OnDevice, local))
        val brave = Provider.braveAnswers
        assertEquals(ReasoningControl.None, ReasoningControl.resolve(brave, null, null))
        assertFalse(ReasoningEffort.canThinkHarder(ReasoningControl.None, brave))
        val grounded = provider("https://openrouter.ai/api/v1/chat/completions").copy(hasBuiltInGrounding = true)
        assertNull(ReasoningEffort.THINK_HARDER.bodyFields(grounded))
    }

    /** A 400 on the field hides Think Harder for that model until restart; another model on the host keeps it. */
    @Test fun aRefusedModelOffersNoThinkHarder() {
        val p = provider("https://api.x.ai/v1/chat/completions")
        assertTrue(ReasoningEffort.canThinkHarder(ReasoningControl.current(p), p))
        ReasoningEffort.noteRefused(p)
        assertFalse(ReasoningEffort.canThinkHarder(ReasoningControl.current(p), p))
        val other = p.copy(model = "other")
        assertTrue(ReasoningEffort.canThinkHarder(ReasoningControl.current(other), other))
    }

    // MARK: - the wire

    private class Capture : Interceptor {
        val bodies = ArrayList<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            bodies += Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .header("Content-Type", "text/event-stream").body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }
    }

    private fun bodyOf(p: Provider, effort: ReasoningEffort.For?, messages: List<Map<String, Any>> = listOf(mapOf("role" to "user", "content" to "hi"))): String = runBlocking {
        val capture = Capture()
        val transport = if (effort == null) {
            // The 1.0.5 call, verbatim: no effort argument at all.
            OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(),
                extraParams = mapOf("max_tokens" to "512"), providerName = p.name)
        } else {
            OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(capture).build(),
                extraParams = mapOf("max_tokens" to "512"), providerName = p.name, effort = effort)
        }
        transport.runTurn(messages, includeTools = false, tools = emptyList(), onPartial = {})
        capture.bodies.single()
    }

    /**
     * No effort leaves every provider's request body byte-identical to the shape 1.0.5 sent — keys sorted since 3917.8
     * (iOS bbfeb0f, conformance/requests.json), the same fields in a fixed order.
     */
    @Test fun anUnsetEffortLeavesTheBodyByteIdentical() {
        val pinned = """{"max_tokens":512,"messages":[{"content":"hi","role":"user"}],"model":"m","stream":true,"stream_options":{"include_usage":true}}"""
        for (url in listOf("https://api.fireworks.ai/inference/v1/chat/completions", "https://cloud-api.near.ai/v1/chat/completions",
                "https://openrouter.ai/api/v1/chat/completions", "https://api.x.ai/v1/chat/completions",
                "https://integrate.api.nvidia.com/v1/chat/completions", "http://192.168.1.20:11434/v1/chat/completions")) {
            assertEquals(url, pinned, bodyOf(provider(url), effort = null))
        }
    }

    /**
     * With no pick — what AppSettings hands over with developer mode off, whatever is stored — the body on the wire is
     * byte-identical to the 1.0.x send's low, whether the transport asks a chooser or not (pm 2026-11-13; iOS asserts the
     * same). The control: a pick does change the bytes.
     */
    @Test fun withNoPickTheBodyIsTheSameBytesWhateverTheChooser() {
        try {
            for (url in listOf("https://api.fireworks.ai/inference/v1/chat/completions", "https://cloud-api.near.ai/v1/chat/completions",
                    "https://openrouter.ai/api/v1/chat/completions", "https://integrate.api.nvidia.com/v1/chat/completions")) {
                val p = provider(url)
                if ("openrouter" in url) ReasoningControl.note(ReasoningControl.OpenRouter, p)
                ReasoningLevel.chooser = { null }
                val today = bodyOf(p, ReasoningEffort.For(ReasoningEffort.FAST, p))
                ReasoningLevel.chooser = { _ -> null }
                assertEquals(url, today, bodyOf(p, ReasoningEffort.For(ReasoningEffort.FAST, p)))
                ReasoningLevel.chooser = { if ("nvidia" in url) "on" else "high" }
                assertTrue("$url: a pick reaches the wire", today != bodyOf(p, ReasoningEffort.For(ReasoningEffort.FAST, p)))
            }
        } finally { ReasoningLevel.chooser = { null } }
    }

    /** A set effort lands in the body after the provider's extras, the pass after a tool result alike; NVIDIA gets its switch. */
    @Test fun aSetEffortReachesTheBody() {
        val fw = provider("https://api.fireworks.ai/inference/v1/chat/completions")
        assertEquals("""{"max_tokens":512,"messages":[{"content":"hi","role":"user"}],"model":"m","reasoning_effort":"high","stream":true,"stream_options":{"include_usage":true}}""",
            bodyOf(fw, ReasoningEffort.For(ReasoningEffort.THINK_HARDER, fw)))
        val followUp = listOf(mapOf("role" to "user", "content" to "hi"), mapOf("role" to "tool", "tool_call_id" to "c1", "content" to "r"))
        assertTrue(bodyOf(fw, ReasoningEffort.For(ReasoningEffort.THINK_HARDER, fw), followUp).contains(""","reasoning_effort":"high","""))
        assertTrue(bodyOf(fw, ReasoningEffort.For(ReasoningEffort.FAST, fw), followUp).contains(""","reasoning_effort":"low","""))
        val nvidia = provider("https://integrate.api.nvidia.com/v1/chat/completions")
        assertTrue(bodyOf(nvidia, ReasoningEffort.For(ReasoningEffort.THINK_HARDER, nvidia)).contains(""""chat_template_kwargs":{"enable_thinking":true}"""))
    }

    /** iOS ThinkHarderTests.itAsksForHighEffortAndRoomToThink (7cd8c10): high, and no cap but near.ai's. */
    @Test fun itAsksForHighEffortAndRoomToThink() {
        val fw = provider("https://api.fireworks.ai/inference/v1/chat/completions")
        val fields = ReasoningEffort.THINK_HARDER.bodyFields(fw)
        assertEquals("high", level(fields))
        // No cap: Fireworks' own default wrote 13k tokens (2026-10-22); 8192 gave Think Harder less room.
        assertNull(fields?.get("max_tokens"))
        assertNull("a normal turn keeps the provider's budget", ReasoningEffort.FAST.bodyFields(fw)?.get("max_tokens"))
        val near = provider("https://cloud-api.near.ai/v1")
        assertEquals("near.ai's default is unmeasured", 8192, (ReasoningEffort.THINK_HARDER.bodyFields(near)?.get("max_tokens") as? JsonPrimitive)?.content?.toInt())
    }

    /** The card's words read the body that went out: "effort low/high" or "thinking off/on" (iOS 7cd8c10). */
    @Test fun theCardReadsTheLevelFromTheBody() {
        assertEquals("effort low", ReasoningEffort.sentLevel("""{"model":"m","reasoning_effort":"low"}"""))
        assertEquals("effort high", ReasoningEffort.sentLevel("""{"model":"m","reasoning":{"effort":"high"}}"""))
        assertEquals("thinking off", ReasoningEffort.sentLevel("""{"chat_template_kwargs":{"enable_thinking":false}}"""))
        assertEquals("thinking on", ReasoningEffort.sentLevel("""{"chat_template_kwargs":{"enable_thinking":true}}"""))
        assertNull(ReasoningEffort.sentLevel("""{"model":"m"}"""))
        assertNull(ReasoningEffort.sentLevel("not json"))
    }

    @Test fun neverNone() {
        for (effort in ReasoningEffort.entries) assertTrue(level(effort.bodyFields(provider("https://api.x.ai/v1/chat/completions"))) != "none")
    }

    @Test fun onlyA400NamingTheParameterIsARefusal() {
        assertTrue(ReasoningEffort.isRefusal(400, """{"error":"Invalid reasoning effort."}"""))
        assertTrue(ReasoningEffort.isRefusal(400, """{"error":{"message":"Unknown field reasoning_effort"}}"""))
        // xAI, grok-build-0.1 (2026-10-22): camel case. Missed in 3917.8 (iOS 58), so every send failed (iOS 09dcb6e).
        assertTrue(ReasoningEffort.isRefusal(400, """{"code":"invalid-argument","error":"Model grok-build-0.1 does not support parameter reasoningEffort."}"""))
        assertTrue(ReasoningEffort.isRefusal(400, """{"error":"unknown field enableThinking"}"""))
        assertTrue(!ReasoningEffort.isRefusal(400, """{"error":"context length exceeded"}"""))
        assertTrue(!ReasoningEffort.isRefusal(401, "reasoning_effort"))
        assertTrue(!ReasoningEffort.isRefusal(400, """{"error":"invalid effort"}"""))
        // android-only: the bare word is not enough
        assertTrue(!ReasoningEffort.isRefusal(400, """{"error":"low effort request rejected"}"""))
    }

    /** 400s any body carrying `"reasoning_effort":"low"`, as grok-4.7 answers "none"; answers "ok" otherwise. */
    private class EffortRefusing(val refusal: String = """{"error":"Invalid reasoning effort."}""") : Interceptor {
        val bodies = ArrayList<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8().also { bodies += it }
            val refuse = body.contains(""""reasoning_effort":"low"""")
            val (type, text) = if (refuse) "application/json" to refusal
                else "text/event-stream" to "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (refuse) 400 else 200).message("x")
                .header("Content-Type", type).body(text.toResponseBody(type.toMediaType())).build()
        }
    }
    private fun refusingTransport(endpoint: String, server: EffortRefusing, extra: Map<String, String> = emptyMap(), model: String = "grok-x"): OpenAiTransport {
        val p = Provider(id = "p", name = "p", endpoint = endpoint, model = model, requiresAPIKey = true)
        return OpenAiTransport(p.chatCompletionsUrl, p.model, apiKey = "k", client = OkHttpClient.Builder().addInterceptor(server).build(),
            extraParams = extra, providerName = p.name, effort = ReasoningEffort.For(ReasoningEffort.FAST, p))
    }

    @Test fun aModelThatRefusesIsAskedAgainWithoutItAndRemembered() = runBlocking {
        val server = EffortRefusing()
        val transport = refusingTransport("https://api.x.ai/v1", server)
        val out = transport.runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
        assertEquals("ok", out.content)
        assertEquals(2, server.bodies.size)
        assertTrue(server.bodies.first().contains(""""reasoning_effort":"low""""))
        assertTrue(!server.bodies.last().contains("reasoning_effort"))
        // The retry builds its body through the same makeBody, so it is sorted too (iOS bbfeb0f; the audit, 3917.8).
        assertEquals("""{"messages":[{"content":"hi","role":"user"}],"model":"grok-x","stream":true,"stream_options":{"include_usage":true}}""", server.bodies.last())
        // The next turn on that model does not try again.
        transport.runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
        assertEquals(3, server.bodies.size)
        val other = Provider(id = "q", name = "q", endpoint = "https://api.x.ai/v1", model = "grok-y", requiresAPIKey = true)
        assertTrue("another model on the host is unaffected", ReasoningEffort.FAST.bodyFields(other) != null)
    }

    /** android-only, through the transport: xAI's camel-case 400 (iOS 09dcb6e) retries without the field and answers. */
    @Test fun xAIsCamelCaseRefusalIsRetriedWithoutTheField() = runBlocking {
        val server = EffortRefusing("""{"code":"invalid-argument","error":"Model grok-build-0.1 does not support parameter reasoningEffort."}""")
        val out = refusingTransport("https://api.x.ai/v1", server, model = "grok-build-0.1")
            .runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
        assertEquals("ok", out.content)
        assertEquals(2, server.bodies.size)
        assertTrue(!server.bodies.last().contains("reasoning_effort"))
    }

    @Test fun aKeyThePersonSetInExtraParametersWins() = runBlocking {
        val server = EffortRefusing()
        refusingTransport("https://api.fireworks.ai/inference/v1", server, extra = mapOf("reasoning_effort" to "medium"))
            .runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) {}
        assertTrue(server.bodies.first().contains(""""reasoning_effort":"medium""""))
        assertEquals(1, server.bodies.size)
    }
}
