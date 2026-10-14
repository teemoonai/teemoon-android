package ai.teemoon.app

// android-only: every synchronous OkHttp seam must hop to Dispatchers.IO itself, because the generation loop and the
// probes are driven from Main. Android throws NetworkOnMainThreadException for a blocking call there, and the tool's
// catch turned it into "check your network connection" — every Brave search failed instantly on the emulator
// (2026-09-08) while the reply streamed fine. iOS cannot have this failure (URLSession is asynchronous). This calls each
// seam FROM MAIN against the in-process fake server; a seam that blocks on Main throws here.

import ai.teemoon.inference.BraveWebSearchTool
import ai.teemoon.providers.EndpointModelCatalog
import ai.teemoon.providers.OllamaAdapter
import ai.teemoon.providers.liveStatusFetch
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainThreadNetworkTest {
    private fun <T> fromMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun braveFetchDoesNotBlockMain() { FakeSseServer().use { server ->
        val (status, _) = fromMain { BraveWebSearchTool.liveFetch("${server.baseUrl}/models", mapOf("Accept" to "application/json"), 5_000) }
        assertEquals(200, status)
    } }
    @Test fun keyValidationFetchDoesNotBlockMain() { FakeSseServer().use { server ->
        assertEquals(200, fromMain { liveStatusFetch("${server.baseUrl}/models", emptyMap()) })
    } }
    @Test fun endpointProbeDoesNotBlockMain() { FakeSseServer().use { server ->
        val result = fromMain { EndpointModelCatalog.probe(server.baseUrl, null, "") }
        assertEquals(true, result is EndpointModelCatalog.ProbeResult.Connected)
    } }
    @Test fun ollamaListDoesNotBlockMain() { FakeSseServer().use { server ->
        fromMain { OllamaAdapter.listModels(server.baseUrl) }   // any result; the assertion is that no exception crossed Main
    } }
}
