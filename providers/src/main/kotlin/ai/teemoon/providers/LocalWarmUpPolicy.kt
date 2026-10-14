// ports: teemoon/Inference/LocalWarmUpPolicy.swift @ v1.0.4
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
}
