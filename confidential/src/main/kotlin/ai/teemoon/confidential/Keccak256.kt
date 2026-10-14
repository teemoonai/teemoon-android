// ports: teemoon/Confidential/Keccak256.swift @ v1.0.1
//
// Keccak-256 (the pre-NIST original with 0x01 domain padding, as used by
// Ethereum — NOT SHA3-256, whose padding byte is 0x06). Needed for EIP-191
// message hashing and Ethereum address derivation in response-signature
// verification. Straightforward Keccak-f[1600] sponge, rate 1088 bits.
package ai.teemoon.confidential

object Keccak256 {
    private val roundConstants = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808bL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800aL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    /** Rotation offsets, indexed [x + 5y]. */
    private val rho = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    fun hash(message: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        // Pad: message ‖ 0x01 ‖ 0…0 ‖ 0x80 (last byte OR'd).
        val padLen = rate - (message.size % rate)
        val padded = ByteArray(message.size + padLen)
        message.copyInto(padded)
        padded[message.size] = 0x01
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()
        var blockStart = 0
        while (blockStart < padded.size) {
            for (lane in 0 until rate / 8) {
                var word = 0L
                for (byte in 0 until 8) word = word or ((padded[blockStart + lane * 8 + byte].toLong() and 0xff) shl (8 * byte))
                state[lane] = state[lane] xor word
            }
            keccakF(state)
            blockStart += rate
        }
        val out = ByteArray(32)
        for (lane in 0 until 4) { var word = state[lane]; for (b in 0 until 8) { out[lane * 8 + b] = word.toByte(); word = word ushr 8 } }
        return out
    }

    private fun keccakF(a: LongArray) {
        val c = LongArray(5); val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) { val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1); for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d }
            for (x in 0 until 5) for (y in 0 until 5) b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(rho[x + 5 * y])
            for (x in 0 until 5) for (y in 0 until 5) a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            a[0] = a[0] xor roundConstants[round]
        }
    }
}
