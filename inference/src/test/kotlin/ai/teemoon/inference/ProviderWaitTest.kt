// ports: teemoonTests/ProviderWaitTests.swift @ v1.0.6
//
// Fireworks held requests 2–69 s before headers and NVIDIA's free tier 4–5 min (2026-10-19), with "thinking..." on
// screen the whole time. The chip now says the request is waiting on the provider, and only that.
package ai.teemoon.inference

import java.net.ServerSocket
import java.time.Instant
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderWaitTest {
    private class Edges : StreamCallbacks {
        val edges = ArrayList<Pair<Int, Boolean>>()
        override fun onText(text: String) {}
        override fun onAwaitingResponse(turn: Int, sentAt: Instant?) { edges.add(turn to (sentAt != null)) }
    }

    private object NoopSearch : Tool {
        override val name = "web_search"
        override val description = "Search the web."
        override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        override suspend fun execute(argumentsJson: String) = "nothing"
    }

    @Test fun shownOnlyFromTheThresholdAndCountsThisRequest() {
        val wait = ProviderWait(turn = 1, sentAtMs = 100_000, providerName = "fireworks")
        assertNull(wait.shownSeconds(104_900))
        assertEquals(5, wait.shownSeconds(105_000))
        assertEquals(69, wait.shownSeconds(169_700))
    }

    @Test fun theLabelClaimsNoQueue() {
        val label = ProviderWait.label("nvidia", 42)
        assertEquals("waiting for nvidia\u2026 42s", label)
        assertFalse(label.contains("queue") || label.contains("busy"))
    }

    /** Each request of a search turn gets its own wait, cleared by its own headers. */
    @Test fun everyRequestOfAToolTurnWaitsAfresh() = runTest {
        val transport = object : GenerationTransport {
            var calls = 0
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                fail("the engine must call the headers-aware entry point"); return TurnResult(content = "")
            }
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onResponse: () -> Unit, onPartial: (String) -> Unit): TurnResult {
                calls += 1
                onResponse()
                if (calls == 1) return TurnResult(content = "", toolCalls = mapOf(0 to ToolCallState(id = "c1", name = "web_search", arguments = """{"query":"q"}""")))
                onPartial("Done."); return TurnResult(content = "Done.")
            }
        }
        val events = Edges()
        GenerationEngine(transport, listOf(NoopSearch), events).run(emptyList(), "hi")
        assertEquals("got ${events.edges}", listOf(1, 2), events.edges.filter { it.second }.map { it.first })
        for (turn in listOf(1, 2)) assertTrue(events.edges.indexOf(turn to true) < events.edges.indexOf(turn to false))
    }

    /** On-device has no network wait: the default entry point clears it at once. */
    @Test fun aTransportWithoutHeadersNeverWaits() = runTest {
        val events = Edges()
        var seen = -1
        val local = object : GenerationTransport {
            override suspend fun runTurn(messages: List<Map<String, Any>>, includeTools: Boolean, tools: List<Tool>, onPartial: (String) -> Unit): TurnResult {
                seen = events.edges.size; onPartial("Hi."); return TurnResult(content = "Hi.")
            }
        }
        GenerationEngine(local, emptyList(), events).run(emptyList(), "hi")
        assertEquals("opened and cleared before any work: ${events.edges}", 2, seen)
        assertEquals(listOf(1 to true, 1 to false), events.edges.take(2))
    }

    @Test fun httpReportsHeadersBeforeTheFirstText() = runBlocking {
        val server = ServerSocket(0)
        try {
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val input = s.getInputStream().bufferedReader()
                    var length = 0
                    while (true) { val line = input.readLine() ?: return@use; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                    repeat(length) { input.read() }
                    s.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        write("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: [DONE]\n\n".toByteArray()); flush()
                    }
                }
            }
            val order = ArrayList<String>()
            OpenAiTransport("http://127.0.0.1:${server.localPort}/v1/chat/completions", "m", apiKey = "k")
                .runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList(), onResponse = { order += "headers" }) { if ("text" !in order) order += "text" }
            assertEquals(listOf("headers", "text"), order)
        } finally { server.close() }
    }
}
