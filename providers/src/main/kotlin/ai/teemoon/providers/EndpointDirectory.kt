// ports: teemoon/Providers/EndpointDirectory.swift @ v1.0.4
package ai.teemoon.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.URI

/**
 * near.ai's authoritative model → direct-completions-host directory,
 * published (unauthenticated) at https://completions.near.ai/endpoints as
 * `{ "endpoints": [ { "domain": "<slug>.completions.near.ai",
 *                     "models": ["vendor/Model", …] }, … ] }`.
 *
 * This is the source of truth for the per-model direct TEE host used by the
 * E2EE key fetch. Resolution order:
 *   1. the fetched directory (cached in-memory + persisted for offline),
 *   2. the persisted last-good copy,
 *   3. null — and for an own-fleet model that means DEGRADED, never
 *      "ordinary model, send in the clear" (ConfidentialSession.outcomeWhenNoDirectHost).
 * Every resolved host is still model_name-verified at fetch time, so a stale
 * directory can never bind E2EE to the wrong model. Hosts are accepted only
 * under near.ai over https: this map picks where sealed traffic goes.
 *
 * The shipped KnownModel.directBaseURL column is no longer consulted — it was a
 * frozen copy of a fetch, and a stale host is worse than no host now that a
 * missing one degrades rather than reading as ordinary.
 */
