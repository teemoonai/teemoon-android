// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (the OpenRouterAdapter suite).
//        The payload is RECONSTRUCTED from the Swift assertions rather than copied from
//        teemoonTests/Fixtures/openrouter_models.json — that fixture is not in the conformance
//        kit, and the kit is the only vendoring path (conformance/VENDOR.md). Same precedent as
//        EndpointDirectoryTest's snapshot. Every id and value below is one the Swift suite names.
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlinx.serialization.json.Json

class OpenRouterAdapterTest {

    private val now = Instant.ofEpochSecond(1_790_000_000)

    /** Twelve records, ten of them chat: one `:batch`, one embedding. */
    private val payload = """
    {"data":[
      {"id":"inclusionai/ling-3.0-flash-vl:free","name":"InclusionAI: Ling 3.0 Flash VL","created":1789900000,
       "context_length":131072,"hugging_face_id":"inclusionAI/Ling-3.0-flash-vl",
       "pricing":{"prompt":"0","completion":"0"},
       "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
       "supported_parameters":["tools","reasoning"],"description":"A free vision model."},
      {"id":"openai/gpt-5.4","name":"OpenAI: GPT-5.4","created":1789800000,"context_length":1100000,
       "pricing":{"prompt":"0.0000025","completion":"0.000015","input_cache_read":"0.00000025","web_search":"0.01"},
       "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
       "supported_parameters":["tools","structured_outputs"],"description":"The frontier model.",
       "top_provider":{"max_completion_tokens":128000,"is_moderated":true},
       "knowledge_cutoff":"2026-01-01"},
      {"id":"x-ai/grok-4.5","name":"SpaceXAI: Grok 4.5","created":1789700000,"context_length":500000,
       "pricing":{"prompt":"0.000002","completion":"0.000006"},
       "architecture":{"output_modalities":["text"]},"supported_parameters":["tools"]},
      {"id":"anthropic/claude-sonnet-5","name":"Anthropic: Claude Sonnet 5","created":1789600000,
       "context_length":200000,"pricing":{"prompt":"0.000003","completion":"0.000015"},
       "architecture":{"output_modalities":["text"]},"supported_parameters":["tools"]},
      {"id":"openrouter/auto-beta","name":"Auto Router (Beta)","created":1789500000,"context_length":200000,
       "pricing":{"prompt":"-1","completion":"-1"},"architecture":{"output_modalities":["text"]}},
      {"id":"tencent/hy-mt2-1.8b","name":"Tencent: HY MT2 1.8B","created":1789400000,"context_length":32768,
       "pricing":{"prompt":"0.0000001","completion":"0.0000002"},
       "architecture":{"output_modalities":["text"]},"supported_parameters":["temperature"]},
      {"id":"openai/gpt-5.4:batch","name":"OpenAI: GPT-5.4 (batch)","created":1789300000,
       "context_length":1100000,"pricing":{"prompt":"0.00000125","completion":"0.0000075"},
       "architecture":{"output_modalities":["text"]}},
      {"id":"openai/text-embedding-4","name":"OpenAI: Embedding 4","created":1789200000,
       "pricing":{"prompt":"0.0000001","completion":"0"},
       "architecture":{"output_modalities":["embedding"]}}
    ]}
    """

    private fun records() = OpenRouterAdapter.decode(payload).data

    /**
     * The catalogue's `hugging_face_id` IS the open-weights signal: present for a
     * published model, absent for a closed one. Rows carry it so the default rule
     * can prefer open weights without a hand-kept list.
     */
    @Test fun openWeightsFollowTheHuggingFaceId() {
        val byID = OpenRouterAdapter.buildModels(records(), now).associateBy { it.id }
        assertEquals(true, byID["inclusionai/ling-3.0-flash-vl:free"]?.openWeights)
        assertEquals(false, byID["anthropic/claude-sonnet-5"]?.openWeights)
    }

