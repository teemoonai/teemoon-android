package ai.teemoon.app

import android.content.Context
import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.search.ChatSearchResult
import ai.teemoon.chat.search.ChatSearchService
import ai.teemoon.chat.search.RoomChatSearchSource
import ai.teemoon.confidential.AndroidDcapQvl
import ai.teemoon.confidential.AuditIndex
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DcapService
import ai.teemoon.confidential.FileProvenanceCacheStore
import ai.teemoon.confidential.IntelPcsCollateralProvider
import ai.teemoon.confidential.NrasService
import ai.teemoon.confidential.TlsAttestationVerifier
import ai.teemoon.confidential.ProvenanceService
import ai.teemoon.providers.ConfigLoad
import ai.teemoon.providers.ConfigStore
import ai.teemoon.providers.AndroidKeystoreCipher
import ai.teemoon.providers.Keystore
import ai.teemoon.providers.OllamaDownloadCenter
import ai.teemoon.providers.Provider
import ai.teemoon.providers.LocalMemory
import ai.teemoon.providers.LocalModelStorage
import ai.teemoon.providers.LocalModelDownloader
import ai.teemoon.providers.LocalModel
import ai.teemoon.providers.LocalModelCatalog
import ai.teemoon.providers.ModelCatalog
import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.LiveCatalogStore
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.WhereProviderPresentation
import ai.teemoon.providers.WhereLocality
import ai.teemoon.providers.WhereGetPolicy
import ai.teemoon.providers.ServerKind
import ai.teemoon.providers.OllamaAdapter
import ai.teemoon.providers.LocalServerKind
import ai.teemoon.providers.HomeServerProbe
import ai.teemoon.providers.Equipped
import ai.teemoon.providers.ProviderCredentials
import ai.teemoon.providers.AddEditProviderModel
import ai.teemoon.providers.ProviderHealing
import ai.teemoon.providers.EndpointModelCatalog
import ai.teemoon.providers.ModelCapabilities
import ai.teemoon.providers.ProviderRecents
import ai.teemoon.providers.unequipping
import ai.teemoon.providers.WhereRecentEntry
import ai.teemoon.providers.fromCapabilitySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import ai.teemoon.chat.search.ChatListSearchMemory
import ai.teemoon.chat.search.TranscriptDeepLink
import ai.teemoon.confidential.AttestationService
import ai.teemoon.confidential.TeeContext
import ai.teemoon.inference.GenerationTransport
import ai.teemoon.inference.LiteRTTransport
import ai.teemoon.inference.LlmError
import ai.teemoon.inference.LocalEngineCache
import ai.teemoon.inference.LocalEngineResidency
import ai.teemoon.inference.LocalGenerationGate
import ai.teemoon.inference.LocalInferenceError
import ai.teemoon.inference.OpenAiTransport
import ai.teemoon.inference.StreamCallbacks
import ai.teemoon.ondevice.LiteRtLmEngineFactory
import ai.teemoon.ondevice.NpuDispatch
import ai.teemoon.providers.CellularDownloadRequest
import ai.teemoon.providers.DownloadNetwork
import ai.teemoon.providers.KnownModel
import ai.teemoon.providers.LocalWarmUpPolicy
import ai.teemoon.providers.ModelBrowserDoor
import android.app.ActivityManager
import android.os.Build
import android.util.Log

/**
 * The five roots iOS builds in teemoonApp and passes with .environment(...) are
 * built here instead and passed through CompositionLocals. No DI framework: the
 * object graph is small and a framework adds audit surface for nothing (§3).
 */
