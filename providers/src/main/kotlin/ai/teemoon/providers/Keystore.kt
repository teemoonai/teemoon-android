// ports: teemoon/Providers/Keychain.swift @ v1.0.4 — REBUILD (§5.3, §6.2): AndroidKeystore AES-256-GCM master key
//        wrapping per-account ciphertext files under filesDir/keys/, atomic replace on write.
package ai.teemoon.providers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens one secret blob. Production is [AndroidKeystoreCipher]; tests use a plain in-memory AES key. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * The master key: AES-256-GCM in the Android Keystore, non-exportable.
 *
 * `setIsStrongBoxBacked(true)` retried without on StrongBoxUnavailableException:
 * StrongBox is opportunistic, never claimable — hardware backing varies by OEM,
 * so copy must say "in the Android Keystore", never "in a secure element".
 *
 * NO user-auth gate. Parity with `kSecAttrAccessibleAfterFirstUnlock`: the app
 * must read provider keys after reboot + first unlock without biometrics.
 * Keystore keys do not migrate between devices — consistent with allowBackup=false.
 *
 * Blob layout: 12-byte GCM IV (the Keystore mints it) then ciphertext+tag.
 */
class AndroidKeystoreCipher(private val alias: String = "ai.teemoon.apikeys.master") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return try {
            gen.init(spec(strongBox = true)); gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false)); gen.generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

sealed class KeystoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class SaveFailed(cause: Throwable) : KeystoreException("Keystore save failed (${cause.javaClass.simpleName}: ${cause.message})", cause)
    class DeleteFailed(cause: Throwable) : KeystoreException("Keystore delete failed (${cause.javaClass.simpleName}: ${cause.message})", cause)
    class InvalidInput(reason: String) : KeystoreException("Invalid keystore input: $reason")
}

/**
 * The Keychain's API over per-account ciphertext files. One file per account,
 * named by a digest of the account string so any account (`endpoint:https://…`)
 * is a legal file name.
 */
class Keystore(
    private val dir: File,
    private val cipher: SecretCipher,
    private val warn: (String) -> Unit = {},
) {
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".bin")
    }

    /**
     * UPDATE, THEN ADD — never delete-then-add.
     *
     * The delete-then-write form is NOT ATOMIC: the delete lands first, so if
     * the write then fails — a storage error, a Keystore hiccup — the OLD KEY IS
     * GONE and the new one was never written. The throw tells the caller
     * something failed; it cannot tell them the previous value is
     * unrecoverable, and a lost inference key costs a trip to a vendor console.
     *
     * The new blob goes to a temp file and is moved over the old one
     * atomically, so a failure at any point leaves the stored value exactly as
     * it was. Nothing is destroyed to make room.
     */
    fun save(value: String, key: String) {
        if (key.isEmpty()) throw KeystoreException.InvalidInput("key must not be empty")
        if (value.isEmpty()) throw KeystoreException.InvalidInput("value must not be empty")
        try {
            val blob = cipher.encrypt(value.toByteArray(Charsets.UTF_8))
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(blob)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: KeystoreException) { throw e } catch (e: Exception) {
            throw KeystoreException.SaveFailed(e)
        }
    }

    /** The stored value, or null when there is none. A blob that will not open is logged and reads as null — never as another value. */
    fun load(key: String): String? {
        if (key.isEmpty()) { warn("Attempted to load with empty key"); return null }
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            String(cipher.decrypt(file.readBytes()), Charsets.UTF_8)
        } catch (e: Exception) {
            warn("Keystore load failed for an account: ${e.javaClass.simpleName}")
            null
        }
    }

    fun delete(key: String) {
        val file = fileFor(key)
        if (!file.exists()) return
        try { Files.delete(file.toPath()) } catch (e: Exception) { throw KeystoreException.DeleteFailed(e) }
    }
}
