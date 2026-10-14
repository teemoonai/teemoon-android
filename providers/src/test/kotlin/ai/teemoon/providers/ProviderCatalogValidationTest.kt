// ports: teemoonTests/ProviderCatalogValidationTests.swift @ v1.0.4 (fireworksPricesAreWellFormed; the four gates over the
//        captured near.ai lists are OWED — see below)
package ai.teemoon.providers

import ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_THIRD_PARTY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * teemoon ships no model catalogue: a provider's models are whatever its
 * `/models` answers with. What it still ships is the data no API serves —
 * Fireworks' prices and near.ai's attested-3p id set — plus the invariant those
 * lists exist to protect: an own-fleet near.ai model has a confidential host,
 * and nothing else does.
 *
 * OWED, and not faked: iOS checks that invariant against near.ai's two real
 * captured responses (`nearai_models.json`, `nearai_endpoints_directory.json`) —
 * everyOwnFleetModelHasAConfidentialHost, noOtherTierHasAConfidentialHost,
 * theAttestedThirdPartySetMatchesTheLiveTiers, directHostsAreWellFormed. Those fixtures may only cross
 * through `conformance/`, which has no near.ai class, and a list rebuilt here
 * would certify the port against itself. They land with the kit class.
 */
class ProviderCatalogValidationTest {

    /**
     * Android-only: every attested-third-party id classifies as that tier offline.
     *
     * There used to be a second "retired" set, kept disjoint from this one and
     * consulted alongside it. ac11ef2 merged them: the tier is a claim about
     * whose hardware runs the weights, and that does not change when near.ai
     * stops listing a model. An id dropped from both sets would be guessed
     * own-fleet — the overclaiming direction — so the set only ever grows, and
     * this asserts the whole of it rather than the retired half.
     */
    @Test fun everyAttestedThirdPartyIdClassifiesAsThirdParty() {
        val ids = KnownModel.nearAIAttestedThirdPartyIDs
        assertTrue(ids.isNotEmpty())
        assertEquals("ids must be lowercase-unique", ids.size, ids.map { it.lowercase() }.toSet().size)
        for (id in ids) assertEquals(TEE_THIRD_PARTY, NearAIModelCatalog.classify(id))
    }

    /**
     * Fireworks prices are the one table that stays: no Fireworks API serves a
     * price (verified 2026-07-26), so a stale entry is the only way a row can lie
     * about cost. Resource ids and "$in/$out" — the shape the row renders and the sort parses.
     */
    @Test fun fireworksPricesAreWellFormed() {
        assertTrue(FireworksAdapter.fireworksPrices.isNotEmpty())
        for ((id, price) in FireworksAdapter.fireworksPrices) {
            assertTrue("$id is not a Fireworks resource id", id.startsWith("accounts/"))
            assertTrue("$id has a malformed price: $price", price.split("/").size == 2 && price.startsWith("$"))
        }
    }

    /**
     * The two presets added for 1.0.2. Both list PUBLICLY, so neither needs a
     * key to browse and neither ships offline rows — which is why their default
     * rule is a live one rather than a shortlist.
     */
    @Test fun theNewPresetsAreWellFormed() {
        for (p in listOf(Provider.openRouter, Provider.nvidia)) {
            assertTrue("${p.name} browses", ProviderCapability.MODEL_BROWSING in p.capabilities)
            assertTrue("${p.name} has a signup URL", !ProviderPresets.signupURL(p.id).isNullOrEmpty())
            assertTrue("${p.name} has a description", !ProviderPresets.description(p.id).isNullOrEmpty())
            assertEquals("${p.name} picks by rule, not a shortlist",
                ModelDefaultRule.StrongestOpenWeight, ProviderPresets.defaultModelRules[p.id])
            assertTrue("${p.name} lists publicly, so it is not key-gated",
                URI(p.endpoint).host !in ProviderPresets.modelListRequiresKeyHosts)
        }
        // Each preset's endpoint resolves to its own adapter, or the picker
        // renders bare ids.
        assertEquals(EndpointModelCatalog.Source.OPEN_ROUTER,
            EndpointModelCatalog.Source.resolve(URI(Provider.openRouter.endpoint).host))
        assertEquals(EndpointModelCatalog.Source.NVIDIA,
            EndpointModelCatalog.Source.resolve(URI(Provider.nvidia.endpoint).host))
    }

    /** Preset ids are FIXED — a saved provider is matched by id across updates. */
    @Test fun presetIdsAreUniqueAndStable() {
        val ids = Provider.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("A0000000-0000-0000-0000-000000000005", Provider.openRouter.id)
        assertEquals("A0000000-0000-0000-0000-000000000006", Provider.nvidia.id)
    }
}
