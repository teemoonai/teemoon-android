// ports: teemoon/Providers/NearAIModelCatalog.swift @ v1.0.4
package ai.teemoon.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * near.ai's model list, built from the LIVE `/v1/models` — the only source.
 * Direct TEE hosts come from [EndpointDirectory]; tiers come from `owned_by`
 * and are what every E2EE claim rests on. When the fetch fails, the caller
 * falls back to the last saved answer (LiveCatalogStore), never to a list
 * compiled into the app.
 */
object NearAIModelCatalog {

    // MARK: - Confidentiality tier
    //
    // near.ai serves models in three tiers, distinguished by `/v1/models`'
    // `owned_by`: its own attested GPU fleet, an attested third party (Chutes),
    // and a plain proxy to an upstream vendor (Anthropic / OpenAI / Google) that
    // runs in NO enclave. Only the first two are attestable — claiming
    // attestation for a proxied model would be proof that isn't there.

    enum class Confidentiality(
        /**
         * ONE VOCABULARY for the tier, so a section header, a row tag and the
         * model detail page cannot describe the same guarantee three ways.
         * It was a private helper in the model browser, which is why nothing
         * else could say it without restating it.
         */
        val label: String,
    ) {
        /** near.ai's own attested enclave. */
        TEE_OWN("end-to-end encrypted"),
        /** Attested, third-party hardware (Chutes). */
        TEE_THIRD_PARTY("attested on third-party hardware"),
        /** Passthrough to an upstream API — no enclave. */
        PROXIED("proxied");

        /** Whether a model in this tier can present a verifiable attestation. */
        val isAttestable: Boolean get() = this != PROXIED
    }

    /**
     * Cache of authoritative tiers keyed off the live `/v1/models` `owned_by`,
     * so the (synchronous) capability gate can consult the catalog once it has
     * loaded. Before then, [classify] provides an id-based fallback.
     */
    private val tierCache = HashMap<String, Confidentiality>()
    private val tierLock = Any()

    fun recordTiers(tiers: Map<String, Confidentiality>) {
        if (tiers.isEmpty()) return
        synchronized(tierLock) {
            for ((id, tier) in tiers) tierCache[id.lowercase()] = tier
        }
    }

    /**
     * What the catalogue has actually SAID about these ids, for saving beside a
     * list. Only ids near.ai answered for: an id the guess merely assumed must
     * not be written down as though it were told to us — a saved guess read back
     * later is indistinguishable from an authoritative answer, and this map is
     * what seeds the tier cache on a cold launch.
     */
    fun knownTiers(forIDs: List<String>): Map<String, Confidentiality> = synchronized(tierLock) {
        val out = LinkedHashMap<String, Confidentiality>()
        for (id in forIDs) tierCache[id.lowercase()]?.let { out[id.lowercase()] = it }
        out
    }

    /** Test hook: clear the authoritative cache so [classify] is exercised. */
    fun resetTierCache() { synchronized(tierLock) { tierCache.clear() } }

    /**
     * The confidentiality tier for a model id — authoritative cache first
     * (populated from the live catalog's `owned_by`), else the id heuristic.
     */
    fun confidentiality(id: String): Confidentiality {
        // THE ID VETOES THE CATALOGUE, one way only: down.
        //
        // `owned_by` is near.ai's own claim and it is not always right. Observed
        // live 2026-08-02: `anthropic/claude-sonnet-5` came back
        // `owned_by: "nearai"` while `claude-sonnet-4-5` and `-4-6` came back
        // `"anthropic"`. The cache is consulted before the heuristic, so that
        // one field promoted a PROXIED Anthropic model to TEE_OWN and teemoon
        // told the user "end-to-end encrypted" about a passthrough to a closed
        // frontier API.
        //
        // Anthropic, OpenAI and Google do not release these weights, so no
        // third party can be running them in its own enclave. When the id names
        // one of those vendors, no upstream field may upgrade the tier —
        // teemoon must never claim a stronger guarantee than the evidence
        // supports, and a wrong "end-to-end encrypted" is the worst thing this
        // app can say.
        //
        // Downgrades still work: the cache can move a model teemoon guessed was
        // own-fleet to third-party or proxied. Only the promotion is refused.
        val byID = classify(id)
        if (byID == Confidentiality.PROXIED) return Confidentiality.PROXIED
        val cached = synchronized(tierLock) { tierCache[id.lowercase()] }
        if (cached != null) return cached
        return byID
    }

