// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the ListPriceSnapshot suite).
//        Prices read without decimals on whole dollars — the accepted divergence, so the Swift's
//        prices print two decimals, as iOS does (kit 0.5.0; the short form was retired 2026-10-02).
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListPriceSnapshotTest {

    @Test fun firstPartyHostsGetTheirListPrice() {
        assertEquals("$2.50/$15.00", ListPriceSnapshot.price("api.openai.com", "gpt-5.4"))
        assertEquals("$2.50/$15.00", ListPriceSnapshot.price("API.OpenAI.com", "GPT-5.4"))
        // Google's OpenAI-compat list prefixes ids with "models/".
        assertEquals("$0.30/$2.50",
            ListPriceSnapshot.price("generativelanguage.googleapis.com", "models/gemini-2.5-flash"))
        // Anthropic's own API spells versions with a dash; OpenRouter with a dot.
        assertEquals("$5.00/$25.00", ListPriceSnapshot.price("api.anthropic.com", "claude-opus-4-8"))
        assertEquals("$2.00/$10.00", ListPriceSnapshot.price("api.anthropic.com", "claude-sonnet-5"))
    }

    /**
     * BLANK OVER BORROWED. A host the table does not know, an id it does not
     * carry, or another vendor's id on the wrong host all render nothing — a
     * wrong number is worse than a blank, because the blank says "unknown".
     */
    @Test fun unknownHostsAndUnmatchedIDsStayBlank() {
        assertTrue(ListPriceSnapshot.price("llm.example.com", "gpt-5.4").isEmpty())
        assertTrue(ListPriceSnapshot.price(null, "gpt-5.4").isEmpty())
        assertTrue("a dated snapshot id is not an exact match",
            ListPriceSnapshot.price("api.openai.com", "gpt-5.4-2026-03-05").isEmpty())
        assertTrue("another vendor's id on the wrong host",
            ListPriceSnapshot.price("api.openai.com", "claude-sonnet-5").isEmpty())
        assertTrue(ListPriceSnapshot.price("api.openai.com", "").isEmpty())
    }

    @Test fun snapshotIsWellFormed() {
        assertTrue(ListPriceSnapshot.prices.size > 50)
        val namespaces = ListPriceSnapshot.hosts.values.toSet()
        for ((id, rate) in ListPriceSnapshot.prices) {
            assertTrue("$id is an OpenRouter-only variant", !id.contains(":"))
            assertTrue("$id has a non-positive rate: $rate", rate.first >= 0 && rate.second >= 0)
            assertTrue("$id is under no first-party host", id.substringBefore('/') in namespaces)
        }
    }

    /**
     * The table holds NUMBERS, so it cannot encode a format — which is the whole
     * reason it holds numbers. Rendering goes through the one price formatter,
     * and a change to that formatter moves every row at once rather than
     * leaving 136 stale strings behind.
     */
    @Test fun theTableStoresRatesNotRenderedPrices() {
        val (input, output) = ListPriceSnapshot.prices.getValue("openai/gpt-5.4")
        assertEquals(2.5, input, 0.0001)
        assertEquals(15.0, output, 0.0001)
        assertEquals(ModelCatalog.priceLabel(input, output),
            ListPriceSnapshot.price("api.openai.com", "gpt-5.4"))
    }
}
