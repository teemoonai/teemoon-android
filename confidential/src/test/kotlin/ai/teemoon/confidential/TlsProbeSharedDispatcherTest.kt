package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1.1.1 regression (frame-5 capture, 2026-11-22): OkHttpTlsProbe shut down its client's dispatcher executor after each
 * probe, but the client came from TeemoonHttp.builder(), which shares ONE dispatcher with every client in the app. Once
 * the enclave-list fetch became an enqueued, cancellable call (1.1.1 audit integ read item 1, e77ac650), every later
 * attestation fetch failed with "executor rejected" until the app restarted ("verified … (refresh failed)"). The
 * probe must leave the shared dispatcher running.
 */
class TlsProbeSharedDispatcherTest {
    @Test fun aTlsProbeLeavesTheSharedDispatcherRunning() = runBlocking {
        // a closed port: the probe fails fast, and its finally block still runs
        val port = ServerSocket(0).use { it.localPort }
        runCatching { OkHttpTlsProbe().get("https://127.0.0.1:$port/") }
        assertFalse("the probe shut the app's shared dispatcher down", TeemoonHttp.client().dispatcher.executorService.isShutdown)

        // and an enqueued call on the shared dispatcher still runs (it fails to connect, but it is not "executor rejected")
        val done = CountDownLatch(1); var message: String? = null
        TeemoonHttp.client().newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { message = e.message; done.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); done.countDown() }
        })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertFalse("an enqueued call was rejected: $message", message == "executor rejected")
    }
}
