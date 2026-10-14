// ports: teemoonTests/ModelBrowserQueryTests.swift @ v1.0.4 (all 13)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelBrowserQueryTest {

    private fun model(
        id: String, vendor: String, price: String = "", context: String = "",
        free: Boolean = false, caps: Set<ModelCapability>? = null, open: Boolean? = null,
        created: Long? = null,
    ) = KnownModel(
        id = id, displayName = id, vendor = vendor, price = price, contextWindow = context,
        capabilities = caps, isFree = free, openWeights = open,
        created = created?.let { Instant.ofEpochSecond(it) })

    /** Two vendors, interleaved prices. Grouped they never meet; ranked they do. */
    private val mixed get() = listOf(
        model("alpha/dear", "Alpha", "$10.00/$30.00", "200k"),
        model("beta/cheap", "Beta", "$0.10/$0.20", "1M"),
        model("alpha/mid", "Alpha", "$1.00/$2.00", "128k"),
        model("beta/free", "Beta", "", "32k", free = true),
        model("beta/unpriced", "Beta", "", "8k"),
    )

    private fun listing(
        models: List<KnownModel>, query: ModelBrowserQuery,
        tiered: Boolean = false, selected: String = "", customID: Boolean = false,
    ) = ModelBrowserListing.of(models, query, tiered, selected, customID) { id ->
        when {
            id.startsWith("own/") -> NearAIModelCatalog.Confidentiality.TEE_OWN
            id.startsWith("third/") -> NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY
            else -> NearAIModelCatalog.Confidentiality.PROXIED
        }
    }

    private fun ids(l: ModelBrowserListing) = l.sections.flatMap { it.rows }.map { it.id }

    // MARK: sorting

    /**
     * THE point of the screen: the cheapest model in the catalogue is the first
     * row, whoever makes it. Free counts as nothing; a model with NO PUBLISHED
     * PRICE is not "cheapest", so it sorts last in both directions.
     */
    @Test fun priceSortRanksAcrossVendors() {
        val low = listing(mixed, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH))
        assertEquals("a ranked list has no vendor sections", 1, low.sections.size)
        assertNull(low.sections.first().header)
        assertEquals(listOf("beta/free", "beta/cheap", "alpha/mid", "alpha/dear", "beta/unpriced"), ids(low))

        val high = listing(mixed, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_HIGH_TO_LOW))
        assertEquals(listOf("alpha/dear", "alpha/mid", "beta/cheap", "beta/free", "beta/unpriced"), ids(high))
    }

    /** A flat row has no header above it, so it names its own vendor. */
    @Test fun aRankedRowNamesItsVendor() {
        val low = listing(mixed, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH))
        assertTrue(low.sections.first().rows.all { it.showsVendor })
        assertTrue(listing(mixed, ModelBrowserQuery()).sections.all { s -> s.rows.none { it.showsVendor } })
    }

    /** The default keeps the SERVER'S own order, in vendor sections. */
    @Test fun recommendedGroupsByVendorInTheServersOrder() {
        val grouped = listing(mixed, ModelBrowserQuery())
        assertEquals(listOf("alpha", "beta"), grouped.sections.map { it.header })
        assertEquals(listOf("alpha/dear", "alpha/mid"), grouped.sections.first().rows.map { it.id })
    }

    /** near.ai's resting structure is trust, strongest first. */
    @Test fun aTieredCatalogueGroupsByTrust() {
        val models = listOf(
            model("proxy/one", "V", "$3.00/$9.00"),
            model("own/one", "V", "$1.00/$2.00"),
            model("third/one", "V", "$2.00/$4.00"))
        val grouped = listing(models, ModelBrowserQuery(), tiered = true)
        assertEquals(listOf("TEE_OWN", "TEE_THIRD_PARTY", "PROXIED"), grouped.sections.map { it.id })
        // The header says the tier, so the rows do not repeat it.
        assertTrue(grouped.sections.all { s -> s.rows.none { it.showsTier } })
        // Ranked, the headers are gone and each row carries its own tag.
        val ranked = listing(models, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH), tiered = true)
        assertTrue(ranked.sections.first().rows.all { it.showsTier })
    }

    @Test fun newestAndContextSortsPutTheUnknownLast() {
        val models = listOf(
            model("a/old", "A", context = "8k", created = 1_700_000_000),
            model("a/new", "A", context = "1M", created = 1_800_000_000),
            model("a/undated", "A"))
        assertEquals(listOf("a/new", "a/old", "a/undated"),
            ids(listing(models, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.NEWEST))))
        assertEquals(listOf("a/new", "a/old", "a/undated"),
            ids(listing(models, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.LARGEST_CONTEXT))))
    }

    /** Equal prices must not shuffle between renders. */
    @Test fun tiesBreakByName() {
        val models = listOf(
            model("z/one", "Z", "$1.00/$1.00"),
            model("a/two", "A", "$1.00/$1.00"),
            model("a/dear", "A", "$9.00/$9.00"))
        assertEquals(listOf("a/two", "z/one", "a/dear"),
            ids(listing(models, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH))))
    }

    // MARK: search

    @Test fun everyTypedWordMustMatch() {
        val models = listOf(
            model("qwen/qwen3.8-27b", "Qwen"),
            model("qwen/qwen3.6-35b", "Qwen"),
            model("openai/gpt-5.4", "OpenAI"))
        assertEquals(listOf("qwen/qwen3.8-27b"), ids(listing(models, ModelBrowserQuery(text = "qwen 27"))))
        val byVendor = listing(models, ModelBrowserQuery(text = "openai"))
        assertEquals(1, byVendor.shown)
        assertEquals(3, byVendor.total)
        assertEquals("1 of 3", byVendor.countLabel)
    }

    /** Searching a vendor's name is how "cheapest from this vendor" is asked. */
    @Test fun searchNarrowsAndTheSortStillApplies() {
        assertEquals(listOf("beta/free", "beta/cheap", "beta/unpriced"),
            ids(listing(mixed, ModelBrowserQuery(text = "beta", sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH))))
    }

    @Test fun aTypedIdIsOfferedOnlyWhenNothingMatchesAndItIsAllowed() {
        val models = listOf(model("a/one", "A"))
        assertEquals("zzz", listing(models, ModelBrowserQuery(text = "zzz"), customID = true).customID)
        assertNull(listing(models, ModelBrowserQuery(text = "zzz"), customID = false).customID)
        assertNull(listing(models, ModelBrowserQuery(text = "a/one"), customID = true).customID)
    }

    // MARK: filters and what is offered

    @Test fun filtersHideRowsThatDoNotPromiseTheCapability() {
        val models = listOf(
            model("a/tools", "A", caps = setOf(ModelCapability.TOOLS)),
            model("a/vision", "A", caps = setOf(ModelCapability.VISION)),
            model("a/unknown", "A", caps = null))
        val tools = listing(models, ModelBrowserQuery(filters = setOf(ModelBrowserQuery.Filter.TOOLS)))
        // UNKNOWN capabilities do not match: a filter is a promise.
        assertEquals(listOf("a/tools"), ids(tools))
        assertEquals("tools", tools.activeSummary)
    }

    /**
     * A control that changes nothing is not shown: NVIDIA prices nothing and
     * gives everything away, so it offers no price sort and no "free" switch.
     */
    @Test fun onlyControlsThatDivideTheListAreOffered() {
        val nvidia = (1..3).map { model("nv/$it", "NVIDIA", free = true, open = true) }
        val listed = listing(nvidia, ModelBrowserQuery())
        assertEquals(listOf(ModelBrowserQuery.Sort.RECOMMENDED), listed.sorts)
        assertTrue(listed.filters.isEmpty())

        val offered = listing(mixed + model("a/tooled", "Alpha", caps = setOf(ModelCapability.TOOLS)),
            ModelBrowserQuery())
        assertTrue(ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH in offered.sorts)
        assertTrue(ModelBrowserQuery.Filter.TOOLS in offered.filters)
        // "end-to-end encrypted" only where models carry a tier at all.
        assertFalse(ModelBrowserQuery.Filter.ENCRYPTED in offered.filters)
    }

    @Test fun anUnavailableSortFallsBackToRecommended() {
        val nvidia = (1..3).map { model("nv/$it", "NVIDIA", free = true) }
        val listed = listing(nvidia, ModelBrowserQuery(sort = ModelBrowserQuery.Sort.PRICE_LOW_TO_HIGH))
        assertEquals(ModelBrowserQuery.Sort.RECOMMENDED, listed.effectiveSort)
        assertTrue(listed.sections.isNotEmpty())
    }

    @Test fun theSelectedModelIsMarkedOnce() {
        val listed = listing(mixed, ModelBrowserQuery(), selected = "alpha/mid")
        assertEquals(listOf("alpha/mid"),
            listed.sections.flatMap { it.rows }.filter { it.isSelected }.map { it.id })
        // Nothing selected is nothing marked: the trust ladder's picker opens with no pick.
        assertTrue(listing(mixed, ModelBrowserQuery()).sections.flatMap { it.rows }.none { it.isSelected })
    }

    @Test fun theCountLabelReadsAsAWholeOrAPart() {
        assertEquals("5 models", listing(mixed, ModelBrowserQuery()).countLabel)
        assertEquals("1 model", listing(listOf(mixed[0]), ModelBrowserQuery()).countLabel)
        assertEquals("3 of 5", listing(mixed, ModelBrowserQuery(text = "beta")).countLabel)
    }
}
