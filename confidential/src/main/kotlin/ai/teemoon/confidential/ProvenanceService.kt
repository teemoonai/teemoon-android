// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (the fetch service, verifyManifests,
//        fetchModelLayerManifest and the verified-digest cache; the parsers are ProvenanceParsing.kt and
//        the value types ProvenanceTypes.kt)
//        teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (recentMisses: a clean miss is not asked again within the hour)
// ports: teemoon/Confidential/ProvenanceService.swift @ v1.0.4 (fetchAndVerify asks the guessed repo, then the compose repo)
//        teemoon/Confidential/LauncherIndex.swift + ProvenanceService.swift @ 8cbd15c3 (the launcher's child → index: prod,
//        latest, the newest 200 of one list; the index signed by its builder; a registry that doesn't answer is T2b);
//        teemoon/Confidential/LauncherIndex.swift @ e03c78a5 (an in-org signer that isn't the build is worded apart),
//        teemoon/Confidential/ProvenanceService.swift @ cf8002d5 (the registry's outage is worded as Docker Hub's)
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    /** A HEAD: Docker Hub's manifest digest without the bytes (HEADs do not count against its anonymous pull limit). */
    suspend fun head(url: String, headers: Map<String, String>): ProvenanceResponse = get(url, headers)
}

class OkHttpProvenanceHttp(private val client: OkHttpClient = TeemoonHttp.builder().callTimeout(30, TimeUnit.SECONDS).build()) : ProvenanceHttp {
    override suspend fun get(url: String, headers: Map<String, String>): ProvenanceResponse = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).get(); headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            ProvenanceResponse(r.code, r.headers.names().associate { it.lowercase() to (r.header(it) ?: "") }, r.body.bytes())
        }
    }
    override suspend fun head(url: String, headers: Map<String, String>): ProvenanceResponse = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).head(); headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r -> ProvenanceResponse(r.code, r.headers.names().associate { it.lowercase() to (r.header(it) ?: "") }, ByteArray(0)) }
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
    /**
     * The file is read once per launch; every later load answers from memory. A cold verification asked ~47 times, and
     * each read and decoded the whole file — the typing lag of a cold start on a phone deep in swap (2026-11-06).
     */
    private var memory: Map<String, List<String>>? = null
    @Synchronized override fun load(): Map<String, List<String>> = memory ?: (
        if (!file.exists()) emptyMap() else runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
    ).also { memory = it }
    @Synchronized override fun save(cache: Map<String, List<String>>) {
        memory = cache
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
    /** Where the compose-file work runs: never the session's (main) thread — the cache, the hashes and the parse. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private val globalUpgradeAttempted: MutableSet<String> = Collections.synchronizedSet(HashSet())
        private val globalRecentMisses: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())
        const val MISS_TTL_MS: Long = 3_600_000
        /** Cache key prefix for a child digest's resolved index (child → [index digest]). */
        const val INDEX_KEY = "index:"
        /** Cache key prefix for "no index found" (child → [epoch ms]), asked again after [NO_INDEX_TTL_MS]. */
        const val NO_INDEX_KEY = "noindex:"
        /** (c): "composefile:<commit>/<file>" → [sha256 of the body]. */
        const val COMPOSE_FILE_KEY = "composefile2:"   // 2: the entry carries the service names too
        const val NO_INDEX_TTL_MS: Long = 24 * 3_600_000L
        /** The tag scan's ceiling: the newest this many of one list (iOS LauncherIndex.scanCap). */
        const val MAX_TAGS = 200
        /**
         * The only repositories whose index is looked up on Docker Hub: the maintainer approved the two hosts for the launcher's
         * index alone (audit, 2026-10-31: pull scope repository:nearaidev/compose-manager-launcher, nothing else). Any
         * other image without its own attestation stays the ordinary provenance failure.
         */
        val INDEX_REPOS = setOf("nearaidev/compose-manager-launcher")
        /** Tags tried before any listing, in this order (the maintainer 2026-10-31: "prod/latest first"; iOS LauncherIndex.firstTags). */
        val FIRST_TAGS = listOf("prod", "latest")

        /** The one signer the index's attestation may carry (org-level trust is not enough here; iOS LauncherIndex @ 8cbd15c3). */
        const val BUILDER_REPOSITORY = "https://github.com/nearai/compose-manager"
        const val BUILDER_REF = "refs/heads/master"
        const val BUILDER = "$BUILDER_REPOSITORY/.github/workflows/build.yml@$BUILDER_REF"

        /**
         * [outcome] is the index attestation's verdict; anything not signed by [BUILDER] is refused. An in-org signer is
         * worded apart from an out-of-org one: "outside near.ai's org" would be false for it (iOS e03c78a5).
         */
        fun signedByItsBuilder(outcome: ImageProvenance): ImageProvenance {
            if (outcome !is ImageProvenance.Verified) return outcome
            if (outcome.repositoryURL == BUILDER_REPOSITORY && outcome.workflowIdentity == BUILDER && outcome.sourceRef == BUILDER_REF) return outcome
            val inOrg = BuildIdentityPolicy.nearAI.any { it.repository(outcome.workflowIdentity) != null }
            return ImageProvenance.Unverified(if (inOrg) ImageProvenance.Reason.IdentityNotThisBuild(outcome.workflowIdentity)
                else ImageProvenance.Reason.IdentityNotTrusted(outcome.workflowIdentity))
        }

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

    /**
     * (c) A running service's compose file at its pinned commit, as its body's digest (audit's option A, 2026-11-04). A
     * digest is kept in the provenance cache on disk with no expiry, as (d) keeps an index by digest: a commit's file
     * never changes, and a cold launch refetching up to ten files from a shared IP is where GitHub's rate limit bites
     * first (audit). A 404 or a failed fetch is asked again next time — a network blip must never stand as a verdict,
     * and a file may yet be published.
     */
    suspend fun fetchComposeFile(commit: String, file: String): ComposeFileFetch = withContext(io) {
        val key = "$COMPOSE_FILE_KEY$commit/$file"
        // kept as [digest, service names…]: a whole-file entry's walk reads the names (audit's rule A, 2026-11-06)
        cache.load()[key]?.takeIf { it.isNotEmpty() }?.let { return@withContext ComposeFileFetch.Digest(it[0], it.drop(1)) }
        try {
            val r = http.get(ComposeFiles.url(commit, file), emptyMap())
            when (r.status) {
                200 -> {
                    composeBodies["$commit/$file"] = r.body   // the chat path reads it this launch: never asked twice
                    val digest = sha256Hex(r.body)
                    val names = DeployLog.serviceNames(String(r.body, Charsets.UTF_8))
                    cache.save(cache.load() + (key to listOf(digest) + names))
                    ComposeFileFetch.Digest(digest, names)
                }
                404 -> ComposeFileFetch.NotFound
                else -> ComposeFileFetch.Failed
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { ComposeFileFetch.Failed }
    }

    /** Bodies fetched this launch, for the chat path. Memory only: a file at a commit never changes (iOS b3d90fac). */
    private val composeBodies = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /**
     * A running revision's file, as text, for the chat path and the answering proxy: the same commit-addressed URL as
     * [fetchComposeFile], and only a body that hashes to what the log pins for it. null: not fetched, or not that file.
     */
    suspend fun fetchComposeBody(source: DeployLog.Source): String? = withContext(io) {
        val key = "${source.commit}/${source.file}"
        val bytes = composeBodies[key] ?: try {
            val r = http.get(ComposeFiles.url(source.commit, source.file), emptyMap())
            if (r.status != 200) return@withContext null
            r.body.also { composeBodies[key] = it }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withContext null }
        if (source.fileSha256.isNotEmpty() && !sha256Hex(bytes).equals(source.fileSha256, ignoreCase = true)) return@withContext null
        String(bytes, Charsets.UTF_8)
    }

    /** Verifies provenance for every near.ai image in [manifest]. Fail-closed for near.ai images; third-party sidecars are classified, not verified. */
    suspend fun verifyManifest(manifest: String): ManifestProvenance = verifyManifests(listOf("" to manifest))

    /** Verifies provenance across several host-labeled attested manifests as one set, deduplicated by digest with host membership merged. */
    /** Main-safe: the manifest parse, the cache file and the signature checks run on [VerifyWork]. */
    suspend fun verifyManifests(manifests: List<Pair<String, String>>): ManifestProvenance = withContext(VerifyWork.dispatcher) { verifyManifestsHere(manifests) }

    private suspend fun verifyManifestsHere(manifests: List<Pair<String, String>>): ManifestProvenance {
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
        // A measured CHILD whose attestation was made for its multi-platform index (the launcher, audit 2026-10-30):
        // find the index on Docker Hub and verify ITS attestation (iOS ProvenanceService @ 8cbd15c3). Content addressing
        // makes the lookup safe; Docker Hub not answering is GitHub not answering — never unpublished.
        if (dockerHubRepo(ref.image) in INDEX_REPOS) when (val lookup = resolveIndex(ref)) {
            is IndexLookup.Found -> verifyThroughIndex(ref, lookup.indexDigest, repos)?.let { return it }
            // worded as Docker Hub's outage, still inconclusive (iOS cf8002d5)
            is IndexLookup.Inconclusive -> return ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient(lookup.why, ImageProvenance.TransientSource.DOCKER_HUB))
            IndexLookup.NotFound -> Unit
        }
        synchronized(recentMisses) { recentMisses[ref.digest] = nowMs() }
        return ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404"))
    }

    // MARK: - Child → index (Docker Hub) — iOS LauncherIndex @ 8cbd15c3

    /** What the launcher's child → index lookup found (iOS LauncherIndex.Result). */
    sealed interface IndexLookup {
        /** The index that lists the child for linux/amd64, verified by content hash. */
        data class Found(val indexDigest: String) : IndexLookup
        /** No index near.ai published lists it (within the cap); remembered for a day. */
        data object NotFound : IndexLookup
        /** Network trouble: no evidence either way (T2b) — never unpublished, and no miss is remembered. */
        data class Inconclusive(val why: String) : IndexLookup
    }

    /** The index a child digest was resolved to, from the persistent cache: index bytes are immutable, so is the answer. */
    fun cachedIndex(childDigest: String): String? = cache.load()["$INDEX_KEY$childDigest"]?.firstOrNull()

    /**
     * The verified multi-platform index listing [ref] as its linux/amd64 image, from Docker Hub (the maintainer approved
     * auth.docker.io and registry-1.docker.io for near.ai verification only, 2026-10-30), as iOS LauncherIndex.resolve:
     * an anonymous pull token; `prod`, then `latest` (the maintainer 2026-10-31: "prod/latest first"); then the newest tags
     * of ONE list, capped at [MAX_TAGS]. A HEAD per tag for its digest; each new candidate's bytes are fetched and checked
     * by [ImageIndexResolution] (their hash and media type decide, never the HEAD's content type). The first match is
     * kept by child digest; a clean miss is remembered for a day; any trouble on the way is [IndexLookup.Inconclusive].
     */
    private suspend fun resolveIndex(ref: ImageRef): IndexLookup {
        val child = ref.digest.lowercase()
        cachedIndex(child)?.let { return IndexLookup.Found(it) }
        cache.load()["$NO_INDEX_KEY$child"]?.firstOrNull()?.toLongOrNull()?.let { if (nowMs() - it < NO_INDEX_TTL_MS) return IndexLookup.NotFound }
        val repo = dockerHubRepo(ref.image)?.takeIf { it in INDEX_REPOS } ?: return IndexLookup.NotFound
        val token = runCatching {
            val r = http.get("https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repo:pull", emptyMap())
            if (r.status != 200) null else (Json.parseToJsonElement(r.body.decodeToString()) as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
        }.getOrElse { if (it is CancellationException) throw it; null } ?: return IndexLookup.Inconclusive("couldn't get a registry token")
        val auth = mapOf("Authorization" to "Bearer $token", "Accept" to ImageIndexResolution.INDEX_MEDIA_TYPE)
        val tried = HashSet<String>()
        var trouble = false
        suspend fun check(tag: String): String? {
            val head = try { http.head("https://registry-1.docker.io/v2/$repo/manifests/$tag", auth) } catch (e: CancellationException) { throw e } catch (e: Exception) { trouble = true; return null }
            if (head.status == 404) return null
            val digest = head.headers["docker-content-digest"]?.takeIf { head.status == 200 && it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                ?: run { trouble = true; return null }
            if (!tried.add(digest)) return null
            val body = try { http.get("https://registry-1.docker.io/v2/$repo/manifests/sha256:$digest", auth) } catch (e: CancellationException) { throw e } catch (e: Exception) { trouble = true; return null }
            if (body.status != 200) { trouble = true; return null }
            return digest.takeIf { ImageIndexResolution.check(body.body, digest, child) is BindingCheck.Verified }
        }
        fun found(index: String): IndexLookup {
            cache.save(cache.load() + ("$INDEX_KEY$child" to listOf(index)))
            log("provenance: ${ref.image} ${child.take(8)} → its linux/amd64 index ${index.take(8)}")
            return IndexLookup.Found(index)
        }
        for (tag in FIRST_TAGS) check(tag)?.let { return found(it) }
        // Neither prod nor latest resolved to an index listing the child: the newest tags of one list, capped.
        val tags = try {
            val r = http.get("https://registry-1.docker.io/v2/$repo/tags/list", auth)
            if (r.status != 200) null
            else ((Json.parseToJsonElement(r.body.decodeToString()) as? JsonObject)?.get("tags") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return IndexLookup.Inconclusive("couldn't list the launcher's tags")
        for (tag in tags.sortedDescending().filter { it !in FIRST_TAGS }.take(MAX_TAGS)) check(tag)?.let { return found(it) }
        if (trouble) return IndexLookup.Inconclusive("some tags couldn't be read")
        cache.save(cache.load() + ("$NO_INDEX_KEY$child" to listOf(nowMs().toString())))
        return IndexLookup.NotFound
    }

    /**
     * The index's attestation, from the candidate repos, held to its builder ([signedByItsBuilder]). Null: no candidate
     * repo has it either. A verified answer is cached under the CHILD's digest.
     */
    private suspend fun verifyThroughIndex(ref: ImageRef, index: String, repos: List<String>): ImageProvenance? {
        for (repo in repos) {
            when (val fetched = fetchAttestation(repo, index)) {
                is AttestationFetch.Found -> {
                    val outcome = signedByItsBuilder(verifier.verify(fetched.data, index))
                    if (outcome is ImageProvenance.Verified) {
                        cacheVerification(ref.digest, outcome)
                        log("provenance: ${ref.image} ${ref.digest.take(8)} attested via its linux/amd64 index ${index.take(8)}")
                    }
                    return outcome
                }
                is AttestationFetch.Transient -> return ImageProvenance.Unverified(ImageProvenance.Reason.FetchTransient(fetched.reason))
                AttestationFetch.NotFound -> continue
            }
        }
        return null
    }

    /** `nearaidev/x` or `docker.io/nearaidev/x` → `nearaidev/x`; an image on another registry has no Docker Hub index. */
    private fun dockerHubRepo(image: String): String? {
        val parts = image.split('/')
        return when {
            parts.size == 2 && !parts[0].contains('.') -> image
            parts.size == 3 && (parts[0] == "docker.io" || parts[0] == "index.docker.io") -> "${parts[1]}/${parts[2]}"
            else -> null
        }
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
        // Old digests rotate out of manifests; keep the cache bounded (resolved indexes ride along: they are few).
        if (all.size > 64) all = all.filterKeys { it == digest || it.startsWith(INDEX_KEY) || it.startsWith(NO_INDEX_KEY) }
        cache.save(all)
    }
}
