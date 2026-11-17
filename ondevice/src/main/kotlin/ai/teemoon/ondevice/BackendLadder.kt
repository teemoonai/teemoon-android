// android-only: no Swift counterpart. iOS runs LiteRT on one Apple GPU stack and picks no backend at runtime;
// Android has to choose, on hardware the app has never seen, and be right on the first turn.
package ai.teemoon.ondevice

/**
 * The accelerators a LiteRT-LM engine may be asked to run on, best first.
 *
 * Kept as an enum rather than `Backend` instances so the ORDER is pure data: the ladder is decided from device facts
 * and tested on the JVM, while :ondevice's binding turns a rung into the runtime's own type.
 */
enum class BackendKind {
    /** The GPU compute path (the LITERT_WEBGPU delegate over the vendor's OpenCL). */
    GPU,

    /** A vendor NPU through a dispatch library found from the APK's `nativeLibraryDir`. */
    NPU,

    /** Google Tensor's own path. 0.16.1, 0.17.0 and 0.18.0 all reject it: "Not recognized backend: 5" (0.18.0 on a Pixel 10 Pro, 2026-10-14). */
    GOOGLE_TENSOR,

    /** Always last, always present: the floor the ladder can never fall through. */
    CPU,
}

/**
 * Which accelerators to TRY, in order, on this device.
 *
 * The ladder is deliberately optimistic — it lists everything the device might support, because the probe that walks
 * it is honest (see [LiteRtLmEngineFactory]: a rung is accepted only once it has decoded a real token). Being wrong
 * about a rung therefore costs one failed attempt, not a broken model, so the cost of listing a rung the device
 * cannot use is bounded and the cost of omitting one it CAN use is permanent.
 *
 * **The order is measured, not assumed.** Pixel 10 Pro (Tensor G5, Android 17), Gemma 4 E2B, one turn with the web
 * tool attached, 2026-09-15 — every rung forced in turn through [BackendMemo]:
 *
 * | rung | load | first token | prefill | decode |
 * |---|---|---|---|---|
 * | gpu | 3.7 s warm | **3.7 s** | **705 tok/s** | 7 tok/s |
 * | cpu | 5.6 s | 17.4 s | 149 tok/s | **13 tok/s** |
 *
 * The GPU leads. Normalised to a 200-token answer the two finish within half a second of each other, but the GPU
 * shows the first word in 3.7 s against 17.4 s, and a streaming transcript is judged on when it starts moving.
 * Prefill decides that, the GPU is 4.7x the CPU there, and every turn re-prefills a system prompt carrying the tools.
 */
object BackendLadder {
    /** `Build.HARDWARE` values for the Android emulator, whose "GPU" is the host's software adapter. */
    private val EMULATORS = setOf("ranchu", "goldfish")

    /**
     * The NPU is NEVER on this ladder, and the reason is the bundle rather than the hardware.
     *
     * We do now ship a dispatch library (see `jniLibs/README.md`), so the accelerator can register. But the ladder is
     * walked for the GENERAL bundle, and a general `.litertlm` has no NPU graph in it. Offered one, the NPU rung does
     * not refuse: it loads, falls through internally to something that is not the NPU, and decodes at 6 tok/s against
     * the real CPU path's 13 — a DISGUISED FALLBACK, slower than the floor while claiming to be silicon. A rung that
     * cannot do what it says must not be able to win an honest probe.
     *
     * The NPU is reached the only way it can be: by installing a bundle compiled for this chip, which makes
     * `LocalModelStorage.ref` mark the model `requiresNpu` and the factory use that one rung alone. See
     * [ai.teemoon.providers.LocalModelCatalog.npuBundle].
     *
     * `Backend.GOOGLE_TENSOR` is absent for a different reason: 0.16.1, 0.17.0 and 0.18.0 reject it outright, "Not
     * recognized backend: 5". The native side's own list reads [CPU, GPU, NPU, GPU_ARTISAN, CPU_ARTISAN,
     * GOOGLE_TENSOR_ARTISAN, TENSOR_LLM] and the Kotlin binding exposes no ARTISAN variant to map onto.
     */

