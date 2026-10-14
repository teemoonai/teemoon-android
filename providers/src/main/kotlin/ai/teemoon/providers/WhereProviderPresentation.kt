// ports: teemoon/Presentation/WhereProviderPresentation.swift @ v1.0.4 (adds presetCaption)
// ports: teemoon/Presentation/WhereProviderPresentation.swift @ v1.0.4 (browseModels only: the saved live list)
// ports: teemoon/Presentation/WhereProviderPresentation.swift @ v1.0.4 (offersLoader, liveModelsLoader)
package ai.teemoon.providers

import java.net.URI

/**
 * How a configured Provider maps onto a list row: model label, place caption,
 * canonical name. Pure functions over Provider + catalogs. Views render the
 * strings.
 *
 * liveModelsLoader (at the foot of this file) is the one seam that reaches adapters, through LiveCatalogStore.
 */
object WhereProviderPresentation {

    /**
     * The row's TITLE: the model, never the place. A downloaded model uses the
     * catalog name; a cloud model its compact id — unless the provider's own
     * name already starts with it (a fixed-answer service like Brave Answers),
     * in which case the name IS the label and is not repeated.
     */
    fun modelLabel(forProvider: Provider): String {
        // iOS: the catalog's display name. Android's device catalog renames a local place for the file it runs
        // ("Gemma 4 E2B · Tensor" once the Tensor download lands — LocalModel.forDevice); the place's name is that
        // name when it is the catalog's plus a suffix, and the catalog's own when it is anything else (an old name).
        forProvider.localModelID?.let { LocalModelCatalog.model(it) }?.let { base ->
            return (forProvider.name.takeIf { it.startsWith(base.displayName) } ?: base.displayName).lowercase()
        }
        ModelCatalog.titleLabel(forProvider.model, canonicalName(forProvider))?.let { return it }
        val tail = forProvider.model.substringAfterLast('/')
        return if (tail.isEmpty()) forProvider.name.lowercase() else tail.lowercase()
    }

    /**
     * The PICKER's caption for a preset nobody has configured yet — one line,
     * two at most, on the "add cloud key" list.
     *
     * Deliberately not `presetDescription`. That one is the add-form's footer
     * and runs to a sentence and a half ("open models running inside attested
     * enclaves encrypted to the llm model, so the operator can't read your
     * chats. teemoon checks the proof on the device."). In a row with a
     * two-line cap it truncates mid-clause, and the half that survives is the
     * mechanism rather than the reason to pick it. These say the reason.
     */
    fun presetCaption(forPreset: Provider): String? = when (forPreset.id) {
        Provider.nearAI.id -> "open models in enclaves the host can't read"
        Provider.grok.id -> "xai's models, 2m context, live web and x"
        Provider.fireworks.id -> "fast, low-cost open models — large catalog"
        Provider.openRouter.id -> "most comprehensive collection of models"
        Provider.nvidia.id -> "free, rate limited to 40 requests per minute"
        Provider.braveAnswers.id -> "cited answers and strong privacy from an independent index"
        else -> forPreset.presetDescription
    }

    fun systemImage(forProvider: Provider): String = WhereLocality.of(forProvider).systemImage

    /** near.ai runs some models in enclaves and proxies others: the tag is news there, noise elsewhere. */
    fun showsConfidentialityTags(forProvider: Provider): Boolean = forProvider.isNearAI

    /** "caption · trailing" — a missing side never leaves an orphaned separator. */
    fun metadataRun(caption: String?, trailing: String?): String? = when {
        caption != null && trailing != null -> "$caption · $trailing"
        caption != null -> caption
        trailing != null -> trailing
        else -> null
    }
    /** The service's name. Owned by Provider; this is the presentation spelling. */
    fun canonicalName(forProvider: Provider): String = forProvider.canonicalName

    /**
     * The catalog's one-liner for a downloaded model, lowercased.
     *
     * Used instead of the place caption when the user is already filtered to
     * the phone: "on this device" is the answer to a question the segment has
     * already answered, and it was the same string on every row.
     */
    fun modelDescription(forProvider: Provider): String? {
        val localID = forProvider.localModelID ?: return null
        val model = LocalModelCatalog.model(localID) ?: return null
        return model.blurb.lowercase()
    }

    /**
     * What this server answered with last time, for the first frame of a browse
     * and for naming a model offline. Empty until it has answered once; the
     * live loader fills it in behind whatever this returns.
     *
     * The store is passed in rather than reached for (iOS: `LiveCatalogStore.shared`),
     * as [ModelBrowserDoor.encryptedPicker] takes it: the app's state holder owns
     * the one instance, and a test drives a real one over a temp directory.
     */
    fun browseModels(forProvider: Provider, apiKey: String, store: LiveCatalogStore): List<KnownModel> {
        if (forProvider.isFixedAnswerService) return listOf(KnownModel.braveAnswersModel)
        return store.models(forProvider, apiKey)
    }

    /**
     * Who can serve a model, for the model card — OpenRouter only, the one
     * catalogue that publishes such a list. null elsewhere: an empty table would
     * imply the catalogue has one.
     */
    fun offersLoader(
        provider: Provider,
        apiKey: String,
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): (suspend (String) -> List<ModelOffer>)? {
        val base = provider.openAIBaseUrl ?: return null
        if (liveCatalogSource(provider) != EndpointModelCatalog.Source.OPEN_ROUTER) return null
        val header = provider.authHeaderName
        return { modelID -> OpenRouterAdapter.listOffers(modelID, base, apiKey, header, fetch) }
    }

