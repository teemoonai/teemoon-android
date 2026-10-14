// ports: teemoonTests/ConfigStoreTests.swift @ v1.0.1 (Config store, Probe facts, Deleting a model — the
//        five "through ProviderStore" tests and the two recentlyUsed assertions are ProviderStore's, a human row;
//        stampUse / recordProbe / forgetModel are exercised directly on the store here)
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer

/**
 * Pins the persistence change. The test that matters most is
 * migrationPreservesProviderIDs: API keys live in the Keystore under the
 * provider id, and a migration that mints fresh ids orphans every one of them
 * SILENTLY — a missing key reads as "not configured", not as an error.
 */
class ConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("configstore-${UUID.randomUUID()}")
    private fun fileIn(d: File) = File(d, "teemoon-config.json")

    private data class Legacy(
        override val providersJSON: String? = null,
        override val currentProviderID: String? = null,
        override val whereRecentsJSON: String? = null,
    ) : LegacyConfigSource

    private fun cloud(name: String, endpoint: String, model: String, models: List<String>? = null, caps: ModelCapabilities? = null) =
        Provider(id = UUID.randomUUID().toString(), name = name, endpoint = endpoint, model = model, modelCapabilities = caps, equippedModels = models)

    private val json = Json { encodeDefaults = true }
    private fun legacyBlob(providers: List<Provider>) = json.encodeToString(ListSerializer(Provider.serializer()), providers)

    private fun store(d: File, legacy: LegacyConfigSource = Legacy()) = ConfigStore(fileIn(d), legacy)

    // MARK: - Migration (§4)

    @Test fun migrationPreservesProviderIDs() {
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1"); val b = cloud("openrouter", "https://openrouter.ai/api/v1", "sonnet")
        val s = store(dir(), Legacy(providersJSON = legacyBlob(listOf(a, b))))
        val snapshot = (s.load() as ConfigLoad.Ok).snapshot
        assertTrue(s.didMigrate)
        assertEquals(setOf(a.id, b.id), snapshot.servers.map { it.id }.toSet())
    }

    @Test fun migrationOfAProviderSavedBeforeEquipping() {
        val solo = cloud("brave", "https://api.search.brave.com", "answers")
        assertNull(solo.equippedModels)
        val snapshot = (store(dir(), Legacy(providersJSON = legacyBlob(listOf(solo)))).load() as ConfigLoad.Ok).snapshot
        assertEquals(1, snapshot.equipped.size)
        assertEquals("answers", snapshot.equipped[0].modelID); assertEquals(solo.id, snapshot.equipped[0].serverID)
    }

    @Test fun migrationOfALocalProvider() {
        val local = Provider(id = UUID.randomUUID().toString(), name = "Gemma 4", endpoint = "on-device", model = "gemma-4-e2b",
            requiresAPIKey = false, localModelID = "gemma-4-e2b", equippedModels = listOf("gemma-4-e2b"))
        val snapshot = (store(dir(), Legacy(providersJSON = legacyBlob(listOf(local)))).load() as ConfigLoad.Ok).snapshot
        assertEquals(ServerKind.ON_DEVICE, snapshot.servers.first().kind)
        // `isLocal` is what the whole app branches on — it must survive the trip.
        val projected = ProviderConfigProjection.providers(snapshot).providers
        assertTrue(projected.first().isLocal); assertEquals("gemma-4-e2b", projected.first().localModelID)
    }

    @Test fun migrationCarriesTheSelection() {
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1", "qwen3.5"))
        val snapshot = (store(dir(), Legacy(providersJSON = legacyBlob(listOf(a)), currentProviderID = a.id)).load() as ConfigLoad.Ok).snapshot
        val current = snapshot.equipped.first { it.id == snapshot.currentEquippedID }
        assertEquals(a.id, current.serverID); assertEquals("glm-5.1", current.modelID)
        assertEquals(a.id, ProviderConfigProjection.providers(snapshot).currentProviderID)
    }

    /** The iOS recents blob (seconds since 2001) becomes lastUsedAt on the row it belongs to. */
    @Test fun migrationAbsorbsRecents() {
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1", "qwen3.5"))
        val usedMs = 1_753_000_000_000L
        val recordedAt = 1_753_000_000.0 - ConfigStore.SWIFT_REFERENCE_DATE_EPOCH_SECONDS
        val recents = """[{"providerID":"${a.id}","modelID":"qwen3.5","recordedAt":$recordedAt,"modelLabel":"Qwen","placeCaption":"near.ai","localityRaw":"cloud"}]"""
        val snapshot = (store(dir(), Legacy(providersJSON = legacyBlob(listOf(a)), whereRecentsJSON = recents)).load() as ConfigLoad.Ok).snapshot
        assertEquals(usedMs, snapshot.equipped.first { it.modelID == "qwen3.5" }.lastUsedAt)
        assertNull(snapshot.equipped.first { it.modelID == "glm-5.1" }.lastUsedAt)
    }

    /** A v1 blob written before equipping, capabilities or local models still decodes. */
    @Test fun migrationOfCapturedLegacyJSON() {
        val id = UUID.randomUUID().toString()
        val raw = """[{"id":"$id","name":"near.ai","endpoint":"https://api.near.ai/v1","model":"glm-5.1","requiresAPIKey":true,"supportsModelBrowsing":true,"extraParams":{},"hasBuiltInGrounding":false,"omitSystemPrompt":false}]"""
        val s = store(dir(), Legacy(providersJSON = raw))
        val snapshot = (s.load() as ConfigLoad.Ok).snapshot
        assertNull(s.loadFailure)
        assertEquals(id, snapshot.servers.first().id); assertEquals(listOf("glm-5.1"), snapshot.equipped.map { it.modelID })
    }

    @Test fun freshInstallIsNotAFailure() {
        val s = store(dir())
        assertTrue(s.load() is ConfigLoad.Empty)
        assertTrue(s.snapshot.isEmpty); assertNull(s.loadFailure); assertFalse(s.didMigrate)
    }

    // MARK: - The file (§7 amendments)

    @Test fun versionIsInsideTheFile() {
        val d = dir(); store(d).write(ConfigSnapshot(servers = emptyList(), equipped = emptyList()))
        val obj = Json.parseToJsonElement(fileIn(d).readText()).jsonObject
        assertEquals(ConfigSnapshot.CURRENT_VERSION, obj["version"]!!.jsonPrimitive.int)
    }

    @Test fun migrationDoesNotRewriteV1() {
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1")
        val original = legacyBlob(listOf(a)); val legacy = Legacy(providersJSON = original, currentProviderID = a.id)
        val s = store(dir(), legacy)
        s.load()
        s.write(s.snapshot.copy(servers = listOf(s.snapshot.servers[0].copy(name = "renamed after migrating"))))
        // A rollback loses changes made after the migration — recoverable. It must never read a v1 blob a v2 write corrupted.
        assertEquals(original, legacy.providersJSON)
    }

    @Test fun writeRoundTrips() {
        val d = dir()
        val server = Server(id = UUID.randomUUID().toString(), name = "box", endpoint = "https://box.ts.net:11434/v1", kind = ServerKind.OLLAMA, requiresAPIKey = false, activeModelID = "gemma4")
        val row = EquippedModel(serverID = server.id, modelID = "gemma4", capabilities = ModelCapabilities.TOOLS)
        store(d).write(ConfigSnapshot(servers = listOf(server), equipped = listOf(row), currentEquippedID = row.id))
        val reader = store(d)
        val snapshot = (reader.load() as ConfigLoad.Ok).snapshot
        assertEquals(listOf(server), snapshot.servers); assertEquals(listOf(row), snapshot.equipped); assertEquals(row.id, snapshot.currentEquippedID)
        assertFalse(reader.didMigrate)   // the file won, not the legacy blob
    }

    /** An unreadable config falls back to v1, keeps the bad file, and says so. Rule 2: never present a decode failure as "no setups". */
    @Test fun corruptFileFallsBackToLegacyRatherThanEmpty() {
        val d = dir(); fileIn(d).writeText("{ this is not json")
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1")
        val s = store(d, Legacy(providersJSON = legacyBlob(listOf(a))))
        val snapshot = (s.load() as ConfigLoad.Ok).snapshot
        assertEquals(listOf(a.id), snapshot.servers.map { it.id })
        assertNotNull(s.loadFailure)
        assertTrue(File(d, "teemoon-config.corrupt.json").exists())
    }

    /** With no legacy to fall back on, corruption is QUARANTINED and surfaced — never silently replaced. */
    @Test fun corruptFileWithNoLegacyIsQuarantinedNotEmpty() {
        val d = dir(); val f = fileIn(d); f.writeText("{not json at all")
        val q = store(d).load() as ConfigLoad.Quarantined
        assertTrue(q.reason.isNotEmpty()); assertFalse("original must not remain in place", f.exists()); assertTrue(File(q.quarantinePath).exists())
    }

    @Test fun futureVersionIsRefused() {
        val d = dir(); fileIn(d).writeText("""{"version":${ConfigSnapshot.CURRENT_VERSION + 1},"servers":[],"equipped":[]}""")
        val s = store(d); s.load()
        assertNotNull(s.loadFailure)
    }

    // MARK: - The invariants a database would have enforced (§7)

    private fun box(activeModelID: String? = null) = Server(id = UUID.randomUUID().toString(), name = "box", endpoint = "https://box.ts.net/v1", activeModelID = activeModelID)

    @Test fun orphanRowsAreSwept() {
        val server = box()
        val snapshot = ConfigSnapshot(servers = listOf(server), equipped = listOf(EquippedModel(serverID = UUID.randomUUID().toString(), modelID = "ghost"), EquippedModel(serverID = server.id, modelID = "gemma4"))).validated()
        assertEquals(listOf("gemma4"), snapshot.equipped.map { it.modelID })
    }

    @Test fun danglingSelectionIsCleared() {
        val orphan = EquippedModel(serverID = UUID.randomUUID().toString(), modelID = "ghost")
        assertNull(ConfigSnapshot(servers = listOf(box()), equipped = listOf(orphan), currentEquippedID = orphan.id).validated().currentEquippedID)
    }

    @Test fun duplicateRowsCollapse() {
        val server = box(); val first = EquippedModel(serverID = server.id, modelID = "gemma4"); val second = EquippedModel(serverID = server.id, modelID = "gemma4")
        val snapshot = ConfigSnapshot(servers = listOf(server), equipped = listOf(first, second)).validated()
        assertEquals(1, snapshot.equipped.size); assertEquals(first.id, snapshot.equipped[0].id)
    }

    /** Selection says qwen, the server still claims gemma — the disagreement that having two fields makes possible. */
    @Test fun theCurrentServerRunsTheCurrentModel() {
        val server = box(activeModelID = "gemma4")
        val gemma = EquippedModel(serverID = server.id, modelID = "gemma4"); val qwen = EquippedModel(serverID = server.id, modelID = "qwen3.5")
        val snapshot = ConfigSnapshot(servers = listOf(server), equipped = listOf(gemma, qwen), currentEquippedID = qwen.id).validated()
        assertEquals("qwen3.5", snapshot.servers[0].activeModelID)
    }

    @Test fun staleActiveModelFallsBack() {
        val server = box(activeModelID = "removed")
        val snapshot = ConfigSnapshot(servers = listOf(server), equipped = listOf(EquippedModel(serverID = server.id, modelID = "gemma4"))).validated()
        assertEquals("gemma4", snapshot.servers[0].activeModelID)
    }

    // MARK: - Projection

    @Test fun providerRoundTripsThroughTheSchema() {
        val provider = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1", "qwen3.5"), caps = ModelCapabilities.TOOLS)
            .copy(authHeaderName = "X-Subscription-Token", extraParams = mapOf("temperature" to "0.7"), maxMessages = 1, hasBuiltInGrounding = true, omitSystemPrompt = true, supportsModelBrowsing = true)
        val snapshot = ProviderConfigProjection.snapshot(listOf(provider), currentProviderID = provider.id)
        val back = ProviderConfigProjection.providers(snapshot)
        assertEquals(listOf(provider), back.providers); assertEquals(provider.id, back.currentProviderID)
    }

    /** §2.2: equipping a second model no longer erases the first one's capabilities. */
    @Test fun capabilitiesAreKeptPerModel() {
        val toolModel = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1"), caps = ModelCapabilities.TOOLS)
        val first = ProviderConfigProjection.snapshot(listOf(toolModel), currentProviderID = null)
        val second = toolModel.equipping("no-tools-model").copy(modelCapabilities = ModelCapabilities.NONE)   // a KNOWN "supports none of these"
        val after = ProviderConfigProjection.snapshot(listOf(second), currentProviderID = null, previous = first)
        assertEquals(ModelCapabilities.TOOLS, after.equipped.first { it.modelID == "glm-5.1" }.capabilities)
        assertEquals(ModelCapabilities.NONE, after.equipped.first { it.modelID == "no-tools-model" }.capabilities)
    }

    /** A save cannot flatten what a Provider has nowhere to carry. */
    @Test fun normalizationPreservesServerFacts() {
        val provider = cloud("box", "https://box.ts.net:11434/v1", "gemma4", models = listOf("gemma4"))
        var previous = ProviderConfigProjection.snapshot(listOf(provider), currentProviderID = null)
        previous = previous.copy(servers = listOf(previous.servers[0].copy(kind = ServerKind.OLLAMA, servedModels = listOf("gemma4", "qwen3.5"))))
        val addedAt = previous.equipped[0].addedAt; val rowID = previous.equipped[0].id
        val after = ProviderConfigProjection.snapshot(listOf(provider), currentProviderID = null, previous = previous, nowMs = addedAt + 5_000)
        assertEquals(ServerKind.OLLAMA, after.servers[0].kind); assertEquals(listOf("gemma4", "qwen3.5"), after.servers[0].servedModels)
        assertEquals(rowID, after.equipped[0].id); assertEquals(addedAt, after.equipped[0].addedAt)
    }

    @Test fun unknownCapabilitiesNeverClearKnownOnes() {
        val known = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1"), caps = ModelCapabilities.TOOLS)
        val previous = ProviderConfigProjection.snapshot(listOf(known), currentProviderID = null)
        val after = ProviderConfigProjection.snapshot(listOf(known.copy(modelCapabilities = null)), currentProviderID = null, previous = previous)   // null is "unknown", not "none"
        assertEquals(ModelCapabilities.TOOLS, after.equipped[0].capabilities)
    }

    /** RETIRED ROWS SURVIVE: unequipping a used model keeps its row, marked, once; re-equipping clears the mark. */
    @Test fun retiredRowsSurviveOnceStampedAndCapped() {
        val p = cloud("box", "https://box.ts.net:11434/v1", "gemma4", models = listOf("gemma4", "qwen3.5"))
        val s = ConfigStore.inMemory()
        s.save(listOf(p), p.id)
        s.stampUse(p.id, "qwen3.5", atMs = 1_000)
        s.save(listOf(p.copy(equippedModels = listOf("gemma4"))), p.id, ) ; val retired = s.snapshot.equipped.first { it.modelID == "qwen3.5" }
        assertFalse(retired.isEquipped); assertNotNull(retired.unequippedAt)
        s.save(listOf(p.copy(equippedModels = listOf("gemma4"))), p.id)
        assertEquals(retired.unequippedAt, s.snapshot.equipped.first { it.modelID == "qwen3.5" }.unequippedAt)   // stamped once
        assertEquals(listOf("gemma4"), s.snapshot.providers[0].equippedModels)   // never projected back into ready now
        s.save(listOf(p), p.id)
        assertTrue(s.snapshot.equipped.first { it.modelID == "qwen3.5" }.isEquipped)
        // An unused unequipped model is dropped, not retired, and the list is capped.
        val many = (1..20).map { "m$it" }
        s.save(listOf(p.copy(equippedModels = many)), p.id); many.forEachIndexed { i, m -> s.stampUse(p.id, m, atMs = 10_000L + i) }
        s.save(listOf(p.copy(equippedModels = listOf("gemma4"))), p.id)
        val kept = s.snapshot.equipped.filter { !it.isEquipped }
        assertEquals(ProviderConfigProjection.RETIRED_ROW_LIMIT, kept.size); assertEquals("m20", kept.first().modelID)   // newest first
    }

    // MARK: - Store verbs (iOS reaches these through ProviderStore, a human row here)

    private fun machine(s: ConfigStore): Provider {
        val p = Provider(id = UUID.randomUUID().toString(), name = "box", endpoint = "http://100.100.0.12:11434/v1", model = "gemma4", requiresAPIKey = false, equippedModels = listOf("gemma4"))
        s.save(listOf(p), p.id); return p
    }

    @Test fun recordUseStampsTheRow() {
        val s = ConfigStore.inMemory()
        val p = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1", "qwen3.5")); s.save(listOf(p), p.id)
        s.stampUse(p.id, "glm-5.1")
        assertNotNull(s.snapshot.equipped.first { it.modelID == "glm-5.1" }.lastUsedAt); assertNull(s.snapshot.equipped.first { it.modelID == "qwen3.5" }.lastUsedAt)
    }

    /** §2.3: a probe writes SERVER facts, never the user's selection. */
    @Test fun recordingAProbeLeavesTheSelectionAlone() {
        val s = ConfigStore.inMemory(); val p = machine(s)
        s.recordProbe(p.id, ServerKind.OLLAMA, listOf("gemma4", "qwen3.5:4b"))
        assertEquals(listOf("gemma4"), s.snapshot.providers.first { it.id == p.id }.equipped)
        assertEquals(listOf("gemma4", "qwen3.5:4b"), s.servedModels(p.id)); assertEquals(ServerKind.OLLAMA, s.kind(p.id))
    }

    /** §2.4: a probe that can't classify the server must not erase a kind an earlier one established. */
    @Test fun anUndeterminedKindDoesNotEraseAKnownOne() {
        val s = ConfigStore.inMemory(); val p = machine(s)
        s.recordProbe(p.id, ServerKind.OLLAMA, listOf("gemma4"))
        assertNull(LocalServerKind.UNKNOWN.stored)
        s.recordProbe(p.id, LocalServerKind.UNKNOWN.stored, null)
        assertEquals(ServerKind.OLLAMA, s.kind(p.id)); assertEquals(listOf("gemma4"), s.servedModels(p.id))
    }

    @Test fun probeFactsSurviveAReload() {
        val d = dir(); val s = store(d); val p = machine(s)
        s.recordProbe(p.id, ServerKind.OLLAMA, listOf("gemma4", "qwen3.5:4b"))
        val reloaded = store(d); reloaded.load()
        assertEquals(listOf("gemma4", "qwen3.5:4b"), reloaded.servedModels(p.id)); assertEquals(ServerKind.OLLAMA, reloaded.kind(p.id))
        // And a flat save through the store keeps them (the projection's `previous`).
        reloaded.save(listOf(p), p.id)
        assertEquals(ServerKind.OLLAMA, reloaded.kind(p.id))
    }

    /** §2.6's counterpart: deleting removes the row AND the servedModels claim; the other model is untouched. */
    @Test fun deletingRemovesTheRowAndTheServedClaim() {
        val s = ConfigStore.inMemory()
        val p = Provider(id = UUID.randomUUID().toString(), name = "box", endpoint = "http://100.100.0.12:11434/v1", model = "gemma4", requiresAPIKey = false, equippedModels = listOf("gemma4", "qwen3.5:4b"))
        s.save(listOf(p), p.id); s.recordProbe(p.id, ServerKind.OLLAMA, listOf("gemma4", "qwen3.5:4b"))
        for (m in listOf("gemma4", "qwen3.5:4b")) s.stampUse(p.id, m)
        s.forgetModel(p.id, "qwen3.5:4b")
        assertNull(s.snapshot.equipped.firstOrNull { it.modelID == "qwen3.5:4b" })
        assertEquals(listOf("gemma4"), s.servedModels(p.id))
        assertNotNull(s.snapshot.equipped.first { it.modelID == "gemma4" }.lastUsedAt)
    }

    @Test fun inMemoryStoreIsInert() {
        val s = ConfigStore.inMemory(); val p = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1"); s.save(listOf(p), p.id)
        assertEquals(1, s.snapshot.providers.size); assertNull(s.quarantineFile)
    }

    // MARK: - Android-only: the flat file this store used to write

    @Test fun flatFileMigratesKeepsIdsAndFreezesAV1Copy() {
        val d = dir(); val f = fileIn(d)
        val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1", models = listOf("glm-5.1", "qwen3.5"))
        f.writeText("""{"version":2,"providers":${legacyBlob(listOf(a))},"selectedProviderId":"${a.id}"}""")
        val s = ConfigStore(f)
        val loaded = s.load() as ConfigLoad.Ok
        assertTrue(loaded.migrated); assertNull(s.loadFailure)
        assertEquals(listOf(a.id), loaded.snapshot.servers.map { it.id }); assertEquals(a.id, loaded.snapshot.selectedProviderId)
        assertTrue("v1 copy frozen before any v2 write", File(d, "teemoon-config.v1.json").exists())
        s.write(loaded.snapshot)
        assertFalse(FlatFileLegacyConfig.isFlatShape(Json.parseToJsonElement(f.readText()).jsonObject))
        assertFalse((ConfigStore(f).load() as ConfigLoad.Ok).migrated)   // second launch reads the file, not the blob
    }

    /** The app layer's bridge: the flat constructor and the flat readers round-trip. */
    @Test fun flatBridgeRoundTrips() {
        val d = dir(); val a = cloud("near.ai", "https://api.near.ai/v1", "glm-5.1")
        store(d).save(ConfigSnapshot(providers = listOf(a), selectedProviderId = a.id))
        val loaded = store(d).load() as ConfigLoad.Ok
        // The projection materializes the equipped set (iOS does the same): `null` reads as `[model]`.
        assertEquals(listOf(a.copy(equippedModels = listOf("glm-5.1"))), loaded.snapshot.providers); assertEquals(a.id, loaded.snapshot.selectedProviderId)
    }

    @Test fun unknownServerKindDecodesToOpenAICompatibleNotFailure() {
        val d = dir(); val id = UUID.randomUUID().toString()
        fileIn(d).writeText("""{"version":2,"servers":[{"id":"$id","name":"x","endpoint":"https://x/v1","kind":"quantum"}],"equipped":[]}""")
        assertEquals(ServerKind.OPENAI_COMPATIBLE, (store(d).load() as ConfigLoad.Ok).snapshot.servers[0].kind)
    }
}
