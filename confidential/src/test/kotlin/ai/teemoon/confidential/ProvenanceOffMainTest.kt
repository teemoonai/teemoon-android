// android-only: the typing lag on the maintainer's Pixel (2026-11-06). The session lives on the main thread, and the 1.1.0
// compose-file work ran there: every one of ~47 fetches read and decoded the whole provenance cache file, and a miss
// hashed the body, parsed its service names and rewrote the file — 47 disk reads per verification, which a phone deep
// in swap turns into a frozen keyboard for the first minutes after a cold start.
package ai.teemoon.confidential

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class ProvenanceOffMainTest {
    /** Records the thread each cache access ran on. */
    private class RecordingCache : ProvenanceCacheStore {
        val threads = java.util.Collections.synchronizedList(ArrayList<String>())
        private var map: Map<String, List<String>> = emptyMap()
        override fun load(): Map<String, List<String>> { threads += Thread.currentThread().name; return map }
        override fun save(cache: Map<String, List<String>>) { threads += Thread.currentThread().name; map = cache }
    }

    private val body = "services:\n  web:\n    image: nginx\n".toByteArray()
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Test fun composeFileWorkNeverRunsOnTheCallersThread() {
        val cache = RecordingCache()
        val service = ProvenanceService(http = { _, _ -> ProvenanceResponse(200, emptyMap(), body) }, cache = cache)
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
        runBlocking {
            withContext(main) {
                val miss = service.fetchComposeFile("c1", "a.yaml")   // a miss: fetch, hash, parse, save
                val hit = service.fetchComposeFile("c1", "a.yaml")    // a hit: the cache answers
                assertEquals(miss, hit)
                assertEquals(body.toString(Charsets.UTF_8), service.fetchComposeBody(DeployLog.Source("c1", "a.yaml", sha)))
            }
        }
        main.close()
        assertTrue("the cache was never touched", cache.threads.isNotEmpty())
        assertTrue("cache work ran on the caller's thread: ${cache.threads}", cache.threads.none { it.startsWith("fake-main") })
    }

    /** The file-backed cache reads its file once per launch and answers from memory after; a save still reaches disk. */
    @Test fun theFileCacheReadsItsFileOnce() {
        val dir = Files.createTempDirectory("prov-cache").toFile()
        val file = File(dir, "provenance-cache.json")
        file.writeText("""{"k":["v1"]}""")
        val store = FileProvenanceCacheStore(file)
        assertEquals(listOf("v1"), store.load()["k"])
        file.writeText("""{"k":["changed on disk"]}""")
        assertEquals("a second load read the file again", listOf("v1"), store.load()["k"])
        store.save(mapOf("k" to listOf("v2")))
        assertEquals(listOf("v2"), store.load()["k"])
        assertEquals("the save never reached disk", listOf("v2"), FileProvenanceCacheStore(file).load()["k"])
    }
}
