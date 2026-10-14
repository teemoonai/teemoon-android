// ports: teemoon/Confidential/Data+Hex.swift @ v1.0.1
package ai.teemoon.confidential

/** Hex encoding/decoding used by the E2EE wire format and attestation parsing. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Strict: an odd length or a non-hex digit throws, as `Data(hexString:)` does. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "invalid hex" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex" }
        ((hi shl 4) or lo).toByte()
    }
}

/** null instead of throwing — the `try?` reading. */
fun String.hexToBytesOrNull(): ByteArray? = runCatching { hexToBytes() }.getOrNull()
