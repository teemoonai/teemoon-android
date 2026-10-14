// ports: teemoon/Providers/Adapters/NVIDIAAdapter.swift @ v1.0.4
package ai.teemoon.providers

/**
 * NVIDIA's hosted API catalogue (build.nvidia.com), contained in one file.
 *
 * Inference is OpenAI-compatible under `https://integrate.api.nvidia.com/v1`,
 * and `/v1/models` is the plain OpenAI list — ids and an `owned_by`, nothing to
 * render. It is public: no key is needed to read it.
 *
 * Why native rather than the generic probe:
 *  - The list mixes embedders, rerankers, guard/safety classifiers, a reward
 *    model, translators, parsers and vision encoders in with the chat models,
 *    and none of them carry a marker the generic keyword filter catches.
 *    Picking one breaks the chat.
 *  - There is no per-token price to show, and that is a FACT ABOUT THE TIER
 *    rather than a gap in a table: the hosted catalogue is free for development
 *    under the NVIDIA Developer Program, rate-limited per model. Rows say
 *    "free" rather than sitting blank the way an unpublished price does.
 */
object NVIDIAAdapter {

    /**
     * Hosts this adapter serves. `integrate.api.nvidia.com` today; matched on
     * the api subtree so a regional host still resolves here.
     */
    fun handles(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "integrate.api.nvidia.com" || h.endsWith(".api.nvidia.com")
    }

    /**
     * The shared non-chat filter plus `parse`, which stays NVIDIA-only:
     * nemotron-parse is a document parser, and the word is too common to put on
     * the shared list without catching something that does chat.
     */
    fun isChatModel(id: String): Boolean =
        !ModelCatalog.isNonChat(id) && !ModelCatalog.slug(id).lowercase().contains("parse")

    suspend fun listModels(
        baseUrl: String,
        apiKey: String,
        // NOT defaulted: requiring the seam means no call site reaches the
        // network by accident. (The shared live implementation is
        // EndpointModelCatalog.liveFetch; ModelCatalog.liveCatalog passes it.)
        fetch: suspend (url: String, headers: Map<String, String>, timeoutMs: Long) -> Pair<Int, ByteArray>,
    ): EndpointModelCatalog.ProbeResult {
        val url = baseUrl.trimEnd('/') + "/models"
        val list = when (val r = EndpointModelCatalog.fetchList(url, apiKey, fetch = fetch) {
            EndpointModelCatalog.decode(it)
        }) {
            is EndpointModelCatalog.Fetched.Failure -> return EndpointModelCatalog.ProbeResult.Failed(r.kind)
            is EndpointModelCatalog.Fetched.Success -> r.value
        }
        val models = buildModels(list.data)
        if (models.isEmpty()) return EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.BAD_RESPONSE)
        return EndpointModelCatalog.ProbeResult.Connected(models)
    }

    /**
     * Pure (no network) so it is unit-testable against a captured payload.
     *
     * NVIDIA's `created` is ONE CONSTANT for the whole list, so recency cannot be
     * read from it — sorting by it would produce an arbitrary order wearing the
     * look of a meaningful one. Rows sort by vendor then id, NVIDIA's own first.
     */
    fun buildModels(records: List<OpenAIModelsResponse.Model>): List<KnownModel> {
        val seen = HashSet<String>()
        val models = records.mapNotNull { m ->
            if (!isChatModel(m.id) || !seen.add(m.id.lowercase())) return@mapNotNull null
            KnownModel(
                id = m.id,
                displayName = ModelCatalog.displayName(m.id),
                vendor = ModelCatalog.vendorLabel(m.id),
                // No context window: the list does not report one, and a number
                // from elsewhere would be a guess. Blank, honestly.
                price = "",
                // The model page is `build.nvidia.com/<namespace>/<slug>`,
                // verified for nemotron-3-super-120b-a12b and kimi-k3.
                modelPageURL = "https://build.nvidia.com/" + m.id,
                isFree = true,
                openWeights = true,
            )
        }
        return models.sortedWith(compareBy({ rank(it.vendor) }, { it.id }))
    }

    /** NVIDIA's own models lead; everything else is alphabetical after them. */
    private fun rank(vendor: String) = if (vendor == "NVIDIA") "" else vendor.lowercase()
}
