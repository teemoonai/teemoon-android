// ports: teemoon/Confidential/TierDowngrade.swift @ d2d70d45 (1.1.1 item 1, docs/TIER_DOWNGRADE.md; the sheet itself is app's SendChoices.kt E2EEAsk.tierDowngrade)
//
// Ask before the first plain send to a near.ai model a send once went to sealed (1.1.0 audit LOW: near.ai's unauthenticated
// /v1/models can move a model to third-party/proxied; it then fell out of the gate at the capability guard and the next turn
// went plain over TLS with no ask). The maintainer approved 2026-11-18: ids only, in the app's key-value preferences (iOS UserDefaults
// `nearai.sealedModels`), NOT Room: no schema change.
package ai.teemoon.confidential

import ai.teemoon.providers.ModelCatalog
import ai.teemoon.providers.Provider
import ai.teemoon.providers.ProviderCapability

/** Where the ids live: a string set that is removed when empty. The app backs it with SharedPreferences; tests with memory. */
interface SealedModelStore {
    fun read(): Set<String>?
    /** null removes the key. */
    fun write(ids: Set<String>?)
}

/** The near.ai model ids a request went out sealed to (`nearai.sealedModels`). Ids only: no content, key or chat. */
class SealedModels(val store: SealedModelStore) {
    val ids: Set<String> get() = store.read() ?: emptySet()

    /** A sealed request to [modelID] reached the wire (rule 1); a later downgrade asks again (rule 4). */
    fun arm(modelID: String) = write(ids + modelID.lowercase())

    /** "send without encryption": the consent is the removal, so later plain sends to it don't ask (rule 3). */
    fun accept(modelID: String) = write(ids - modelID.lowercase())

    /** "delete provider" for near.ai. */
    fun clear() = store.write(null)

    private fun write(set: Set<String>) = store.write(set.ifEmpty { null })

    companion object { const val KEY = "nearai.sealedModels" }
}

object TierDowngrade {
    /** Rule 2: near.ai, no ATTESTATION now, and once sealed. Never for any other provider. */
    fun owed(provider: Provider?, sealed: Set<String>): Boolean {
        if (provider == null || !provider.isNearAI || ProviderCapability.ATTESTATION in provider.capabilities) return false
        return provider.model.lowercase() in sealed
    }

    /** Card tier-downgrade-ask v4, approved 2026-11-19 (iOS 073489a1): the model is named once, in the title. Don't reword without the maintainer. */
    fun title(model: String) = "$model isn't end-to-end encrypted anymore"
    const val MESSAGE = "near.ai now lists it as running outside its own enclaves, so this message would reach near.ai over ordinary TLS. your earlier messages to it were sealed."
}

data class TierDowngradeAsk(val title: String, val message: String)

/** Rule 2: the active near.ai model was sent to sealed and now reads third-party or proxied. */
val ConfidentialSession.sealedModelWentPlain: Boolean get() = TierDowngrade.owed(activeProvider, sealedModels.ids)

/** The approved words, or null when no downgrade ask is owed. The app shows this before `E2EEAsk.make`. */
val ConfidentialSession.tierDowngradeAsk: TierDowngradeAsk?
    get() {
        val provider = activeProvider?.takeIf { sealedModelWentPlain } ?: return null
        val name = ModelCatalog.compactName(provider.model)
        return TierDowngradeAsk(TierDowngrade.title(name), TierDowngrade.MESSAGE)
    }

