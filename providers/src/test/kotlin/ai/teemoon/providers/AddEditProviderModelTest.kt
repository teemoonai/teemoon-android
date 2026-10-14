// ports: teemoonTests/AddEditProviderModelTests.swift @ v1.0.1 (both), teemoonTests/HTTPSeamTests.swift @ v1.0.1
//        ("Provider key validation is offline-testable", 3) + android-only: endpoint normalisation, key policy, preset switching, save folding.
package ai.teemoon.providers

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddEditProviderModelTest {
    /** Offline probe: every surface answers without the network, so the re-probe after a delete runs for real and costs nothing. */
    private fun offlineCatalog() = EndpointProbe.Catalog(
        detectKind = { LocalServerKind.UNKNOWN },
        listOllama = { EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.NOTHING_LISTENING) },
        listLMStudio = { EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.NOTHING_LISTENING) },
        liveCatalog = { _, _, _, _ -> EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.NOTHING_LISTENING) },
        loadedOllama = { emptySet() }, loadedLMStudio = { emptySet() },
        validateKey = { _, _ -> ProviderKeyValidator.ValidationResult.OTHER_FAILURE })

    private fun makeForm() = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply {
        scheme = EndpointScheme.HTTP; endpointHost = "box.local:11434/v1"; model = "gemma4:e4b"; probeCatalog = offlineCatalog()
    }
    private val gemma = KnownModel(id = "gemma4:e4b", displayName = "gemma4", vendor = "", price = "")

    @Test fun failedDeleteSurfacesAlertAndKeepsSelection() = runBlocking {
        val form = makeForm(); var haptics = 0
        form.deleteModelFromServer = { _, _ -> throw OllamaPullException(500, "delete failed on server") }
        form.onPlayHaptic = { haptics++ }
        form.performDelete(gemma)
        assertTrue(form.modelDeleteError!!.contains("gemma4")); assertTrue(form.modelDeleteError!!.contains("delete failed on server"))
        assertEquals("gemma4:e4b", form.model); assertTrue(form.hasProbed); assertEquals(0, haptics); assertNull(form.pendingDelete)
    }

    @Test fun successfulDeleteClearsSelectionAndRaisesNoAlert() = runBlocking {
        val form = makeForm(); val deleted = ArrayList<String>(); var haptics = 0
        form.deleteModelFromServer = { id, _ -> deleted.add(id) }
        form.onPlayHaptic = { haptics++ }
        form.performDelete(gemma)
        assertEquals(listOf("gemma4:e4b"), deleted); assertNull(form.modelDeleteError); assertEquals("", form.model); assertTrue(form.hasProbed); assertEquals(1, haptics)
    }

    // MARK: - Provider key validation is offline-testable

    @Test fun http200IsSuccess() = runBlocking { assertEquals(ProviderKeyValidator.ValidationResult.SUCCESS, ProviderKeyValidator.validate("sk-ok", ProviderKeyValidator.Endpoint.NEAR_AI) { _, _ -> 200 }) }
    @Test fun http401IsUnauthorized() = runBlocking { assertEquals(ProviderKeyValidator.ValidationResult.UNAUTHORIZED, ProviderKeyValidator.validate("sk-bad", ProviderKeyValidator.Endpoint.BRAVE_SEARCH) { _, _ -> 401 }) }
    @Test fun transportErrorIsOtherFailure() = runBlocking { assertEquals(ProviderKeyValidator.ValidationResult.OTHER_FAILURE, ProviderKeyValidator.validate("sk", ProviderKeyValidator.Endpoint.NEAR_AI) { _, _ -> throw IOException("boom") }) }

    // MARK: - Android-only

    /** THE BARE HOST:PORT CASE: `:11434` gains `/v1`; a pasted completions URL loses its suffix; a typed path is a decision. */
    @Test fun normalisedBase_suppliesV1OnlyForAnEmptyPath() {
        val f = AddEditProviderModel(AddEditProviderModel.Mode.Add)
        f.endpointEdited("https://ringzero.tailnet-name.ts.net:11434"); assertEquals("https://ringzero.tailnet-name.ts.net:11434/v1", f.normalisedBase)
        f.endpointEdited("http://box.local:11434/v1/chat/completions"); assertEquals(EndpointScheme.HTTP, f.scheme); assertEquals("http://box.local:11434/v1", f.normalisedBase)
        f.endpointEdited("https://proxy.example/llm"); assertEquals("https://proxy.example/llm", f.normalisedBase)
    }

    /** Key policy: a self-hosted box hides the key field and saves keyless; a 401 reveals it and marks the key required; cloud always shows it. */
    @Test fun keyFieldAndRequirementFollowTheEndpoint() {
        val f = AddEditProviderModel(AddEditProviderModel.Mode.Add)
        f.endpointEdited("http://192.168.1.20:11434/v1"); assertTrue(f.isSelfHosted); assertFalse(f.showKeyField); assertFalse(f.requiresAPIKey)
        f.authRequirement = AddEditProviderModel.AuthRequirement.NEEDED; assertTrue(f.showKeyField); assertTrue(f.requiresAPIKey); assertEquals("api key — required", f.apiKeyPlaceholder)
        f.endpointEdited("https://api.x.ai/v1"); assertFalse(f.isSelfHosted); assertTrue(f.showKeyField); assertTrue(f.requiresAPIKey); assertTrue(f.probeBlockedOnMissingKey)
        f.apiKey = "xai-…"; assertFalse(f.probeBlockedOnMissingKey); assertEquals(" — and sent only to api.x.ai", f.keyDestinationClause)
    }

    private class FakeStore(override var providers: List<Provider> = emptyList()) : AddEditProviderModel.Store {
        override var currentProviderID: String? = null
        val keys = HashMap<String, String>(); val endpointKeys = HashMap<String, String>(); var keystoreFails = false
        override fun credential(provider: Provider) = keys[provider.id] ?: ""
        override fun credential(endpoint: String) = endpointKeys[endpoint]
        override fun addProvider(provider: Provider): String {
            providers.firstOrNull { it.sameEndpoint(provider) }?.let { existing -> providers = providers.map { if (it.id == existing.id) provider.copy(id = existing.id) else it }; return existing.id }
            providers = providers + provider; return provider.id
        }
        override fun updateProvider(provider: Provider) { providers = providers.map { if (it.id == provider.id) provider else it } }
        override fun removeProvider(provider: Provider) { providers = providers.filterNot { it.id == provider.id } }
        override fun setCredential(apiKey: String, providerID: String) { if (keystoreFails) throw IllegalStateException("keystore refused"); keys[providerID] = apiKey }
    }

    /** Switching preset tiles keeps the key typed under each; a saved key for the endpoint is picked up. */
    @Test fun presetSwitchingKeepsTypedKeysAndPicksUpSavedOnes() {
        val store = FakeStore(); store.endpointKeys[Provider.fireworks.endpoint] = "fw-saved"
        val f = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply { this.store = store }
        f.loadInitialValues(); assertEquals("near.ai", f.selectedPresetName)   // the first preset not yet configured
        f.applyPresetChange("", "near.ai"); assertEquals("near.ai", f.name); assertEquals(Provider.nearAI.model, f.model); assertEquals("cloud-api.near.ai/v1/chat/completions", f.endpointHost)
        f.apiKey = "near-typed"
        f.applyPresetChange("near.ai", "Fireworks"); assertEquals("fw-saved", f.apiKey); assertEquals("fireworks", f.name)
        f.applyPresetChange("Fireworks", "near.ai"); assertEquals("near-typed", f.apiKey)
        assertEquals("https://cloud.near.ai/signin", f.providerConsoleURL); assertEquals("near.ai", f.consoleDisplayName)
    }

    /** save(): the normalised base is what is stored, an add activates, an existing endpoint folds into its record, and a Keystore failure keeps the screen up. */
    @Test fun saveRefusesAnInvalidForm() {
        // A host with no model must not become a stored, activated place.
        val store = FakeStore(); val f = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply { this.store = store }
        f.name = "box"; f.endpointEdited("http://10.0.2.2:8765")
        assertFalse(f.isValid); assertFalse(f.save()); assertTrue(store.providers.isEmpty()); assertEquals(null, store.currentProviderID)
        f.model = "fake-model"; assertTrue(f.isValid); assertTrue(f.save()); assertEquals(1, store.providers.size)
    }

    @Test fun saveNormalisesFoldsActivatesAndSurfacesKeystoreFailure() {
        val store = FakeStore(); val f = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply { this.store = store }
        f.name = "box"; f.endpointEdited("http://box.local:11434"); f.model = "gemma4:e4b"; f.authRequirement = AddEditProviderModel.AuthRequirement.NEEDED; f.apiKey = "k1"
        assertTrue(f.save()); val saved = store.providers.single()
        assertEquals("http://box.local:11434/v1/chat/completions", saved.endpoint); assertTrue(saved.requiresAPIKey); assertEquals(saved.id, store.currentProviderID); assertEquals("k1", store.keys[saved.id])
        val g = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply { this.store = store }
        g.name = "box again"; g.endpointEdited("http://box.local:11434/v1"); g.model = "qwen3.5:4b"; g.apiKey = "k2"
        assertTrue(g.save()); assertEquals(1, store.providers.size); assertEquals("k2", store.keys[saved.id])   // folded into the existing record, key written where the store put it
        store.keystoreFails = true
        val h = AddEditProviderModel(AddEditProviderModel.Mode.Edit(store.providers.single())).apply { this.store = store }
        h.loadInitialValues(); assertEquals("k2", h.apiKey); h.apiKey = ""
        assertFalse(h.save()); assertTrue(h.keySaveError!!.contains("keystore"))
    }

    // MARK: - AddEditProviderModelRequiredKeyTests (AddEditProviderModelTests @ v1.0.4 — iOS 148a131 + a9ba0ef)

    @Test fun cloudPresetCannotBeAddedWithoutAKey() {
        val store = FakeStore(); val form = AddEditProviderModel(AddEditProviderModel.Mode.Add).apply { this.store = store }
        form.apply(Provider.braveAnswers)
        assertTrue(form.requiresAPIKey)
        // Save stays ENABLED; the refusal is inline, on the field.
        assertTrue(form.isValid)
        assertEquals("api key — required", form.apiKeyPlaceholder)
        assertEquals(null, form.keyFieldError)

        assertFalse(form.save())
        assertTrue("a keyless cloud setup must not be created", store.providers.isEmpty())
        assertTrue(form.keyFieldError?.contains("api key") == true)
        assertEquals(1, form.keyFocusRequest)

        form.apiKey = "   "
        assertFalse(form.save()); assertEquals(2, form.keyFocusRequest)

        // Typing clears the error; saving then stores the setup and its key.
        form.apiKey = "BSA-test"
        assertEquals(null, form.keyFieldError)
        assertEquals("api key", form.apiKeyPlaceholder)
        assertTrue(form.save())
        assertEquals("BSA-test", store.keys[store.providers.single().id])
    }

    @Test fun selfHostedSetupStillSavesKeyless() {
        val form = AddEditProviderModel(AddEditProviderModel.Mode.Add)
        form.scheme = EndpointScheme.HTTP; form.endpointHost = "box.local:11434/v1"; form.model = "gemma4:e4b"; form.name = "box"
        assertFalse(form.requiresAPIKey); assertFalse(form.missingRequiredKey)
    }

    /** A tester cleared the key of a saved Brave Answers setup from settings and could save. Revoking is deleting the setup. */
    @Test fun editingRefusesAnEmptiedKey() {
        val saved = Provider.braveAnswers
        val store = FakeStore(listOf(saved)); store.keys[saved.id] = "BSA-old"
        val form = AddEditProviderModel(AddEditProviderModel.Mode.Edit(saved)).apply { this.store = store }
        form.loadInitialValues()
        assertEquals("BSA-old", form.apiKey)
        form.apiKey = ""
        assertTrue(form.missingRequiredKey)
        assertFalse(form.save()); assertTrue(form.keyFieldError != null)
        assertEquals("a refused save must not touch the stored key", "BSA-old", store.keys[saved.id])
        form.apiKey = "BSA-new"
        assertTrue(form.save()); assertEquals("BSA-new", store.keys[saved.id])
    }

    /** The clear path still exists where it is safe: a self-hosted box that had a key and no longer needs one. */
    @Test fun selfHostedEditCanStillClearItsKey() {
        val box = Provider(id = "box", name = "box", endpoint = "http://box.local:11434/v1/chat/completions", model = "gemma4:e4b", requiresAPIKey = true)
        val store = FakeStore(listOf(box)); store.keys[box.id] = "old"
        val form = AddEditProviderModel(AddEditProviderModel.Mode.Edit(box)).apply { this.store = store }
        form.loadInitialValues()
        form.apiKey = ""
        assertFalse(form.missingRequiredKey)
        assertTrue(form.save())
    }

    /** aProbeDoesNotTouchTheSavedCatalogueUntilSave (AddEditProviderModelTests @ v1.0.4, iOS 9e47a63). */
    @Test fun aProbeDoesNotTouchTheSavedCatalogueUntilSave() = runBlocking {
        val store = LiveCatalogStore(Files.createTempDirectory("editor-catalog").toFile())
        store.record(listOf(KnownModel(id = "accounts/a/models/saved", displayName = "saved", vendor = "a", price = "")), Provider.fireworks, "key-one")
        val form = AddEditProviderModel(AddEditProviderModel.Mode.Edit(Provider.fireworks))
        form.scheme = EndpointScheme.HTTPS
        form.endpointHost = Provider.fireworks.endpoint.removePrefix("https://")
        form.catalogStore = store
        form.apiKey = "key-two"
        val probed = KnownModel(id = "accounts/b/models/probed", displayName = "probed", vendor = "b", price = "")
        form.probeCatalog = offlineCatalog().copy(liveCatalog = { _, _, _, _ -> EndpointModelCatalog.ProbeResult.Connected(listOf(probed)) })
        form.probe(userInitiated = true)
        assertEquals(listOf("accounts/b/models/probed"), form.fetchedModels.map { it.id })
        assertEquals("untouched", 1, store.count(Provider.fireworks, "key-one"))
        assertEquals("not written", null, store.count(Provider.fireworks, "key-two"))
    }
}
