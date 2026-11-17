// android-only: KeyPresence — whether an account holds a key, without decrypting it on every read.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyPresenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Counting(private val inner: SecretCipher = InMemoryAesCipher()) : SecretCipher by inner {
        var decrypts = 0
        override fun decrypt(blob: ByteArray): ByteArray { decrypts++; return inner.decrypt(blob) }
    }

    /**
     * The web chip asks whether a Brave key exists on every recomposition, and each ask decrypted the key from the
     * Android Keystore on the main thread (557 ms across one chat open and a send on the Pixel 10 Pro, 2026-11-07). The
     * answer is decrypted once, kept as a yes/no — never the key — and moved only by a save or a delete.
     */
    @Test fun presenceIsDecryptedOnceAndFollowsSaveAndDelete() {
        val cipher = Counting()
        val keystore = Keystore(tmp.newFolder("keys"), cipher)
        keystore.save("bsa-key", "brave-grounding")
        val brave = KeyPresence(keystore, "brave-grounding")
        repeat(5) { assertTrue(brave.has()) }
        assertEquals("decrypted on every ask", 1, cipher.decrypts)
        brave.delete(); assertFalse(brave.has())
        brave.save("bsa-other"); assertTrue(brave.has())
        assertEquals("a save or delete needs no decrypt to know", 1, cipher.decrypts)
        assertEquals("bsa-other", keystore.load("brave-grounding"))
    }
}
