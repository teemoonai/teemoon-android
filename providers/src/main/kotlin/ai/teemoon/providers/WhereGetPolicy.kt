// ports: teemoon/Presentation/WhereGetPolicy.swift @ v1.0.4
// ports: teemoon/Presentation/WhereGetPolicy.swift @ v1.0.4 (catalogCount, presetTrailingText, canBrowse by live source)
package ai.teemoon.providers

/**
 * Who appears in Where's `get` list. The sheet draws rows; this decides the
 * lists. See WhereGetPolicyTest.
 */
class WhereGetPolicy(
    val filter: WhereLocality?,
    val providers: List<Provider>,
    val networkSatisfied: Boolean,
    val home: Map<String, HomeInfo>,
    val credentialFor: (Provider) -> String,
    val credentialForEndpoint: (String) -> String,
    /**
     * How many models this server last answered with. Injected so the policy
     * stays testable and there is one number: the saved live list's length.
     */
    val catalogCount: (Provider) -> Int? = { null },
    val catalog: List<LocalModel> = LocalModelCatalog.all,
    /**
     * True when this catalog entry is the DEVICE's file for the model and that file is not on disk — a Tensor phone
     * that downloaded the general bundle before the device catalog. The place exists (it runs the general file), and
     * the get row still offers the download: same row, same button, the Tensor file this time. The policy otherwise
     * asks the providers, not the filesystem; this is the one question only the filesystem can answer.
     */
    val deviceFileMissing: (LocalModel) -> Boolean = { false },
) {
    data class HomeInfo(
        val kind: LocalServerKind,
        val modelCount: Int? = null,
        val warm: Set<String> = emptySet(),
    )

    val filteredProviders: List<Provider>
        get() = providers.filter { p ->
            if (!networkSatisfied) return@filter WhereLocality.of(p) == WhereLocality.PHONE
            val f = filter ?: return@filter true
            WhereLocality.of(p) == f
        }

    val downloadableModels: List<LocalModel>
        get() {
            val owned = providers.mapNotNull { it.localModelID }.toSet()
            return catalog.filter { it.id !in owned || deviceFileMissing(it) }
        }

    /** In `all`, only the catalog leader (E2B). E4B is not a fallback. */
    val recommendedDownload: LocalModel?
        get() {
            val recommended = catalog.firstOrNull() ?: return null
            if (providers.any { it.localModelID == recommended.id } && !deviceFileMissing(recommended)) return null
            return recommended
        }

    val getSectionIsEmpty: Boolean
        get() = filter == WhereLocality.PHONE &&
            downloadableModels.isEmpty() &&
            browseableProviders.isEmpty()

    val pullableProviders: List<Provider>
        get() = filteredProviders.filter { home[it.id]?.kind == LocalServerKind.OLLAMA }

    val browseableProviders: List<Provider>
        get() = filteredProviders.filter { canBrowse(it) }

    /**
     * The trailing text on a `get` row: "add key" until there is one, then the
     * model count. Nothing at all when the server has not answered yet — a
     * made-up number is worse than no number.
     */
    fun presetTrailingText(provider: Provider, keyed: Boolean): String? {
        if (!keyed) return "add key"
        return catalogCount(provider)?.toString()
    }

    fun canDeleteFromServer(provider: Provider): Boolean =
        WhereLocality.of(provider) == WhereLocality.HOME &&
            home[provider.id]?.kind == LocalServerKind.OLLAMA

    fun hasKey(provider: Provider): Boolean {
        if (!provider.requiresAPIKey) return true
        if (credentialFor(provider).trim().isNotEmpty()) return true
        return credentialForEndpoint(provider.endpoint).trim().isNotEmpty()
    }

    fun canBrowse(provider: Provider): Boolean {
        if (WhereLocality.of(provider) == WhereLocality.HOME) return false
        if (provider.isFixedAnswerService) return false
        if (!hasKey(provider)) return false
        // A known catalogue is browsable; a custom endpoint only when its setup says so, since its `/models` may not
        // exist and a 401 there reads as a bad key (iOS ac11ef2). There is no "search-first" class any more: every
        // cloud catalogue browses the same way (df74f65).
        val source = WhereProviderPresentation.liveCatalogSource(provider)
        return provider.supportsModelBrowsing || (source != null && source != EndpointModelCatalog.Source.GENERIC)
    }

    val addProviderLabel: String
        get() = when (filter) {
            WhereLocality.HOME -> "connect a computer"
            WhereLocality.CLOUD -> "add a cloud key"
            else -> "add a provider"
        }

    val addProviderGlyph: String
        get() = when (filter) {
            WhereLocality.HOME -> WhereLocality.HOME.systemImage
            WhereLocality.CLOUD -> "key"
            else -> "plus"
        }

    val addProviderCaption: String?
        get() = when (filter) {
            WhereLocality.CLOUD -> "configures an api key for any provider"
            WhereLocality.HOME -> "ollama, lm studio, or any openai-compatible server"
            else -> null
        }

    /** Server product name, not the user's nickname. */
    fun browseName(forProvider: Provider): String =
        home[forProvider.id]?.kind?.displayName ?: forProvider.name.lowercase()

    fun browseCaption(forProvider: Provider): String {
        home[forProvider.id]?.modelCount?.let { count ->
            return if (count == 1) "1 model on this server" else "$count models on this server"
        }
        catalogCount(forProvider)?.let { count ->
            return if (count == 1) "1 model on this setup" else "$count models on this setup"
        }
        return "equip another model on this setup"
    }

    fun isCustom(provider: Provider): Boolean =
        Provider.presets.none { it.sameEndpoint(asOther = provider) }

    fun getFooter(openToGet: Boolean, memoryFigure: String?): String {
        if (openToGet) {
            return "start here — add a key or connect a computer, then pick it under your setups."
        }
        if (filter == WhereLocality.PHONE) {
            val free = memoryFigure ?: return ""
            return "$free of memory free — memory, not storage, decides what can run."
        }
        return ""
    }

    /**
     * What a swipe / context-menu may destroy. Home servers teemoon cannot
     * delete on (LM Studio, llama.cpp) get [RowDestruction.NONE] — unequip would
     * bounce back on the next probe.
     */
    enum class RowDestruction {
        DELETE_LOCAL_WEIGHTS,
        DELETE_FROM_SERVER,
        UNEQUIP_CLOUD,
        NONE,
    }

    fun destruction(forProvider: Provider): RowDestruction {
        if (forProvider.isLocal) return RowDestruction.DELETE_LOCAL_WEIGHTS
        if (canDeleteFromServer(forProvider)) return RowDestruction.DELETE_FROM_SERVER
        if (WhereLocality.of(forProvider) == WhereLocality.HOME && forProvider.equipped.size > 1) {
            return RowDestruction.NONE
        }
        return RowDestruction.UNEQUIP_CLOUD
    }

    fun showsFirstRun(loadFailed: Boolean, recommended: LocalModel?): Boolean =
        providers.isEmpty() &&
            filter == null &&
            !loadFailed &&
            networkSatisfied &&
            recommended != null

    val emptyTitle: String
        get() = when (filter) {
            null -> "no setups yet"
            WhereLocality.PHONE -> "nothing downloaded"
            WhereLocality.HOME -> "no computers connected"
            WhereLocality.CLOUD -> "no cloud keys"
        }

    val emptyGlyph: String
        get() = filter?.systemImage ?: "arrow.triangle.branch"

    fun emptyDescription(openToGet: Boolean): String = when (filter) {
        null -> if (openToGet) {
            "download a model to this phone, connect a computer, or add a cloud key in get below."
        } else {
            "download a model to this phone, connect a computer, or add a cloud key below."
        }
        WhereLocality.PHONE ->
            "download a model below to run it here — no key, no network."
        WhereLocality.HOME ->
            "point teemoon at ollama on your laptop or home server."
        WhereLocality.CLOUD ->
            "add a key to use a hosted model."
    }

    fun readyCaption(forProvider: Provider): String? {
        if (filter == WhereLocality.PHONE) {
            WhereProviderPresentation.modelDescription(forProvider)?.let { return it }
        }
        if (WhereLocality.of(forProvider) == WhereLocality.HOME) {
            val info = home[forProvider.id]
                ?: return WhereProviderPresentation.placeCaption(forProvider)
            val name = info.kind.displayName
            if (name == null) {
                val host = WhereProviderPresentation.placeCaption(forProvider)
                return if (info.modelCount == null) "$host · couldn't reach this server" else host
            }
            val machines = providers.filter { WhereLocality.of(it) == WhereLocality.HOME }
                .map { it.id }.toSet()
            return if (filter == WhereLocality.HOME && machines.size < 2) null else name
        }
        val caption = WhereProviderPresentation.placeCaption(forProvider)
        if (ProviderCapability.END_TO_END_ENCRYPTION !in forProvider.capabilities &&
            !forProvider.isFixedAnswerService &&
            caption == WhereProviderPresentation.canonicalName(forProvider)
        ) {
            return "$caption · not end-to-end encrypted"
        }
        return caption
    }

    /** null = unmeasured, not cold. Unknown servers have no warmth for any model. */
    fun isWarm(provider: Provider, modelID: String): Boolean? {
        if (WhereLocality.of(provider) != WhereLocality.HOME) return null
        val info = home[provider.id] ?: return null
        if (info.kind == LocalServerKind.UNKNOWN) return null
        return info.warm.contains(modelID)
    }

    /** Orange caption: a near.ai model that is proxied, not enclave. */
    fun warnsUnencryptedNear(provider: Provider): Boolean =
        provider.isNearAI &&
            ProviderCapability.END_TO_END_ENCRYPTION !in provider.capabilities

    companion object {
        /**
         * Catalog leader if it fits; null means do not hero a download that would fail.
         * `availableMB == 0` is "API silent" — do not block (previews, simulator).
         */
        fun recommendedPhoneModel(
            /**
             * The DEVICE catalog (LocalModelCatalog.forDevice) — no default. iOS defaults to the one catalog it has;
             * here a default of `LocalModelCatalog.all` was the general file, and on a Tensor phone that is the wrong
             * one: the Pixel 10 Pro, with the Tensor bundle downloaded, was offered "download · 2.4 gb" of the general
             * file on the start-here card (2026-09-24), and the step golden hid it because the general file had
             * happened to be on the phone the day it was recorded.
             */
            catalog: List<LocalModel>,
            availableMB: Int,
        ): LocalModel? {
            val model = catalog.firstOrNull() ?: return null
            if (availableMB <= 0) return model
            return if (availableMB >= model.sizeMB + LocalMemory.headroomMB) model else null
        }
    }
}
