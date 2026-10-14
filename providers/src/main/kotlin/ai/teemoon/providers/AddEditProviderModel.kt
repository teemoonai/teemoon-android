// ports: teemoon/Views/Settings/AddEditProviderModel.swift @ v1.0.4
// ports: teemoon/Views/Settings/AddEditProviderModel.swift @ v1.0.4 (presets matched by presetMatchKey; a probe's list is recorded)
//
// State and policy for the add/edit provider screen: the probe, preset
// switching, the soft-gated save, and server-side model delete. The Compose
// screen is layout over this type — new branching belongs here, not in the
// view. No Android imports: animation, haptics and focus dismissal are
// injected closures, the store is a seam.
package ai.teemoon.providers

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException

class AddEditProviderModel(
    val mode: Mode,
    val scope: Scope = Scope.FULL,
    /** Preview/test seam. */
    val keyOverride: String? = null,
    val startsCustom: Boolean = false,
    val customStart: CustomStart = CustomStart.COMPUTER,
    val initialPreset: Provider? = null,
) {
    sealed interface Mode { data object Add : Mode; data class Edit(val provider: Provider) : Mode }

    /**
     * How much of the setup this screen is for. `places & keys` manages
     * PLACES: an endpoint and the credential that opens it. SERVER_AND_KEY
     * drops the model half — save() writes back what setupForEdit loaded, so
     * the equipped set and the active id survive untouched.
     */
    enum class Scope { FULL, SERVER_AND_KEY }

    /**
     * What a custom start is FOR: a machine on your own network is almost
     * never behind TLS, so a computer starts on http; a hosted endpoint is
     * the exact opposite, and http on a public host is a mistake worth not
     * suggesting.
     */
    enum class CustomStart(val title: String, val defaultScheme: EndpointScheme) {
        COMPUTER("connect a computer", EndpointScheme.HTTP), CLOUD_KEY("add a cloud key", EndpointScheme.HTTPS)
    }

    sealed interface ConnState {
        data object Idle : ConnState; data object Testing : ConnState; data object Connected : ConnState
        data class Failed(val kind: EndpointModelCatalog.FailureKind) : ConnState
    }

    /** What the screen needs from the provider store. */
    interface Store {
        val providers: List<Provider>
        var currentProviderID: String?
        fun credential(provider: Provider): String
        fun credential(endpoint: String): String?
        /** Returns the id the setup ACTUALLY lands on: an endpoint already configured folds into that record. */
        fun addProvider(provider: Provider): String
        fun updateProvider(provider: Provider)
        fun removeProvider(provider: Provider)
        /** Throws on a Keystore failure. */
        fun setCredential(apiKey: String, providerID: String)
    }

    var store: Store? = null

    // MARK: View wiring (injected — this type must not import Compose)
    var onPlayHaptic: (() -> Unit)? = null
    var onAnimatedChange: (() -> Unit) -> Unit = { it() }
    var onDismissEndpointFocus: (() -> Unit)? = null

    // MARK: Network seams (production defaults; tests inject)
    var probeCatalog: EndpointProbe.Catalog = liveCatalog()
    /** The app's one saved copy of every server's list; null in previews. */
    var catalogStore: LiveCatalogStore? = null
    /** Server-side Ollama delete. A seam so performDelete's failure path is testable offline. */
    var deleteModelFromServer: suspend (id: String, baseUrl: String) -> Unit = { id, base -> OllamaAdapter.deleteModel(id, base) }

    // MARK: Form fields
    var name = ""
    /** The last label autofill generated — distinguishes an auto label (safe to refresh) from one the user typed (never overwrite). */
    var autoLabel = ""
    var scheme: EndpointScheme = EndpointScheme.HTTPS
    var endpointHost = ""
    var model = ""
    var apiKey = ""
        // Typing is the fix, so the inline error goes the moment it starts.
        set(value) { field = value; if (value.isNotBlank()) keyFieldError = null }
    var showAPIKey = false
    /** Inline validation under the key field, set by a save refused for a blank required key. Cleared by typing. */
    var keyFieldError: String? = null
    /** Bumped with [keyFieldError] so the screen moves focus into the key field (and shakes it once). */
    var keyFocusRequest = 0

    // Preset + hidden fields round-tripped through save/edit.
    var selectedPresetName = ""
    var authHeaderName = ""
    var supportsModelBrowsing = false
    var extraParamsList: List<Pair<String, String>> = emptyList()
    var maxMessages: Int? = null
    var hasBuiltInGrounding = false
    var omitSystemPrompt = false
    /** Keys typed per preset name but not yet saved, so switching preset tiles doesn't discard them. Session-only. */
    val typedKeys = HashMap<String, String>()
    /** Set when the Keystore write fails on save — visible instead of dismissing as though the key were stored. */
    var keySaveError: String? = null
    /** Set when a confirmed server-side model delete fails: the confirmation promised "can't be undone", so a failure must say so. */
    var modelDeleteError: String? = null
    // Saved "none" state — restored when the user returns to the none preset.
    private var savedNoneName = ""; private var savedNoneEndpoint = ""; private var savedNoneModel = ""; private var savedNoneAPIKey = ""

    // MARK: Connection state (verify-first)
    var conn: ConnState = ConnState.Idle
    var fetchedModels: List<KnownModel> = emptyList()
    /** Set by probe() when the endpoint is detected as Ollama — enables the server-side download and delete affordances. */
    var localKind: LocalServerKind = LocalServerKind.UNKNOWN
    val isOllama: Boolean get() = localKind == LocalServerKind.OLLAMA
    var pendingDelete: KnownModel? = null
    var loadedModelIDs: Set<String> = emptySet()
    /** The model id loaded in Edit mode, so save() can preserve stored capabilities when the model is unchanged. */
    var loadedModel = ""

    /** Only the *definitive* signal is trusted: a 401 from /models means a key is required. A 200 never means "keyless". */
    enum class AuthRequirement { UNKNOWN, NEEDED }
    var authRequirement = AuthRequirement.UNKNOWN

    /**
     * A probe cannot succeed: this endpoint has already answered 401 (LEARNED)
     * or is a measured key-gated host (KNOWN), and the key field is empty.
     */
    val probeBlockedOnMissingKey: Boolean
        get() {
            if (apiKey.trim().isNotEmpty()) return false
            if (authRequirement == AuthRequirement.NEEDED) return true
            val host = fullEndpointHost ?: return false
            return host in ProviderPresets.modelListRequiresKeyHosts
        }

    /** A probe has run to completion at least once for this endpoint — gates the manual model field. */
    var hasProbed = false

    // MARK: Computed
    val editingProvider: Provider? get() = (mode as? Mode.Edit)?.provider
    val editingProviderID: String? get() = editingProvider?.id
    val isEditing: Boolean get() = editingProviderID != null

    val fullEndpoint: String get() = endpointHost.trim().let { if (it.isEmpty()) "" else scheme.prefix + it }
    val fullEndpointURL: URI? get() = fullEndpoint.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull()?.takeIf { u -> u.host != null } }
    private val fullEndpointHost: String? get() = fullEndpointURL?.host?.lowercase()

    /**
     * The entered address reduced to an OpenAI-compatible BASE — no
     * "/chat/completions", no trailing slash, and "/v1" supplied when the user
     * gave only a host and port. THE BARE HOST:PORT CASE IS THE COMMON ONE:
     * `:11434/models` 404s while `:11434/v1/models` answers. Shared by the
     * probe and by save(), and that sharing is the point — two places deriving
     * a URL from one field is how a screen disagrees with itself. Only when
     * the path is EMPTY: a typed path is a decision.
     */
    val normalisedBase: String?
        get() {
            val u = fullEndpointURL ?: return null
            var s = u.toString()
            if (s.endsWith("/chat/completions")) s = s.removeSuffix("/chat/completions")
            while (s.endsWith("/")) s = s.dropLast(1)
            if (runCatching { URI(s).path.isNullOrEmpty() }.getOrDefault(false)) s += "/v1"
            return s
        }
    val probeBaseURL: String? get() = normalisedBase

    val isSelfHosted: Boolean get() = fullEndpointHost?.let { Provider.isPrivateHost(it) } ?: false
    val isValid: Boolean get() = name.isNotBlank() && endpointHost.isNotBlank() && fullEndpointURL != null && model.isNotBlank()
    /** In SERVER_AND_KEY nothing on screen shows a model, so a disabled save needs this reason while the endpoint has yet to answer. */
    val awaitingFirstModel: Boolean get() = scope == Scope.SERVER_AND_KEY && !isEditing && fullEndpointURL != null && model.isBlank()

    /** The preset whose endpoint matches what's currently entered, if any. */
    val browsablePreset: Provider? get() = Provider.presets.firstOrNull { it.endpoint == scheme.prefix + endpointHost }

    data class EndpointSuggestion(val scheme: EndpointScheme, val hostPath: String) { val id: String get() = hostPath }

    /** Saved endpoints whose host+path contains the typed text; excludes an exact match and the provider being edited. */
    val endpointSuggestions: List<EndpointSuggestion>
        get() {
            val typed = endpointHost.trim().lowercase()
            if (typed.isEmpty()) return emptyList()
            val seen = HashSet<String>()
            return (store?.providers ?: emptyList()).mapNotNull { p ->
                if (p.id == editingProviderID) return@mapNotNull null
                val (s, hp) = EndpointScheme.split(p.endpoint)
                val key = hp.lowercase()
                if (!key.contains(typed) || key == typed || !seen.add(key)) return@mapNotNull null
                EndpointSuggestion(s, hp)
            }.take(4)
        }

    val matchedCloudPreset: Provider? get() = Provider.presets.firstOrNull { Provider.presetMatchKey(it.endpoint) == Provider.presetMatchKey(fullEndpoint) } ?: Provider.presets.firstOrNull { it.name == selectedPresetName }
    /** Console / keys URL for a matching cloud preset. null for custom / self-hosted. */
    val providerConsoleURL: String? get() = matchedCloudPreset?.signupURL
    val consoleDisplayName: String get() = matchedCloudPreset?.name ?: name.ifEmpty { "provider" }
    val apiKeyPlaceholder: String get() = if (authRequirement == AuthRequirement.NEEDED || missingRequiredKey) "api key — required" else "api key"

    /**
     * Whether the SAVED provider should claim it needs a key: a key is entered,
     * the probe proved auth is required, or it is a cloud endpoint. Hardcoding
     * true here broke machine identity for every keyless self-hosted box.
     */
    val requiresAPIKey: Boolean get() = apiKey.isNotBlank() || authRequirement == AuthRequirement.NEEDED || !isSelfHosted

    /**
     * A setup that claims [requiresAPIKey] cannot be saved without one — new OR edited (iOS 148a131 + a9ba0ef). A cloud
     * preset fills name, endpoint and model, so save went through with the key field blank and the first message went
     * out with no auth header; clearing the field on an existing setup recreated the same state from settings. Save
     * stays ENABLED — a disabled button explains nothing — and [save] refuses with an inline error on the field.
     * Revoking a key is "delete this setup", on the same screen.
     */
    val missingRequiredKey: Boolean get() = requiresAPIKey && apiKey.isBlank()

    /** Show the key field only when plausibly needed. Never hidden on a 200 (public /models ≠ keyless). */
    val showKeyField: Boolean get() = apiKey.isNotEmpty() || authRequirement == AuthRequirement.NEEDED || (fullEndpointURL != null && !isSelfHosted)

    /** " — and sent only to api.near.ai", when the endpoint is far enough along to name a host. */
    val keyDestinationClause: String get() = fullEndpointHost?.takeIf { it.isNotEmpty() }?.let { " — and sent only to $it" } ?: ""

    /** The model id this endpoint is pinned to when it exposes no model list at all (Brave Answers). */
    val fixedModelID: String? get() = (Provider.presets.firstOrNull { Provider.presetMatchKey(it.endpoint) == Provider.presetMatchKey(fullEndpoint) }?.defaultModelRule as? ModelDefaultRule.Fixed)?.id

    /** Inline model list: with a default RULE only the rule-chosen default; otherwise the first few. */
    val recommendedModels: List<KnownModel>
        get() = if (browsablePreset?.defaultModelRule != null) fetchedModels.firstOrNull { it.id == model }?.let { listOf(it) } ?: emptyList() else fetchedModels.take(3)
    val recommendedModelID: String? get() = browsablePreset?.defaultModelRule?.resolve(fetchedModels)
    val showsInlineTiers: Boolean get() = Provider.isNearAIHost(fullEndpointHost)

    fun failureMessage(kind: EndpointModelCatalog.FailureKind): String = EndpointProbe.failureMessage(kind)

    // MARK: - Actions

    fun autofillPlaceLabel() {
        val next = PlaceLabel.proposed(currentName = name, lastAuto = autoLabel, presetName = selectedPresetName, host = fullEndpointURL?.host) ?: return
        name = next; autoLabel = next
    }

    suspend fun probe(select: String? = null, userInitiated: Boolean = false) {
        val base = probeBaseURL ?: return
        try {
            val preset = Provider.presets.firstOrNull { Provider.presetMatchKey(it.endpoint) == Provider.presetMatchKey(fullEndpoint) }
            val request = EndpointProbe.Request(
                baseURL = base, host = fullEndpointURL?.host, apiKey = apiKey, authHeaderName = authHeaderName.ifEmpty { null },
                isSelfHosted = isSelfHosted, stickyKind = localKind, currentModel = model, preferredModel = select, userInitiated = userInitiated,
                existingModels = fetchedModels, defaultModelRule = preset?.defaultModelRule, keyValidationEndpoint = preset?.keyValidationEndpoint)
            if (EndpointProbe.needsNetwork(request)) onAnimatedChange { conn = ConnState.Testing }
            val result = EndpointProbe.run(request, probeCatalog)
            onAnimatedChange { applyProbe(result) }
        } finally { hasProbed = true }
    }

    private fun applyProbe(result: EndpointProbe.Result) {
        localKind = result.kind; loadedModelIDs = result.loaded; fetchedModels = result.models; model = result.selectedModel
        // The probe only fills the form. It used to record the list here, under whatever key was in the field — so
        // testing a second key and cancelling pruned the SAVED account's list (iOS 9e47a63). [save] records it.
        if (result.authNeeded) authRequirement = AuthRequirement.NEEDED
        if (result.dismissEndpointFocus) onDismissEndpointFocus?.invoke()
        if (result.shouldAutofillLabel) autofillPlaceLabel()
        conn = when (val o = result.outcome) {
            EndpointProbe.Outcome.Idle -> ConnState.Idle
            EndpointProbe.Outcome.Connected -> ConnState.Connected
            is EndpointProbe.Outcome.Failed -> ConnState.Failed(o.kind)
        }
    }

    /** Every edit of the endpoint field: peel a pasted scheme into the chip, invalidate what the previous endpoint taught us, drop a preset tile the edit walked away from. */
    fun endpointEdited(newValue: String) {
        when {
            newValue.startsWith("https://") -> { endpointHost = newValue.removePrefix("https://"); scheme = EndpointScheme.HTTPS }
            newValue.startsWith("http://") -> { endpointHost = newValue.removePrefix("http://"); scheme = EndpointScheme.HTTP }
            else -> endpointHost = newValue
        }
        conn = ConnState.Idle; authRequirement = AuthRequirement.UNKNOWN; hasProbed = false; localKind = LocalServerKind.UNKNOWN; loadedModelIDs = emptySet()
        if (selectedPresetName.isNotEmpty() && Provider.presets.firstOrNull { it.name == selectedPresetName }?.endpoint != fullEndpoint) selectedPresetName = ""
    }

    /** Picking a saved endpoint brings its saved key with it; never overwrites a key already typed here. */
    fun applySuggestion(sug: EndpointSuggestion) {
        scheme = sug.scheme; endpointHost = sug.hostPath; conn = ConnState.Idle
        if (apiKey.isBlank()) store?.credential(sug.scheme.prefix + sug.hostPath)?.let { apiKey = it }
    }

    /** True when the provider was removed and the screen should dismiss. */
    fun deleteProvider(): Boolean {
        val p = editingProvider ?: return false; val s = store ?: return false
        s.removeProvider(p); return true
    }

    /**
     * Server-side delete of an Ollama model (from a confirmed swipe). A FAILED
     * delete must say so through the same alert mechanism as a Keystore
     * failure on save; the selection is kept (the model is still on the
     * server) and the re-probe still runs so the UI reflects the true state.
     */
    suspend fun performDelete(m: KnownModel) {
        pendingDelete = null
        val base = probeBaseURL ?: return
        try { deleteModelFromServer(m.id, base) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            modelDeleteError = "“${m.displayName}” is still on the server — ${e.message ?: e}"
            probe(); return
        }
        if (model == m.id) model = ""
        onPlayHaptic?.invoke()
        probe()
    }

    // MARK: - Preset / load / save (soft-gate)

    fun applyPresetChange(oldName: String, newName: String) {
        // A typed key belongs to the preset it was typed under — keyed, not carried.
        if (oldName.isNotEmpty()) typedKeys[oldName] = apiKey
        selectedPresetName = newName   // the iOS view's binding does this before calling; here the model owns it
        if (newName.isEmpty()) {
            name = savedNoneName
            val (s, h) = EndpointScheme.split(savedNoneEndpoint); scheme = s; endpointHost = h
            model = savedNoneModel; apiKey = savedNoneAPIKey
            authHeaderName = ""; supportsModelBrowsing = false; extraParamsList = emptyList(); maxMessages = null; hasBuiltInGrounding = false; omitSystemPrompt = false
            conn = ConnState.Idle; fetchedModels = emptyList()
        } else Provider.presets.firstOrNull { it.name == newName }?.let { preset ->
            if (oldName.isEmpty()) { savedNoneName = name; savedNoneEndpoint = fullEndpoint; savedNoneModel = model; savedNoneAPIKey = apiKey }
            apply(preset)
        }
    }

    fun apply(preset: Provider) {
        name = preset.name.lowercase(); autoLabel = name
        val (s, h) = EndpointScheme.split(preset.endpoint); scheme = s; endpointHost = h
        model = preset.model
        authHeaderName = preset.authHeaderName ?: ""; supportsModelBrowsing = preset.supportsModelBrowsing
        extraParamsList = preset.extraParams.toList().sortedBy { it.first }
        // Key, in order of authority: typed for this preset this session → saved for a provider on this endpoint → stored under the preset's own id.
        apiKey = typedKeys[preset.name] ?: store?.credential(preset.endpoint)?.takeIf { it.isNotEmpty() } ?: store?.credential(preset)?.takeIf { it.isNotEmpty() } ?: ""
        maxMessages = preset.maxMessages; hasBuiltInGrounding = preset.hasBuiltInGrounding; omitSystemPrompt = preset.omitSystemPrompt
        conn = ConnState.Idle; fetchedModels = emptyList()
        if (preset.model.isNotEmpty()) autofillPlaceLabel()
    }

    fun loadInitialValues() {
        val provider = editingProvider
        if (provider == null) {
            // Fresh add: default to the first preset NOT already configured — each add is nearly always a different provider.
            initialPreset?.let { selectedPresetName = it.name; apply(it); return }
            if (startsCustom) { scheme = customStart.defaultScheme; return }
            if (selectedPresetName.isEmpty() && endpointHost.isEmpty()) {
                val configured = (store?.providers ?: emptyList()).map { it.name.lowercase() }.toSet()
                selectedPresetName = Provider.presets.firstOrNull { it.name.lowercase() !in configured }?.name ?: Provider.nearAI.name
            }
            return
        }
        name = provider.name
        val (s, h) = EndpointScheme.split(provider.endpoint); scheme = s; endpointHost = h
        model = provider.model; loadedModel = provider.model
        authHeaderName = provider.authHeaderName ?: ""; supportsModelBrowsing = provider.supportsModelBrowsing
        extraParamsList = provider.extraParams.toList().sortedBy { it.first }
        // BOTH lookups: keys are stored per provider INSTANCE, so a key saved from another screen lives under a different id for the same endpoint.
        apiKey = keyOverride ?: run {
            val byID = store?.credential(provider) ?: ""
            if (byID.isEmpty()) store?.credential(provider.endpoint) ?: "" else byID
        }
        maxMessages = provider.maxMessages; hasBuiltInGrounding = provider.hasBuiltInGrounding; omitSystemPrompt = provider.omitSystemPrompt
    }

    /** A fresh fetch wins; else, on edit with the model unchanged, preserve what was stored; else unknown — never carry the OLD model's caps onto a new one. */
    fun capabilitiesToSave(trimmedModel: String): ModelCapabilities? {
        fetchedModels.firstOrNull { it.id == trimmedModel }?.let { return ModelCapabilities.fromCapabilitySet(it.capabilities) }
        return if (trimmedModel == loadedModel) editingProvider?.modelCapabilities else null
    }

    /** True when everything saved and the screen should dismiss; false keeps it up with keySaveError set. */
    fun save(): Boolean {
        val s = store ?: return false
        // AddEditProviderView disables the save button on `!isValid`; the model refuses too, because a place saved
        // without a model was activated and shown on the chip as "on your own machine · 10.0.2.2" while Where listed
        // "no setups yet" (emulator, 2026-09-04) — an unusable half-record, silently.
        if (!isValid) return false
        if (missingRequiredKey) {
            keyFieldError = "enter your ${consoleDisplayName.lowercase()} api key to save this setup."
            keyFocusRequest += 1
            return false
        }
        val id = editingProviderID ?: UUID.randomUUID().toString()
        val trimmedModel = model.trim()
        val provider = Provider(
            id = id, name = name.trim(),
            // The NORMALISED base, not the raw field: saving what was typed is what made a working "connect a computer" 404 at inference time.
            endpoint = normalisedBase?.let { "$it/chat/completions" } ?: fullEndpoint,
            model = trimmedModel, authHeaderName = authHeaderName.trim().ifEmpty { null }, requiresAPIKey = requiresAPIKey,
            supportsModelBrowsing = supportsModelBrowsing, extraParams = extraParamsList.filter { it.first.isNotEmpty() }.toMap(),
            maxMessages = maxMessages, hasBuiltInGrounding = hasBuiltInGrounding, omitSystemPrompt = omitSystemPrompt,
            modelCapabilities = capabilitiesToSave(trimmedModel))
        // The id the setup ACTUALLY lands on: an endpoint already configured folds into that record.
        val savedID = if (editingProviderID != null) { s.updateProvider(provider); id } else s.addProvider(provider)
        // Unconditional: an EMPTIED field must delete the stored key.
        try { s.setCredential(apiKey, savedID) } catch (e: Exception) { keySaveError = e.message ?: e.toString(); return false }
        // Settings is another door onto the same list — but only once the key is SAVED: what it fetched is what the
        // Where row's count and the browser's first frame should show.
        if (fetchedModels.isNotEmpty()) catalogStore?.record(fetchedModels, provider, apiKey)
        // Adding activates; editing leaves the active provider unchanged unless none is set.
        if (editingProviderID == null || s.currentProviderID == null) s.currentProviderID = savedID
        return true
    }

    companion object {
        /** The production probe surfaces. */
        fun liveCatalog(): EndpointProbe.Catalog = EndpointProbe.Catalog(
            detectKind = { base -> when { OllamaAdapter.isOllama(base) -> LocalServerKind.OLLAMA; LMStudioAdapter.isLMStudio(base) -> LocalServerKind.LM_STUDIO; else -> LocalServerKind.UNKNOWN } },
            listOllama = { OllamaAdapter.listModels(it) },
            listLMStudio = { LMStudioAdapter.listModels(it) },
            // ROUTED BY HOST, as EndpointProbe.Catalog.live is on iOS: each cloud's own adapter, the generic probe only
            // for a host no adapter handles. This dropped the host and probed everything generically, so settings
            // listed near.ai, Grok, Fireworks, OpenRouter and NVIDIA as bare ids — no prices, non-chat models
            // included (OpenRouter: 450 rows where the browser, through the adapter, has 379). Now that the probe's
            // list is also saved to the store, the generic list would have become the Where row's count.
            liveCatalog = { host, base, key, header -> ModelCatalog.liveCatalog(EndpointModelCatalog.Source.resolve(host), base, key, header) },
            loadedOllama = { OllamaAdapter.loadedModels(it) },
            loadedLMStudio = { LMStudioAdapter.loadedModels(it) },
            validateKey = { key, endpoint -> ProviderKeyValidator.validate(key, endpoint) },
        )
    }
}