    /**
     * anAliasRowNamesTheModelThatAnswers (OpenRouterModelCardTests @ v1.0.4, iOS 8682e08): OpenRouter lists alias rows
     * ("~openai/gpt-sol-latest") and has no endpoints for them — a question about how one is served belongs to the
     * model it redirects to. The "~" is the alias marker, not part of the vendor's name.
     */
    @Test fun anAliasRowNamesTheModelThatAnswers() {
        val codec = Json { ignoreUnknownKeys = true }
        fun model(json: String) = codec.decodeFromString(OpenRouterAdapter.ModelsResponse.Model.serializer(), json)
        val alias = model("""{"id":"~openai/gpt-sol-latest","name":"OpenAI: GPT Sol (latest)","alias_target":{"name":"OpenAI: GPT-5.6 Sol","slug":"openai/gpt-5.6-sol"}}""")
        assertEquals("openai/gpt-5.6-sol", OpenRouterAdapter.row(alias, now).aliasOf)
        assertEquals(null, OpenRouterAdapter.row(model("""{"id":"openai/gpt-5.4"}"""), now).aliasOf)
        assertEquals("OpenAI", ModelCatalog.vendorLabel("~openai/gpt-sol-latest"))
        assertEquals("DeepSeek", ModelCatalog.vendorLabel("~deepseek/deepseek-pro-latest"))
    }

    @Test fun perTokenStringsBecomePerMillion() {
        assertEquals(2.5, OpenRouterAdapter.perMillion("0.0000025")!!, 0.0001)
        assertEquals(0.27, OpenRouterAdapter.perMillion("0.000000269")!!, 0.0001)
        assertEquals(0.0, OpenRouterAdapter.perMillion("0")!!, 0.0001)
        assertNull(OpenRouterAdapter.perMillion(null))
        assertNull(OpenRouterAdapter.perMillion("n/a"))
    }

    /**
     * Three shapes, three renderings: a rate, a free model, and "not a price"
     * (the auto router's -1) — which must be blank, never "$-1".
     */
    @Test fun pricingClassifiesPaidFreeAndDynamic() {
        val paid = OpenRouterAdapter.priceLabel(
            OpenRouterAdapter.ModelsResponse.Model.Pricing(prompt = "0.0000025", completion = "0.000015"))
        // "$15", not iOS's "$15.00": decimals only when there are cents, an
        // ACCEPTED DIVERGENCE (ports.json acceptedDivergences, decided 2026-09-20).
        // Do not "fix" this back — iOS declined to change and the two formats
        // differ on purpose.
        assertEquals("$2.50/$15.00", paid.first); assertFalse(paid.second)
        val free = OpenRouterAdapter.priceLabel(
            OpenRouterAdapter.ModelsResponse.Model.Pricing(prompt = "0", completion = "0"))
        assertTrue(free.first.isEmpty()); assertTrue(free.second)
        val dynamic = OpenRouterAdapter.priceLabel(
            OpenRouterAdapter.ModelsResponse.Model.Pricing(prompt = "-1", completion = "-1"))
        assertTrue("the auto router's -1 is not a price", dynamic.first.isEmpty()); assertFalse(dynamic.second)
        val none = OpenRouterAdapter.priceLabel(null)
        assertTrue(none.first.isEmpty()); assertFalse(none.second)
    }

    /**
     * "Vendor: Product" loses the vendor half — the section header already says
     * it — and the product half reads like every other catalogue name.
     */
    @Test fun namesDropTheVendorPrefix() {
        assertEquals("GPT 5.4", OpenRouterAdapter.productName("OpenAI: GPT-5.4", "openai/gpt-5.4", "OpenAI"))
        assertEquals("Grok 4.5", OpenRouterAdapter.productName("SpaceXAI: Grok 4.5", "x-ai/grok-4.5", "xAI"))
        assertEquals("Auto Router (Beta)",
            OpenRouterAdapter.productName("Auto Router (Beta)", "openrouter/auto-beta", "Openrouter"))
        assertEquals(ModelCatalog.displayName("qwen/qwen3.5-397b-a17b"),
            OpenRouterAdapter.productName(null, "qwen/qwen3.5-397b-a17b", "Qwen"))
    }

    /**
     * `:batch` cannot answer a chat turn — it is asynchronous. A `:free` variant
     * can, and so can a text model whose price is dynamic.
     */
    @Test fun chatFilterDropsBatchVariants() {
        val all = records()
        val chat = all.filter { it.isChatModel }.map { it.id }
        assertFalse(chat.any { it.endsWith(":batch") })
        assertFalse("an embedding is not a chat model", chat.any { it.contains("embedding") })
        assertTrue(chat.contains("inclusionai/ling-3.0-flash-vl:free"))
        assertTrue(chat.contains("openrouter/auto-beta"))
    }

