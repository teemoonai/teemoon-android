// ports: teemoonTests/CandidateListFetchTests.swift @ 01a337a4 (1.1.1 audit integ read item 1: theRequestIsCancelledAtTheCap, theRequestIsCancelledWithItsCaller, the request bytes (loopback socket in place of the stub))
// ports: teemoonTests/CandidateListFetchTests.swift @ 86c7c6ce (1.1.1 audit A11: the entry cap, anOversizeListIsAListThatDidNotLoad; read of 5e36628e: theCapStopsTheDownload; the
//        planted-bytes seam's limit check). iOS serves the chunks through a URLProtocol; here a loopback socket does, to a plain
//        OkHttpClient (the cleartext gate is the app's client's; this test reads the cap, not the gate).
//
// Audit read of 86c7c6ce: the cap must stop the download, not judge it after the whole body arrived.
//
// 1.1.1 audit integ read, items 1 and 4 (iOS teemoonTests/CandidateListFetchTests.swift @ 01a337a4: theRequestIsCancelledAtTheCap,
// theRequestIsCancelledWithItsCaller, theCapStopsTheDownload through the client's own limit): the key-bearing list request is
// cancelled at the 15 s cap and with its caller, and the streaming cap is chosen by capability, not by which client object it is.
package ai.teemoon.confidential

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class CandidateListCapTest {
    /** Serves [chunks] x 256 KB, with no Content-Length (chunked) or with the full length declared, and counts what it wrote. */
    private class Server(val declareLength: Boolean) : AutoCloseable {
        val socket = ServerSocket(0)
        val sent = AtomicInteger()
        @Volatile var stopped = false
        val chunk = ByteArray(256 * 1024) { 0x20 }
        val chunks = 64   // 16 MB if nobody stops reading
        val url get() = "http://127.0.0.1:${socket.localPort}/list"
        init {
            Thread {
                runCatching {
                    socket.accept().use { s ->
                        val out = s.getOutputStream()
                        s.getInputStream().bufferedReader().let { r -> while (r.readLine().orEmpty().isNotEmpty()) { /* the request head */ } }
                        val head = if (declareLength) "HTTP/1.1 200 OK\r\nContent-Length: ${chunk.size.toLong() * chunks}\r\n\r\n"
                            else "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        out.write(head.toByteArray()); out.flush()
                        try {
                            repeat(chunks) {
                                if (!declareLength) out.write("${chunk.size.toString(16)}\r\n".toByteArray())
                                out.write(chunk)
                                if (!declareLength) out.write("\r\n".toByteArray())
                                out.flush(); sent.incrementAndGet(); Thread.sleep(20)
                            }
                            if (!declareLength) { out.write("0\r\n\r\n".toByteArray()); out.flush() }
                        } catch (e: java.io.IOException) { stopped = true }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        override fun close() { socket.close() }
    }

    private fun overLimit(server: Server) {
        try {
            runBlocking { AttestationService.getLimited(OkHttpClient(), server.url, emptyMap(), 1_000_000) }
            fail("a body past the limit was returned")
        } catch (e: HttpBodyTooLarge) { /* the cap */ }
        val deadline = System.currentTimeMillis() + 2_000
        while (!server.stopped && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("the transfer was never stopped", server.stopped)
        assertTrue("sent ${server.sent.get()} of ${server.chunks} chunks", server.sent.get() < server.chunks / 2)
    }

    @Test fun theCapStopsTheDownload() { Server(declareLength = false).use(::overLimit) }

    @Test fun aContentLengthPastTheCapStopsItAtOnce() { Server(declareLength = true).use(::overLimit) }

    @Test fun aBodyWithinTheCapIsReturnedWhole() {
        Server(declareLength = true).use { server ->
            val (status, body) = runBlocking { AttestationService.getLimited(OkHttpClient(), server.url, emptyMap(), 64 * 256 * 1024) }
            assertEquals(200, status); assertEquals(64 * 256 * 1024, body.size)
        }
    }

    /** A planted-bytes seam returns whole bodies: the limit is checked on what it returns, and an oversize list is one that did not load, never retried. */
    @Test fun anOversizeListIsAListThatDidNotLoadAndIsNotRetried() {
        val live = LiveCandidates.load().body
        var requests = 0
        val got = runBlocking {
            AttestationService.fetchCandidateList("https://cloud-api.near.ai/v1", "m", "k") { _, _ -> requests++; 200 to live + ByteArray(AttestationService.MAX_LIST_BYTES + 1 - live.size) { 0x20 } }
        }
        assertNull(got); assertEquals("an oversize body is final", 1, requests)
        val ok = runBlocking { AttestationService.fetchCandidateList("https://cloud-api.near.ai/v1", "m", "k") { _, _ -> 200 to live } }
        assertTrue("the live list is within the cap", ok != null)
    }

    private fun listing(entries: Int) = """{"model_attestations":[${(0 until entries).joinToString(",") { """{"signing_public_key":"ab"}""" }}]}""".toByteArray()

    /** CandidateListFetchTests.anOversizeListIsAListThatDidNotLoad @ 86c7c6ce, the entry-count half: more than MAX_LISTED_ENCLAVES is a list that did not load, final on the first answer. */
    @Test fun moreThanTheEntryCapIsAListThatDidNotLoad() {
        var requests = 0
        val many = runBlocking { AttestationService.fetchCandidateList("https://cloud-api.near.ai/v1", "m", "k") { _, _ -> requests++; 200 to listing(AttestationService.MAX_LISTED_ENCLAVES + 1) } }
        assertNull("a 9-entry list was adopted", many); assertEquals("an over-cap list is final", 1, requests)
        val atCap = runBlocking { AttestationService.fetchCandidateList("https://cloud-api.near.ai/v1", "m", "k") { _, _ -> 200 to listing(AttestationService.MAX_LISTED_ENCLAVES) } }
        assertTrue("a list at the cap loads", atCap != null)
    }

    /** A server that reads the request head and then never answers; records the head and whether the client hung up. */
    private class SilentServer : AutoCloseable {
        val socket = ServerSocket(0)
        val accepted = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val connections = AtomicInteger()
        @Volatile var head: List<String> = emptyList()
        val base get() = "http://127.0.0.1:${socket.localPort}/v1"
        init {
            Thread {
                runCatching {
                    while (true) {
                        val s = socket.accept(); connections.incrementAndGet()
                        Thread {
                            runCatching {
                                val r = s.getInputStream().bufferedReader()
                                val lines = ArrayList<String>()
                                while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; lines.add(l) }
                                head = lines; accepted.countDown()
                                // the request is sent; the hang-up shows as EOF or a reset on this read
                                runCatching { while (s.getInputStream().read() >= 0) { /* nothing more is sent */ } }
                                closed.countDown()
                            }
                        }.apply { isDaemon = true }.start()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        override fun close() { socket.close() }
    }

    private val plain = AttestationService.OkHttpAttestation { OkHttpClient() }
    private val seconds2 = java.util.concurrent.TimeUnit.SECONDS

    /** iOS theRequestIsCancelledAtTheCap: the cap passing cancels the request, and nothing outlives it. */
    @Test fun theRequestIsCancelledAtTheCap() {
        SilentServer().use { server ->
            val t0 = System.nanoTime()
            val got = runBlocking { AttestationService.candidateList(server.base, "m", "k", plain, timeoutMs = 300) }
            val tookMs = (System.nanoTime() - t0) / 1_000_000
            assertNull(got)
            // a blocking execute() that only ends at OkHttp's own read timeout (10 s) returns late and closes late: the cap must end it
            assertTrue("the cap (300 ms) returned after $tookMs ms", tookMs < 3_000)
            assertTrue("the request outlived the cap: the server never saw the connection close", server.closed.await(2, seconds2))
            assertEquals(1, server.connections.get())
        }
    }

    /** iOS theRequestIsCancelledWithItsCaller: a model switch (the caller's cancel) cancels the request too. */
    @Test fun theRequestIsCancelledWithItsCaller() {
        SilentServer().use { server ->
            runBlocking {
                val caller = launch(kotlinx.coroutines.Dispatchers.Default) { AttestationService.candidateList(server.base, "m", "k", plain) }
                assertTrue("the request never arrived", server.accepted.await(2, seconds2))
                caller.cancel()
                assertTrue("the request outlived its caller: the server never saw the connection close", server.closed.await(2, seconds2))
            }
            assertEquals(1, server.connections.get())
        }
    }

    /** iOS theRequestCarriesTheModelTheKeyAndTheNonceItReturns: cancelling changed no request byte (URL, query, Authorization). */
    @Test fun theRequestBytesAreUnchanged() {
        SilentServer().use { server ->
            runBlocking { AttestationService.candidateList(server.base, "z-ai/glm-5.3-flash", "k", plain, timeoutMs = 300) }
            server.accepted.await(2, seconds2)
            val line = server.head.first()
            assertTrue(line, line.startsWith("GET /v1/attestation/report?model=z-ai%2Fglm-5.3-flash&signing_algo=ed25519&nonce="))
            assertTrue(server.head.joinToString("\n"), server.head.any { it.equals("Authorization: Bearer k", ignoreCase = true) })
        }
    }

    /** A client that wraps the production one: the cap is by capability (item 4), so a wrapper that offers it keeps the streaming cap. */
    private class Wrapped(val inner: AttestationService.OkHttpAttestation) : suspend (String, Map<String, String>) -> Pair<Int, ByteArray>, AttestationService.LimitedHttp {
        override suspend fun invoke(url: String, headers: Map<String, String>) = inner(url, headers)
        override suspend fun getLimited(url: String, headers: Map<String, String>, limit: Int) = inner.getLimited(url, headers, limit)
    }

    @Test fun aWrappedClientKeepsTheStreamingCap() {
        Server(declareLength = false).use { server ->
            val got = runBlocking { AttestationService.fetchCandidateList(server.url.removeSuffix("/list"), "m", "k", Wrapped(plain)) }
            assertNull(got)
            val deadline = System.currentTimeMillis() + 2_000
            while (!server.stopped && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("a wrapped client downloaded the whole body before the cap judged it", server.stopped)
            assertTrue("sent ${server.sent.get()} of ${server.chunks} chunks", server.sent.get() < server.chunks / 2)
        }
    }
}
