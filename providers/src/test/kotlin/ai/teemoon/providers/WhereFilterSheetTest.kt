// spec: teemoon-ios docs/IMAGES.md §4e @ f04a55d2 — the where sheet's filtered state built from real setups: the kit
// (WhereFilterKitTest) holds the rules over abstract rows; this holds the mapping from providers, presets, keys and
// saved lists onto them, which is where the sheet's numbers come from.
package ai.teemoon.providers

import ai.teemoon.providers.ModelBrowserQuery.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhereFilterSheetTest {
    private val vision = Provider(id = "vis", name = "fireworks", endpoint = Provider.fireworks.endpoint, model = "qwen-vl", equippedModels = listOf("qwen-vl", "deepseek"))
    private val mine = Provider(id = "mine", name = "my server", endpoint = "https://llm.example.org/v1", model = "a", supportsModelBrowsing = true)
    private val near = Provider.nearAI.copy(id = "near")
    private val brave = Provider.braveAnswers.copy(id = "brave")

    private fun sheet(ps: List<Provider>, tab: WhereLocality?, showOnly: Set<Filter>, keyed: Set<String> = setOf("vis", "mine", "near"),
                      lists: Map<String, Map<Set<Filter>, Int>> = emptyMap(), online: Boolean = true, recents: List<WhereRecentEntry> = emptyList(),
                      imagesOnDevice: Boolean = LocalModelCatalog.imagesOnDevice): WhereFilterSheet {
        val policy = WhereGetPolicy(tab, ps, online, emptyMap(), { if (it.id in keyed) "k" else "" }, { "" },
            catalogCount = { p -> lists[p.id]?.get(emptySet()) })
        return WhereFilterSheet.of(policy, WhereReadyRows.rows(policy.filteredProviders), recents, showOnly,
            readsImages = { it.model == "qwen-vl" }, hasKey = policy::hasKey,
            listCount = { p, f -> lists[p.id]?.let { it[f] ?: 0 } },
            claimsE2ee = { it.isNearAI }, imagesOnDevice = imagesOnDevice)
    }

    private fun get(s: WhereFilterSheet) = s.get!!.map { r ->
        when (val row = r.row) {
            is WhereFilter.GetRow.Server -> (if (row.custom) "browse:" else "preset:") + row.name
            is WhereFilter.GetRow.Download -> "download:${row.id}"
            is WhereFilter.GetRow.Pull -> "pull:${row.machine}"
            WhereFilter.GetRow.Connect -> "connect"
            WhereFilter.GetRow.AddCloudKey -> "key"
        } to r.trailing
    }

    @Test fun readsImagesNarrowsReadyAndCountsTheTab() {
        val s = sheet(listOf(vision), WhereLocality.CLOUD, setOf(Filter.VISION))
        assertEquals(listOf("qwen-vl"), s.ready.map { it.modelID })
        assertEquals("1 of 2", s.count)
        assertEquals("reads images", s.control)
        assertEquals("2 models", sheet(listOf(vision), WhereLocality.CLOUD, emptySet()).count)
    }

    /** A keyed preset's number is its saved list under the filter, from the same count the browser filters by. */
    @Test fun aKeyedPresetShowsKOfNAndHidesAtZero() {
        val lists = mapOf("vis" to mapOf(emptySet<Filter>() to 12, setOf(Filter.VISION) to 5), "near" to mapOf(emptySet<Filter>() to 42, setOf(Filter.VISION) to 0))
        val s = sheet(listOf(vision, near), WhereLocality.CLOUD, setOf(Filter.VISION), lists = lists)
        val rows = get(s).toMap()
        assertEquals("5 of 12", rows["preset:fireworks"])
        assertEquals(false, "preset:near.ai" in rows)
        assertEquals("add key", rows["preset:grok"])
        assertEquals(false, "preset:brave answers" in rows)
    }

    /** With no filter the number is the policy's, as today: one number per row, never two that can disagree. */
    @Test fun withNoFilterThePresetNumberIsThePolicys() {
        val lists = mapOf("vis" to mapOf(emptySet<Filter>() to 12))
        val rows = get(sheet(listOf(vision), WhereLocality.CLOUD, emptySet(), lists = lists)).toMap()
        assertEquals("12", rows["preset:fireworks"])
        assertEquals("add key", rows["preset:brave answers"])
    }

    @Test fun aConfiguredBraveHasNoRow() {
        val rows = get(sheet(listOf(brave), WhereLocality.CLOUD, emptySet(), keyed = setOf("brave"))).map { it.first }
        assertEquals(false, "preset:brave answers" in rows)
    }

    @Test fun aCustomServerBrowsesWithItsOwnCount() {
        val lists = mapOf("mine" to mapOf(emptySet<Filter>() to 9, setOf(Filter.VISION) to 3))
        val g = get(sheet(listOf(mine), WhereLocality.CLOUD, setOf(Filter.VISION), lists = lists))
        assertEquals("browse:mine" to "3 of 9", g.first())
    }

    @Test fun endToEndEncryptedKeepsOnlyServersThatClaimIt() {
        val g = get(sheet(listOf(vision), null, setOf(Filter.ENCRYPTED), keyed = setOf("vis"))).map { it.first }
        assertEquals(listOf("preset:near.ai", "key"), g)
    }

    /** The phone can't: this phone fails the memory check, or its model still needs its second download (§4e). */
    @Test fun thePhoneTabSaysNoPhoneModelReadsImagesYet() {
        val s = sheet(listOf(Provider.local(LocalModelCatalog.all.first()).copy(id = "local")), WhereLocality.PHONE, setOf(Filter.VISION), imagesOnDevice = false)
        assertEquals("0 of 1", s.count)
        assertEquals(WhereFilter.PHONE_CANT_YET, s.line)
    }

    @Test fun offlineGetIsTheOfflineSection() {
        val s = sheet(listOf(vision), null, setOf(Filter.VISION), online = false)
        assertNull(s.get)
        assertNull(s.count)
    }
    /**
     * Phone models read images in 1.1.0 (the maintainer 2026-11-12), where this phone's memory fits them (run time, the
     * same day): the phone tab under reads images offers both downloads on a phone that fits both, and "no model on
     * this phone reads images yet." does not show. A phone that can't fit one: aPhoneModelThatNeverFits….
     */
    @Test fun onThePhoneTabReadsImagesOffersTheDownloadsThatFit() {
        val s = sheet(emptyList(), WhereLocality.PHONE, setOf(Filter.VISION))
        assertEquals(LocalModelCatalog.all.map { "download:${it.id}" }, get(s).map { it.first })
        assertNull(s.line)
    }

    /**
     * IMAGES.md §4b (the maintainer 2026-11-12, run time): a phone model this phone can never fit is listed under reads
     * images in its place with the permanent caption, not counted; a download that would never fit is not offered.
     */
    @Test fun aPhoneModelThatNeverFitsIsListedRefusedNotCountedAndNotOffered() {
        val e4b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E4B" }
        val local = Provider.local(e4b).copy(id = "e4b")
        val policy = WhereGetPolicy(WhereLocality.PHONE, listOf(local), true, emptyMap(), { "" }, { "" })
        val s = WhereFilterSheet.of(policy, WhereReadyRows.rows(policy.filteredProviders), emptyList(), setOf(Filter.VISION),
            readsImages = { false }, hasKey = policy::hasKey, listCount = { _, _ -> null }, imagesOnDevice = true,
            imagesRefused = { it.id == "e4b" }, phoneFits = { it.id != e4b.id })
        assertEquals(emptyList<String>(), s.ready.map { it.provider.id })
        assertEquals(listOf("e4b"), s.refused.map { it.provider.id })
        assertEquals("0 of 1", s.count)
        val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }.id
        assertEquals("E2B's download is offered, E4B's is not", listOf("download:$e2b"), get(s).map { it.first })
    }
}