    /**
     * Which live catalogue a CLOUD provider's browse row should load — the same
     * table Settings' probe uses, so a Grok row carries what a near.ai row
     * carries. The regression this closes: this returned near.ai or nothing for
     * everyone else, so a Grok row was a name and a vendor while a near.ai row a
     * tap away was a name, a badge, a price and a context window.
     *
     * null for the other two tiers, and for different reasons. The phone has no
     * browse affordance at all — a downloaded model is already in `ready now`.
     * Home does have one, but every model the machine serves lands in that same
     * list, so there is nothing on the other side of the tap; its catalogue
     * arrives through the home-server probe, not through here.
     */
    fun liveCatalogSource(forProvider: Provider): EndpointModelCatalog.Source? {
        if (WhereLocality.of(forProvider) != WhereLocality.CLOUD) return null
        val host = forProvider.openAIBaseUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
        return EndpointModelCatalog.Source.resolve(host)
    }

    /**
     * WhereChip.chipPlace(for:): the text after the model on the composer chip. The phone shows nothing;
     * a machine shows its host; a cloud shows the provider's NAME, without the trust suffix — `placeCaption`
     * appends the guarantee ("near.ai · end-to-end encrypted"), which is right for the Where sheet, whose
     * cloud header promises every row says whether it is, and wrong here, where the title block already
     * states the same phrase directly above.
     */
    fun chipPlace(forProvider: Provider): String? = when (WhereLocality.of(forProvider)) {
        WhereLocality.PHONE -> null
        WhereLocality.HOME -> placeCaption(forProvider)
        // A sealed cloud (near.ai) shows its canonical name — the title block already states the guarantee; any
        // other cloud shows the place caption, which for a fixed-answer service is its one-line description.
        WhereLocality.CLOUD -> if (ProviderCapability.END_TO_END_ENCRYPTION in forProvider.capabilities || forProvider.isNearAI) canonicalName(forProvider) else placeCaption(forProvider)
    }

    fun placeCaption(forProvider: Provider): String {
        return when (WhereLocality.of(forProvider)) {
            WhereLocality.PHONE ->
                // Verbatim the download screen's title and the title block's
                // caption. The promise made before installing a model is the
                // promise repeated while using it — three surfaces, one sentence.
                "on this device"
            WhereLocality.HOME -> {
                // THE MACHINE'S NAME, not its fully-qualified address.
                // A tailnet FQDN is mostly suffix: ringzero.tailnet-name.ts.net
                // → "ringzero".
                val host = forProvider.let {
                    var s = it.endpoint
                    if (s.endsWith("/chat/completions")) s = s.removeSuffix("/chat/completions")
                    if (!s.contains("://")) return@let null
                    Provider.hostOf(s).takeIf { h -> h.isNotEmpty() }
                }
                host?.let { HostLabel.friendly(it).lowercase() }
                    ?: forProvider.name.lowercase()
            }
            WhereLocality.CLOUD -> {
                if (forProvider.answersSingleTurnOnly && forProvider.hasBuiltInGrounding) {
                    // CONSTRAINT FIRST. "single turn q&a" carries the warning
                    // in three words: maxMessages: 1 means this endpoint is sent
                    // your latest message and nothing else. NOT "private": this
                    // is a plaintext POST to Brave.
                    return "single turn q&a, fast built-in search"
                }
                val name = canonicalName(forProvider)
                if (ProviderCapability.END_TO_END_ENCRYPTION in forProvider.capabilities) {
                    return "$name · end-to-end encrypted"
                }
                // Only stated where it's a real distinction the user can act on:
                // near.ai runs some models in enclaves and PROXIES others, so the
                // negative is news. Elsewhere it's the unremarkable default.
                if (forProvider.isNearAI) {
                    return "$name · not end-to-end encrypted"
                }
                name
            }
        }
    }
}

/**
 * Where a provider's live catalogue comes from: a cloud source by host, an
 * Ollama machine's own list, nothing for the phone or an unknown server.
 */
fun WhereProviderPresentation.liveModelsLoader(
    provider: Provider, apiKey: String, homeKind: LocalServerKind?,
    /** The one saved copy; its `list` seam is what actually reaches the server. */
    store: LiveCatalogStore,
    ollama: suspend (String) -> EndpointModelCatalog.ProbeResult = { OllamaAdapter.listModels(it) },
    /** Told why a live load did not connect (the probe's failure kind), so a blank browser is not a mystery. */
    onFailure: (String) -> Unit = {},
): (suspend () -> List<KnownModel>?)? {
    val base = provider.openAIBaseUrl ?: return null
    liveCatalogSource(provider)?.let { source ->
        // Through the store, so opening a browser is also what updates the count
        // on the row behind it. An hour old is fresh enough to show first and
        // refresh behind.
        return { store.refresh(provider, apiKey, maxAgeMs = 3_600_000L) ?: run { onFailure("$source $base → no list"); null } }
    }
    if (WhereLocality.of(provider) == WhereLocality.HOME && homeKind == LocalServerKind.OLLAMA) {
        return { (ollama(base) as? EndpointModelCatalog.ProbeResult.Connected)?.models }
    }
    return null
}