    /**
     * @param socModel `Build.SOC_MODEL` ("Tensor G5"), or empty when the device will not say.
     * @param hardware `Build.HARDWARE` ("blazer", "ranchu").
     */
    /**
     * The rungs an image turn may run on: those with a vision encoder beside them — the GPU and the CPU; and the NPU when
     * the bundle is NPU-only ([requireNpu]) and an image turn reaches it at all, which only a bundle that carries the
     * encoder does (NpuBundle.seesImages: the Snapdragon 8 Elite's and the Tensor G5's and G6's; LocalModelStorage routes an
     * unmeasured bundle's to the general file).
     */
    /**
     * The order for an image turn on the general file: the GPU then the CPU where the GPU reads images right
     * ([readsImagesOnGpu]), else the CPU alone. The Pixel 10's PowerVR leads with the GPU here though its text does not (the
     * maintainer 2026-11-12, on the measurement): Pixel 10 Pro, E2B, three cached GPU runs against the CPU, every answer right —
     * first token 0.72 / 1.50 / 4.15 s for 1 / 4 / 10 pictures against 2.09 / 6.31 / 14.37 s, no slowdown from run to run.
     * Its text on the general file stays on the CPU ([order]): the cached-kernel halving of 10-26 was text decode.
     */
    fun visionOrder(socModel: String, hardware: String): List<BackendKind> = when {
        hardware.lowercase() in EMULATORS -> listOf(BackendKind.CPU)
        readsImagesOnGpu(socModel) -> listOf(BackendKind.GPU, BackendKind.CPU)
        else -> listOf(BackendKind.CPU)
    }

    /**
     * A GPU whose image turns are right: a Google Tensor's or a Snapdragon 8-series' (the maintainer 2026-11-12, "use the
     * data you gathered to come up with a rule"; docs/bench/2026-11-12-images). Those named all 10 pictures right
     * (Snapdragon 8 Elite and 8 Elite Gen 5; Tensor G2, G4, G5); two mid-range GPUs named them WRONG with no error — the
     * Galaxy A25's Mali-G68 (Exynos 1280) 2 of 10, the Moto g54's (Dimensity 7020) 0 of 10, gibberish even for one — and
     * the CPU named every one right on every phone. A wrong answer that looks right is the failure this app cannot ship,
     * so every other chip's image turns run on the CPU: slower (14–54 s for 10 pictures), never wrong that way.
     * `Build.SOC_MODEL`: "SM8750", "Tensor G5"; the Pixel 6 and 7 read "GS101" / "GS201", the Pixel 8 "Zuma".
     */
    fun readsImagesOnGpu(socModel: String): Boolean {
        val soc = socModel.trim().lowercase()
        return soc.startsWith("sm8") || soc.startsWith("tensor") || soc.startsWith("gs") || soc.startsWith("zuma")
    }

    fun visionLadder(full: List<BackendKind>, requireNpu: Boolean): List<BackendKind> =
        full.filter { it == BackendKind.GPU || it == BackendKind.CPU || (requireNpu && it == BackendKind.NPU) }

    fun order(socModel: String, hardware: String): List<BackendKind> {
        // The emulator's GPU initializes on the host's software adapter and then dies at decode. The honest probe
        // would catch that, but only after paying a full model load for it, so skip straight to the floor.
        if (hardware.lowercase() in EMULATORS) return listOf(BackendKind.CPU)
        // The Pixel 10 family's PowerVR GPU (Tensor G5), measured on a Pixel 10 Pro 2026-10-26 with the general Gemma 4
        // E2B file: LiteRT's cached GPU kernels decode at HALF speed (5-6 tok/s against 10-13 on a fresh compile;
        // deleting only *_mldrift_program_cache.bin restores it), and GPU loads take 13.7 s cached / 56.5 s cold. The
        // CPU rung loads in 5.3 / 13.2 s and decodes at ~13.5 tok/s; Google AI Edge Gallery offers this phone the CPU
        // alone for the file. the maintainer 2026-10-26: the CPU. The cost is prefill (~120 tok/s at 4 threads against the GPU's
        // 600+), paid on long histories — on this phone the general file only serves image turns (text runs the NPU
        // bundle), and cpuThreads is tuned for it. Mali (Pixel 8a, 6a) keeps the GPU: no slowdown there.
        if (isPowerVr(socModel)) return listOf(BackendKind.CPU)
        return listOf(BackendKind.GPU, BackendKind.CPU)
    }

    /** `Build.SOC_MODEL` of a chip whose GPU is a PowerVR: the Tensor G5. */
    fun isPowerVr(socModel: String): Boolean = socModel.trim().lowercase() == "tensor g5"