class AppState(context: Context, dao: ChatDao? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val configFile = File(context.filesDir, "providers.json")
    private val store = ConfigStore(configFile)

    /** Provider API keys: AES-GCM under an Android Keystore master key, one file per endpoint account. */
    private val keystore = Keystore(File(context.filesDir, "keys"), AndroidKeystoreCipher())
    val credentials = ProviderCredentials(keystore, { _providers.value })
    // every key the app holds, for the debug copy path: a header carrying one is masked whatever its name
    init { DebugHeaderRedaction.storedSecrets = { _providers.value.map { credentials.credential(it) }.filter { it.isNotBlank() } + listOfNotNull(braveKey()) } }

    /**
     * Chat search: the FTS5 sidecar beside the Room database. Hardening on
     * Android is the manifest's allowBackup=false + data_extraction_rules, which
     * cover the whole app-private data dir; the callback is where a per-file
     * step would go if one were ever needed.
     */
    val search = ChatSearchService(scope, log = { Log.w("teemoon", it) })

    /** In-flight server-side Ollama pulls; app-level so a download outlives the sheet. Mutates on Main, as iOS does. */
    val downloads = OllamaDownloadCenter(scope)

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** Non-null when the config was unreadable — surfaced, never swallowed. */
    private val _configProblem = MutableStateFlow<String?>(null)
    val configProblem: StateFlow<String?> = _configProblem.asStateFlow()

    val selected: Provider? get() = _providers.value.firstOrNull { it.id == _selectedId.value }

    private val log: (String) -> Unit = { Log.w("teemoon", it) }

    /** User preferences (iOS AppSettings) over SharedPreferences. */
    val settings = AppSettings(object : Prefs {
        private val sp = context.getSharedPreferences("teemoon-settings", Context.MODE_PRIVATE)
        override fun getString(key: String) = sp.getString(key, null)
        override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
        override fun getBoolean(key: String) = if (sp.contains(key)) sp.getBoolean(key, false) else null
        override fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
        override fun getInt(key: String) = if (sp.contains(key)) sp.getInt(key, 0) else null
        override fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    }, log)
    private val _developerMode = MutableStateFlow(false)
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()
    fun setDeveloperMode(on: Boolean) { settings.developerModeEnabled = on; _developerMode.value = on }
    private val _groundingEnabled = MutableStateFlow(false)
    val groundingEnabled: StateFlow<Boolean> = _groundingEnabled.asStateFlow()
    fun setGroundingEnabled(on: Boolean) { settings.braveGroundingEnabled = on; _groundingEnabled.value = on }

    /** Real DCAP over Intel PCS collateral and the vendored dcap-qvl engine; NRAS and TLS attestation below complete the verifier fan-out. */
    private val dcap = DcapService(IntelPcsCollateralProvider(warn = log), AndroidDcapQvl, log)

    /**
     * The attestation session: the read gate, the verdict and THE send gate.
     * Re-attests whenever the active provider changes, as teemoonApp wires
     * `onActiveProviderChanged`.
     */
    val session = ConfidentialSession(scope, { selected }, credentialFor = ::credentialFor,
        // The fetcher logs through the app log (iOS: its os.Logger lines) — silent, a gateway leg that answered nothing
        // usable left no trace on a device where the same fetch worked from the JVM (2026-09-24).
        services = ConfidentialSession.Services(fetchRecord = AttestationService.recordFetcher(log = log), verifyDcap = { dcap.verify(it) }, verifyNras = { payload, nonce -> nras.verify(payload, nonce) }, verifyTls = { url -> tls.verify(url) }), warn = log)
    private val nras = NrasService(log = log)
    private val tls = TlsAttestationVerifier(log = log)

    /**
     * The one saved copy of every server's model list (iOS: `LiveCatalogStore.shared`,
     * under Application Support/ModelLists, excluded from backup — here
     * `noBackupFilesDir`, which is Android's name for the same promise). Counts,
     * browse rows, healing's model words and the trust ladder's encrypted picker all
     * read THIS, so they cannot disagree. teemoon ships no catalogue to fall back to.
     */
    val catalogStore = LiveCatalogStore(File(context.noBackupFilesDir, "ModelLists"),
        list = { s, b, k, h -> ModelCatalog.liveCatalog(s, b, k, h, warn = { log("[catalog] $it") }) })

    init {
        // near.ai's /endpoints, saved: the send path reads a model's direct host
        // synchronously (Provider.inferenceBaseUrl) before anything in this process
        // has fetched, so a cold launch must answer from disk — iOS keeps it in
        // UserDefaults. FIRST, before the session below attests anything.
        val directoryFile = EndpointDirectory.FileStore(File(context.filesDir, "endpoint-directory.json"))
        EndpointDirectory.configurePersistence(directoryFile)
        NearAIModelCatalog.endpointDirectory = EndpointDirectory(store = directoryFile)
        // Tiers next, and synchronously cheap: what a near.ai model is decides
        // whether the row claims encryption, and the id guess answers "own fleet"
        // for anything it has not seen (ContentView's .task, c3fb76d).
        catalogStore.seedNearAITiers()
        // A digest's provenance is immutable; the verified-digest cache persists so GitHub's anonymous
        // rate limit can never flip a verified image back to failed (iOS: UserDefaults).
        // The published source reviews ("fable miniaudit"): persisted so an offline launch still shows the last index
        // (iOS: UserDefaults), fetched when the trust sheet opens (TrustLadderView's .task). Never installed until
        // 2026-09-23 — every audit accessor answered null, so no miniaudit and no everyday audit line ever rendered.
        AuditIndex.shared = AuditIndex(store = FileAuditIndexStore(File(context.filesDir, "audit-index")))
        ProvenanceService.default = ProvenanceService(cache = FileProvenanceCacheStore(File(context.filesDir, "provenance-cache.json")), log = log)
        loadConfig()
        if (dao != null) {
            search.configure(context.getDatabasePath("teemoon-chat.db"), isStoredInMemoryOnly = false) { }
            scope.launch {
                search.reconcile(RoomChatSearchSource(dao))
                search.rehardenIfNeeded { }
            }
        }
        // No seed: an empty list is first run (iOS has no fixture either). The device scripts add the fixture server by url.
        heal()
        _developerMode.value = settings.developerModeEnabled
        _groundingEnabled.value = settings.braveGroundingEnabled
        settings.incrementNumberOfVisits()
        session.refreshAttestation()
    }

    /** The launch read of providers.json: a problem the user must SEE, never an empty list that looks like a first run. */
    private fun loadConfig() {
        when (val loaded = store.load()) {
            is ConfigLoad.Ok -> {
                _providers.value = loaded.snapshot.providers
                _selectedId.value = loaded.snapshot.selectedProviderId
                    ?: loaded.snapshot.providers.firstOrNull()?.id
                if (loaded.repaired) _configProblem.value = "config had unusable records; they were dropped"
            }
            is ConfigLoad.Quarantined ->
                _configProblem.value =
                    "config was unreadable (${loaded.reason}). It was moved to ${loaded.quarantinePath} — not deleted."
            ConfigLoad.Empty -> Unit   // nothing configured is a real state: the Where sheet's first-run hero owns it
        }
        // A corrupt file that fell back to the legacy blob is still a problem the user must SEE.
        store.loadFailure?.let { _configProblem.value = it }
    }

    /** Debug-only: a seeded launch that plants no config starts with none of the last journey's notices — the AppState is the process's. */
    fun uiTestClearConfigNotices() { _configProblem.value = null; _mergeNotice.value = null }

    /**
     * Debug-only (journeys, C9): re-read providers.json after [write] replaced it, then run the launch heal — the app's
     * own load path, which a test cannot otherwise reach because the AppState read the file before the activity started.
     */
    fun uiTestReloadConfig(write: (File) -> Unit) {
        write(configFile)
        _configProblem.value = null; _mergeNotice.value = null
        _providers.value = emptyList(); _selectedId.value = null
        loadConfig()
        heal()
    }

    /** A merge notice for the settings screen ("teemoon merged one duplicate setup — …"). */
    private val _mergeNotice = MutableStateFlow<String?>(null)
    val mergeNotice: StateFlow<String?> = _mergeNotice.asStateFlow()

    /**
     * Launch-time repairs iOS runs in ProviderStore: auto-labels off teemoon's own
     * "place model" shape, keyless self-hosted flags, duplicate endpoints, duplicate
     * machines. Each is a pure function; this applies the results and the log lines.
     */
    private fun heal() {
        var list = _providers.value
        ProviderHealing.healAutoLabels(list, catalogStore::allModels).let { if (it.renamed.isNotEmpty()) { log("[providers] renamed ${it.renamed.size} record(s) off teemoon's own label: ${it.renamed.joinToString()}"); list = it.providers } }
        ProviderHealing.healSelfHostedKeyFlags(list) { credentials.migratedCredential(it) }.let { if (it.renamed.isNotEmpty()) { log("[providers] cleared requiresAPIKey on keyless self-hosted: ${it.renamed.joinToString()}"); list = it.providers } }
        val hasKey: (Provider) -> Boolean = { credentials.migratedCredential(it).isNotEmpty() }
        for (merge in listOf(ProviderHealing.mergeDuplicateEndpoints(list, _selectedId.value, hasKey), ProviderHealing.mergeDuplicateMachines(list, _selectedId.value))) {
            if (merge.dropped.isEmpty()) continue
            list = merge.providers; merge.newCurrentProviderID?.let { _selectedId.value = it }; _mergeNotice.value = merge.notice
            // The survivor has its own key: the loser's may go. Otherwise the last copy stays — discarding it is not recoverable.
            merge.keysSafeToDelete.forEach { loser -> runCatching { credentials.removeSecrets(loser) } }
            log("[providers] ${merge.notice}")
        }
        // A Fireworks model that left serverless fails every send until moved (iOS 5d2fb66): move it, and say so once.
        ProviderHealing.healRetiredFireworksModels(list).let { heal ->
            if (heal.moved.isEmpty()) return@let
            list = heal.providers
            _mergeNotice.value = (listOfNotNull(_mergeNotice.value) + heal.notices).joinToString(" ")
            log("[providers] moved ${heal.moved.size} fireworks setup(s) off a retired model")
        }
        if (list != _providers.value) { _providers.value = list; persist() }
    }

    /** Equips [modelID] on [provider] and makes it current (iOS ProviderStore.activate). */
    fun activate(modelID: String, provider: Provider) {
        val updated = refillCapabilities(provider.equipping(modelID))
        _providers.value = _providers.value.map { if (it.id == updated.id) updated else it }
        select(updated.id)
    }

    /** Removes a model from a setup; the server stays even when it is the last one. Returns whether it is now empty. */
    fun unequip(modelID: String, provider: Provider): Boolean {
        val updated = provider.unequipping(modelID)
        _providers.value = _providers.value.map { if (it.id == updated.id) updated else it }
        persist(); if (_selectedId.value == updated.id) session.refreshAttestation()
        return updated.equipped.isEmpty()
    }

    /**
     * Registers a provider and, when given, its key. Keyed by ENDPOINT, so it
     * does not matter which record id ends up holding the setup. Returns the
     * Keystore failure message, or null.
     */
    fun addProvider(p: Provider, apiKey: String = ""): String? {
        _providers.value = _providers.value.filterNot { it.id == p.id } + p
        if (_selectedId.value == null) _selectedId.value = p.id
        persist()
        val failure = setKey(p.id, apiKey)
        if (_selectedId.value == p.id) session.refreshAttestation()   // the active record or its credential may have changed
        return failure
    }

    /** Stores (or clears, when blank) the key for a provider id. Returns the failure message, or null. */
    fun setKey(id: String, apiKey: String): String? = runCatching { storeCredential(apiKey, id) }.exceptionOrNull()?.message

    /**
     * The one way a key is written — setKey and the add/edit screen's store both come here. A changed or cleared key drops
     * the server's saved lists: they were the old account's, scoped by its key. The cleanup sat in setKey alone at first,
     * which the screen never calls (it saves through providerStore.setCredential), so the UI's key edits left the old
     * list on disk (the local audit of c0ed24c, 2026-10-13). Throws what the Keystore throws.
     */
    private fun storeCredential(apiKey: String, id: String) {
        val p = _providers.value.firstOrNull { it.id == id }
        val before = p?.let { credentials.credential(it) }.orEmpty()
        credentials.setCredential(apiKey, id)
        if (p != null && before.isNotEmpty() && before != apiKey.trim()) catalogStore.forget(p.endpoint)
    }

    fun hasKey(p: Provider): Boolean = credentials.credential(p).isNotEmpty()
    fun credentialFor(p: Provider): String = credentials.credential(p)

    /** The stored Brave key, or null when unset. */
    fun braveKey(): String? = keystore.load(BRAVE_ACCOUNT)?.trim()?.takeIf { it.isNotEmpty() }
    /** The key a turn may ground with: the toggle AND a key, as iOS's AppSettings.groundingAPIKey. */
    fun groundingKey(): String? = settings.groundingAPIKey(braveKey())
    fun hasBraveKey(): Boolean = braveKey() != null
    /** Stores (or clears, when blank) the Brave Search key and turns grounding on with it (iOS connectBraveGrounding). Returns the failure message, or null. */
    fun setBraveKey(apiKey: String): String? = runCatching {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) keystore.delete(BRAVE_ACCOUNT) else keystore.save(trimmed, BRAVE_ACCOUNT)
        setGroundingEnabled(trimmed.isNotEmpty())
    }.exceptionOrNull()?.message

    /** Ranked thread hits, or null when the index cannot answer (the caller falls back to the in-memory filter). */
    suspend fun searchThreads(query: String): List<ChatSearchResult>? = search.search(query)
    /** ChatListSearchMemory / TranscriptDeepLink — app-lifetime, as iOS's shared instances. */
    val listSearchMemory = ChatListSearchMemory()
    val deepLink = TranscriptDeepLink()

    fun select(id: String) {
        _selectedId.value = id; persist(); session.refreshAttestation()
        // Picking a model is the earliest honest signal it is about to be used: load it now, so the load overlaps the
        // user typing instead of landing on the first token (teemoonApp's onActiveProviderChanged, 56d51d7).
        warmUp(selected)
    }

    /** What the add/edit screen needs — iOS's ProviderStore surface, over this state. */
    val providerStore: AddEditProviderModel.Store = object : AddEditProviderModel.Store {
        override val providers: List<Provider> get() = _providers.value
        override var currentProviderID: String?
            get() = _selectedId.value
            set(value) { if (value != null) select(value) }
        override fun credential(provider: Provider): String = credentials.credential(provider)
        override fun credential(endpoint: String): String? = credentials.credential(endpoint)
        /** An endpoint already configured folds into that record (iOS ProviderStore.addProvider): one place, never two rows for one server. */
        override fun addProvider(provider: Provider): String {
            val added = ProviderHealing.addProvider(_providers.value, provider, _selectedId.value)
            _providers.value = added.providers
            added.newCurrentProviderID?.let { _selectedId.value = it }
            if (_selectedId.value == null) _selectedId.value = added.id
            persist(); return added.id
        }
        override fun updateProvider(provider: Provider) {
            val old = _providers.value.firstOrNull { it.id == provider.id }
            _providers.value = _providers.value.map { if (it.id == provider.id) provider else it }; persist()
            // moved to another server: the old one's saved lists go, unless another setup still talks to it. The screen
            // saves the endpoint before the key, so the key-change cleanup in storeCredential sees only the new endpoint
            // (the local audit of 15df68e, 2026-10-13).
            if (old != null && old.presetMatchKey != provider.presetMatchKey && _providers.value.none { it.presetMatchKey == old.presetMatchKey })
                catalogStore.forget(old.endpoint)
            if (_selectedId.value == provider.id) session.refreshAttestation()
        }
        override fun removeProvider(provider: Provider) = remove(provider.id)
        override fun setCredential(apiKey: String, providerID: String) { storeCredential(apiKey, providerID); if (_selectedId.value == providerID) session.refreshAttestation() }
    }

    // MARK: - The Where sheet (iOS WhereSheetView+Actions / HomeServerProbe)

    val network = NetworkPathObserver(context)
    val homeProbe = HomeServerProbe()

    fun storedKind(p: Provider): LocalServerKind? = when (store.kind(p.id)) {
        ServerKind.OLLAMA -> LocalServerKind.OLLAMA
        ServerKind.LM_STUDIO -> LocalServerKind.LM_STUDIO
        else -> null
    }
    fun servedModels(p: Provider): List<String>? = store.servedModels(p.id)
    fun credentialForEndpoint(endpoint: String): String = credentials.credential(endpoint) ?: ""
    fun dismissMergeNotice() { _mergeNotice.value = null }

    fun getPolicy(filter: WhereLocality?): WhereGetPolicy {
        val home = HashMap<String, WhereGetPolicy.HomeInfo>()
        for (p in _providers.value) homeProbe.info(p)?.let { home[p.id] = WhereGetPolicy.HomeInfo(it.kind, it.modelCount, it.warm) }
        return WhereGetPolicy(filter, _providers.value, network.isSatisfied.value, home, ::credentialFor, ::credentialForEndpoint,
            catalogCount = { catalogStore.count(it, browseCredential(it)) }, catalog = localCatalog,
            deviceFileMissing = { m -> m.fileName != m.generalFileName && !localStorage.isInstalled(m) })
    }

    /** The key a catalogue is fetched with — by record, then by endpoint (ProviderStore.browseCredential). */
    fun browseCredential(p: Provider): String = credentials.browseCredential(p)

    /**
     * ChatView.activeKnownModel + its loaders (iOS 835d7c8, c3fb76d): the catalogue entry for the model answering now,
     * through the SAME door the model browser opens — the saved live list first, the live fetch and OpenRouter's routes
     * behind it. NEVER NULL FOR A REAL PROVIDER, or the chip's menu renders empty and the long press looks broken: the
     * saved list can be behind (or absent), and identity needs no catalogue — the card opens with what is certain and
     * fills in from the live loader.
     */
    fun activeModelCard(): Pair<KnownModel, ModelBrowserDoor>? {
        val p = selected ?: return null
        if (p.model.isEmpty()) return null
        val door = ModelBrowserDoor.browsing(p, browseCredential(p), storedKind(p), catalogStore)
        val local = p.localModelID?.let { id -> LocalModelCatalog.all.firstOrNull { it.id == id } }
        val known = door.models.firstOrNull { it.id == p.model }
            ?: KnownModel(
                id = p.model,
                displayName = local?.displayName ?: ModelCatalog.compactName(p.model),
                vendor = WhereProviderPresentation.canonicalName(p), price = "",
                summary = local?.blurb,
                confidentialityNote = WhereProviderPresentation.placeCaption(p))
        return known to door
    }

    /**
     * Where's daily catalogue refresh, for setups the user actually has — never a
     * vendor they did not choose. Runs beside the home probe, not behind it: that
     * waits on a LAN box that may be asleep, and the counts should not queue behind it.
     */
    fun refreshCatalogues() {
        scope.launch { catalogStore.refreshAll(_providers.value, maxAgeMs = 86_400_000L, credential = ::browseCredential) }
    }

    private var retiredHealStarted = false

    /**
     * Launch-time version of [ProviderHealing.healRetiredNearAIModels]: asks near.ai what it serves (through the one
     * store, so Where's count and the browser's first frame get the same fresh list) and heals from that; a moved
     * ACTIVE setup re-verifies. Offline or keyless it moves nothing — a send would fail either way, and the next launch
     * tries again. Once per process: an activity recreated by a rotation is not a launch (iOS ContentView's .task).
     */
    fun healRetiredNearAIModelsFromLive() {
        if (retiredHealStarted) return
        retiredHealStarted = true
        val p = _providers.value.firstOrNull { WhereProviderPresentation.liveCatalogSource(it) == EndpointModelCatalog.Source.NEAR_AI } ?: return
        val key = browseCredential(p).takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            val served = catalogStore.refresh(p, key, maxAgeMs = 0) ?: return@launch
            val (healed, moved) = ProviderHealing.healRetiredNearAIModels(_providers.value, served.map { it.id })
            if (moved.isEmpty()) return@launch
            log("[providers] moved ${moved.size} near.ai setup(s) off a retired model: ${moved.joinToString { it.model }}")
            _providers.value = healed; persist()
            if (moved.any { it.id == _selectedId.value }) session.refreshAttestation()
        }
    }

    /** Seed from the persisted probe, probe each machine once, then fold what they serve into the equipped lists. */
    fun refreshHome() {
        homeProbe.seed(_providers.value, ::servedModels, ::storedKind)
        scope.launch { homeProbe.refresh(_providers.value, ::credentialFor); syncHomeEquipped() }
        refreshCatalogues()
    }

    /**
     * A machine's equipped list IS what it serves (plus what is still arriving).
     * A probe records server facts; only here does the selection follow them.
     */
    fun syncHomeEquipped() {
        for (provider in _providers.value) {
            if (WhereLocality.of(provider) != WhereLocality.HOME) continue
            val info = homeProbe.info(provider) ?: continue
            val served = info.models?.takeIf { it.isNotEmpty() } ?: continue
            store.recordProbe(provider.id, info.kind.stored, served)
            val arriving = downloads.downloads.value.filter { it.baseUrl == provider.openAIBaseUrl && !it.isFinished }.map { it.id }
            val target = served + arriving.filter { it !in served }
            if (target.toSet() == provider.equipped.toSet()) continue
            var updated = provider.copy(equippedModels = target)
            if (updated.model.isEmpty() || updated.model !in target) updated = updated.withModel(target[0])
            providerStore.updateProvider(updated)
        }
    }

    /** Ollama only. The row goes at once; if the server refuses, the row comes back and the failure is reported. */
    fun deleteFromServer(row: Equipped, onFailed: (String) -> Unit) {
        val base = row.provider.openAIBaseUrl ?: return
        var updated = row.provider.copy(equippedModels = row.provider.equipped.filter { it != row.modelID })
        if (updated.model == row.modelID) updated = updated.withModel(updated.equippedModels?.firstOrNull() ?: "")
        providerStore.updateProvider(updated)
        scope.launch {
            try {
                OllamaAdapter.deleteModel(row.modelID, base)
                homeProbe.forget(row.modelID, row.provider)
                ProviderRecents.forgetModel(store, row.modelID, row.provider)
            } catch (e: Exception) {
                providerStore.updateProvider(row.provider)
                onFailed("${row.modelID} is still on ${WhereProviderPresentation.canonicalName(row.provider)} — ${e.message}")
            }
        }
    }

    /** WhereSheetView.applyBrowseSelection: equip the pick (with its capabilities when the catalogue stated them) and make it current. */
    fun applyBrowseSelection(provider: Provider, model: KnownModel) {
        var updated = provider.equipping(model.id)
        ModelCapabilities.fromCapabilitySet(model.capabilities)?.let { updated = updated.copy(modelCapabilities = it) }
        val id = if (_providers.value.any { it.id == updated.id }) { providerStore.updateProvider(updated); updated.id } else providerStore.addProvider(updated)
        select(id)
    }

    /**
     * The Where sheet's browser door for a setup (ModelBrowserDoor.browsing): the saved list first, the live one
     * behind it, keyed with the browse credential and the machine's stored kind. The browse credential, not the
     * record's alone: the store scopes a keyed list by a hash of the key, so the count and the browser must ask
     * with the same one.
     */
    fun browseDoor(provider: Provider): ModelBrowserDoor {
        val key = browseCredential(provider)
        val door = ModelBrowserDoor.browsing(provider, key, storedKind(provider), catalogStore,
            onFailure = { log("[browse] live catalog failed: $it") })
        val inner = door.liveLoader ?: run { log("[browse] no live source for ${provider.endpoint}"); return door }
        return door.copy(liveLoader = {
            val list = inner()
            log("[browse] ${provider.name}: key=${if (key.isBlank()) "none" else "present"} live=${list?.size ?: "failed"}")
            list
        })
    }

    // MARK: - On-device inference (LocalRuntime / LocalModelCatalog / ChatGeneration+Stream.resolveLanguageModel)

    /** "Tensor G5" on a Pixel 10 Pro; empty below API 31 or where the device will not say. */
    private val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else ""
    /** The chip asks whether the equipped local model can answer: `localStorage.ref(id, socModelForUi) == null` is "not downloaded". */
    val socModelForUi: String get() = socModel
    val localStorage = LocalModelStorage(File(context.filesDir, "LocalModels"), LocalModelCatalog.forDevice(socModel))

    /** The engine files loaded right now — the Where sheet's phone rows read warm/cold from it (iOS 390d8fa). */
    val residentEngines = LocalEngineResidency.shared.resident
    /** Phone warmth: this device's bundle for [p] is a resident engine. Null when [p] is not on-device or not downloaded. */
    fun isPhoneWarm(p: Provider): Boolean? = p.localModelID?.let { id ->
        localStorage.ref(id, socModel)?.bundleFile?.let(LocalEngineResidency.shared::isResident)
    }
    /**
     * The local models that can answer right now, by id — STATE, so the chip and the where sheet redraw when a file
     * lands or goes. They used to ask the disk during composition, which nothing re-ran when the download finished:
     * the chip could say "not downloaded" over a file that was there and answering (Pixel, 2026-09-18). Refreshed on
     * every path that changes the disk: a finished download, a delete, a failure, and at launch.
     */
    private val _localRunnable = MutableStateFlow<Set<String>>(emptySet())
    val localRunnable: StateFlow<Set<String>> = _localRunnable.asStateFlow()
    fun refreshLocalRunnable() { _localRunnable.value = LocalModelCatalog.all.map { it.id }.filter { localStorage.ref(it, socModel) != null }.toSet() }
    /**
     * The catalog as this phone downloads it (LocalModel.forDevice): on Tensor the E2B row IS the Tensor bundle —
     * unless that file has refused to start here, when the row is the general one again. Read fresh: the marker
     * appears mid-session.
     */
    val localCatalog: List<LocalModel> get() = LocalModelCatalog.forDevice(socModel) { localStorage.npuUnusable(it) }
    val localDownloads = LocalModelDownloader(scope, localStorage, log = log, onMobileData = network.mobileData).also { d ->
        // A finished, verified download registers the model as a provider, so it shows up runnable without a second tap.
        d.onInstalled = { model -> refreshLocalRunnable(); val p = (_providers.value.firstOrNull { it.localModelID == model.id }?.let { existing -> if (existing.name == model.displayName) existing else existing.copy(name = model.displayName).also { providerStore.updateProvider(it) } }) ?: Provider.local(model).let { minted -> providerStore.addProvider(minted).let { id -> _providers.value.first { it.id == id } } }; activate(model.id, p) }
    }
    init {
        // A download keeps the network only from inside a foreground service (DownloadService): every change to the
        // jobs starts it, moves its progress bar, or stops it. Collected on Main, which DownloadService requires.
        val appContext = context.applicationContext
        scope.launch { localDownloads.jobs.collect { jobs -> DownloadService.reflect(appContext, jobs.values) } }
    }
    // The factory picks the accelerator and PROVES it decodes before accepting it (BackendLadder / BackendMemo).
    // Device facts are read here, not there, so the ladder stays testable off-device. The directory Backend.NPU lists
    // for a vendor dispatch library is the APK's own .so directory on a Tensor phone, and a directory of links to the
    // Qualcomm dispatcher and its QNN runtime on a Snapdragon 8 Elite (NpuDispatch says why).
    private val engineFactory = LiteRtLmEngineFactory(
        context.cacheDir.path, log,
        nativeLibraryDir = NpuDispatch.directory(context.applicationInfo.nativeLibraryDir, socModel, context.noBackupFilesDir, log),
        socModel = socModel,
        hardware = Build.HARDWARE.orEmpty(),
        onNpuUnusable = { path, reason ->
            // The Tensor file refused on this phone: remember it against the file, so the next message runs the general
            // one (or the where sheet offers its download). A delete of the weights clears it.
            val id = LocalModelCatalog.all.firstOrNull { m -> LocalModelCatalog.npuBundle(m, socModel)?.let { localStorage.npuFile(m, it).path == path } == true }?.id
            if (id != null) { localStorage.markNpuUnusable(id, reason); refreshLocalRunnable(); log("[litert] NPU bundle marked unusable for $id: $reason") }
            else log("[litert] NPU bundle refused at $path and no catalog model owns it: $reason")
        },
    )
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    /** Debug-only (UiTestSeeding.EXTRA_AVAILABLE_MB): what the get section is told is free; null reads the device. */
    @Volatile var uiTestAvailableMB: Int? = null
    /** The get section's figure — the device's, unless a journey pinned it. The load gate always reads the device. */
    fun availableMBForUi(): Int = uiTestAvailableMB ?: availableMB()
    /** Memory this process may still allocate, in MB — availMem less the low-memory threshold. 0 when the API is silent. */
    fun availableMB(): Int = runCatching { val info = ActivityManager.MemoryInfo(); activityManager.getMemoryInfo(info); ((info.availMem - info.threshold) / (1024 * 1024)).toInt().coerceAtLeast(0) }.getOrDefault(0)
    val engineCache = LocalEngineCache(engineFactory, memoryCheck = { need ->
        // Decline-to-start beats dying mid-answer. 0 means the API is unavailable — don't block on a number we couldn't read.
        val available = availableMB(); if (available > 0 && available < need) throw LocalInferenceError.InsufficientMemory(need, available)
    }, log = log)
    /** Releases loaded weights when the system asks for memory back (TeemoonApplication.onTrimMemory). */
    fun evictEngines(trimLevel: Int? = null) { trimLevel?.let { log("[litert] trim memory level $it") }; engineCache.evictAll() }

    /**
     * Builds the engine for an on-device provider ahead of its first turn — on selection and at launch, per LiteRT-LM's
     * guidance to create the engine once, early and off the main thread (LiteRTTransport.warmUp, 56d51d7). A turn that
     * arrives while this runs SHARES the build (LocalEngineCache.building). Skipped while a generation is in flight: that
     * turn is loading it anyway. The memory gate inside `engine()` still decides; a refusal is quiet.
     *
     * The key is the one [transportFor] will ask for — this phone's bundle and its context — or the turn would find no
     * engine under its key and build a second one.
     */
    fun warmUp(provider: Provider?) {
        val ref = LocalWarmUpPolicy.target(provider) { localStorage.ref(it, socModel) } ?: return
        scope.launch(Dispatchers.IO) {
            // Not behind the user's back (BackgroundWarmUp): a download finishing while the app is away selects its
            // model, and iOS's warm-up needs no such check only because iOS suspends a backgrounded app.
            if (!BackgroundWarmUp.shouldStart(BackgroundWarmUp.isForeground(), LocalGenerationGate.shared.isBusy)) {
                log("[litert] warm-up skipped: ${if (ai.teemoon.inference.LocalGenerationGate.shared.isBusy) "a turn is loading it" else "the app is in the background"}"); return@launch
            }
            log("[litert] warm-up: ${java.io.File(ref.bundleFile).name}")
            runCatching { engineCache.engine(ref.bundleFile, ref.contextTokens ?: LiteRTTransport.DEFAULT_CONTEXT_TOKENS,
                ref.sizeMB, LocalMemory.headroomMB, ref.requiresNpu) }
                .onFailure { log("[litert] warm-up declined: ${it.message}") }
            // the app left while it built: the trim that evicts engines has already fired, so this load gives it back
            if (BackgroundWarmUp.shouldEvictAfterBuild(BackgroundWarmUp.isForeground(), LocalGenerationGate.shared.isBusy)) {
                log("[litert] warm-up finished after the app left — evicting"); engineCache.evictAll()
            }
        }
    }

    // The launch warm-up is MainActivity.onStart's (iOS b42f5d8): it runs at launch AND on every return, because
    // backgrounding evicts (TeemoonApplication.onTrimMemory) and a returning user's first message paid the engine load.

    /**
     * android-only: the equipped local model's KV window AS THIS DEVICE RUNS IT — an NPU bundle's is compiled in
     * (4096 on Tensor), the general bundle takes the transport's default. Sizes the search payload; null for a cloud model.
     */
    fun localContextTokens(provider: Provider): Int? =
        provider.localModelID?.let { localStorage.ref(it, socModel)?.contextTokens ?: LiteRTTransport.DEFAULT_CONTEXT_TOKENS }

    /** ChatGeneration+Stream.resolveLanguageModel: a local place runs LiteRT, anything else the HTTP transport. */
    fun transportFor(endpoint: String, model: String, context: TeeContext?, events: StreamCallbacks): GenerationTransport {
        val place = selected
        val localID = place?.localModelID
        if (localID != null) {
            // Build.SOC_MODEL picks this chip's NPU bundle when one is downloaded; the general bundle otherwise.
            val ref = localStorage.ref(localID, socModel) ?: throw LlmError(LlmError.Source.Provider(place.name),
                "${place.name} isn't downloaded on this device. Open where to download it.")
            return LiteRTTransport(ref.bundleFile, ref.sizeMB, cache = engineCache, events = events, headroomMB = LocalMemory.headroomMB, backendLabel = engineFactory.backendLabel, requireNpu = ref.requiresNpu,
                // an NPU bundle's window is compiled in; the general bundle takes the transport's default
                contextTokens = ref.contextTokens ?: LiteRTTransport.DEFAULT_CONTEXT_TOKENS, log = log)
        }
        return OpenAiTransport(endpoint, model, apiKey = place?.let(::credentialFor), authHeaderName = place?.authHeaderName,
            teeContext = context, extraParams = place?.extraParams ?: emptyMap(), providerName = place?.name ?: endpoint)
    }

    /**
     * A multi-gigabyte download waiting on the user's answer, or null. The Where
     * sheet renders the question; [answerCellularDownload] carries the answer back.
     */
    private val _pendingCellularDownload = MutableStateFlow<CellularDownloadRequest?>(null)
    val pendingCellularDownload: StateFlow<CellularDownloadRequest?> = _pendingCellularDownload

    /**
     * WhereSheetView.startAndSelect: register the model as a place, make it
     * current, start the download.
     *
     * The place is activated either way. The gate decides only whether the BYTES
     * move now — a user who picks a model and then says "wait for wi-fi" has
     * still chosen that model, and making them choose it twice would be the
     * wrong reading of a question about data.
     */
    fun startAndSelect(model: LocalModel) {
        // the record that LANDED, by the id addProvider returns — never the one minted here (a fold keeps another id)
        val provider = _providers.value.firstOrNull { it.localModelID == model.id }
            ?: Provider.local(model).let { minted -> providerStore.addProvider(minted).let { id -> _providers.value.first { it.id == id } } }
        activate(provider.model, provider)
        if (localStorage.isInstalled(model)) return
        _pendingCellularDownload.value = CellularDownloadRequest.resolve(
            model, pathIsExpensive = network.isMobileData, pathIsConstrained = network.isConstrained,
        ) { m, net -> localDownloads.start(m, network = net) }
    }

    /**
     * The user's answer. A null [network] is a dismissal and starts nothing —
     * closing the question is not consent to spend the data.
     */
    fun answerCellularDownload(network: DownloadNetwork?) {
        val pending = _pendingCellularDownload.value ?: return
        _pendingCellularDownload.value = null
        // The answer IS the allowance: "wait for wi-fi" is a WIFI_ONLY job that parks on mobile data, "download now"
        // is ANY. This dropped the choice and started every download the same way until 2026-09-24.
        if (network != null) localDownloads.start(pending.model, network = network)
    }

    /** WhereSheetView.deleteWeights: the download stops, the failure clears, the bundle goes, the place goes with it. */
    fun deleteWeights(model: LocalModel) {
        localDownloads.cancel(model.id); localDownloads.clearFailure(model.id)
        localStorage.delete(model); refreshLocalRunnable()
        _providers.value.filter { it.localModelID == model.id }.forEach { remove(it.id) }
    }

    /** Stamps a model as used and re-reads the list so the section reflects it. */
    fun recordUse(provider: Provider) { ProviderRecents.recordUse(store, provider) }

    /** What has answered lately, newest first — the Where sheet's "recently used" rows. */
    fun recentlyUsed(limit: Int = 3): List<WhereRecentEntry> = ProviderRecents.recentlyUsed(store, _providers.value, limit)

    /** Tapping a recent: re-equip the model on its provider and make it active. */
    fun activateRecent(entry: WhereRecentEntry) {
        val live = _providers.value.firstOrNull { it.id == entry.providerID } ?: return
        _providers.value = _providers.value.map { if (it.id == live.id) it.equipping(entry.modelID) else it }
        select(live.id)
    }

    /** The trust sheet's "re-verify": keep the record on screen while the fresh one loads. */
    fun reverify() = session.refreshAttestation(keepExisting = true)
    /** Bounded and TTL-gated (an hour); a failed fetch keeps whatever the persisted snapshot held. */
    fun loadAuditIndex() = AuditIndex.shared.loadIfNeeded(scope)

    /** Removes the provider AND its stored key — an add/delete cycle must not leave an orphaned secret. */
    fun remove(id: String) {
        val removed = _providers.value.firstOrNull { it.id == id }
        removed?.let { credentials.removeSecrets(it) }
        _providers.value = _providers.value.filterNot { it.id == id }
        // ...and the server's saved lists, unless another setup still talks to it (LiveCatalogStore.forget had no caller,
        // so a list scoped by the key outlived both; the local audit, 2026-10-13)
        if (removed != null && _providers.value.none { it.presetMatchKey == removed.presetMatchKey }) catalogStore.forget(removed.endpoint)
        if (_selectedId.value == id) { _selectedId.value = _providers.value.firstOrNull()?.id; session.refreshAttestation() }
        persist()
    }

    fun dismissProblem() { _configProblem.value = null }

    /** Saves THROUGH the store's last snapshot, so probe facts, per-model capabilities and lastUsedAt survive. */
    private fun persist() {
        // A move left capabilities unknown (Provider.withModel); refill each from its model's own row before the
        // projection reads them, as ProviderStore.save does (iOS 9e47a63).
        _providers.value = _providers.value.map(::refillCapabilities)
        store.save(_providers.value, _selectedId.value)
    }

    private fun refillCapabilities(p: Provider): Provider = store.refilled(p)

    companion object {
        /** iOS: `BraveWebSearchTool.keychainKey`. */
        const val BRAVE_ACCOUNT = "brave-grounding"
    }
}

