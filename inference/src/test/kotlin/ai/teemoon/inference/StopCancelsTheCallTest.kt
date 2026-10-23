// A stop ends the HTTP call, not only the coroutine. OkHttp's execute() and the stream read block their thread, so a
// cancelled turn whose provider was still holding the request kept going: the reply arrived after the stop, reached
// the transcript, and was billed (the 3917.6 disk gate, 2026-10-21: a stopped Think Harder showed the new reply).
// iOS cancels the URLSession task with the Task. A real socket, as StreamStallTest: the call lives in OkHttp's I/O.
package ai.teemoon.inference

import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCancelsTheCallTest {
    private val server = ServerSocket(0)
    @After fun close() = server.close()

    private val requestRead = CountDownLatch(1)
    private val clientClosed = CountDownLatch(1)

    /** Reads the request, then holds the headers (or, with [streamFirst], sends one event and holds the rest) and watches for the client to hang up. */
    private fun serve(streamFirst: Boolean) = thread(isDaemon = true) {
        server.accept().use { s ->
            val input = s.getInputStream()
            val reader = input.bufferedReader()
            var length = 0
            while (true) { val line = reader.readLine() ?: return@use; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
            repeat(length) { reader.read() }
            if (streamFirst) {
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n".toByteArray())
                s.getOutputStream().flush()
            }
            requestRead.countDown()
            s.soTimeout = 10_000
            try { if (input.read() == -1) clientClosed.countDown() } catch (_: SocketException) { clientClosed.countDown() } catch (_: java.net.SocketTimeoutException) {}
        }
    }

    private fun transport() = OpenAiTransport("http://127.0.0.1:${server.localPort}/v1/chat/completions", "m", apiKey = "k",
        client = OkHttpClient.Builder().readTimeout(StreamTimeouts.HEADER_WAIT_MS, TimeUnit.MILLISECONDS).build(), streamIdleMs = 30_000)

    private fun stopMidway(streamFirst: Boolean) = runBlocking {
        serve(streamFirst)
        val partials = ArrayList<String>()
        val turn = async(Dispatchers.Default) { transport().runTurn(listOf(mapOf("role" to "user", "content" to "hi")), false, emptyList()) { partials += it } }
        assertTrue("the request reached the server", requestRead.await(5, TimeUnit.SECONDS))
        delay(200)
        val started = System.nanoTime()
        turn.cancel()
        val outcome = runCatching { turn.await() }.exceptionOrNull()
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("a stop is a cancellation, not an error card: $outcome", outcome is CancellationException)
        assertTrue("the turn ended at the stop, not when the provider answered: ${seconds}s", seconds < 2)
        assertTrue("the server saw the connection close", clientClosed.await(3, TimeUnit.SECONDS))
        partials
    }

    @Test fun aStopWhileTheProviderHoldsTheHeadersEndsTheCall() {
        assertEquals("nothing arrived", emptyList<String>(), stopMidway(streamFirst = false))
    }

    @Test fun aStopMidStreamEndsTheCall() {
        assertEquals(listOf("Hel"), stopMidway(streamFirst = true))
    }
}
