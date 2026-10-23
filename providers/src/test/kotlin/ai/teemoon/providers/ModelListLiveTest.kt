// android-only: no Swift counterpart. 1.0.6 performance row 12 (model lists): `GET /models` per cloud provider through
// the app's own EndpointModelCatalog.probe, timed twice — the first call pays DNS and TLS, the second rides the shared
// connection pool (TeemoonHttp.shared). Opt-in: free list endpoints, keys from the host's ~/.<NAME>_API_KEY files.
//   TEEMOON_BASELINE_MODELS=1 ./gradlew :providers:testDebugUnitTest --tests '*ModelListLiveTest*' -i | grep '\[models\]'
package ai.teemoon.providers

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class ModelListLiveTest {
    private fun hostKey(name: String): String? = File(System.getProperty("user.home"), ".${name}_API_KEY").takeIf { it.isFile }?.readText()?.trim()

    @Test fun modelListPerProvider() = runBlocking {
        assumeTrue("set TEEMOON_BASELINE_MODELS to run", System.getenv("TEEMOON_BASELINE_MODELS") != null)
        listOf(
            "fireworks" to ("https://api.fireworks.ai/inference/v1" to "FIREWORKS"),
            "openrouter" to ("https://openrouter.ai/api/v1" to "OPENROUTER"),
            "nvidia" to ("https://integrate.api.nvidia.com/v1" to "NVIDIA"),
            "near.ai" to ("https://cloud-api.near.ai/v1" to "NEAR_AI"),
            "xai" to ("https://api.x.ai/v1" to "XAI"),
        ).forEach { (name, p) ->
            val key = hostKey(p.second) ?: return@forEach println("[models] $name: skipped (no key file)")
            val times = (1..2).map {
                val t0 = System.nanoTime()
                val r = EndpointModelCatalog.probe(p.first, apiKey = key)
                (System.nanoTime() - t0) / 1_000_000 to r
            }
            val (cold, r) = times[0]
            val count = (r as? EndpointModelCatalog.ProbeResult.Connected)?.models?.size
            println("[models] $name: first ${cold} ms · second ${times[1].first} ms · ${count?.let { "$it chat models" } ?: "failed: $r"}")
        }
    }
}
