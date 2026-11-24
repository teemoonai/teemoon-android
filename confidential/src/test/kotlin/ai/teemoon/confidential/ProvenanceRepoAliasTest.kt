// ports: teemoonTests/ImageProvenanceTests.swift @ a5a3f6d3 (ProvenanceRepoAliasTests, 1.1.1 audit C5)
//        Fixtures are GitHub's keyless repo-level answers (fetched 2026-11-22), vendored byte-identical from iOS
//        teemoonTests/Fixtures/github_attestation_<name>.json. Saved responses only: no live network.
package ai.teemoon.confidential

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit C5 (1.1.1): `nearaidev/compose-manager-otelcol` read "GitHub 404" because its attestations live on
 * nearai/compose-manager. Every row of the alias table verifies a real saved attestation through the real verifier.
 */
class ProvenanceRepoAliasTest {
    private data class Row(val name: String, val digest: String, val repo: String)

    /** One row per `ProvenanceParsing.repoAliases` entry; digests are ones near.ai's fleet ran. */
    private val rows = listOf(
        Row("compose-manager-otelcol", "ac9b349081fc86413c2e25b44558daa14e8b33669f9e50fc0fec92dc77ce768a", "nearai/compose-manager"),
        Row("compose-manager-launcher", "69055bd12da3d2f065768a8551e73750f095662212beb4190f3c16859cd7dd9b", "nearai/compose-manager"),
        Row("vllm-proxy-rs", "b183677a5d32267539b9b21ec45327a4f3be0a013afeb608c68c4d76e9472e36", "nearai/inference-proxy"),
    )

    @Test fun everyAliasRowHasAFixtureRow() {
        assertEquals(rows.associate { it.name to it.repo.removePrefix("nearai/") }, ProvenanceParsing.repoAliases)
    }

    @Test fun theAliasedRepoIsAskedAndItsAttestationVerifies() = runTest {
        for (row in rows) {
            val path = "/repos/${row.repo}/attestations/sha256:${row.digest}"
            val body = javaClass.getResource("/fixtures/github_attestation_${row.name}.json")!!.readBytes()
            val asked = mutableListOf<String>()
            // GitHub as the app sees it: the aliased repo serves its saved bundle for one digest; every other URL is a clean 404.
            val http = object : ProvenanceHttp {
                override suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse {
                    val p = java.net.URI(url).path; asked += p
                    return if (p == path) ProvenanceResponse(200, mapOf("x-ratelimit-remaining" to "40"), body)
                    else ProvenanceResponse(404, mapOf("x-ratelimit-remaining" to "40"), "{\"message\":\"Not Found\"}".toByteArray())
                }
            }
            val service = ProvenanceService(http = http, cache = InMemoryProvenanceCacheStore(), upgradeAttempted = HashSet(), sleepMs = {})

            val outcome = service.verify(ImageRef(image = "docker.io/nearaidev/${row.name}", digest = row.digest))

            assertEquals("${row.name}: asked $asked", path, asked.first())
            val v = outcome as? ImageProvenance.Verified ?: error("expected Verified for ${row.name}, got $outcome")
            assertEquals("https://github.com/${row.repo}", v.repositoryURL)
            assertTrue(v.workflowIdentity, v.workflowIdentity.startsWith("https://github.com/${row.repo}/.github/workflows/"))
            assertTrue(v.transparencyLogChecked)
        }
    }
}
