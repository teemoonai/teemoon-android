// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (the fetch service, verifyManifests,
//        fetchModelLayerManifest and the verified-digest cache; the parsers are ProvenanceParsing.kt and
//        the value types ProvenanceTypes.kt)
//        teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (recentMisses: a clean miss is not asked again within the hour)
// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (fetchAndVerify asks the guessed repo, then the compose repo)
//
// Fetches GitHub Artifact Attestations for the image digests named in an
// attested compose manifest and verifies each with ProvenanceVerifier.
// This is the network half of the "running code traces to near.ai's source"
// check; the crypto is in ProvenanceVerifier.kt.
//
// Fail-closed: any image whose attestation can't be fetched or verified
// leaves the overall result unverified.
package ai.teemoon.confidential

import ai.teemoon.providers.TeemoonHttp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/** One GET's answer: status, response headers (lower-cased names), body. */
data class ProvenanceResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/** The network seam. Throws on transport failure. */
fun interface ProvenanceHttp {
    suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse
}

class OkHttpProvenanceHttp(private val client: OkHttpClient = TeemoonHttp.builder().callTimeout(30, TimeUnit.SECONDS).build()) : ProvenanceHttp {
    override suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).get(); headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            ProvenanceResponse(r.code, r.headers.names().associate { it.lowercase() to (r.header(it) ?: "") }, r.body.bytes())
        }
    }
}

/**
 * Where verified digests persist (iOS: UserDefaults). Entries are the same
 * string arrays iOS stores — `[repo, identity, ref, commit, tlog]`, or the
 * legacy 3-field `[repo, identity, tlog]`.
 */
interface ProvenanceCacheStore {
    fun load(): Map<String, List<String>>
    fun save(cache: Map<String, List<String>>)
}

class InMemoryProvenanceCacheStore : ProvenanceCacheStore {
    private var cache: Map<String, List<String>> = emptyMap()
    override fun load() = cache
    override fun save(cache: Map<String, List<String>>) { this.cache = cache }
}

