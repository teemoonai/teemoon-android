// ports: teemoon/Providers/LocalModelCatalog.swift @ v1.0.4
package ai.teemoon.providers

import java.util.Locale

/**
 * A model teemoon will offer to run on-device.
 *
 * Curated rather than a live search: an arbitrary repo is a multi-gigabyte
 * download that may not fit, may not be a LiteRT bundle at all, and may have a
 * chat template teemoon has never seen.
 *
 * The download/storage half is a REBUILD (LiteRT) and is not this file.
 */
data class LocalModel(
    /** HuggingFace repo id. Also the identity teemoon stores on the provider. */
    val id: String,
    val displayName: String,
    /** Download size in MB, from the Hub API's blob metadata — not estimated. */
    val sizeMB: Int,
    /** One line on what it's for. */
    val blurb: String,
    /**
     * Whether this model calls tools usefully — **measured on device**, not
     * claimed from a model card.
     *
     * **THE BAR IS NOT THE CALL RATE — IT IS THE FAILURE MODE.** A model that
     * sometimes declines to search and then says nothing useful is merely
     * unimpressive. A model that declines and *fabricates* the answer is
     * actively harmful.
     */
    val supportsTools: Boolean,
    val fileName: String,
    /** Git revision to fetch, instead of whatever `main` points at today. */
    val revision: String,
    /** Expected SHA-256. Integrity, not provenance. */
    val sha256: String,
    /**
     * Bundles compiled for ONE vendor's NPU, keyed by the lowercased `Build.SOC_MODEL` ("tensor g5").
     *
     * [fileName] above is the general bundle: it carries CPU and GPU graphs and runs on any phone. These carry
     * NEITHER, so a device that cannot reach its NPU cannot load them at all — the failure is "Input tensor not
     * found", measured 2026-09-15. That is why they are a separate map rather than a replacement: the general
     * bundle stays the thing every device can fall back to.
     *
     * Keys and revisions are Google's own, read from the Gallery app's `model_allowlist.json`, which is the only
     * published index that says which file belongs to which SoC.
     */
    val npuBundles: Map<String, NpuBundle> = emptyMap(),
) {
    /**
     * This model AS THIS DEVICE DOWNLOADS IT. On a chip with an NPU bundle the general file is the wrong file: it
     * runs on the GPU or CPU at a fraction of the accelerator's speed (Pixel 10 Pro: 7 tok/s on PowerVR against the
     * NPU's 17, and prefill 8x slower), and the app never offered the other one — the only Pixel that had the Tensor
     * bundle had it pushed by hand (2026-09-17). Same id, same row, same button: the file, size, checksum and the
     * name change, so "gemma 4 e2b · tensor" is what the get row, the download and the equipped place all say.
     * Every other chip gets the model unchanged. `Build.SOC_MODEL`, "Tensor G5"; empty below API 31.
     */
    fun forDevice(socModel: String, npuUnusable: (id: String) -> Boolean = { false }): LocalModel {
        val bundle = LocalModelCatalog.npuBundle(this, socModel) ?: return this
        if (npuUnusable(id)) return this   // the NPU file refused on this phone: the general form, so the get row offers that download
        return copy(displayName = "$displayName · ${bundle.chipLabel}", sizeMB = bundle.sizeMB, fileName = bundle.fileName, revision = bundle.revision, sha256 = bundle.sha256)
    }
    /** The general file's name, whatever this device downloads: an install from before the device catalog stays runnable. */
    val generalFileName: String get() = LocalModelCatalog.all.firstOrNull { it.id == id }?.fileName ?: fileName

    /** "2.4 GB" over a gigabyte, "512 MB" under it — what an alert quotes back. */
    val sizeLabel: String
        get() = if (sizeMB >= 1024) String.format(Locale.US, "%.1f GB", sizeMB / 1024.0)
                else "$sizeMB MB"
}

/**
 * One SoC's NPU bundle. Bigger than the general file — the NPU wants its weights laid out its own way — and gated
 * on memory, because a phone that cannot hold it will be killed rather than told.
 */
data class NpuBundle(
    val fileName: String,
    /** Git revision, as with [LocalModel.revision]: `main` moves and these files are re-cut. */
    val revision: String,
    val sizeMB: Int,
    /** Google's `minDeviceMemoryInGb` for the variant. */
    val minMemoryGB: Int,
    /**
     * The KV-cache ceiling COMPILED INTO the bundle. An NPU graph is built for a fixed sequence length; the runtime
     * clamps a larger request ("max_num_tokens (8192) is larger than what the model supports (4096). Using model
     * limit") and rejects a prompt that does not fit ("Input token ids are too long ... 4146 >= 4096"). Google's index
     * says 4096 for both Tensor bundles, and the device agreed.
     */
    val maxTokens: Int,
    /** Expected SHA-256, or empty where we have not yet pinned one. Empty means the download cannot be verified. */
    val sha256: String = "",
    /**
     * The chip family the row names ("gemma 4 e2b · tensor"). Was hard-coded "Tensor" while Tensor was the only NPU;
     * a Snapdragon phone would have read "· Tensor" (the local audit of ef2cdc5).
     */
    val chipLabel: String = "Tensor",
)