    /** The whole picker row, through the fetch seam, with NO key: the list is public. */
    @Test fun listModelsBuildsCompletePickerRowsWithoutAKey() = runTest {
        val seen = ArrayList<Pair<String, Map<String, String>>>()
        val result = OpenRouterAdapter.listModels(
            "https://openrouter.ai/api/v1", apiKey = "",
            fetch = { url, headers, _ -> seen.add(url to headers); 200 to payload.toByteArray() },
            now = now)
        val models = (result as EndpointModelCatalog.ProbeResult.Connected).models
        assertEquals(listOf("https://openrouter.ai/api/v1/models"), seen.map { it.first })
        assertTrue("a public list is read without a credential", seen.single().second.isEmpty())

        val gpt = models.first { it.id == "openai/gpt-5.4" }
        assertEquals("GPT 5.4", gpt.displayName)
        assertEquals("OpenAI", gpt.vendor)
        assertEquals("$2.50/$15.00", gpt.price)
        assertEquals("1.1M", gpt.contextWindow)
        assertTrue(gpt.capabilities!!.contains(ModelCapability.TOOLS))
        assertTrue(gpt.capabilities.contains(ModelCapability.VISION))
        assertEquals("https://openrouter.ai/openai/gpt-5.4", gpt.modelPageURL)
        assertTrue(gpt.summary!!.isNotEmpty())
        // The card's cost lines, which the row's two rates do not carry.
        assertTrue(gpt.extraCosts.any { it.label == "cache read" })
        assertTrue(gpt.extraCosts.any { it.label == "web search" })
        assertEquals("2026-01-01", gpt.knowledgeCutoff)

        // A namespace the vendor map has to know, or the section reads "X-ai".
        assertEquals("xAI", models.first { it.id == "x-ai/grok-4.5" }.vendor)

        // A free model says so; a dynamic price says nothing.
        val free = models.first { it.id.endsWith(":free") }
        assertTrue(free.price.isEmpty()); assertTrue(free.isFree)
        assertTrue(free.metaLabel.startsWith("free"))
        val auto = models.first { it.id == "openrouter/auto-beta" }
        assertTrue(auto.price.isEmpty()); assertFalse(auto.isFree)

        // No tools claimed for a model that lists none.
        assertFalse(models.first { it.id == "tencent/hy-mt2-1.8b" }.capabilities!!.contains(ModelCapability.TOOLS))

        // Newest first, so a vendor section leads with the fresh release.
        assertEquals("inclusionai/ling-3.0-flash-vl:free", models.first().id)
    }

    /** A key IS sent when there is one, and a rejected key is UNAUTHORIZED rather than offline. */
    @Test fun aKeyIsSentWhenPresentAndARejectedOneIsUnauthorized() = runTest {
        val seen = ArrayList<Map<String, String>>()
        val ok = OpenRouterAdapter.listModels("https://openrouter.ai/api/v1", apiKey = "sk-or-x",
            fetch = { _, headers, _ -> seen.add(headers); 200 to payload.toByteArray() }, now = now)
        assertTrue(ok is EndpointModelCatalog.ProbeResult.Connected)
        assertEquals("Bearer sk-or-x", seen.single()["Authorization"])

        val bad = OpenRouterAdapter.listModels("https://openrouter.ai/api/v1", apiKey = "sk-or-bad",
            fetch = { _, _, _ -> 401 to ByteArray(0) }, now = now)
        assertEquals(EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED), bad)
    }

    @Test fun handlesOnlyOpenRouterHosts() {
        assertTrue(OpenRouterAdapter.handles("openrouter.ai"))
        assertTrue(OpenRouterAdapter.handles("api.openrouter.ai"))
        assertFalse(OpenRouterAdapter.handles("openrouter.ai.evil.com"))
        assertFalse(OpenRouterAdapter.handles("api.openai.com"))
        assertFalse(OpenRouterAdapter.handles(null))
    }
}