    /**
     * True when a near.ai model runs in an attested enclave (own or third-party)
     * rather than a plain proxy. The single gate for claiming attestation/E2EE.
     */
    fun isAttestable(id: String): Boolean = confidentiality(id).isAttestable

    /**
     * Maps `/v1/models` `owned_by` to a tier. `nearai` → own fleet; a marker of
     * third-party attestation (Chutes) → third-party; any real upstream vendor
     * name → proxied. null when `owned_by` is absent (caller keeps the heuristic).
     */
    fun tierFromOwnedBy(ownedBy: String?): Confidentiality? {
        val o = ownedBy?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (o == "nearai" || o == "near" || o == "near.ai") return Confidentiality.TEE_OWN
        if (o.contains("chutes") || o.contains("3p") || o.contains("attested")) return Confidentiality.TEE_THIRD_PARTY
        return Confidentiality.PROXIED
    }

    /**
     * Id-based fallback tier, used before the live catalog has loaded. near.ai
     * proxies the closed frontier APIs (Claude, GPT-5.x/4.x/o-series, Gemini,
     * Qwen-Max); everything else it lists is open-weight and self-hosted in a
     * TEE. gpt-oss and gemma are open-weight and checked first so the broader
     * vendor rules don't misfile them as proxied.
     */
    fun classify(id: String): Confidentiality {
        val l = id.lowercase()
        // Known third-party "attested" ids (Chutes — anonymous; near.ai exposes
        // no confidential endpoint for them, so no E2EE path via near.ai).
        // An exact-id set because their lowercased ids collide with the
        // own-fleet namespace (`qwen/…` vs `Qwen/…`).
        if (KnownModel.nearAIAttestedThirdPartyIDs.any { it.lowercase() == l }) {
            return Confidentiality.TEE_THIRD_PARTY
        }
        if (l.contains("gpt-oss") || l.contains("gemma")) return Confidentiality.TEE_OWN
        if (l.contains("claude") || l.contains("anthropic")) return Confidentiality.PROXIED
        if (l.contains("gemini")) return Confidentiality.PROXIED
        if (l.contains("openai/") || l.contains("gpt-") || l.startsWith("gpt")) return Confidentiality.PROXIED
        if (l.startsWith("o3") || l.startsWith("o4") || l.contains("/o3") || l.contains("/o4")) return Confidentiality.PROXIED
        if (l.contains("-max") || l.contains(".max")) return Confidentiality.PROXIED
        // Vendor namespaces near.ai only proxies. `deepseek/…` is proxied,
        // `deepseek-ai/…` is a near.ai node — keep the Python mirror in step.
        // `typesafe/…` (jev) is proxied live (owned_by, 2026-10-02): filed own-fleet offline it read end-to-end
        // encrypted until the live tiers arrived — sending stayed fail-closed, the screen over-claimed.
        if (l.startsWith("x-ai/") || l.startsWith("deepseek/") || l.startsWith("typesafe/")) return Confidentiality.PROXIED
        return Confidentiality.TEE_OWN
    }

    // Identity/formatting helpers are catalog-generic — see [ModelCatalog],
    // which every adapter shares. Kept here as forwarders so near.ai call sites
    // (and its tests) read as before.

    fun isNonChat(id: String): Boolean = ModelCatalog.isNonChat(id)

    /** Display vendor label for a model id (`vendor/name` namespace, else family). */
    fun vendorLabel(id: String): String = ModelCatalog.vendorLabel(id)

    /** The last path component of the id, used as a display name for models the curated list doesn't cover. */
    fun displayName(id: String): String = ModelCatalog.displayName(id)

    /**
     * Whether two model ids belong to DIFFERENT vendors (both must resolve; a
     * null id yields false — can't tell). Vendor labels normalize namespace
     * aliases (`z-ai`/`zai-org` → Z.ai), so alias spellings of the same model
     * are NOT a mismatch. Used to reject a reused near.ai node whose
     * compose-manager action log's latest `compose_up` points at a DIFFERENT
     * model's YAML than the one requested (the DeepSeek-host-shows-Qwen bug).
     */
    fun differentVendor(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return !vendorLabel(a).equals(vendorLabel(b), ignoreCase = true)
    }

