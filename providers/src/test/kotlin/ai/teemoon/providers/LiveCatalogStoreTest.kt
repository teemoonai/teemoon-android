// ports: teemoonTests/LiveCatalogStoreTests.swift @ v1.0.4 (13 of 13 — the refresh/refreshAll four landed with
//        the store's fetch wiring, 2026-09-22), plus two Android-only cases on the read-back host rule.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * The saved copy of what each server answered with. It is the only place a model
 * count, a browse row and a model's name come from, so what it keeps — and what
 * it refuses to keep — is the whole contract.
 */
private val lenientJson = Json { ignoreUnknownKeys = true }

class LiveCatalogStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun offline() {
        NearAIModelCatalog.resetTierCache()
        EndpointDirectory.resetPersisted()
    }

    private fun store() = LiveCatalogStore(tmp.newFolder())
    private fun rows(ids: List<String>) =
        ids.map { KnownModel(id = it, displayName = it, vendor = "v", price = "$1.00/$2.00") }

    private val openRouter get() = Provider.openRouter
    private val fireworks get() = Provider.fireworks

    @Test fun aSavedListSurvivesANewStore() {
        val dir = tmp.newFolder()
        LiveCatalogStore(dir).record(rows(listOf("a/one", "a/two")), openRouter, "")
        val reopened = LiveCatalogStore(dir)
        assertEquals(2, reopened.count(openRouter, ""))
        assertEquals("$1.00/$2.00", reopened.model("a/two", openRouter, "")?.price)
    }

    /**
     * No answer yet is null, NEVER 0: a row prints nothing rather than a number
     * that reads as "this server has no models".
     */
    /** iOS stores the whole model (c3fb76d): a row read back keeps what the browser and the defaults read. */
    @Test fun aSavedRowKeepsEveryFieldTheBrowserReads() {
        val dir = tmp.newFolder()
        val full = KnownModel(id = "a/full", displayName = "Full", vendor = "A", price = "\$1/\$2",
            capabilities = setOf(ModelCapability.TOOLS, ModelCapability.VISION), openWeights = true, aliasOf = "a/real",
            extraCosts = listOf(KnownModel.ExtraCost("web search", perCall = 0.004)), knowledgeCutoff = "2026-03",
            reasoningEfforts = listOf("high", "low"), summary = "a model", maxOutputTokens = 32_000, inputModalities = listOf("text", "image"),
            huggingFaceURL = "https://huggingface.co/a/full", modelPageURL = "https://example.com/a/full",
            deprecationDate = Instant.ofEpochSecond(1_800_000_000), quantization = "fp8", parameterSize = "1T")
        LiveCatalogStore(dir).record(listOf(full), openRouter, "")
        val back = LiveCatalogStore(dir).models(openRouter, "").single()
        assertEquals(full.capabilities, back.capabilities); assertEquals(true, back.openWeights); assertEquals("a/real", back.aliasOf)
        assertEquals(full.extraCosts, back.extraCosts); assertEquals("2026-03", back.knowledgeCutoff); assertEquals(full.reasoningEfforts, back.reasoningEfforts)
        assertEquals("a model", back.summary); assertEquals(32_000, back.maxOutputTokens); assertEquals(full.inputModalities, back.inputModalities)
        assertEquals(full.huggingFaceURL, back.huggingFaceURL); assertEquals(full.modelPageURL, back.modelPageURL)
        assertEquals(full.deprecationDate, back.deprecationDate); assertEquals("fp8", back.quantization); assertEquals("1T", back.parameterSize)
    }

    /** A file written before the wider row still decodes: every new field is defaulted. */
    @Test fun aRowSavedBeforeTheWiderFormStillDecodes() {
        val old = """{"id":"a/old","displayName":"Old","vendor":"A","price":"","contextWindow":"8k"}"""
        val m = lenientJson.decodeFromString(LiveCatalogStore.StoredModel.serializer(), old)
        assertEquals("a/old", m.id); assertEquals(null, m.capabilities); assertEquals(null, m.openWeights)
    }

    @Test fun anUnaskedServerHasNoCount() {
        val s = store()
        assertNull(s.count(openRouter, ""))
        assertTrue(s.models(openRouter, "").isEmpty())
    }

    /**
     * A keyed list is ONE ACCOUNT'S entitlements. A different key must not see
     * them, and must not leave them behind either.
     */
    @Test fun aKeyedListBelongsToItsAccount() {
        val dir = tmp.newFolder()
        val s = LiveCatalogStore(dir)
        s.record(rows(listOf("accounts/a/models/one")), fireworks, "key-one")
        assertEquals(1, s.count(fireworks, "key-one"))
        assertNull(s.count(fireworks, "key-two"))

        s.record(rows(listOf("accounts/b/models/x", "accounts/b/models/y")), fireworks, "key-two")
        assertEquals(2, s.count(fireworks, "key-two"))
        assertNull("the old account's list is gone", s.count(fireworks, "key-one"))
    }

    /** A public list has no account in it, so one copy serves every key — including none. */
    @Test fun aPublicListIsNotScopedToAKey() {
        val s = store()
        s.record(rows(listOf("a/one")), openRouter, "")
        assertEquals(1, s.count(openRouter, "some-key"))
    }

    @Test fun forgettingAServerDropsEveryScope() {
        val s = store()
        s.record(rows(listOf("accounts/a/models/one")), fireworks, "key-one")
        s.forget(fireworks.endpoint)
        assertNull(s.count(fireworks, "key-one"))
    }

    /**
     * Badges age on disk. A row saved as "new" months ago must not still wear the
     * badge when the copy is read back — which is why isNew is REMADE on read
     * rather than stored.
     */
    @Test fun savedBadgesAgeThemselves() {
        val dir = tmp.newFolder()
        val fresh = KnownModel(id = "a/new", displayName = "New", vendor = "v", price = "",
            isNew = true, created = Instant.now())
        val old = KnownModel(id = "a/old", displayName = "Old", vendor = "v", price = "",
            isNew = true, created = Instant.now().minusSeconds(60L * 24 * 3600))
        LiveCatalogStore(dir).record(listOf(fresh, old), openRouter, "")

        val models = LiveCatalogStore(dir).models(openRouter, "").associateBy { it.id }
        assertTrue(models["a/new"]!!.isNew)
        assertFalse("a badge saved months ago has expired", models["a/old"]!!.isNew)
    }

    /** A KEY NEVER REACHES THE DISK — not in a filename, not in a file. */
    @Test fun noKeyIsWrittenDown() {
        val dir = tmp.newFolder()
        val secret = "sk-secret-value-123"
        LiveCatalogStore(dir).record(rows(listOf("accounts/a/models/one")), fireworks, secret)
        val files = dir.listFiles()!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            assertFalse("the key is in a FILENAME", f.name.contains(secret))
            assertFalse("the key is in a file's CONTENTS", f.readText().contains(secret))
        }
    }

    /**
     * near.ai's tiers ride with the rows, so a cold launch labels them from what
     * near.ai SAID rather than from the id guess — which answers "own fleet" for
     * an id it has never seen.
     */
    @Test fun nearAITiersAreSavedAndSeeded() {
        val dir = tmp.newFolder()
        NearAIModelCatalog.recordTiers(mapOf("moonshotai/kimi-k9" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
        LiveCatalogStore(dir).record(rows(listOf("moonshotai/kimi-k9")), Provider.nearAI, "")
        NearAIModelCatalog.resetTierCache()
        assertEquals("without the saved tier the guess answers own-fleet",
            NearAIModelCatalog.Confidentiality.TEE_OWN, NearAIModelCatalog.confidentiality("moonshotai/kimi-k9"))

        LiveCatalogStore(dir).seedNearAITiers()
        assertEquals(NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY,
            NearAIModelCatalog.confidentiality("moonshotai/kimi-k9"))
        NearAIModelCatalog.resetTierCache()
    }

    /**
     * A near.ai list built while the endpoints directory was COLD has no hosts.
     * The directory, once on disk, answers for EVERY own-fleet row — not only
     * for rows that already had one.
     */
    @Test fun aColdListGetsItsHostsFromTheDirectoryOnReopen() {
        val dir = tmp.newFolder()
        NearAIModelCatalog.recordTiers(mapOf("z-ai/glm-cold-test" to NearAIModelCatalog.Confidentiality.TEE_OWN))
        val s = LiveCatalogStore(dir)
        s.record(rows(listOf("z-ai/glm-cold-test")), Provider.nearAI, "")
        assertNull("nothing to host it with yet", s.model("z-ai/glm-cold-test", Provider.nearAI, "")?.directBaseURL)

        EndpointDirectory.rememberPersisted(mapOf("z-ai/glm-cold-test" to "https://glm-cold.completions.near.ai/v1"))
        assertEquals("https://glm-cold.completions.near.ai/v1",
            LiveCatalogStore(dir).model("z-ai/glm-cold-test", Provider.nearAI, "")?.directBaseURL)
        EndpointDirectory.resetPersisted()
    }

    /** A tier is only written down for an id near.ai ANSWERED for, never one the guess assumed. */
    @Test fun onlyAnsweredTiersAreStored() {
        val dir = tmp.newFolder()
        NearAIModelCatalog.recordTiers(mapOf("moonshotai/kimi-k9" to NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY))
        LiveCatalogStore(dir).record(rows(listOf("moonshotai/kimi-k9", "z-ai/never-answered")), Provider.nearAI, "")
        val saved = dir.listFiles()!!.single().readText()
        assertTrue(saved.contains("moonshotai/kimi-k9"))
        assertFalse("a guessed tier must not be saved as though it were told to us",
            saved.substringAfter("\"tiers\"").contains("never-answered"))
    }

    // MARK: - Refresh (the store's own fetch)

    private fun connected(vararg ids: String) =
        EndpointModelCatalog.ProbeResult.Connected(ids.map { KnownModel(id = it, displayName = it, vendor = "v", price = "") })

    /** A refresh fetches once for a fresh copy, and shares one fetch between callers — opening the sheet twice is not two catalogue downloads. */
    @Test fun refreshHonoursAgeAndSharesOneFetch() = runTest {
        var calls = 0
        val s = LiveCatalogStore(tmp.newFolder(), list = { _, _, _, _ -> calls++; delay(20); connected("a/one") })
        listOf(async { s.refresh(openRouter, "", 3_600_000L) }, async { s.refresh(openRouter, "", 3_600_000L) }).awaitAll()
        assertEquals("concurrent callers share one fetch", 1, calls)
        s.refresh(openRouter, "", 3_600_000L)
        assertEquals("a fresh copy is not re-fetched", 1, calls)
        s.refresh(openRouter, "", 0L)
        assertEquals(2, calls)
    }

    /**
     * A forget while a refresh is listing wins: the old key's (or old endpoint's) list is not saved back, nor handed to
     * the caller (the local audit of 5af7d25). A refresh started AFTER the forget saves as usual.
     */
    @Test fun aListThatLandsAfterAForgetIsNotSaved() = runTest {
        val dir = tmp.newFolder()
        val listing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val s = LiveCatalogStore(dir, list = { _, _, _, _ -> listing.complete(Unit); release.await(); connected("fw/old-account") })
        val pending = async { s.refresh(fireworks, "fw-old-key", 0L) }
        listing.await()
        s.forget(fireworks.endpoint)
        release.complete(Unit)
        assertNull("a forgotten endpoint's list was handed back", pending.await())
        assertNull("a forgotten endpoint's list was saved", s.count(fireworks, "fw-old-key"))
        assertNull("…or is on disk", LiveCatalogStore(dir).count(fireworks, "fw-old-key"))
        val after = LiveCatalogStore(dir, list = { _, _, _, _ -> connected("fw/new-account") })
        assertEquals(1, after.refresh(fireworks, "fw-new-key", 0L)?.size)
        assertEquals(1, after.count(fireworks, "fw-new-key"))
    }

    /** A failed refresh keeps what was there. An empty list on screen because the network blinked is worse than yesterday's list. */
    @Test fun aFailedRefreshKeepsTheSavedCopy() = runTest {
        val s = LiveCatalogStore(tmp.newFolder(), list = { _, _, _, _ -> EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.OFFLINE) })
        s.record(rows(listOf("a/one", "a/two")), openRouter, "")
        assertNull(s.refresh(openRouter, "", 0L))
        assertEquals(2, s.count(openRouter, ""))
    }

    /** Counting a public catalogue must not hand it a credential it never asked for. */
    @Test fun aPublicListIsCountedWithoutTheKey() = runTest {
        val sent = ArrayList<String>()
        val s = LiveCatalogStore(tmp.newFolder(), list = { _, _, key, _ -> sent.add(key); connected("a/one") })
        s.refresh(openRouter, "sk-openrouter", 0L)
        s.refresh(fireworks, "sk-fireworks", 0L)
        assertEquals(listOf("", "sk-fireworks"), sent)
    }

    /**
     * The daily refresh is for catalogues teemoon knows. A custom endpoint's `/models` may not exist, and sending its
     * key there unprompted is not something a Where-sheet open consents to; a browse tap is.
     */
    @Test fun theDailyRefreshNeverProbesACustomEndpoint() = runTest {
        val asked = ArrayList<EndpointModelCatalog.Source>()
        val s = LiveCatalogStore(tmp.newFolder(), list = { source, _, _, _ -> asked.add(source); EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.OFFLINE) })
        val custom = Provider(id = "c", name = "mine", endpoint = "https://llm.example.com/v1/chat/completions", model = "m")
        s.refreshAll(listOf(custom, openRouter), 0L) { "sk-custom" }
        assertEquals(listOf(EndpointModelCatalog.Source.OPEN_ROUTER), asked)
    }

    /**
     * Android-only. A read-back row is handed near.ai's confidential host only when THAT entry's saved tiers call it
     * own fleet. The first port asked the process-wide guess instead, which answers "own fleet" for any id it has not
     * seen — so an OpenRouter row named like a near.ai model would have come back carrying near.ai's sealed host.
     */
    @Test fun aHostIsNeverLentAcrossProviders() {
        val dir = tmp.newFolder()
        LiveCatalogStore(dir).record(rows(listOf("z-ai/glm-5.3-flash")), openRouter, "")
        EndpointDirectory.rememberPersisted(mapOf("z-ai/glm-5.3-flash" to "https://glm-5-3-flash.completions.near.ai/v1"))
        assertNull(LiveCatalogStore(dir).model("z-ai/glm-5.3-flash", openRouter, "")?.directBaseURL)
        EndpointDirectory.resetPersisted()
    }

    /** Every write bumps the revision a composable redraws on — iOS gets this from @Observable. */
    @Test fun writesAreObservable() {
        val s = store(); val before = s.revision.value
        s.record(rows(listOf("a/one")), openRouter, "")
        s.forget(openRouter.endpoint)
        assertEquals(before + 2, s.revision.value)
    }

    /**
     * NOTHING ON DISK MATCHES THE KEY. The scope was "key-" + the first 16 hex of an unsalted SHA-256 of the key, so a
     * copied file could be checked against a guessed key, and it outlived the key (the local audit, an INFO since
     * 3910). It is now a hash of a per-install random salt and the key: two installs with one key share nothing.
     */
    @Test fun theSavedScopeCannotBeMatchedAgainstTheKey() {
        val key = "sk-live-0123456789abcdef"
        val unsaltedHex = LiveCatalogStore.hex(key).take(16)   // whatever the prefix, the key's own hash must not appear
        val dirA = tmp.newFolder(); val dirB = tmp.newFolder()
        LiveCatalogStore(dirA).record(rows(listOf("accounts/a/models/one")), fireworks, key)
        LiveCatalogStore(dirB).record(rows(listOf("accounts/a/models/one")), fireworks, key)
        fun lists(dir: java.io.File) = dir.listFiles()!!.filter { it.extension == "json" }
        fun scopes(dir: java.io.File) = lists(dir).map { Json.decodeFromString(LiveCatalogStore.Entry.serializer(), it.readText()).scope }
        val onDisk = lists(dirA).joinToString { it.name + it.readText() }
        assertTrue(onDisk.isNotEmpty())
        assertFalse("the key's unsalted hash is on disk", onDisk.contains(unsaltedHex))
        assertFalse("the key itself is on disk", onDisk.contains(key))
        // the scope itself, not whole files (which differ by fetch time anyway): one key, two installs, two scopes
        val a = scopes(dirA).single(); val b = scopes(dirB).single()
        assertTrue("not a salted scope: $a", a.startsWith("scope-"))
        assertFalse("two installs with one key wrote the same scope ($a)", a == b)
        assertEquals("the list still reads back", 1, LiveCatalogStore(dirA).count(fireworks, key))
    }

    /** A list saved under the old key-derived scope is swept the first time the store opens after the upgrade. */
    @Test fun anOldKeyDerivedListIsSweptOnOpen() {
        val dir = tmp.newFolder()
        val old = java.io.File(dir, "legacy.json")
        old.writeText(Json.encodeToString(LiveCatalogStore.Entry.serializer(), LiveCatalogStore.Entry(endpoint = Provider.presetMatchKey(fireworks.endpoint),
            scope = "key-" + LiveCatalogStore.hex("sk-old").take(16), fetchedAtMs = 0, models = emptyList())))
        LiveCatalogStore(dir)
        assertFalse("the key-derived list outlived the upgrade", old.exists())
    }
}
