// ports: teemoon/Presentation/PlacesKeysBadge.swift @ v1.0.4
package ai.teemoon.providers

/**
 * The badge on the "cloud keys" row of places & keys.
 *
 * COUNTS KEYS, NOT RECORDS. A cloud setup saved without a key is a row that
 * cannot send, and "1" there told a tester she had a key when she had none.
 */
object PlacesKeysBadge {
    /** [keyed] setups have a stored key; [unkeyed] are cloud setups that still need one. */
    fun cloud(keyed: Int, unkeyed: Int): String {
        val need = if (unkeyed == 1) "1 needs key" else "$unkeyed need keys"
        return when {
            keyed == 0 && unkeyed == 0 -> "none"
            keyed == 0 -> need
            unkeyed == 0 -> "$keyed"
            else -> "$keyed · $need"
        }
    }
}