object LocalModelCatalog {
    /**
     * Ordered smallest-first: the top of the list should be the one a user can
     * try without committing to a multi-gigabyte download over cellular.
     */
    val all: List<LocalModel> = listOf(
        LocalModel(
            id = "litert-community/gemma-4-E2B-it-litert-lm",
            displayName = "Gemma 4 E2B",
            sizeMB = 2468,
            // One line, comparative, and it has to survive being read next to
            // E4B's: these two are the only choice on this screen, so the copy's
            // job is to separate them, not to praise either.
            blurb = "Best balance of speed and quality",
            // Measured 12/12 across four sweeps at 6.7s mean — the only local
            // model that calls tools reliably, and the reason this runtime won.
            supportsTools = true,
            fileName = "gemma-4-E2B-it.litertlm",
            revision = "9262660a1676eed6d0c477ab1a86344430854664",
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            // A row here promises a download that can RUN, so a chip is listed only when the APK carries its vendor's
            // dispatch library and runtime: Tensor (the dispatcher in jniLibs), and the Snapdragon 8 Elite (the
            // Qualcomm dispatcher + QNN v79, linked in by NpuDispatch, from 2026-10-14). The repo's other variants
            // (qcs8275, a Qualcomm IoT board; Intel LNL/PTL, laptops) stay out: nothing here could load them.
            npuBundles = mapOf(
                // b3ca0d2f: the newer G5 build, 3.11 GB against 3f250541's 3.95 — 21% fewer bytes through the memory bus
                // per decoded token, on a backend that is bandwidth-bound (2026-09-18). Sizes are MiB of the LFS object.
                "tensor g5" to NpuBundle("gemma-4-E2B-it_Google_Tensor_G5.litertlm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", 2970, 8, maxTokens = 4096,
                    sha256 = "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"),
                "tensor g6" to NpuBundle("gemma-4-E2B-it_Google_Tensor_G6.litertlm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", 3161, 8, maxTokens = 4096,
                    sha256 = "f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7"),
                // Snapdragon 8 Elite (Galaxy S25 series). Measured on an S25 Ultra in Samsung's Remote Test Lab, 2026-10-07:
                // 1938-token prefill 7042 tok/s (the same phone's GPU 2737), decode 43 tok/s (GPU 28), engine load 0.5 s, on
                // 2026-10-07; 4939 / 35 tok/s on 2026-10-14 — single runs both, so the benchmark campaign's medians decide
                // (GPU 7.5 s). Its Build.SOC_MODEL reads "SM8750".
                "sm8750" to NpuBundle("gemma-4-E2B-it_qualcomm_sm8750.litertlm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", 2877, 8, maxTokens = 4096,
                    sha256 = "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e", chipLabel = "Snapdragon"),
            ),
        ),
        LocalModel(
            id = "litert-community/gemma-4-E4B-it-litert-lm",
            displayName = "Gemma 4 E4B",
            sizeMB = 3490,
            // Slower is measured (12.3s vs 6.7s mean). Higher quality is the
            // reasonable expectation from the larger model, but ON_DEVICE_INFERENCE
            // still lists E4B's answer quality as unmeasured.
            blurb = "Higher quality but slower generation",
            // MEASURED 3/3 — as reliable a tool-caller as the E2B above, at
            // 12.3s mean against its 6.7s. Same rate, 1.8x the latency, 1 GB
            // more to download, so it does NOT lead the catalog: on the axis
            // that was measured it is not better, only bigger.
            supportsTools = true,
            fileName = "gemma-4-E4B-it.litertlm",
            revision = "f7ad3343bd6ebc9607f4dc3bc4f2398bd5749bc5",
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        ),
    )

    fun model(id: String): LocalModel? = all.firstOrNull { it.id == id }

    /**
     * The NPU bundle for this chip, or null when there is none for it.
     *
     * Matched on the lowercased SoC name because that is the key Google's own index uses, and because `Build.SOC_MODEL`
     * reports "Tensor G5" with capitals that no index agrees on.
     */
    fun npuBundle(model: LocalModel, socModel: String): NpuBundle? =
        model.npuBundles[socModel.trim().lowercase()]

    /** The catalog as this device downloads it: each model through [LocalModel.forDevice]. */
    fun forDevice(socModel: String, npuUnusable: (id: String) -> Boolean = { false }): List<LocalModel> = all.map { it.forDevice(socModel, npuUnusable) }
}

/**
 * The allowance a download runs under.
 *
 * Two values rather than a boolean because it is the download's own property,
 * not the current path's: one started on wi-fi stays wi-fi-only and parks when
 * the phone leaves the house, rather than quietly moving gigabytes onto the plan.
 */
enum class DownloadNetwork {
    /** Pauses off wi-fi and continues when it is back — what a download started on wi-fi does. */
    WIFI_ONLY,
    /** Mobile data too. Only ever the result of an explicit choice. */
    ANY,
}
