// The maintainer 2026-11-13 on the Pixel: "when teemoon is verifying near the ui slows down". The session's scope is the main
// thread, and the verifiers only left it for their HTTP: quote parsing, the native DCAP check (X.509 chain, signatures,
// CRLs) up to four times, the TDX chain, the NRAS JWT and the report decodes all ran on it, between the fetches.
//
// Each verifier is main-safe now: called from the main thread, its work runs on VerifyWork's one background worker. The
// fakes run inline wherever the verifier's own code runs, so the thread they see is the thread the work runs on.
package ai.teemoon.confidential

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class VerifyOffMainTest {
    private val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    @After fun stop() = main.shutdown()

    private val seen = CopyOnWriteArrayList<String>()
    private fun mark(what: String) { seen += "$what@${Thread.currentThread().name}" }
    /** Called from the main thread, as the session calls it. */
    private fun fromMain(block: suspend () -> Unit) = runBlocking(main.asCoroutineDispatcher()) { block() }
    private fun assertOffMain(what: String) {
        assertTrue("$what reached its fakes: $seen", seen.isNotEmpty())
        assertEquals("$what ran on the main thread: $seen", emptyList<String>(), seen.filter { "@fake-main" in it })
    }

    @Test fun theDcapCheckRunsOffMain() {
        val verifier = DcapVerifier(CollateralProvider { mark("collateral"); ByteArray(0) },
            DcapQvlEngine { _, _, _ -> mark("engine"); throw IllegalStateException("fake") })
        fromMain { verifier.verify("00", 0) }
        assertOffMain("DcapVerifier.verify")
    }

    @Test fun theNrasCheckRunsOffMain() {
        val nras = NrasService(http = { _, _ -> mark("post"); "not a response".toByteArray() }, keys = { mark("jwks"); ByteArray(0) })
        fromMain { nras.verify("""{"nonce":"ab"}""", "ab") }
        assertOffMain("NrasService.verify")
    }

    @Test fun theTlsCheckRunsOffMain() {
        val tls = TlsAttestationVerifier(probe = { mark("probe"); TlsProbeResult(500, ByteArray(0), null) })
        fromMain { tls.verify("https://node.example") }
        assertOffMain("TlsAttestationVerifier.verify")
    }

    @Test fun theProvenanceCheckRunsOffMain() {
        val cache = object : ProvenanceCacheStore {
            override fun load(): Map<String, List<String>> { mark("cache.load"); return emptyMap() }
            override fun save(cache: Map<String, List<String>>) { mark("cache.save") }
        }
        val service = ProvenanceService(http = { _, _ -> mark("http"); ProvenanceResponse(404, emptyMap(), ByteArray(0)) }, cache = cache, upgradeAttempted = HashSet())
        fromMain { service.verifyManifests(listOf("" to "image: ghcr.io/nearai/private-ml-sdk@sha256:" + "a".repeat(64))) }
        assertOffMain("ProvenanceService.verifyManifests")
    }

    @Test fun theAttestationFetchRunsOffMain() {
        fromMain {
            runCatching { AttestationService.fetch("https://cloud-api.near.ai/v1", "k", model = "", providerID = "p",
                http = { _, _ -> mark("http"); 500 to ByteArray(0) }, directBase = { null }) }
        }
        assertOffMain("AttestationService.fetch")
    }
}
