// ports: teemoonTests/KeychainTests.swift @ v1.0.1 (all 14; the roundtrip tests run against the file store with an
//        in-memory AES-GCM cipher — the Android Keystore master key itself needs a device)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** The test cipher: real AES-GCM over a process-local key, same blob layout as the Keystore cipher. */
class InMemoryAesCipher : SecretCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key); return c.iv + c.doFinal(plain)
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)); return c.doFinal(blob, 12, blob.size - 12)
    }
}

class KeystoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun keystore(dir: File = File(tmp.root, "keys")) = Keystore(dir, InMemoryAesCipher())
    private val testKey = "ai.teemoon.test.${UUID.randomUUID()}"

    // MARK: - Input validation

    @Test fun save_emptyKey_throws() { assertTrue(runCatching { keystore().save("value", "") }.exceptionOrNull() is KeystoreException.InvalidInput) }
    @Test fun save_emptyValue_throws() { assertTrue(runCatching { keystore().save("", "test-key") }.exceptionOrNull() is KeystoreException.InvalidInput) }
    @Test fun load_emptyKey_returnsNull() { assertNull(keystore().load("")) }

    // MARK: - Save / Load / Delete roundtrip

    @Test fun saveAndLoad_roundtrip() {
        val k = keystore(); val value = "test-api-key-${UUID.randomUUID()}"
        k.save(value, testKey); assertEquals(value, k.load(testKey)); k.delete(testKey)
    }

    @Test fun save_overwritesExistingValue() {
        val k = keystore(); k.save("first", testKey); k.save("second", testKey); assertEquals("second", k.load(testKey))
    }

    /** Overwriting must never pass through a state where NOTHING is stored: repeated overwrites keep the latest value and never lose one. */
    @Test fun save_repeatedOverwrites_neverLoseTheValue() {
        val k = keystore()
        for (i in 1..5) { k.save("value-$i", testKey); assertEquals("value-$i", k.load(testKey)) }
    }

    /** An add path and an update path must produce the same stored result. */
    @Test fun save_firstWriteAndOverwriteAgree() {
        val k = keystore(); k.save("same", testKey); val afterAdd = k.load(testKey); k.save("same", testKey); assertEquals(afterAdd, k.load(testKey))
    }

    @Test fun delete_removesValue() { val k = keystore(); k.save("to-delete", testKey); k.delete(testKey); assertNull(k.load(testKey)) }
    @Test fun delete_nonexistentKey_doesNotThrow() { keystore().delete("ai.teemoon.nonexistent.${UUID.randomUUID()}") }
    @Test fun load_nonexistentKey_returnsNull() { assertNull(keystore().load("ai.teemoon.nonexistent.${UUID.randomUUID()}")) }

    @Test fun save_specialCharacters() {
        val k = keystore(); val v = "sk-proj-abc123!@#\$%^&*()_+-=[]{}|;':\",./<>?"; k.save(v, testKey); assertEquals(v, k.load(testKey))
    }
    @Test fun save_unicodeValue() { val k = keystore(); val v = "日本語テスト-🔑-مفتاح"; k.save(v, testKey); assertEquals(v, k.load(testKey)) }
    @Test fun save_longValue() { val k = keystore(); val v = "a".repeat(10_000); k.save(v, testKey); assertEquals(v, k.load(testKey)) }

    @Test fun keystoreError_messages() {
        assertTrue(KeystoreException.SaveFailed(IllegalStateException("disk full")).message!!.contains("disk full"))
        assertTrue(KeystoreException.DeleteFailed(IllegalStateException("busy")).message!!.contains("busy"))
        assertTrue(KeystoreException.InvalidInput("empty key").message!!.contains("empty key"))
    }

    // MARK: - Android-only: the file layer

    /** Account strings like `endpoint:https://…/v1` must be legal file names, and two accounts must never share a file. */
    @Test fun anyAccountStringIsAFile() {
        val k = keystore()
        k.save("a", "endpoint:https://api.example.com/v1"); k.save("b", "endpoint:https://api.example.com/v2")
        assertEquals("a", k.load("endpoint:https://api.example.com/v1")); assertEquals("b", k.load("endpoint:https://api.example.com/v2"))
    }

    /** A write that fails leaves the stored value exactly as it was — the update-then-add lesson. */
    @Test fun aFailedWriteLeavesTheOldValue() {
        val dir = File(tmp.root, "keys"); val good = InMemoryAesCipher()
        var fail = false
        val flaky = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = if (fail) throw IllegalStateException("keystore unavailable") else good.encrypt(plain)
            override fun decrypt(blob: ByteArray) = good.decrypt(blob)
        }
        val k = Keystore(dir, flaky)
        k.save("sk-old", testKey)
        fail = true
        assertTrue(runCatching { k.save("sk-new", testKey) }.exceptionOrNull() is KeystoreException.SaveFailed)
        assertEquals("sk-old", k.load(testKey))
    }

    /** A blob that will not open (a different master key, a corrupt file) reads as null and is logged — never as another value, never a crash. */
    @Test fun anUnopenableBlobReadsAsNullAndIsLogged() {
        val dir = File(tmp.root, "keys"); val logged = ArrayList<String>()
        Keystore(dir, InMemoryAesCipher()).save("secret", testKey)
        val other = Keystore(dir, InMemoryAesCipher()) { logged.add(it) }   // a different key
        assertNull(other.load(testKey)); assertTrue(logged.any { it.contains("load failed") })
    }

    @Test fun nothingIsStoredInPlaintext() {
        val dir = File(tmp.root, "keys"); keystore(dir).save("sk-visible-secret", testKey)
        for (f in dir.listFiles()!!) assertTrue(!String(f.readBytes(), Charsets.ISO_8859_1).contains("sk-visible-secret"))
    }
}
