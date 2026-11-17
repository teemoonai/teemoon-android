// ports: teemoonTests/StreamStallTests.swift @ 8780c32
//
// NVIDIA's free endpoint held glm-5.3-flash 253–282 s before headers (2026-10-19, pm's curl runs), so a 240 s
// timeout failed requests that were only queued. The wait for headers is long; a stream that stops after headers
// still ends. A real socket, not an interceptor: the timeouts live in OkHttp's I/O, which an interceptor skips.
package ai.teemoon.inference

import java.io.InterruptedIOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStallTest {
    private val server = ServerSocket(0)
    @After fun close() = server.close()

    /** One response: headers after [headerDelayMs], the first SSE event, then either the rest or a stall. */
    private fun serve(headerDelayMs: Long, stallAfterFirstLine: Boolean) = thread(isDaemon = true) {
        server.accept().use { s ->
            val input = s.getInputStream().bufferedReader()
            var length = 0
            while (true) { val line = input.readLine() ?: return@use; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
            repeat(length) { input.read() }
            Thread.sleep(headerDelayMs)
            val out = s.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n".toByteArray()); out.flush()
            if (stallAfterFirstLine) { runCatching { Thread.sleep(10_000) }; return@use }
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()); out.flush()
        }
    }

    private fun transport(streamIdleMs: Long) = OpenAiTransport("http://127.0.0.1:${server.localPort}/v1/chat/completions", "m", apiKey = "k",
        client = OkHttpClient.Builder().readTimeout(StreamTimeouts.HEADER_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).build(), streamIdleMs = streamIdleMs)

    @Test fun theWaitForHeadersOutlastsAQueuedFreeTierModel() = runBlocking {
        assertTrue(StreamTimeouts.HEADER_WAIT_MS >= 600_000)
        assertTrue(OpenAiTransport.chatClient().readTimeoutMillis >= 600_000)
        assertEquals("no total bound", 0, OpenAiTransport.chatClient().callTimeoutMillis)
        // headers later than the stream's idle bound: the bound must not apply before them
        serve(headerDelayMs = 1_000, stallAfterFirstLine = false)
        var partial = ""
        transport(streamIdleMs = 400).runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) { partial = it }
        assertEquals("Hello", partial)
    }

    /**
     * An image turn's upload (the earlier images ride along) may pause on a phone's uplink: OkHttp's 10 s write default
     * failed the maintainer's fourth image message on every retry (2026-10-28). The upload's idle bound is the header wait, as
     * iOS's timeoutIntervalForRequest covers both.
     */
    @Test fun anUploadThatPausesIsNotCutAtOkHttpsTenSecondDefault() {
        assertEquals(StreamTimeouts.HEADER_WAIT_MS, OpenAiTransport.chatClient().writeTimeoutMillis.toLong())
    }

    @Test fun aStreamThatStopsAfterHeadersTimesOut() = runBlocking {
        serve(headerDelayMs = 0, stallAfterFirstLine = true)
        var partial = ""
        val started = System.nanoTime()
        val thrown = runCatching { transport(streamIdleMs = 400).runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) { partial = it } }.exceptionOrNull()
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("timed out, not hung: $thrown", generateSequence(thrown) { it.cause }.any { it is InterruptedIOException })
        assertEquals("Hel", partial)
        assertTrue("ended at the stream's idle bound, not the header wait: ${seconds}s", seconds < 5)
        // the error carries no reply text: what the stream said stays out of the message the UI and the card show
        assertFalse(generateSequence(thrown) { it.cause }.any { it.message.orEmpty().contains("Hel") })
    }
}