/** A JSON file beside the app's other state; unreadable content is treated as empty (a cache, never evidence). */
class FileProvenanceCacheStore(private val file: File) : ProvenanceCacheStore {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    override fun load(): Map<String, List<String>> =
        if (!file.exists()) emptyMap() else runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
    override fun save(cache: Map<String, List<String>>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp"); tmp.writeText(Json.encodeToString(serializer, cache))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

class ProvenanceService(
    val verifier: ProvenanceVerifier = ProvenanceVerifier(),
    private val http: ProvenanceHttp = OkHttpProvenanceHttp(),
    private val cache: ProvenanceCacheStore = InMemoryProvenanceCacheStore(),
    /**
     * Digests whose legacy cache entry we already tried to upgrade this
     * launch — one attempt each, so a rate-limited hour can't be burned by
     * re-fetching the same digests on every verification run. Process-wide by default.
     */
    private val upgradeAttempted: MutableSet<String> = globalUpgradeAttempted,
    /**
     * Digests every candidate repo answered 404 for, with when: an unpublished
     * image is asked about once an hour, not on every pass, so the anonymous
     * GitHub quota is not spent re-learning the same absence. Process-wide by default.
     */
    private val recentMisses: MutableMap<String, Long> = globalRecentMisses,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    companion object {
        private val globalUpgradeAttempted: MutableSet<String> = Collections.synchronizedSet(HashSet())
        private val globalRecentMisses: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())
        const val MISS_TTL_MS: Long = 3_600_000

        /** The instance the session's service seams use; the app installs a file-backed cache at startup. */
        @Volatile var default: ProvenanceService = ProvenanceService()

        private fun sha256Hex(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toHex()
    }

    /**
     * Fetches the model-layer compose YAML at the attested commit and path and
     * hash-checks it against the action log's pinned `file_sha256`. The three
     * outcomes are DISTINCT and must stay so: a hashMismatch is an adversarial
     * integrity break (the recipe on disk is not what the signed action log
     * pinned — fail loud), while a fetchFailed is transient (GitHub
     * unreachable / throttled — never accuse near.ai of tampering over a
     * network blip). Collapsing the two makes the loud tamper state
     * unreachable and the seam check fail open.
     */
    suspend fun fetchModelLayerManifest(path: String, commit: String, expectedSha256: String): ModelLayerFetch {
        val url = "https://raw.githubusercontent.com/$MODEL_COMPOSE_REPO/$commit/$path"
        return try {
            val r = http.get(url, emptyMap())
            if (r.status != 200) return ModelLayerFetch.FetchFailed
            val digest = sha256Hex(r.body)
            if (digest != expectedSha256.lowercase()) {
                log("model-layer compose hash mismatch: attested $expectedSha256 got $digest")
                return ModelLayerFetch.HashMismatch(expectedSha256.lowercase(), digest)
            }
            ModelLayerFetch.Verified(String(r.body, Charsets.UTF_8))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { ModelLayerFetch.FetchFailed }
    }

    /** Verifies provenance for every near.ai image in [manifest]. Fail-closed for near.ai images; third-party sidecars are classified, not verified. */
    suspend fun verifyManifest(manifest: String): ManifestProvenance = verifyManifests(listOf("" to manifest))

    /** Verifies provenance across several host-labeled attested manifests as one set, deduplicated by digest with host membership merged. */
    suspend fun verifyManifests(manifests: List<Pair<String, String>>): ManifestProvenance {
        val refs = ProvenanceParsing.imageRefs(manifests)
        val nearAI = refs.filter { ProvenanceParsing.isNearAIImage(it.image) }
        val thirdParty = refs.filter { !ProvenanceParsing.isNearAIImage(it.image) }
        // A manifest with no near.ai images at all is not something this check can vouch for — fail-closed.
        if (nearAI.isEmpty()) return ManifestProvenance.Incomplete(emptyList(), emptyList(), thirdParty)
        val verified = ArrayList<ImageRef>(); val failures = ArrayList<ProvenanceFailure>()
        for (ref in nearAI) {
            when (val outcome = verify(ref)) {
                is ImageProvenance.Verified -> verified.add(ref.copy(sourceRepo = outcome.repositoryURL, sourceRef = outcome.sourceRef, sourceCommit = outcome.sourceCommit))
                else -> failures.add(ProvenanceFailure(ref, outcome))
            }
        }
        return if (failures.isEmpty()) ManifestProvenance.AllVerified(verified, thirdParty) else ManifestProvenance.Incomplete(verified, failures, thirdParty)
    }

    /**
     * Fetches and verifies one image's GitHub Artifact Attestation.
     *
     * A digest's provenance is immutable, so verified results are cached
     * persistently — GitHub's unauthenticated rate limit (60/hr) can then
     * never flip a previously-verified image back to failed, and routine
     * attestation refreshes cost no API calls for unchanged images.
     */
    suspend fun verify(ref: ImageRef): ImageProvenance {
        val cached = cachedVerification(ref.digest)
        if (cached != null) {
            // Legacy cache entries predate source ref/commit capture. Try one
            // re-fetch (per launch) to upgrade them so provenance can link the
            // exact code — but a failed re-fetch (rate limit, offline) must
            // never downgrade an already-verified digest.
            if (cached is ImageProvenance.Verified && cached.sourceRef == null && cached.sourceCommit == null && upgradeAttempted.add(ref.digest)) {
                val fresh = fetchAndVerify(ref)
                if (fresh is ImageProvenance.Verified) return fresh
            }
            return cached
        }
        if (recentlyMissed(ref.digest)) {
            return ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404 (asked within the hour)"))
        }
        return fetchAndVerify(ref)
    }

    fun recentlyMissed(digest: String): Boolean = synchronized(recentMisses) {
        val at = recentMisses[digest] ?: return false
        if (nowMs() - at < MISS_TTL_MS) return true
        recentMisses.remove(digest)
        false
    }

    private sealed interface AttestationFetch {
        class Found(val data: ByteArray) : AttestationFetch
        data object NotFound : AttestationFetch
        data class Transient(val reason: String) : AttestationFetch
    }

    private suspend fun fetchAndVerify(ref: ImageRef): ImageProvenance {
        val repos = ProvenanceParsing.githubRepos(ref.image)
        if (repos.isEmpty()) return ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("no GitHub repo for image ${ref.image}"))
        for (repo in repos) {
            // Every candidate keeps its one retry: the 2026-07-19 flaky 404
            // happened at the right repo, and moving on after one answer would
            // turn that blip into a wrong-repo miss. A clean absence is believed
            // after four answers (two repos × two tries), not two.
            when (val fetched = fetchAttestation(repo, ref.digest)) {
                is AttestationFetch.Found -> {
                    val outcome = verifier.verify(fetched.data, ref.digest)
                    if (outcome is ImageProvenance.Verified) cacheVerification(ref.digest, outcome)
                    return outcome
                }
                is AttestationFetch.Transient -> return ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient(fetched.reason))
                AttestationFetch.NotFound -> continue
            }
        }
        synchronized(recentMisses) { recentMisses[ref.digest] = nowMs() }
        return ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404"))
    }

    private suspend fun fetchAttestation(repo: String, digest: String): AttestationFetch {
        val url = "https://api.github.com/repos/$repo/attestations/sha256:$digest"
        val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        try {
            for (attempt in 0 until 2) {
                val r = http.get(url, headers)
                when (r.status) {
                    200 -> return AttestationFetch.Found(r.body)
                    404 -> {
                        // A 404 is only definitive when GitHub actually answered
                        // the question. Under rate pressure the unauthenticated
                        // API returns 404s that are NOT evidence of absence
                        // (observed live 2026-07-19: a digest 404'd on-device
                        // while the same URL served 200 elsewhere — the
                        // attestation existed; the device had exhausted the
                        // 60/hr anonymous quota re-verifying). A 404 carrying
                        // `X-RateLimit-Remaining: 0` is a throttling artifact →
                        // transient, non-blocking. A clean 404 gets one retry;
                        // only a repeatable, un-throttled 404 counts as absent here.
                        if (r.headers["x-ratelimit-remaining"] == "0") return AttestationFetch.Transient("GitHub attestations HTTP 404 under rate limit")
                        if (attempt == 0) { sleepMs(1_500); continue }
                        return AttestationFetch.NotFound
                    }
                    // Rate limits (403/429), server errors: no evidence either way.
                    else -> return AttestationFetch.Transient("GitHub attestations HTTP ${r.status}")
                }
            }
            return AttestationFetch.NotFound
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return AttestationFetch.Transient(e.message ?: e.toString())
        }
    }

    // MARK: - Verified-digest cache

    fun cachedVerification(digest: String): ImageProvenance? {
        val entry = cache.load()[digest] ?: return null
        return when (entry.size) {
            5 -> ImageProvenance.Verified(entry[0], entry[1], entry[2].ifEmpty { null }, entry[3].ifEmpty { null }, entry[4] == "true")   // empty string means null
            3 -> ImageProvenance.Verified(entry[0], entry[1], null, null, entry[2] == "true")   // legacy pre-ref/commit entry
            else -> null
        }
    }

    fun cacheVerification(digest: String, outcome: ImageProvenance) {
        val v = outcome as? ImageProvenance.Verified ?: return
        var all = cache.load() + (digest to listOf(v.repositoryURL, v.workflowIdentity, v.sourceRef ?: "", v.sourceCommit ?: "", if (v.transparencyLogChecked) "true" else "false"))
        // Old digests rotate out of manifests; keep the cache bounded.
        if (all.size > 64) all = all.filterKeys { it == digest }
        cache.save(all)
    }
}
