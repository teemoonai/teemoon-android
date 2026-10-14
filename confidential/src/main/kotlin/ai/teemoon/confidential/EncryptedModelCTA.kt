// ports: teemoon/Presentation/EncryptedModelCTA.swift @ v1.0.4
package ai.teemoon.confidential

import ai.teemoon.providers.Provider

/**
 * The last button on the not-encrypted proof sheet. What it offers depends on
 * ONE thing — whether a usable near.ai key exists on this phone — read through
 * the same credential lookup the send gate uses.
 *
 * The bug it replaced, 2026-09-12: from an NVIDIA chat it read "choose an
 * end-to-end encrypted model" and opened a list of near.ai models with no
 * provider named, whenever a near.ai setup existed — keyed or not. Two states
 * now, gated on the key, both naming near.ai.
 *
 * TRUST COPY IS DATA. Every sentence here is a value the view renders; nothing
 * composes this language in a composable.
 */
sealed class EncryptedModelCTA {

    /**
     * A near.ai setup WITH a key: offer the switch, and say whose models they
     * are and whose key they use. The chat leaves its current provider.
     */
    data class SwitchToNearAI(val provider: Provider) : EncryptedModelCTA()

    /**
     * No usable near.ai key: no setup at all, or a legacy setup whose key is
     * gone (since 1.0.2 a cloud setup cannot be saved without one, and deleting
     * a setup removes its key). Both read the same to the user: offer the key
     * form, editing the legacy setup when there is one.
     */
    data class AddNearAIKey(val existing: Provider?) : EncryptedModelCTA()

    val title: String
        get() = when (this) {
            is SwitchToNearAI -> "switch to an end-to-end encrypted model on near.ai"
            is AddNearAIKey -> "end-to-end encryption is available on near.ai"
        }

    val detail: String
        get() = when (this) {
            is SwitchToNearAI -> "near.ai runs these inside sealed hardware · uses your near.ai key"
            is AddNearAIKey -> "add a near.ai key and the same chats run inside sealed hardware"
        }

    /**
     * A KEYLESS SETUP MUST NOT OPEN THE PICKER: the pick would die at send with
     * "no api key", which is a worse place to learn it than the button.
     */
    val opensPicker: Boolean get() = this is SwitchToNearAI

    companion object {
        fun resolve(nearAI: Provider?, hasKey: Boolean): EncryptedModelCTA =
            if (nearAI != null && hasKey) SwitchToNearAI(nearAI) else AddNearAIKey(nearAI)
    }
}
