// ports: teemoon/Confidential/XChaChaPoly.swift @ v1.0.1
//
// XChaCha20-Poly1305 AEAD. XChaCha20 = HChaCha20 subkey derivation (pure
// Kotlin, below) + ChaCha20-Poly1305 for the actual AEAD, so all
// secret-dependent cipher work stays in the library. Spec:
// draft-irtf-cfrg-xchacha-03 — HChaCha20 follows §2.2 (reference: libsodium's
// crypto_core_hchacha20), subkey/subnonce construction §2.3. The §2.2.1 and
// §A.3.1 vectors are pinned by the conformance kit's crypto class.
package ai.teemoon.confidential

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

object XChaChaPoly {

    sealed class Error(message: String) : Exception(message) {
        /** The system CSPRNG failed. Sealing MUST fail rather than fall back to a predictable nonce. */
        class RandomGeneratorFailure(cause: Throwable) : Error("random generator failure: ${cause.message}")
        object IncorrectNonceSize : Error("incorrect nonce size")
        object IncorrectCombinedSize : Error("incorrect combined size")
        class AuthenticationFailed(cause: Throwable) : Error("authentication failed: ${cause.message}")
    }

    /** A 192-bit nonce. [random] draws from the CSPRNG and throws on failure — an all-zero "fallback" nonce is unrepresentable by construction. */
    class Nonce private constructor(val bytes: ByteArray) {
        companion object {
            fun random(): Nonce = try { Nonce(ByteArray(24).also { SecureRandom().nextBytes(it) }) } catch (e: Exception) { throw Error.RandomGeneratorFailure(e) }
            fun of(data: ByteArray): Nonce { if (data.size != 24) throw Error.IncorrectNonceSize; return Nonce(data.copyOf()) }
        }
    }

    /** Nonce, ciphertext, and authentication tag. */
    class SealedBox(val nonce: Nonce, val ciphertext: ByteArray, val tag: ByteArray) {
        /** `nonce || ciphertext || tag`. */
        val combined: ByteArray get() = nonce.bytes + ciphertext + tag

        companion object {
            fun fromCombined(combined: ByteArray): SealedBox {
                if (combined.size < 24 + 16) throw Error.IncorrectCombinedSize
                return SealedBox(Nonce.of(combined.copyOfRange(0, 24)), combined.copyOfRange(24, combined.size - 16), combined.copyOfRange(combined.size - 16, combined.size))
            }
        }
    }

    // MARK: Seal / open

    fun seal(message: ByteArray, key: ByteArray, nonce: Nonce? = null, aad: ByteArray = ByteArray(0)): SealedBox {
        val n = nonce ?: Nonce.random()
        val (subkey, subnonce) = deriveSubkeyAndNonce(key, n)
        val out = aead(true, subkey, subnonce, aad, message)
        return SealedBox(n, out.copyOfRange(0, out.size - 16), out.copyOfRange(out.size - 16, out.size))
    }

    fun open(box: SealedBox, key: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val (subkey, subnonce) = deriveSubkeyAndNonce(key, box.nonce)
        return try { aead(false, subkey, subnonce, aad, box.ciphertext + box.tag) } catch (e: Exception) { throw Error.AuthenticationFailed(e) }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce12: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return out.copyOf(total)
    }

    /** §2.3: subkey = HChaCha20(key, nonce[0..16]); ChaCha20 nonce = 4 zero bytes || nonce[16..24]. */
    private fun deriveSubkeyAndNonce(key: ByteArray, nonce: Nonce): Pair<ByteArray, ByteArray> {
        val subkey = hchacha20(key, nonce.bytes.copyOfRange(0, 16))
        val subnonce = ByteArray(4) + nonce.bytes.copyOfRange(16, 24)
        return subkey to subnonce
    }

    // MARK: HChaCha20 (§2.2)

    /** Derives a 256-bit subkey from a 256-bit key and 128-bit input — the "X" in XChaCha20. Internal so the §2.2.1 KAT can pin it directly. */
    fun hchacha20(key: ByteArray, input: ByteArray): ByteArray {
        val s = intArrayOf(
            0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
            le32(key, 0), le32(key, 4), le32(key, 8), le32(key, 12),
            le32(key, 16), le32(key, 20), le32(key, 24), le32(key, 28),
            le32(input, 0), le32(input, 4), le32(input, 8), le32(input, 12),
        )
        repeat(10) {
            qr(s, 0, 4, 8, 12); qr(s, 1, 5, 9, 13); qr(s, 2, 6, 10, 14); qr(s, 3, 7, 11, 15)
            qr(s, 0, 5, 10, 15); qr(s, 1, 6, 11, 12); qr(s, 2, 7, 8, 13); qr(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        put32(out, 0, s[0]); put32(out, 4, s[1]); put32(out, 8, s[2]); put32(out, 12, s[3])
        put32(out, 16, s[12]); put32(out, 20, s[13]); put32(out, 24, s[14]); put32(out, 28, s[15])
        return out
    }

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = s[d] xor s[a]; s[d] = s[d].rotateLeft(16)
        s[c] += s[d]; s[b] = s[b] xor s[c]; s[b] = s[b].rotateLeft(12)
        s[a] += s[b]; s[d] = s[d] xor s[a]; s[d] = s[d].rotateLeft(8)
        s[c] += s[d]; s[b] = s[b] xor s[c]; s[b] = s[b].rotateLeft(7)
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }
}
