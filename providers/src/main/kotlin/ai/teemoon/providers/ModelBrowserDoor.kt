// ports: teemoon/Presentation/ModelBrowserDoor.swift @ v1.0.4 (the value, browsing, encryptedPicker; no equipped
//        set — iOS added one in d7a30c9 and took it out again in bf7a86e)
package ai.teemoon.providers

/**
 * What a browser is opened onto: the saved rows to show first, how to fetch
 * fresh ones, and what this catalogue can say about itself. Built once per
 * setup, so the screens that open a browser hand it ONE value instead of
 * assembling the same arguments by hand.
 */
data class ModelBrowserDoor(
    /** What this server last answered with, shown immediately. */
    val models: List<KnownModel> = emptyList(),
    /** Fetches the current list; on failure whatever was saved stays on screen. */
    val liveLoader: (suspend () -> List<KnownModel>?)? = null,
    /** near.ai only: its models carry a trust tier. */
    val showsConfidentialityTags: Boolean = false,
    /**
     * Offers the typed text as a model id when nothing matches. NEVER for
     * near.ai: an unknown id there classifies as own-fleet, which would claim an
     * encryption teemoon cannot check.
     */
    val allowsCustomID: Boolean = false,
    /**
     * Who can serve a given model, for the model card. null for a catalogue that
     * publishes no such list — an empty table would imply it does.
     */
    val offersLoader: (suspend (String) -> List<ModelOffer>)? = null,
) {
    companion object {
        /**
         * The Where sheet's door: a setup's whole catalogue — what it answered
         * last time first, the live list behind it. Every screen that browses a
         * setup opens THIS, so none of them assembles the arguments by hand.
         */
        fun browsing(
            provider: Provider,
            apiKey: String,
            homeKind: LocalServerKind?,
            store: LiveCatalogStore,
            onFailure: (String) -> Unit = {},
        ) = ModelBrowserDoor(
            models = WhereProviderPresentation.browseModels(provider, apiKey, store),
            liveLoader = WhereProviderPresentation.liveModelsLoader(provider, apiKey, homeKind, store, onFailure = onFailure),
            showsConfidentialityTags = WhereProviderPresentation.showsConfidentialityTags(provider),
            // Never for near.ai: an unknown id there classifies as own-fleet.
            allowsCustomID = !WhereProviderPresentation.showsConfidentialityTags(provider),
            offersLoader = WhereProviderPresentation.offersLoader(provider, apiKey, fetch = EndpointModelCatalog::liveFetch))

        /**
         * The trust ladder's door: near.ai filtered to what teemoon can SEAL —
         * own-fleet models with a published confidential host.
         *
         * The store is passed in rather than reached for. iOS uses a shared
         * singleton; here it is owned by the app's state holder, so a test
         * drives a real store over a temporary directory instead of mutating a
         * global.
         */
        fun encryptedPicker(
            provider: Provider,
            apiKey: String,
            store: LiveCatalogStore,
            refresh: (suspend () -> List<KnownModel>?)? = null,
        ) = ModelBrowserDoor(
            models = NearAIModelCatalog.encryptedChoices(store.models(provider, apiKey)),
            liveLoader = refresh?.let {
                {
                    val rows = it()
                    // An empty result is null, not an empty list: the caller keeps
                    // what is on screen rather than replacing it with nothing.
                    rows?.let { r -> NearAIModelCatalog.encryptedChoices(r).takeIf { c -> c.isNotEmpty() } }
                }
            },
            showsConfidentialityTags = true)
    }
}