class EndpointDirectory(
    private val fetch: suspend () -> Pair<Int, ByteArray>? = { liveFetch() },
    private val store: BytesStore? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()
    private val ttlMs = 3_600_000L

    /**
     * A failed fetch is not retried for a minute: `buildModels` asks once per
     * own-fleet row, and a blackholed host would otherwise cost a timeout each.
     */
    private val failureTtlMs = 60_000L

    /** model id (lowercased) → "https://<domain>/v1" */
    private var cache: Map<String, String> = emptyMap()
    private var lastLoadMs: Long? = null
    private var lastFailureMs: Long? = null

    /**
     * The direct-completions base URL (`https://<domain>/v1`) for [model],
     * or null if the model has no direct host. The persisted copy answers
     * while the network does not.
     */
    suspend fun directBase(forModel: String): String? = lock.withLock {
        loadIfNeeded()
        cache[forModel.lowercase()]?.let { return it }
        return persistedBase(forModel)
    }

    private suspend fun loadIfNeeded() {
        val now = clockMs()
        val last = lastLoadMs
        if (last != null && now - last < ttlMs && cache.isNotEmpty()) return
        val failed = lastFailureMs
        if (failed != null && now - failed < failureTtlMs) return
        // Seed from the persisted snapshot first so an offline / slow launch
        // still resolves recently-seen models.
        if (cache.isEmpty()) {
            val saved = store?.load()
            if (saved != null) {
                val parsed = parseDirectory(saved)
                if (!parsed.isNullOrEmpty()) cache = parsed
            }
        }
        val fetched = fetch()
        if (fetched != null) {
            val (status, data) = fetched
            if (status == 200) {
                val parsed = parseDirectory(data)
                if (!parsed.isNullOrEmpty()) {
                    cache = parsed
                    lastLoadMs = clockMs()
                    lastFailureMs = null
                    store?.save(data)
                    rememberPersisted(parsed)
                    return
                }
            }
        }
        lastFailureMs = clockMs()
        // Directory unavailable — keep whatever was cached or persisted.
    }

    interface BytesStore {
        fun load(): ByteArray?
        fun save(data: ByteArray)
    }

    class FileStore(private val file: File) : BytesStore {
        override fun load(): ByteArray? = if (file.exists()) file.readBytes() else null
        /** Atomic: a torn directory file would read as no hosts at all. */
        override fun save(data: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        const val DIRECTORY_URL = "https://completions.near.ai/endpoints"

        private val json = Json { ignoreUnknownKeys = true }
        // LAZY: an OkHttpClient built at class-init runs OkHttp's platform probe, which fails under Paparazzi's layoutlib classloader and poisons the class for every later test.
        private val liveClient by lazy { TeemoonHttp.client() }

        /**
         * Parses the directory JSON into a model-id(lowercased) → "<base>/v1" map.
         * Pure so it is unit-testable against a captured snapshot.
         */
        fun parseDirectory(data: ByteArray): Map<String, String>? {
            val dir = runCatching {
                json.decodeFromString(Directory.serializer(), data.decodeToString())
            }.getOrNull() ?: return null
            val map = LinkedHashMap<String, String>()
            for (endpoint in dir.endpoints) {
                // near.ai over https only. These hosts receive sealed prompts and
                // the API key on the signature re-fetch, so a directory that ever
                // named something else must not be able to redirect either.
                val host = confidentialHost(endpoint.domain) ?: continue
                for (model in endpoint.models) map[model.lowercase()] = "$host/v1"
            }
            return map
        }

        /**
         * "<slug>.completions.near.ai" → "https://<slug>.completions.near.ai".
         * null for any other host, any scheme but https, and anything carrying a
         * port or a path.
         *
         * A rejected row is DROPPED, not kept — one bad entry must not poison the
         * map, and must not be silently downgraded to http either.
         */
        fun confidentialHost(domain: String): String? {
            val raw = if (domain.contains("://")) domain else "https://$domain"
            val url = runCatching { URI(raw) }.getOrNull() ?: return null
            if (url.scheme?.lowercase() != "https") return null
            if (url.port != -1) return null
            if (!(url.rawPath.isNullOrEmpty() || url.rawPath == "/")) return null
            if (url.rawQuery != null || url.rawFragment != null || url.rawUserInfo != null) return null
            val host = url.host ?: return null
            // iOS spells this `Provider.isNearAIHost(host)` (WhereLocality.swift),
            // which is exactly this resolve. That file is not ported, so the
            // expression is inlined rather than inventing a Kotlin API for it —
            // the rule it enforces is the same: near.ai and its subdomains only,
            // so `near.ai.evil.com` and `api.linear.ai` are not hosts for sealed traffic.
            if (EndpointModelCatalog.Source.resolve(host) != EndpointModelCatalog.Source.NEAR_AI) return null
            return "https://$host"
        }

        /**
         * Synchronous last-good answer, for the inference URL, which cannot
         * suspend. Decoded once per process — it is read on every send and on
         * every render of the verification screen.
         *
         * Process-wide rather than per-instance on purpose: the value is the
         * same for every instance, and the whole point is to answer without a
         * fetch or a lock hop on the send path.
         */
        fun persistedBase(forModel: String): String? = synchronized(persistedLock) {
            // Lazily from disk, as iOS reads UserDefaults on first use: the send
            // path asks before anything in this process has fetched, and without
            // the saved copy a cold launch would route every own-fleet model to
            // the gateway until the first attestation happened to load it.
            if (persisted == null) persistedStore?.let { persisted = it.load()?.let(::parseDirectory) ?: emptyMap() }
            persisted?.get(forModel.lowercase())
        }

        /**
         * Where the process-wide copy lives — the app's one directory file, set
         * once at startup. null (tests, previews) keeps the copy in memory only.
         */
        fun configurePersistence(store: BytesStore?) = synchronized(persistedLock) {
            persistedStore = store
            persisted = null
        }

        /** Keeps the synchronous copy in step with a fetch. */
        fun rememberPersisted(map: Map<String, String>) = synchronized(persistedLock) {
            persisted = map
        }

        /** Test seam: forget the process-wide copy between cases. */
        fun resetPersisted() = synchronized(persistedLock) { persisted = null; persistedStore = null }

        private val persistedLock = Any()
        private var persisted: Map<String, String>? = null
        private var persistedStore: BytesStore? = null

        private suspend fun liveFetch(): Pair<Int, ByteArray>? = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(DIRECTORY_URL).get().build()
            val client = liveClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    resp.code to resp.body.bytes()
                }
            }.getOrNull()
        }
    }

    @Serializable
    private data class Directory(val endpoints: List<Endpoint>) {
        @Serializable
        data class Endpoint(val domain: String, val models: List<String> = emptyList())
    }
}