/** [AuditIndex.Store] over two files: the index bytes exactly as fetched, and the fetch time. Written atomically. */
private class FileAuditIndexStore(private val dir: File) : AuditIndex.Store {
    private val indexFile get() = File(dir, "index.json")
    private val stampFile get() = File(dir, "fetched-at")
    private val etagFile get() = File(dir, "etag")
    override fun loadIndex(): ByteArray? = runCatching { indexFile.takeIf { it.isFile }?.readBytes() }.getOrNull()
    override fun loadFetchedAtMs(): Long? = runCatching { stampFile.readText().trim().toLong() }.getOrNull()
    override fun save(index: ByteArray, fetchedAtMs: Long) {
        runCatching {
            dir.mkdirs()
            File(dir, "index.json.tmp").apply { writeBytes(index); renameTo(indexFile) }
            File(dir, "fetched-at.tmp").apply { writeText(fetchedAtMs.toString()); renameTo(stampFile) }
        }
    }
    override fun loadETag(): String? = runCatching { etagFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotEmpty() } }.getOrNull()
    override fun saveETag(etag: String?) {
        runCatching { if (etag == null) etagFile.delete() else { dir.mkdirs(); File(dir, "etag.tmp").apply { writeText(etag); renameTo(etagFile) } } }
    }
    override fun touch(fetchedAtMs: Long) {
        runCatching { dir.mkdirs(); File(dir, "fetched-at.tmp").apply { writeText(fetchedAtMs.toString()); renameTo(stampFile) } }
    }
}
