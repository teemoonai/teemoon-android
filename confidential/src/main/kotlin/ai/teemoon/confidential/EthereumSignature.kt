// ports: teemoon/Confidential/EthereumSignature.swift @ v1.0.1
//
// EIP-191 `personal_sign` recovery (ecrecover) for near.ai per-response
// signatures: recover the secp256k1 public key from the 65-byte r‖s‖v
// signature over keccak256("\x19Ethereum Signed Message:\n" + len + text),
// and derive the Ethereum address (keccak256(pubkey)[12...]).
//
// Recovery failure is a NORMAL outcome for attacker-supplied signatures
// (~half of random r values have no curve point): it must be null, never a crash.
package ai.teemoon.confidential

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECAlgorithms
import java.math.BigInteger

object EthereumSignature {
    private val curve: X9ECParameters = CustomNamedCurves.getByName("secp256k1")

    /** Recovers the lowercase 0x-address that produced [signatureHex] over [text] via EIP-191 personal_sign, or null for any malformed or unrecoverable signature. */
    fun recoverAddress(text: String, signatureHex: String): String? {
        val hex = signatureHex.removePrefix("0x")
        val sig = hex.hexToBytesOrNull() ?: return null
        if (sig.size != 65) return null
        // v is 27/28 (Ethereum) or already 0..3.
        var recid = sig[64].toInt() and 0xff
        if (recid >= 27) recid -= 27
        if (recid !in 0..3) return null
        val digest = personalSignDigest(text)
        return try {
            val n = curve.n
            val r = BigInteger(1, sig.copyOfRange(0, 32)); val s = BigInteger(1, sig.copyOfRange(32, 64))
            if (r < BigInteger.ONE || r >= n || s < BigInteger.ONE || s >= n) return null
            // R = the curve point with x = r + i*n and the parity the recovery id names.
            val x = r.add(n.multiply(BigInteger.valueOf((recid / 2).toLong())))
            if (x >= curve.curve.field.characteristic) return null
            val xBytes = x.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
            val rPoint = curve.curve.decodePoint(byteArrayOf((if (recid and 1 == 1) 3 else 2).toByte()) + xBytes)
            if (!rPoint.multiply(n).isInfinity) return null
            // Q = r^-1 (s·R − e·G)
            val e = BigInteger(1, digest); val rInv = r.modInverse(n)
            val q = ECAlgorithms.sumOfTwoMultiplies(curve.g, n.subtract(e).mod(n).multiply(rInv).mod(n), rPoint, s.multiply(rInv).mod(n)).normalize()
            if (q.isInfinity) return null
            val serialized = q.getEncoded(false)   // 0x04 || X || Y
            if (serialized.size != 65) return null
            // Address = last 20 bytes of keccak256(uncompressed pubkey minus 0x04 prefix).
            "0x" + Keccak256.hash(serialized.copyOfRange(1, 65)).copyOfRange(12, 32).toHex()
        } catch (_: Exception) { null }
    }

    /** keccak256("\x19Ethereum Signed Message:\n" + byte-length + text). */
    fun personalSignDigest(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return Keccak256.hash("\u0019Ethereum Signed Message:\n${body.size}".toByteArray(Charsets.UTF_8) + body)
    }
}