    /**
     * Slug = last path component, lowercased, minus a quant suffix — the
     * vendor-agnostic model key used to match live ids to curated metadata.
     * (A curated "glm-5.1-fp8" and a live "glm-5.1" must produce the SAME slug;
     * that drift is what made the default model flicker.) See [ModelCatalog].
     */
    fun slug(id: String): String = ModelCatalog.slug(id)

    /**
     * The rows an "end-to-end encrypted" pick may offer: near.ai's own fleet
     * only, and only where near.ai publishes a direct host — that host is where
     * the Ed25519 key binding comes from, so it IS "E2EE works". Attested
     * third-party (Chutes) hardware passes [Confidentiality.isAttestable] and has
     * no confidential endpoint, which is the overclaim this screen exists to avoid.
     */
    fun encryptedChoices(from: List<KnownModel>): List<KnownModel> =
        // The directory answers for a row built while it was cold.
        from.filter {
            (it.directBaseURL != null || EndpointDirectory.persistedBase(it.id) != null) &&
                confidentiality(it.id) == Confidentiality.TEE_OWN &&
                !isNonChat(it.id)
        }

    /**
     * Live ids → rows, ordered for a person reading the list: what teemoon can
     * seal first, then what near.ai attests on someone else's hardware, then the
     * plain proxies; newest first inside each tier.
     *
     * This replaced ordering by the curated snapshot's vendor families. There is
     * no curated list any more — the server's answer is the whole row — so the
     * only stable order left is the one the reader actually cares about, and
     * ties keep the server's order rather than inventing one.
     */
    fun ordered(models: List<KnownModel>): List<KnownModel> {
        fun rank(m: KnownModel) = when (confidentiality(m.id)) {
            Confidentiality.TEE_OWN -> 0
            Confidentiality.TEE_THIRD_PARTY -> 1
            Confidentiality.PROXIED -> 2
        }
        return models.withIndex().sortedWith(Comparator { a, b ->
            val ra = rank(a.value); val rb = rank(b.value)
            if (ra != rb) return@Comparator ra - rb
            val x = a.value.created; val y = b.value.created
            when {
                x != null && y != null && x != y -> y.compareTo(x)   // newest first
                x == null && y != null -> 1
                x != null && y == null -> -1
                else -> a.index - b.index                            // keep the server's order
            }
        }).map { it.value }
    }

    // MARK: - /v1/models wire contract
    //
    // near.ai's `/v1/models` is an OpenAI-compatible list, EXTENDED with the metadata
    // teemoon needs — pricing, context, capabilities, tier — so the live response is
    // the whole row. The one thing it does not give is the direct TEE host, which
    // comes from `/endpoints`.
    // The endpoint is PUBLIC (no key required). Full shape modeled per project rule.
    // Docs: https://docs.near.ai — GET https://cloud-api.near.ai/v1/models.

    @Serializable
    data class ModelsResponse(
        val `object`: String? = null,
        val data: List<Model>,
    ) {
        @Serializable
        data class Model(
            val id: String,
            val `object`: String? = null,
            val created: Long? = null,
            @SerialName("owned_by") val ownedBy: String? = null,
            val name: String? = null,
            val description: String? = null,
            val pricing: Pricing? = null,
            @SerialName("context_length") val contextLength: Int? = null,
            @SerialName("max_output_length") val maxOutputLength: Int? = null,
            val architecture: Architecture? = null,
            @SerialName("input_modalities") val inputModalities: List<String>? = null,
            @SerialName("output_modalities") val outputModalities: List<String>? = null,
            @SerialName("supported_sampling_parameters") val supportedSamplingParameters: List<String>? = null,
            @SerialName("supported_features") val supportedFeatures: List<String>? = null,
            /** near.ai publishes both, and teemoon rendered neither: the weights' precision, and the repo they came from. */
            val quantization: String? = null,
            @SerialName("hugging_face_id") val huggingFaceId: String? = null,
            @SerialName("is_ready") val isReady: Boolean? = null,
            @SerialName("top_provider") val topProvider: TopProvider? = null,
        ) {
            /**
             * `created` is a unix timestamp; 0/absent means no date was reported,
             * and [ModelCatalog.isNew] then declines to badge rather than guessing.
             * Same shape and same treatment as xAI's.
             */
            val createdDate: Instant?
                get() = created?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
        }

        /** Per-1M-token prices as Doubles (`input`/`output`), plus the raw per-token strings near.ai also returns. */
        @Serializable
        data class Pricing(
            val input: Double? = null,
            val output: Double? = null,
            val prompt: String? = null,
            val completion: String? = null,
            val image: String? = null,
            val request: String? = null,
            @SerialName("input_cache_read") val inputCacheRead: String? = null,
        )

        @Serializable
        data class Architecture(
            val inputModalities: List<String>? = null,
            val outputModalities: List<String>? = null,
        )

        @Serializable
        data class TopProvider(
            @SerialName("context_length") val contextLength: Int? = null,
            @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
            @SerialName("is_moderated") val isModerated: Boolean? = null,
        )
    }

