// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the NVIDIAAdapter suite).
//        Payload reconstructed from the Swift assertions — nvidia_models.json is not in the
//        conformance kit, which is the only vendoring path (conformance/VENDOR.md).
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NVIDIAAdapterTest {

    /**
     * The hosted list mixes chat models with embedders, rerankers, guards, a
     * reward model, a translator, a parser and a vision encoder — and none of
     * them carry a marker the generic keyword filter catches. Picking one breaks
     * the chat, which is why this adapter exists at all.
     */
    private val payload = """
    {"object":"list","data":[
      {"id":"nvidia/nemotron-3-super-120b-a12b","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/nemotron-3-ultra-550b-a55b","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"moonshotai/kimi-k3","object":"model","created":1700000000,"owned_by":"moonshotai"},
      {"id":"deepseek-ai/deepseek-v4-flash","object":"model","created":1700000000,"owned_by":"deepseek-ai"},
      {"id":"nvidia/nv-embedqa-e5-v5","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/rerank-qa-mistral-4b","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/llama-3.1-nemoguard-8b-topic-control","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/nemotron-4-340b-reward","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/nemotron-parse-1.1","object":"model","created":1700000000,"owned_by":"nvidia"},
      {"id":"nvidia/nvclip","object":"model","created":1700000000,"owned_by":"nvidia"}
    ]}
    """

    private fun records() = EndpointModelCatalog.decode(payload).data

    @Test fun handlesOnlyNvidiaApiHosts() {
        assertTrue(NVIDIAAdapter.handles("integrate.api.nvidia.com"))
        assertTrue(NVIDIAAdapter.handles("eu.api.nvidia.com"))
        assertFalse(NVIDIAAdapter.handles("api.nvidia.com.evil.com"))
        assertFalse(NVIDIAAdapter.handles("build.nvidia.com"))
        assertFalse(NVIDIAAdapter.handles(null))
    }

    /**
     * `parse` stays NVIDIA-only rather than joining the shared non-chat list:
     * nemotron-parse is a document parser, and the word is too common to filter
     * globally without catching something that does chat.
     */
    @Test fun theNonChatMixIsFilteredOut() {
        val kept = NVIDIAAdapter.buildModels(records()).map { it.id }
        assertEquals(listOf(
            "nvidia/nemotron-3-super-120b-a12b",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "deepseek-ai/deepseek-v4-flash",
            "moonshotai/kimi-k3",
        ), kept)
        assertTrue(NVIDIAAdapter.isChatModel("nvidia/nemotron-3-super-120b-a12b"))
        assertFalse("a document parser is not a chat model", NVIDIAAdapter.isChatModel("nvidia/nemotron-parse-1.1"))
        assertFalse(NVIDIAAdapter.isChatModel("nvidia/nvclip"))
        assertFalse(NVIDIAAdapter.isChatModel("nvidia/nemotron-4-340b-reward"))
    }

    /**
     * "free" is a FACT ABOUT THE TIER, not a missing price: the hosted catalogue
     * is free for development and rate-limited. A row that sat blank would read
     * as "price unpublished", which is a different claim.
     */
    @Test fun rowsSayFreeRatherThanSittingBlank() {
        val row = NVIDIAAdapter.buildModels(records()).first()
        assertTrue(row.price.isEmpty())
        assertTrue(row.isFree)
        assertEquals("free", row.metaLabel)
        assertEquals(true, row.openWeights)
        assertEquals("https://build.nvidia.com/nvidia/nemotron-3-super-120b-a12b", row.modelPageURL)
        // No context window: the list reports none, and a number from elsewhere
        // would be a guess.
        assertTrue(row.contextWindow.isEmpty())
    }

    /**
     * NVIDIA's `created` is ONE CONSTANT for the whole list, so recency cannot be
     * read from it. Sorting by it would give an arbitrary order wearing the look
     * of a meaningful one; vendor then id is honest, NVIDIA's own first.
     */
    @Test fun nvidiaLeadsThenAlphabeticalByVendor() {
        val vendors = NVIDIAAdapter.buildModels(records()).map { it.vendor }
        assertEquals(listOf("NVIDIA", "NVIDIA", "DeepSeek", "Moonshot"), vendors)
    }

    @Test fun listModelsReadsThePublicListWithoutAKey() = runTest {
        val seen = ArrayList<Map<String, String>>()
        val result = NVIDIAAdapter.listModels("https://integrate.api.nvidia.com/v1", apiKey = "") { _, h, _ ->
            seen.add(h); 200 to payload.toByteArray()
        }
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(4, models.size)
        assertTrue("a public list is read without a credential", seen.single().isEmpty())
    }
}
