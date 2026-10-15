package ai.teemoon.app

// android-only for now: the iOS runner will read the same values once the shared benchmark spec lives in teemoon-ios
// (vendored here like the conformance kit). Until then this object IS the spec, and every number the README may
// quote comes from a run that read it: NpuBundleProbeTest, OnDeviceDecodeBenchTest.rawNpuDecode / rawPrefillCompare.

/**
 * The one definition of a reported on-device benchmark, so every chip is measured the same way (the user, 2026-10-14:
 * the published numbers must be apples to apples). Raw runtime, not the app turn: no system prompt, no tools.
 */
object BenchSpec {
    /** Decode: a short prompt (~18 tokens) and enough output to measure steady decode. */
    const val SHORT_PROMPT = "Write a short paragraph about the planet Jupiter."
    const val SHORT_MAX_OUTPUT = 128

    /** Prefill: ~1,900 tokens of fixed text, and a little output for time-to-first-token. */
    private const val PARAGRAPH = "The verifier checks the quote against the collateral, binds the measurement to the model that was loaded, and records the outcome in the ledger so a later reader can audit the claim without trusting the operator. "
    val LONG_PROMPT = "Summarise the following in one sentence.\n\n" + PARAGRAPH.repeat(48)
    const val LONG_MAX_OUTPUT = 32

    /** The context the engine is built with: 4096, the NPU bundles' compiled ceiling, on every backend. */
    const val MAX_TOKENS = 4096

    /**
     * GOOGLE'S METHOD, for comparing against the model card (huggingface.co/litert-community/gemma-4-E2B-it-litert-lm:
     * "1024 prefill tokens and 256 decode tokens with a context length of 2048 tokens"). Measured with the runtime's
     * own `benchmark()`, whose counters are the engine's, not ours — see GoogleMethodBenchTest. A number from it is
     * labelled "google method"; a number from the constants above is "teemoon bench"; the two are never mixed.
     */
    const val GOOGLE_PREFILL_TOKENS = 1024
    const val GOOGLE_DECODE_TOKENS = 256

    /** Every reported figure is the median of this many runs, after one discarded warm-up, backends interleaved. */
    const val REPEATS = 3

    /** Thinking off, the runtime's default sampler, and speculative decoding set EXPLICITLY: never the runtime's default. */
    const val SPECULATIVE = false
}