    /**
     * The CPU rung's thread count. LiteRT-LM 0.18.0's `Backend.CPU` takes one count for prefill and decode alike (its two
     * parameters are one setting under two names). 4 was measured best for DECODE on the Pixel 10 Pro (2026-09-16: 14
     * tok/s default, 17 at 4, 16 at 6, 14 at 8); prefill wants more. Per-chip values come from GalleryParityBenchTest's
     * thread sweep. `null` is the runtime's own default.
     */
    fun cpuThreads(socModel: String): Int? = CPU_THREADS_DEFAULT

    private const val CPU_THREADS_DEFAULT = 4

    /**
     * The KV window a rung is built with, for a [requested] one. The CPU rung takes 4096 (Gallery's own maxTokens 4000):
     * Pixel 10 Pro, general Gemma 4 E2B, CPU at 4 threads, 2026-10-26 (GalleryParityBenchTest t11, medians of 3), a long
     * reply took 7.8 s at 4096 against 10.4 s at 8192, prefill 180 tok/s against 124, and 180 MB less. The cost is half
     * the history and a search payload sized to it (BraveWebSearchTool's budget, ~1,500 tokens instead of ~4,200).
     * the maintainer 2026-10-26 ("processor only"): the CPU rung alone — the Pixel 10's image turns and any CPU fallback. GPU
     * phones keep 8192 until an A/B there says otherwise; the NPU bundle's window is compiled in and never asked here.
     */
    fun contextTokens(kind: BackendKind, requested: Int): Int =
        if (kind == BackendKind.CPU) minOf(requested, CPU_CONTEXT_TOKENS) else requested

    const val CPU_CONTEXT_TOKENS = 4096

    /**
     * Speculative decoding (`ExperimentalFlags.enableSpeculativeDecoding`: the bundle's MTP drafter proposes a batch,
     * the main model verifies it in one step) pays off only where verifying a batch costs about what one token does.
     * Measured 2026-09-16, Gemma 4 E2B, 96–128 tokens, decode tok/s off → on:
     *
     *   Adreno 840 (Galaxy S26 Ultra) GPU   24 → 52   raw; 26 → 69 through the app
     *   PowerVR (Pixel 10 Pro) GPU           9 → 7
     *   Tensor G5 CPU, 4 threads            17 → 14
     *   Tensor G5 NPU                       16 → 14; 0.18.0: 19.0 → 17.8 (medians of 3, interleaved, 2026-10-14)
     *   Snapdragon 8 Elite NPU (S25 Ultra)  37.7 → 37.7  medians of 3, 2026-10-15: Google's sm8750 bundle carries NO
     *                                       drafter (its table of contents has no tf_lite_mtp_drafter); the 33 → 37 of
     *                                       2026-10-14 was single runs, i.e. noise
     *
     * Real text matters: on 0.18.0 the Adreno 840 drafter gives +28% on long-form writing and 95–96 tok/s on a code edit
     * through the app (2026-10-15, docs/BENCHMARKS.md); `benchmark()`'s synthetic tokens defeat it.
     *
     * So: the GPU rung on Qualcomm silicon; nowhere else. `Build.HARDWARE` is "qcom"
     * on Snapdragon boards and `Build.SOC_MODEL` reads "SM8850"; either is enough. The factory also checks the FILE carries a drafter.
     */
    fun wantsSpeculativeDecoding(kind: BackendKind, socModel: String, hardware: String): Boolean {
        // No NPU: the Tensor NPU loses with it (16 → 14; 19.0 → 17.8 on 0.18.0) and the sm8750 bundle has no drafter.
        if (kind == BackendKind.NPU) return false
        if (kind != BackendKind.GPU) return false
        val soc = socModel.uppercase()
        return hardware.lowercase().startsWith("qcom") || Regex("^(SM|SDM|SXR|QCM|QCS)[0-9]").containsMatchIn(soc)
    }

    /**
     * Pixel silicon, by the SoC name the device reports ("Tensor G5"). `Build.SOC_MODEL` arrives only on API 31+ and
     * some devices leave it empty, so the Pixel board names are a second source rather than the primary one.
     */
    fun isGoogleTensor(socModel: String, hardware: String): Boolean {
        val soc = socModel.lowercase()
        if (soc.startsWith("tensor") || soc.startsWith("gs1") || soc.startsWith("gs2")) return true
        // Pixel boards carry the SoC generation: gs101 (Pixel 6) through the current ones.
        return Regex("^gs[0-9]{3}").containsMatchIn(hardware.lowercase())
    }
}
