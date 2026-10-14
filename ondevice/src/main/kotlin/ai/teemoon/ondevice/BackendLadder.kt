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

    /** Google Tensor's own path. Both 0.16.1 and 0.17.0 reject it: "Not recognized backend: 5". */
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
     * `Backend.GOOGLE_TENSOR` is absent for a different reason: both 0.16.1 and 0.17.0 reject it outright, "Not
     * recognized backend: 5". The native side's own list reads [CPU, GPU, NPU, GPU_ARTISAN, CPU_ARTISAN,
     * GOOGLE_TENSOR_ARTISAN, TENSOR_LLM] and the Kotlin binding exposes no ARTISAN variant to map onto.
     */

    /**
     * @param socModel `Build.SOC_MODEL` ("Tensor G5"), or empty when the device will not say.
     * @param hardware `Build.HARDWARE` ("blazer", "ranchu").
     */
    fun order(socModel: String, hardware: String): List<BackendKind> {
        // The emulator's GPU initializes on the host's software adapter and then dies at decode. The honest probe
        // would catch that, but only after paying a full model load for it, so skip straight to the floor.
        if (hardware.lowercase() in EMULATORS) return listOf(BackendKind.CPU)
        return listOf(BackendKind.GPU, BackendKind.CPU)
    }

    /**
     * Speculative decoding (`ExperimentalFlags.enableSpeculativeDecoding`: the bundle's MTP drafter proposes a batch,
     * the main model verifies it in one step) pays off only where verifying a batch costs about what one token does.
     * Measured 2026-09-16, Gemma 4 E2B, 96–128 tokens, decode tok/s off → on:
     *
     *   Adreno 840 (Galaxy S26 Ultra) GPU   24 → 52   raw; 26 → 69 through the app
     *   PowerVR (Pixel 10 Pro) GPU           9 → 7
     *   Tensor G5 CPU, 4 threads            17 → 14
     *   Tensor G5 NPU                       16 → 14
     *   Snapdragon 8 Elite NPU (S25 Ultra)  33 → 37   2026-10-14, single runs; the benchmark campaign's repeats decide
     *
     * So: the GPU rung on Qualcomm silicon, and the Snapdragon 8 Elite's NPU; nowhere else. `Build.HARDWARE` is "qcom"
     * on Snapdragon boards and `Build.SOC_MODEL` reads "SM8850"; either is enough. The factory also checks the FILE carries a drafter.
     */
    fun wantsSpeculativeDecoding(kind: BackendKind, socModel: String, hardware: String): Boolean {
        // The Snapdragon 8 Elite's NPU too (33 → 37 tok/s, above); the Tensor NPU still loses with it (16 → 14).
        if (kind == BackendKind.NPU) return socModel.trim().lowercase() == "sm8750"
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
