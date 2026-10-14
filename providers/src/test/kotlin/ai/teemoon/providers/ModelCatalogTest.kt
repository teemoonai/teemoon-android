// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.1 (the "ModelCatalog — vendor, slug,
//        formatting" suite: the five helper tests and productNameReadsLikeTheRestOfTheCatalogue, ported 2026-09-08)
package ai.teemoon.providers

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    /**
     * Bug 4, half one: a namespace-less id used to become its OWN vendor
     * ("Grok-4.3"), so every Grok model got a section to itself.
     */
    @Test fun vendorComesFromTheFamilyWhenThereIsNoNamespace() {
        assertEquals("xAI", ModelCatalog.vendorLabel("grok-4.3"))
        assertEquals("xAI", ModelCatalog.vendorLabel("grok-4.20-0309-reasoning"))
        assertEquals("Qwen", ModelCatalog.vendorLabel("qwen3.5:4b"))       // ollama-style tag
        assertEquals("Google", ModelCatalog.vendorLabel("gemma-3-4b-it"))  // llama.cpp-style
        assertEquals("Other", ModelCatalog.vendorLabel("totally-unknown-model"))
    }

    /**
     * Bug 4, half two: Fireworks ids are `accounts/{account}/models/{slug}`, so
     * the namespace is a hosting account. Only a catalogue that KNOWS that opts
     * into the family lookup — the generic label keeps namespace authority.
     */
    @Test fun familyLookupIsOptInForContainerNamespaces() {
        assertEquals("Accounts", ModelCatalog.vendorLabel("accounts/fireworks/models/kimi-k2p6"))
        assertEquals("Moonshot", ModelCatalog.familyVendor("kimi-k2p6"))
        assertEquals("OpenAI", ModelCatalog.familyVendor("gpt-oss-120b"))
        assertEquals("NVIDIA", ModelCatalog.familyVendor("nemotron-3-ultra"))
        assertEquals("Z.ai", ModelCatalog.familyVendor("glm-5p2"))
        assertNull(ModelCatalog.familyVendor("totally-unknown-model"))
    }

    /**
     * A real namespace still wins — near.ai ids must label exactly as before.
     * Load-bearing: `differentVendor` compares these labels to catch a node
     * serving a different model than the one requested, so a shared family
     * keyword must NOT collapse `QuantTrio/GLM-5.1-AWQ` into `zai-org/…`.
     */
    @Test fun namespaceStillWinsForNearAIStyleIDs() {
        assertEquals("Z.ai", ModelCatalog.vendorLabel("zai-org/GLM-5.1-FP8"))
        assertEquals("DeepSeek", ModelCatalog.vendorLabel("deepseek-ai/DeepSeek-V4-Flash"))
        assertEquals("Moonshot", ModelCatalog.vendorLabel("moonshotai/kimi-k2.6"))
        assertEquals("OpenAI", ModelCatalog.vendorLabel("openai/gpt-oss-120b"))
        assertEquals("Mystery", ModelCatalog.vendorLabel("mystery/model"))
        assertEquals("Other", ModelCatalog.vendorLabel(""))
        assertTrue(NearAIModelCatalog.differentVendor("QuantTrio/GLM-5.1-AWQ", "zai-org/GLM-5.1-FP8"))
    }

    @Test fun slugDropsQuantSuffixesIncludingNVFP4() {
        assertEquals("nemotron-3-ultra", ModelCatalog.slug("accounts/fireworks/models/nemotron-3-ultra-nvfp4"))
        assertEquals("glm-5.1", ModelCatalog.slug("zai-org/GLM-5.1-FP8"))
    }

    @Test fun priceAndContextFormatting() {
        assertEquals("$1.25/$2.50", ModelCatalog.priceLabel(1.25, 2.5))
        assertEquals("$2.00/$6.00", ModelCatalog.priceLabel(2.0, 6.0))
        assertEquals("", ModelCatalog.priceLabel(null, 6.0))
        assertEquals("1M", ModelCatalog.contextLabel(1_048_576))     // not "1.0M"
        assertEquals("1.1M", ModelCatalog.contextLabel(1_100_000))
        assertEquals("202k", ModelCatalog.contextLabel(202_752))
        assertEquals("", ModelCatalog.contextLabel(0))
        assertEquals("", ModelCatalog.contextLabel(null))
    }

    /** Android-only: 1024-based local label vs decimal cloud label. */
    @Test fun localContextLabelIs1024Based() {
        assertEquals("128k", ModelCatalog.localContextLabel(131_072))
        assertEquals("131k", ModelCatalog.contextLabel(131_072))
        assertEquals("1m", ModelCatalog.localContextLabel(1_048_576))
        assertEquals("", ModelCatalog.localContextLabel(0))
        assertEquals("512", ModelCatalog.localContextLabel(512))
    }

    /** Android-only: Fireworks sends fractional seconds. */
    @Test fun parseISO8601AcceptsFractionalSeconds() {
        assertEquals(
            Instant.parse("2026-04-24T04:09:07.488210Z"),
            ModelCatalog.parseISO8601("2026-04-24T04:09:07.488210Z"),
        )
        assertEquals(
            Instant.parse("2026-04-24T04:09:07Z"),
            ModelCatalog.parseISO8601("2026-04-24T04:09:07Z"),
        )
        assertNull(ModelCatalog.parseISO8601(null))
        assertNull(ModelCatalog.parseISO8601("not a date"))
    }

    // sortByVendorFamily / curatedMatch and their test went with the curated
    // snapshot they read (ac11ef2). There is no compiled model list to rank
    // against any more; each source orders its own rows, and those orderings are
    // tested where they live — NearAIModelCatalogTest.tiersLeadAndRecencyOrders.

    /**
     * NVIDIA's hosted catalogue mixes chat models with embedders, guards,
     * translators and encoders, and a browser listing a guardrail model as
     * something to talk to is a bug the user has to diagnose.
     */
    @Test fun nonChatFilterCoversTheHostedCatalogues() {
        for (id in listOf(
            "nvidia/nv-embed-v1", "nvidia/rerank-qa-mistral-4b", "nvidia/llama-3.1-nemoguard-8b-topic-control",
            "nvidia/nvclip", "google/deplot", "microsoft/kosmos-2", "adept/fuyu-8b",
            "meta/llama-guard-4-12b", "nvidia/nemotron-content-safety", "black-forest-labs/flux.1-dev",
            "openai/whisper-large-v3", "Qwen/Qwen3-Embedding-0.6B",
        )) assertTrue("$id must be filtered", ModelCatalog.isNonChat(id))
        // And the ones that only LOOK like tooling stay.
        for (id in listOf("z-ai/glm-5.3-flash", "anthropic/claude-sonnet-5", "nvidia/nemotron-3-super-120b-a12b")) {
            assertFalse("$id must stay", ModelCatalog.isNonChat(id))
        }
    }

    /**
     * OpenRouter marks an alias with a leading "~". It is a marker, not part of
     * the vendor's name — left in, it sorted a second "~openai" section under
     * the real one.
     */
    @Test fun anAliasMarkerIsNotPartOfTheVendorName() {
        assertEquals("OpenAI", ModelCatalog.vendorLabel("~openai/gpt-sol-latest"))
        assertEquals("OpenAI", ModelCatalog.vendorLabel("openai/gpt-5.4"))
        assertEquals("xAI", ModelCatalog.vendorLabel("x-ai/grok-4.6"))
        assertEquals("Meta", ModelCatalog.vendorLabel("meta/llama-4-70b"))
        assertEquals("Mistral", ModelCatalog.vendorLabel("nv-mistralai/mistral-nemo-12b"))
    }

    /**
     * One model must read the same everywhere it appears (iOS fbedb79). A whole-dollar rate printed "$1/$5" in a
     * browse row and "$1.00/$5.00" from a generated snapshot, which is the same model looking like two. Kit 0.5.0
     * pins the two-decimal form.
     */
    @Test fun priceAlwaysCarriesTwoDecimals() {
        assertEquals("$1.00/$5.00", ModelCatalog.priceLabel(1.0, 5.0))
        assertEquals("$2.00/$6.00", ModelCatalog.priceLabel(2.0, 6.0))
        assertEquals("$1.40/$4.40", ModelCatalog.priceLabel(1.4, 4.4))
        assertEquals("$1.00/$4.40", ModelCatalog.priceLabel(1.0, 4.4))
        assertEquals("", ModelCatalog.priceLabel(null, 5.0))
    }

    /** The vendor the section header already prints is dropped — unless it is also the family word; house-style names are left alone. */
    @Test fun productNameReadsLikeTheRestOfTheCatalogue() {
        assertEquals("gpt oss 20b", ModelCatalog.productName("OpenAI gpt-oss-20b", "OpenAI"))
        // a precision suffix goes with it: this row rendered "nvidia nemotron 3 ultra n…", truncating the model out of its own name
        assertEquals("Nemotron 3 Ultra", ModelCatalog.productName("NVIDIA Nemotron 3 Ultra NVFP4", "NVIDIA"))
        // NOT dropped when the vendor word is also the family word — "V4 Flash" names nothing
        assertEquals("DeepSeek V4 Flash", ModelCatalog.productName("DeepSeek-V4-Flash", "DeepSeek"))
        assertEquals("Minimax M3", ModelCatalog.productName("Minimax M3", "MiniMax"))
        assertEquals("Kimi K2.7 Code", ModelCatalog.productName("Kimi K2.7 Code", "Moonshot"))
        assertEquals("GLM 5.2", ModelCatalog.productName("GLM 5.2", "Z.ai"))
        assertEquals("Qwen3p7 Plus", ModelCatalog.productName("Qwen3p7 Plus", "Qwen"))   // case is the ROW's business
        assertEquals("instruct", ModelCatalog.productName("instruct", "Other"))          // never peels to nothing
    }

    // MARK: - The model card's rates (835d7c8)

    /**
     * SUB-CENT RATES KEEP THEIR DIGITS. A cache read of $0.0003 rounded to two
     * places prints "$0.00", which reads as free — and these rates are on a
     * card whose whole job is saying what a model costs besides the two text
     * rates, where the sub-cent ones are the interesting ones.
     */
    @Test fun rateLabelKeepsSubCentDigits() {
        assertEquals("$2.50", ModelCatalog.rateLabel(2.5))
        assertEquals("$2.00", ModelCatalog.rateLabel(2.0))
        assertEquals("$0.00", ModelCatalog.rateLabel(0.0))
        assertEquals("$0.0003", ModelCatalog.rateLabel(0.0003))
        assertEquals("$0.0025", ModelCatalog.rateLabel(0.0025))
        // Trailing zeros go, but never the last significant digit.
        assertEquals("$0.001", ModelCatalog.rateLabel(0.001))
    }

    @Test fun extraCostRendersPerMillionOrPerCall() {
        assertEquals("$0.25 / 1M tokens", KnownModel.ExtraCost("cache read", perMillion = 0.25).amount)
        assertEquals("$0.01", KnownModel.ExtraCost("web search", perCall = 0.01).amount)
        // A long-context tier prices input and output apart.
        assertEquals("$1.00/$3.00 / 1M tokens",
            KnownModel.ExtraCost("past 128k", perMillion = 1.0, perMillionOutput = 3.0).amount)
        assertEquals("", KnownModel.ExtraCost("nothing known").amount)
    }

    /**
     * "free" is a FACT the source reported, not an inference from a blank price.
     * A model with an unpublished price shows the same empty string, and calling
     * that one free would be inventing a commercial claim.
     */
    @Test fun metaLabelSaysFreeOnlyWhenTheSourceSaidSo() {
        val free = KnownModel(id = "a/b", displayName = "B", vendor = "A", price = "", contextWindow = "128k", isFree = true)
        val unpriced = free.copy(isFree = false)
        val priced = free.copy(price = "$1.00/$3.00")
        assertEquals("free · 128k", free.metaLabel)
        assertEquals("128k", unpriced.metaLabel)
        assertEquals("$1.00/$3.00 · 128k", priced.metaLabel)
    }
}