    private val wire = Json { ignoreUnknownKeys = true }

    /**
     * The authoritative model → direct-host directory, consulted by
     * [buildModels] for attestable models. iOS uses `EndpointDirectory.shared`;
     * here the app installs the instance it wants (a persisted store, a stub in
     * tests) and the catalogue reads whichever is current.
     */
    @Volatile
    var endpointDirectory: EndpointDirectory = EndpointDirectory()

    /** Decodes a `/v1/models` body. Throws on a malformed body, as the Swift decoder does. */
    fun decode(body: String): ModelsResponse = wire.decodeFromString(ModelsResponse.serializer(), body)

    /** One HTTP reply, as the injected fetch reports it. */
    data class HttpReply(val status: Int, val body: String)

    /**
     * Fetches `/v1/models` and builds the browser list from LIVE metadata (price,
     * context, capabilities, tier), or null if unavailable (caller falls back to the
     * curated list). No key required — sends one only if the caller has it.
     *
     * The HTTP call is INJECTED: this module's catalogues take their transport
     * from the caller (EndpointProbe injects its adapters the same way). iOS
     * gives the request a 10-second timeout; the fetch seam owns that here.
     * [directBase] is the endpoints-directory lookup, see [buildModels].
     */
    suspend fun fetchLive(
        apiKey: String = "",
        fetch: suspend (url: String, bearer: String?) -> HttpReply?,
        directBase: suspend (String) -> String? = { endpointDirectory.directBase(it) },
        warn: (String) -> Unit = {},
    ): List<KnownModel>? {
        val reply = fetch(ProviderKeyValidator.NEAR_AI_MODELS_URL, apiKey.takeIf { it.isNotEmpty() })
        if (reply == null || reply.status != 200) {
            warn("[catalog] /v1/models unavailable — no live list this refresh")
            return null
        }
        val list = runCatching { decode(reply.body) }.getOrNull()
        if (list == null || list.data.isEmpty()) {
            warn("[catalog] /v1/models decode failed — no live list this refresh")
            return null
        }
        // Record authoritative confidentiality tiers from `owned_by` so the capability
        // gate stops claiming attestation for proxied models.
        val tiers = HashMap<String, Confidentiality>()
        for (model in list.data) {
            tierFromOwnedBy(model.ownedBy)?.let { tiers[model.id.lowercase()] = it }
        }
        recordTiers(tiers)

        val models = buildModels(list.data, directBase = directBase)
        return models.ifEmpty { null }
    }

