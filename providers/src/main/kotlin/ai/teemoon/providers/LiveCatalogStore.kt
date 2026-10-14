// ports: teemoon/Providers/LiveCatalogStore.swift @ v1.0.4
package ai.teemoon.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * The last list each server answered with, kept on disk.
 *
 * teemoon ships no model catalogue. A provider's models are whatever its
 * `/models` says today; this store is the copy of that answer, so a count, a
 * picker row and a model's name survive a relaunch and an aeroplane.
 *
 * ONE STORE. Counts, browser rows and "what is this model called" all read it,
 * so they cannot disagree — a second cache of the same numbers is a second answer.
 */
class LiveCatalogStore(
    private val directory: File?,
    /** Test seam; production lists through `ModelCatalog.liveCatalog`. */
    var list: suspend (EndpointModelCatalog.Source, String, String, String?) -> EndpointModelCatalog.ProbeResult =
        { source, base, key, header -> ModelCatalog.liveCatalog(source, base, key, header) },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * A per-install random salt for keyed scopes, kept beside the lists. The scope was an unsalted hash of the key, which
     * a copied file could be checked against and which outlived the key (the local audit, an INFO since 3910,
     * 2026-10-13). Without a directory (tests, previews) it lives for the process.
     */
    private val salt: String by lazy {
        val file = directory?.let { File(it, "scope-salt") }
        file?.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 32 } ?: run {
            val fresh = java.security.SecureRandom().let { r -> ByteArray(16).also(r::nextBytes) }.joinToString("") { "%02x".format(it) }
            runCatching { directory?.mkdirs(); file?.writeText(fresh) }
            fresh
        }
    }

    // Lists saved under the old key-derived scope ("key-…") are swept once, on the first open after the upgrade.
    init { sweepKeyDerivedScopes() }

    private fun sweepKeyDerivedScopes() {
        val files = directory?.listFiles() ?: return
        for (f in files) {
            if (f.extension != "json") continue
            val e = decode(runCatching { f.readText() }.getOrNull() ?: continue) ?: continue
            if (e.scope.startsWith("key-")) f.delete()
        }
    }

    /**
     * One server's last answer. [tiers] is near.ai's `owned_by` map, saved
     * beside the rows: without it a cold launch would label every row from the
     * id guess, which answers "own fleet" for ids it has never seen.
     */
    @Serializable
    data class Entry(
        val version: Int = CURRENT_VERSION,
        val endpoint: String,
        val scope: String,
        val fetchedAtMs: Long,
        val models: List<StoredModel>,
        val tiers: Map<String, NearAIModelCatalog.Confidentiality> = emptyMap(),
    )

    /**
     * A row as it is stored. NOT [KnownModel] itself: that type carries fields
     * derived at fetch time — the New badge, the direct host — and storing them
     * would freeze a judgement that has to be remade when the copy is read back.
     */
    @Serializable
    data class StoredModel(
        val id: String,
        val displayName: String,
        val vendor: String,
        val price: String,
        val contextWindow: String = "",
        val createdEpochSeconds: Long? = null,
        val directBaseURL: String? = null,
        val confidentialityNote: String? = null,
        val isFree: Boolean = false,
        // The rest of the row, as iOS stores the whole model (c3fb76d). Until 2026-10-02 these were dropped on save, so
        // a list read back from disk lost its capabilities, open weights (the open-weights filter and the
        // strongest-open-weight default), aliases and extra costs until the live list landed again. All defaulted:
        // a file written before them decodes unchanged.
        val capabilities: Set<ModelCapability>? = null,
        val openWeights: Boolean? = null,
        val aliasOf: String? = null,
        val extraCosts: List<KnownModel.ExtraCost> = emptyList(),
        val knowledgeCutoff: String? = null,
        val reasoningEfforts: List<String> = emptyList(),
        val summary: String? = null,
        val features: List<String> = emptyList(),
        val samplingParameters: List<String> = emptyList(),
        val maxOutputTokens: Int? = null,
        val inputModalities: List<String> = emptyList(),
        val huggingFaceURL: String? = null,
        val githubURL: String? = null,
        val modelPageURL: String? = null,
        val deprecationEpochSeconds: Long? = null,
        val quantization: String? = null,
        val parameterSize: String? = null,
    ) {
        companion object {
            fun of(m: KnownModel) = StoredModel(
                id = m.id, displayName = m.displayName, vendor = m.vendor, price = m.price,
                contextWindow = m.contextWindow, createdEpochSeconds = m.created?.epochSecond,
                directBaseURL = m.directBaseURL, confidentialityNote = m.confidentialityNote,
                isFree = m.isFree, capabilities = m.capabilities, openWeights = m.openWeights, aliasOf = m.aliasOf,
                extraCosts = m.extraCosts, knowledgeCutoff = m.knowledgeCutoff, reasoningEfforts = m.reasoningEfforts,
                summary = m.summary, features = m.features, samplingParameters = m.samplingParameters,
                maxOutputTokens = m.maxOutputTokens, inputModalities = m.inputModalities, huggingFaceURL = m.huggingFaceURL,
                githubURL = m.githubURL, modelPageURL = m.modelPageURL, deprecationEpochSeconds = m.deprecationDate?.epochSecond,
                quantization = m.quantization, parameterSize = m.parameterSize)
        }
    }

    /**
     * A server, plus WHOSE list it is. A keyed catalogue can differ per account
     * (Fireworks lists what the key is entitled to), so those entries are scoped
     * by a hash of the key — never the key itself.
     */
    data class Key(val endpoint: String, val scope: String)

    private val entries = LinkedHashMap<Key, Entry>()
    private val loaded = HashSet<Key>()
    private val inFlight = HashMap<Key, CompletableDeferred<List<KnownModel>?>>()

    /**
     * Bumped by [forget], per endpoint. A refresh started before a forget saves nothing: its list answers for the key or
     * endpoint just removed, and a save would put it back on disk under the old scope — or, through [pruneOtherScopes],
     * delete the new key's list. The list takes seconds, the forget is a tap (the local audit of 5af7d25, 2026-10-14).
     */
    private val generation = HashMap<String, Long>()

    /**
     * Bumped on every write. iOS gets redraws from `@Observable`; a composable
     * that shows a count or a saved row reads this so it redraws when a fetch
     * lands behind it.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    // MARK: - Reading

    /** The saved rows for this server, or [] when it has never answered. */
    fun models(forProvider: Provider, apiKey: String): List<KnownModel> {
        val key = key(forProvider, apiKey) ?: return emptyList()
        return synchronized(this) { entry(key)?.let { e -> e.models.map { it.toKnownModel(e) } } } ?: emptyList()
    }

    /** null, never 0: a row prints nothing rather than a number reading as "this server has no models". */
    fun count(forProvider: Provider, apiKey: String): Int? {
        val key = key(forProvider, apiKey) ?: return null
        val e = synchronized(this) { entry(key) } ?: return null
        return e.models.size.takeIf { it > 0 }
    }

    fun model(id: String, forProvider: Provider, apiKey: String): KnownModel? =
        models(forProvider, apiKey).firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * Every saved row for an endpoint, whichever key fetched it. For naming a
     * setup, where the question is "is this word one of its models".
     */
    fun allModels(forEndpoint: String): List<KnownModel> {
        val target = Provider.presetMatchKey(forEndpoint)
        return synchronized(this) {
            loadAll()
            entries.filterKeys { it.endpoint == target }.values
                .flatMap { e -> e.models.map { it.toKnownModel(e) } }
        }
    }

    // MARK: - Writing

    /** Records an answer teemoon already has, so every door updates the same copy. */
    fun record(models: List<KnownModel>, forProvider: Provider, apiKey: String) {
        recordIf(models, forProvider, apiKey, startedAt = null)
    }

    /** [record], unless [forget] dropped the endpoint after [startedAt] (its generation then). Returns whether it saved. */
    private fun recordIf(models: List<KnownModel>, forProvider: Provider, apiKey: String, startedAt: Long?): Boolean {
        if (models.isEmpty()) return false
        val key = key(forProvider, apiKey) ?: return false
        val tiers = if (isNearAI(forProvider)) NearAIModelCatalog.knownTiers(models.map { it.id }) else emptyMap()
        val entry = Entry(
            endpoint = key.endpoint, scope = key.scope, fetchedAtMs = clockMs(),
            models = models.map { StoredModel.of(it) }, tiers = tiers)
        synchronized(this) {
            if (startedAt != null && generation[key.endpoint] ?: 0L != startedAt) return false
            entries[key] = entry
            loaded.add(key)
            pruneOtherScopes(key)
            write(entry, key)
        }
        _revision.value += 1
        return true
    }

    /**
     * Lists the server when the saved copy is older than [maxAgeMs], and saves
     * what comes back. A failure keeps the copy; the caller shows it either way.
     * Concurrent callers share one fetch.
     */
    suspend fun refresh(provider: Provider, apiKey: String, maxAgeMs: Long): List<KnownModel>? {
        val key = key(provider, apiKey) ?: return null
        val source = WhereProviderPresentation.liveCatalogSource(provider) ?: return null
        val base = provider.openAIBaseUrl ?: return null
        val (job, owner, startedAt) = synchronized(this) {
            entry(key)?.let { e -> if (clockMs() - e.fetchedAtMs < maxAgeMs) return e.models.map { it.toKnownModel(e) } }
            val gen = generation[key.endpoint] ?: 0L
            inFlight[key]?.let { return@synchronized Triple(it, false, gen) }
            Triple(CompletableDeferred<List<KnownModel>?>().also { inFlight[key] = it }, true, gen)
        }
        if (!owner) return job.await()
        val models = try {
            // A public list is read without a key: counting OpenRouter's catalogue
            // must not hand it a credential it did not ask for.
            val sent = if (listIsPublic(source)) "" else apiKey
            // a list for an endpoint forgotten while it was being listed is neither saved nor shown
            (list(source, base, sent, provider.authHeaderName) as? EndpointModelCatalog.ProbeResult.Connected)
                ?.models?.takeIf { it.isNotEmpty() }
                ?.takeIf { recordIf(it, provider, apiKey, startedAt) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(this) { inFlight.remove(key) }
            job.complete(null)
            throw e
        }
        synchronized(this) { inFlight.remove(key) }
        job.complete(models)
        return models
    }

    /**
     * Brings every configured server's list up to date, at most one fetch each.
     * Only servers the user has set up: opening a sheet is not consent to
     * contact a vendor they never chose.
     */
    suspend fun refreshAll(providers: List<Provider>, maxAgeMs: Long, credential: (Provider) -> String) {
        val seen = HashSet<Key>()
        val jobs = providers.mapNotNull { provider ->
            val apiKey = credential(provider)
            val storeKey = key(provider, apiKey) ?: return@mapNotNull null
            val source = WhereProviderPresentation.liveCatalogSource(provider) ?: return@mapNotNull null
            if (!seen.add(storeKey)) return@mapNotNull null
            // A custom endpoint has no catalogue to keep fresh: a browse tap is
            // the only consent to send its key to `/models`.
            if (source == EndpointModelCatalog.Source.GENERIC) return@mapNotNull null
            // A keyed list 401s without one, so there is nothing to ask for yet.
            if (!listIsPublic(source) && apiKey.isEmpty()) return@mapNotNull null
            provider to apiKey
        }
        coroutineScope { jobs.map { (p, k) -> async { refresh(p, k, maxAgeMs) } }.awaitAll() }
    }

    /** Drops a server's saved lists — EVERY scope, so removing a key cannot leave the old account's models on screen. */
    fun forget(endpoint: String) {
        val target = Provider.presetMatchKey(endpoint)
        synchronized(this) {
            generation[target] = (generation[target] ?: 0L) + 1
            loadAll()
            for (k in entries.keys.filter { it.endpoint == target }.toList()) {
                entries.remove(k)
                fileFor(k)?.delete()
            }
        }
        _revision.value += 1
    }

    /**
     * near.ai tiers from the saved lists, so a cold launch labels rows from what
     * near.ai SAID rather than from the id guess.
     */
    fun seedNearAITiers() {
        val all = synchronized(this) { loadAll(); entries.values.toList() }
        for (e in all) if (e.tiers.isNotEmpty()) NearAIModelCatalog.recordTiers(e.tiers)
    }

    // MARK: - Keys and storage

    /** null when there is no server list to ask for (on-device, Brave). */
    fun key(forProvider: Provider, apiKey: String): Key? {
        val source = WhereProviderPresentation.liveCatalogSource(forProvider) ?: return null
        val scope = if (listIsPublic(source)) "public" else scopeHash(apiKey, salt)
        return Key(forProvider.presetMatchKey, scope)
    }

    private fun entry(key: Key): Entry? {
        if (loaded.add(key)) read(key)?.let { entries[key] = it }
        return entries[key]
    }

    private fun loadAll() {
        val files = directory?.listFiles() ?: return
        for (f in files) {
            if (f.extension != "json") continue
            val e = decode(runCatching { f.readText() }.getOrNull() ?: continue) ?: continue
            val key = Key(e.endpoint, e.scope)
            if (loaded.add(key)) entries[key] = e
        }
    }

    private fun decode(text: String): Entry? {
        val entry = runCatching { json.decodeFromString(Entry.serializer(), text) }.getOrNull() ?: return null
        if (entry.version != CURRENT_VERSION) return null
        return entry
    }

    private fun read(key: Key): Entry? =
        fileFor(key)?.takeIf { it.isFile }?.let { decode(runCatching { it.readText() }.getOrNull() ?: return null) }

    private fun write(entry: Entry, key: Key) {
        val dir = directory ?: return
        val file = fileFor(key) ?: return
        // Atomic, as iOS writes `.atomic`: a torn file decodes as nothing, and
        // the server's list would silently vanish until the next fetch. The
        // directory is `noBackupFilesDir` in the app — iOS excludes it from backup.
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Entry.serializer(), entry))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { System.err.println("[catalog] could not save ${key.endpoint}: $it") }
    }

    /** One account's list replaces another's; two keys' entitlements must not stack up as one catalogue. */
    private fun pruneOtherScopes(key: Key) {
        loadAll()
        for (other in entries.keys.filter { it.endpoint == key.endpoint && it.scope != key.scope }.toList()) {
            entries.remove(other)
            fileFor(other)?.delete()
        }
    }

    private fun fileFor(key: Key): File? =
        directory?.let { File(it, hex(key.endpoint + "|" + key.scope) + ".json") }

    /**
     * A row as it is READ BACK, with the two derived fields remade.
     *
     * Badges age even while the copy sits on disk, and a near.ai row's direct
     * host belongs to the directory rather than to a month-old list — so both
     * are recomputed here instead of being trusted from the file.
     */
    private fun StoredModel.toKnownModel(entry: Entry): KnownModel {
        val created = createdEpochSeconds?.let { Instant.ofEpochSecond(it) }
        // EVERY own-fleet row, not only those that had a host: a list built while
        // the directory was cold has none, and must not stay that way once the
        // directory is on disk.
        //
        // Own fleet by THIS ENTRY's saved tiers (what near.ai said), not the
        // process-wide guess: the guess answers "own fleet" for any id it has not
        // seen, so an OpenRouter row named like a near.ai model would be handed
        // near.ai's confidential host. Only a near.ai entry carries tiers.
        val ownFleet = entry.tiers[id.lowercase()] == NearAIModelCatalog.Confidentiality.TEE_OWN
        val host = if (directBaseURL != null || ownFleet) EndpointDirectory.persistedBase(id) else null
        return KnownModel(
            id = id, displayName = displayName, vendor = vendor, price = price,
            contextWindow = contextWindow, isNew = ModelCatalog.isNew(created),
            created = created, isFree = isFree,
            directBaseURL = host ?: directBaseURL,
            confidentialityNote = confidentialityNote,
            capabilities = capabilities, openWeights = openWeights, aliasOf = aliasOf, extraCosts = extraCosts,
            knowledgeCutoff = knowledgeCutoff, reasoningEfforts = reasoningEfforts, summary = summary, features = features,
            samplingParameters = samplingParameters, maxOutputTokens = maxOutputTokens, inputModalities = inputModalities,
            huggingFaceURL = huggingFaceURL, githubURL = githubURL, modelPageURL = modelPageURL,
            deprecationDate = deprecationEpochSeconds?.let { Instant.ofEpochSecond(it) },
            quantization = quantization, parameterSize = parameterSize)
    }

    companion object {
        const val CURRENT_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Lists that answer without a credential. Everything else is ONE
         * account's view and is stored under that account only.
         */
        fun listIsPublic(source: EndpointModelCatalog.Source): Boolean = when (source) {
            EndpointModelCatalog.Source.NEAR_AI,
            EndpointModelCatalog.Source.OPEN_ROUTER,
            EndpointModelCatalog.Source.NVIDIA -> true
            EndpointModelCatalog.Source.XAI,
            EndpointModelCatalog.Source.FIREWORKS,
            EndpointModelCatalog.Source.GENERIC -> false
        }

        /**
         * A KEY NEVER LANDS ON DISK — not in a filename, not in a file's contents, and not as a hash that can be
         * checked against it: the per-install [salt] goes in first.
         */
        fun scopeHash(apiKey: String, salt: String): String {
            val trimmed = apiKey.trim()
            if (trimmed.isEmpty()) return "keyless"
            return "scope-" + hex("$salt|$trimmed").take(16)
        }

        fun hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun isNearAI(p: Provider): Boolean =
            WhereProviderPresentation.liveCatalogSource(p) == EndpointModelCatalog.Source.NEAR_AI
    }
}
