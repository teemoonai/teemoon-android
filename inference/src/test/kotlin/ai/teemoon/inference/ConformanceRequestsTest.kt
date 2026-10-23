// ports: teemoonTests/ConformanceRequestVectors.swift @ 7eb6c31 (the consuming half, against conformance/requests.json)
//
// The rule (iOS bbfeb0f, pm 2026-10-22): "A chat request body is a pure function of the turn: JSON with sorted keys and
// no per-request values. Sending the same history twice gives identical bytes, and send N+1's body extends send N's
// byte-for-byte through the last message they share." iOS 57 broke it with per-process dictionary order — 52 tool
// orders in 60 requests — and Fireworks GLM never cached a chat's next send while web_search was offered.
//
// Android's bar is the rule, not iOS's bytes: kotlinx escapes differently (`/` stays `/`, iOS writes `\/`) and the
// body carries `tool_choice`. So each turn is sent twice through the production OpenAiTransport with the vector's
// messages, and the bodies must be identical, sorted at every level, and turn 2's must extend turn 1's through the last
// shared message. The messages themselves must parse equal to iOS's.
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
import java.time.Instant

class ConformanceRequestsTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResourceAsStream("/requests.json")!!.reader().readText()).jsonObject["vectors"]!!.jsonObject

    /** The production body for [messages], as each send builds it: a fresh transport and tool, the vector's date. */
    private fun body(messages: JsonArray): String = runBlocking {
        ReasoningEffort.forgetRefusals()
        var sent: String? = null
        val capture = Interceptor { chain -> sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(); throw IOException("offline") }
        val provider = Provider(id = "f", name = "Fireworks", endpoint = "https://api.fireworks.ai/inference/v1",
            model = "accounts/fireworks/models/glm-5p3-flash", requiresAPIKey = true)
        val transport = OpenAiTransport(provider.chatCompletionsUrl, provider.model, apiKey = "k",
            client = OkHttpClient.Builder().addInterceptor(capture).build(), providerName = provider.name,
            effort = ReasoningEffort.For(ReasoningEffort.FAST, provider))
        // Midday UTC: "Thursday, October 22, 2026" in every zone the suite runs in, as the vector's tool date is fixed.
        val tool = BraveWebSearchTool(apiKey = "b", clock = { Instant.parse("2026-10-22T12:00:00Z") })
        val history = messages.map { m -> m.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content as Any } }
        runCatching { transport.runTurn(history, true, listOf(tool)) {} }
        sent!!
    }

    @Test fun theSameTurnIsTheSameBytes() {
        for (turn in listOf("turn1Messages", "turn2Messages")) {
            val m = vectors[turn]!!.jsonArray
            assertEquals("$turn: two sends of the same history differ", body(m), body(m))
        }
    }

    @Test fun keysAreSortedAtEveryLevel() {
        fun sorted(e: JsonElement, at: String): Unit = when (e) {
            is JsonObject -> { assertEquals("keys at $at", e.keys.sorted(), e.keys.toList()); e.forEach { (k, v) -> sorted(v, "$at.$k") } }
            is JsonArray -> e.forEachIndexed { i, v -> sorted(v, "$at[$i]") }
            else -> Unit
        }
        for (turn in listOf("turn1Messages", "turn2Messages")) sorted(Json.parseToJsonElement(body(vectors[turn]!!.jsonArray)), turn)
    }

    @Test fun turnTwoExtendsTurnOneThroughTheLastSharedMessage() {
        val one = body(vectors["turn1Messages"]!!.jsonArray)
        val two = body(vectors["turn2Messages"]!!.jsonArray)
        val end = closingBracket(one, one.indexOf("\"messages\":[") + "\"messages\":".length)   // turn 1's messages' ']'
        val shared = one.substring(0, end)
        assertTrue("turn 2 does not start with turn 1 through its last message", two.startsWith(shared))
        assertEquals("turn 2 continues the messages array", ',', two[end])
        // iOS's bodies diverge at the same point: ']' against ','.
        val ios1 = vectors["turn1Body"]!!.jsonPrimitive.content; val ios2 = vectors["turn2Body"]!!.jsonPrimitive.content
        val iosShared = vectors["commonPrefixBytes"]!!.jsonPrimitive.content.toInt()
        assertEquals(']', ios1[iosShared]); assertEquals(',', ios2[iosShared])
    }

    @Test fun theMessagesAreIOSs() {
        for ((turn, iosBody) in listOf("turn1Messages" to "turn1Body", "turn2Messages" to "turn2Body")) {
            val ours = Json.parseToJsonElement(body(vectors[turn]!!.jsonArray)).jsonObject["messages"]
            val theirs = Json.parseToJsonElement(vectors[iosBody]!!.jsonPrimitive.content).jsonObject["messages"]
            assertEquals(turn, theirs, ours)
        }
    }

    /** The index of the `]` closing the array that opens at [start], outside strings. */
    private fun closingBracket(s: String, start: Int): Int {
        var depth = 0; var inString = false; var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) { if (c == '\\') i++ else if (c == '"') inString = false }
            else when (c) { '"' -> inString = true; '[', '{' -> depth++; ']', '}' -> { depth--; if (depth == 0) return i } }
            i++
        }
        error("unclosed array")
    }
}
