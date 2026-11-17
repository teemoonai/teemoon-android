// android-only: whether a Keystore account holds a key, for screens that ask on every recomposition.
package ai.teemoon.providers

/**
 * Whether [account] holds a key: decrypted once, then kept as a yes/no — never the key itself — and moved only by
 * [save] and [delete], the account's only writers. A screen that asked [Keystore.load] on every recomposition
 * decrypted through the Android Keystore on the main thread each time (the Brave key, 557 ms across one chat open and
 * a send on the Pixel 10 Pro, 2026-11-07).
 */
class KeyPresence(private val keystore: Keystore, private val account: String) {
    @Volatile private var known: Boolean? = null

    fun has(): Boolean = known ?: (keystore.load(account)?.isNotBlank() == true).also { known = it }

    fun save(value: String) { keystore.save(value, account); known = true }

    fun delete() { keystore.delete(account); known = false }
}
