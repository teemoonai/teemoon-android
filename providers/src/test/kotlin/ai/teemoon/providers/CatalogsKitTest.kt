// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 + ConformanceVectorsFixtures.swift @ cdcae74b (nearTiers with listSeen, attested and claimsE2EE; listModelsBuildsCompletePickerRows, rowsAreCompleteNamedAndVendored,
//        rowsCarryNameVendorContextAndQuant, grokRowsGainThePriceAndContextTheSnapshotNeverHad, fireworksRowsGainTheContext…,
//        fireworksRowsAreNamedInTheHouseStyle) — over the kit's `catalogs` class (kit 0.4.0): the rows the Android adapters build
//        from the recorded responses must equal iOS's, field for field, at the pinned instant.
package ai.teemoon.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CatalogsKitTest {
    private val wire = Json { ignoreUnknownKeys = true }
    private val v by lazy { Json.parseToJsonElement(CatalogsKitTest::class.java.getResourceAsStream("/catalogs.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject }
    private val nowMs by lazy { Instant.parse(v["pinnedNow"]!!.jsonPrimitive.content).toEpochMilli() }

    private data class Row(val id: String, val displayName: String, val vendor: String, val price: String, val contextWindow: String, val isNew: Boolean, val capabilities: List<String>, val summary: String?, val modelPageURL: String?, val huggingFaceURL: String?, val githubURL: String?, val deprecationDate: String?)
    private fun expected(section: String): List<Row> = v[section]!!.jsonObject["rows"]!!.jsonArray.map { e -> val o = e.jsonObject
        Row(o["id"]!!.jsonPrimitive.content, o["displayName"]!!.jsonPrimitive.content, o["vendor"]!!.jsonPrimitive.content, o["price"]!!.jsonPrimitive.content, o["contextWindow"]!!.jsonPrimitive.content,
            o["isNew"]!!.jsonPrimitive.booleanOrNull ?: false, o["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }, o["summary"]?.jsonPrimitive?.contentOrNull, o["modelPageURL"]?.jsonPrimitive?.contentOrNull,
            o["huggingFaceURL"]?.jsonPrimitive?.contentOrNull, o["githubURL"]?.jsonPrimitive?.contentOrNull, o["deprecationDate"]?.jsonPrimitive?.contentOrNull) }
    private fun row(m: KnownModel) = Row(m.id, m.displayName, m.vendor, m.price, m.contextWindow, m.isNew,
        listOf(ModelCapability.TOOLS to "tools", ModelCapability.VISION to "vision", ModelCapability.UPLOADS to "uploads", ModelCapability.AUDIO to "audio", ModelCapability.THINKING to "thinking", ModelCapability.COMPLETION to "completion", ModelCapability.EMBEDDING to "embedding", ModelCapability.INSERT to "insert")
            .filter { (c, _) -> m.capabilities?.contains(c) == true }.map { it.second },
        m.summary, m.modelPageURL, m.huggingFaceURL, m.githubURL, m.deprecationDate?.toString())
    private fun raw(section: String, key: String) = v[section]!!.jsonObject[key]!!.jsonPrimitive.content

    @Test fun xaiRowsMatchIOS() {
        val language = wire.decodeFromString(XAIAdapter.LanguageModelsResponse.serializer(), raw("xai", "languageModels"))
        val models = wire.decodeFromString(XAIAdapter.ModelsResponse.serializer(), raw("xai", "models"))
        val contexts = models.data.mapNotNull { m -> m.context_length?.let { m.id.lowercase() to it } }.toMap()
        val rows = XAIAdapter.buildModels(language.models.filter { it.isTextModel }.map { XAIAdapter.Entry.fromLanguage(it) }, contexts, nowMs).map(::row)
        assertEquals(expected("xai"), rows)
    }
    @Test fun fireworksRowsMatchIOS() {
        val list = wire.decodeFromString(FireworksAdapter.ModelsResponse.serializer(), raw("fireworks", "models"))
        val actual = FireworksAdapter.buildModels(list.models, nowMs).map(::row); val exp = expected("fireworks")
        assertEquals(exp.map { it.id }, actual.map { it.id })
        exp.zip(actual).forEachIndexed { i, (e, a) -> assertEquals("row $i ${e.id}", e, a) }
    }
    @Test fun lmStudioRowsMatchIOS() {
        val list = wire.decodeFromString(LMStudioAdapter.ModelsResponse.serializer(), raw("lmstudio", "models"))
        assertEquals(expected("lmstudio"), LMStudioAdapter.buildModels(list.data).map(::row))
    }

    /**
     * iOS 9862a35 (audit gardener #7): near.ai's tier for an id before the catalogue answers (no ownedBy) and after,
     * each row on a fresh tier cache. An id no rule names is UNKNOWN, never own fleet. Kit 0.5.5
     * (iOS cdcae74b): each row also says whether a send is `attested` (TEE_OWN and UNKNOWN: an unseen tier never sends
     * plain without asking) and whether a screen `claimsE2EE` (TEE_OWN only).
     */
    @Test fun nearTiersMatchIOSBeforeAndAfterTheCatalogue() {
        val names = mapOf("teeOwn" to "TEE_OWN", "teeThirdParty" to "TEE_THIRD_PARTY", "proxied" to "PROXIED", "unknown" to "UNKNOWN")
        val rows = v["nearTiers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(17, rows.size)   // kit 0.5.2: + six never-seen rows (iOS ea19232)
        try {
            val wrong = rows.mapNotNull { r ->
                NearAIModelCatalog.resetTierCache()
                // listSeen (kit 0.5.2): whether near.ai's tier list has been recorded since launch; before it, no guess is own fleet.
                NearAIModelCatalog.tierListSeenOverride = r["listSeen"]?.jsonPrimitive?.booleanOrNull ?: true
                val id = r["id"]!!.jsonPrimitive.content
                val owner = r["ownedBy"]?.jsonPrimitive?.contentOrNull
                owner?.let { NearAIModelCatalog.tierFromOwnedBy(it) }?.let { NearAIModelCatalog.recordTiers(mapOf(id to it)) }
                val got = NearAIModelCatalog.confidentiality(id).name
                val want = names.getValue(r["tier"]!!.jsonPrimitive.content)
                val p = Provider.nearAI.copy(model = id)
                val attested = ProviderCapability.ATTESTATION in p.capabilities
                val wantAttested = r["attested"]!!.jsonPrimitive.boolean
                val wantClaims = r["claimsE2EE"]!!.jsonPrimitive.boolean
                if (got == want && attested == wantAttested && p.claimsEndToEndEncryption == wantClaims) null
                else "$id (ownedBy $owner): expected $want attested=$wantAttested claims=$wantClaims, got $got attested=$attested claims=${p.claimsEndToEndEncryption}"
            }
            assertEquals("rows Android tiers differently", emptyList<String>(), wrong)
        } finally { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.tierListSeenOverride = null }
    }
}