    /**
     * Builds [KnownModel]s straight from the live `/v1/models` metadata. The only
     * thing the endpoint doesn't provide is the E2EE `directBaseURL`, resolved from
     * the authoritative endpoints directory or, failing that, the curated snapshot.
     *
     * [now] is injectable so the recency badge can be tested against a captured
     * response instead of drifting with the clock. [directBase] defaults to
     * [endpointDirectory], as `EndpointDirectory.shared.directBase(forModel:)`
     * on iOS; tests pass their own.
     */
    suspend fun buildModels(
        live: List<ModelsResponse.Model>,
        now: Instant = Instant.now(),
        directBase: suspend (String) -> String? = { endpointDirectory.directBase(it) },
    ): List<KnownModel> {
        val seen = HashSet<String>()
        val result = ArrayList<KnownModel>()
        for (m in live) {
            if (isNonChat(m.id)) continue
            val key = m.id.lowercase()
            if (!seen.add(key)) continue
            // The directory, never a rule. These are deployment hostnames:
            // `deepseek-ai/DeepSeek-V4-Flash` is served by `dsv4-flash`, one host
            // serves TWO model ids (`glm-5-2` ← `z-ai/glm-5.2` AND
            // `zai-org/GLM-5.2-FP8`), and the pair `Qwen3.6-35B-A3B`/`…-FP8` map to
            // `qwen3-6-35b-nvfp4`/`qwen3-6-35b` — the opposite way round from any
            // guess. near.ai publishes the mapping unauthenticated for that reason.
            //
            // TEE_OWN only, not isAttestable: near.ai exposes no confidential
            // endpoint for attested third-party hardware, so asking for one is a
            // round trip that can only answer null.
            //
            // The curated snapshot is no longer a fallback here. It was a frozen
            // copy of a fetch, and a stale host is worse than none now that a
            // missing host degrades rather than reading as ordinary
            // (ConfidentialSession.outcomeWhenNoDirectHost).
            var direct: String? = null
            if (confidentiality(m.id) == Confidentiality.TEE_OWN) direct = directBase(m.id)
            result.add(KnownModel(
                id = m.id,
                displayName = m.name ?: displayName(m.id),
                vendor = vendorLabel(m.id),
                price = priceLabel(m.pricing),
                contextWindow = contextLabel(m.contextLength),
                // Derived from near.ai's own `created`, so the badge expires by
                // itself — the same rule grok and fireworks already use. It was
                // simply never read: the field was modelled and unused, so the
                // live picker showed NO near.ai badge at all, while Where's browse
                // rendered the curated snapshot's frozen ones.
                isNew = ModelCatalog.isNew(m.createdDate, now),
                created = m.createdDate,
                directBaseURL = direct,
                capabilities = capabilities(m),
                // Straight through, unedited. near.ai already writes a real
                // description (it even names the serving stack and the CVM) and
                // publishes the features and sampling parameters each model
                // accepts — the latter being a params catalogue teemoon would
                // otherwise have to probe a 400 for.
                summary = m.description?.trim()?.takeIf { it.isNotBlank() },
                features = m.supportedFeatures ?: emptyList(),
                samplingParameters = m.supportedSamplingParameters ?: emptyList(),
                maxOutputTokens = m.maxOutputLength,
                inputModalities = m.inputModalities ?: emptyList(),
                huggingFaceURL = m.huggingFaceId?.takeIf { it.isNotBlank() }?.let { "https://huggingface.co/$it" },
                quantization = m.quantization?.takeIf { it.isNotBlank() },
                // The tier, resolved where the catalogue is — not in a view.
                confidentialityNote = confidentiality(m.id).label,
            ))
        }
        return ordered(result)
    }

    /** "$1/$5" (whole) or "$1.40/$4.40" from per-1M-token input/output prices. */
    fun priceLabel(p: ModelsResponse.Pricing?): String =
        ModelCatalog.priceLabel(p?.input, p?.output)

    /** "262k" / "1M" / "1.1M" from a token count. */
    fun contextLabel(n: Int?): String = ModelCatalog.contextLabel(n)

    /**
     * Capabilities straight from the live model: `supported_features` → tools
     * and reasoning, input modalities → vision and audio. Authoritative for
     * near.ai (no more guessing).
     *
     * near.ai says `reasoning` where Ollama says `thinking`; both mean the
     * model reasons before answering, so both land on [ModelCapability.THINKING].
     *
     * Deliberately NOT mapped: `structured_outputs`, `json_mode` and
     * `logprobs`. Those describe the shape of a REQUEST the endpoint will
     * accept, not something the model can do — a different axis, and one
     * nothing in teemoon gates on. Recording them here would put two kinds of
     * fact in one set.
     */
    fun capabilities(m: ModelsResponse.Model): Set<ModelCapability> {
        val caps = mutableSetOf<ModelCapability>()
        val features = m.supportedFeatures ?: emptyList()
        if ("tools" in features) caps += ModelCapability.TOOLS
        if ("reasoning" in features) caps += ModelCapability.THINKING
        val modalities = (m.inputModalities ?: emptyList()) + (m.architecture?.inputModalities ?: emptyList())
        if ("image" in modalities) caps += ModelCapability.VISION
        if ("audio" in modalities) caps += ModelCapability.AUDIO
        return caps
    }
}
