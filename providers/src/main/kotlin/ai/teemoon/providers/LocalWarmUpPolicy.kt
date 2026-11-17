// ports: teemoon/Inference/LocalWarmUpPolicy.swift @ v1.1.0
package ai.teemoon.providers

/**
 * Whether picking a provider should start loading an on-device engine, and
 * which one. Cloud and home providers never; an on-device model only when its
 * bundle is on disk — a selection is the earliest honest signal it is about to
 * be used, and the load then overlaps the user typing instead of landing on the
 * first token. See LocalWarmUpPolicyTest.
 */
object LocalWarmUpPolicy {
    fun <R : Any> target(provider: Provider?, installed: (String) -> R?): R? {
        val id = provider?.localModelID ?: return null
        return installed(id)
    }

    /**
     * Android-only (no NPU bundles on iOS; the maintainer 2026-11-12): whether the warm-up loads the image reader too —
     * only for an NPU bundle that reads images, where it is cheap (the Pixel 10 Pro: ready in 1.1 s, 189 MB at the load's
     * peak) and spares the first picture a rebuild. The general file on the GPU or CPU warms text-only: the reader costs
     * ~650 MB there (Galaxy S26 Ultra, A25), which every text chat would pay.
     */
    fun withImageReader(ref: LocalModelStorage.LocalRef): Boolean = ref.requiresNpu && ref.npuSeesImages
}
