// ports: teemoon/Confidential/E2EEPeer.swift @ v1.0.1 (E2EEPeer, E2EEError, E2EEEnvelope, Ed25519ToX25519, Fe)
//        teemoon/Confidential/E2EEStreamCodec.swift @ v1.0.1
//
// The near.ai E2EE v2 protocol on top of XChaChaPoly.
// Protocol: https://docs.near.ai/cloud/guides/e2ee-chat-completions/
//   1. Ephemeral X25519 ECDH → shared secret
//   2. HKDF-SHA256(shared, info: "ed25519_encryption") → 32-byte key
//   3. XChaCha20-Poly1305 (see XChaChaPoly.kt)
//   4. Wire format: ephemeral_pub (32) || nonce (24) || ciphertext+tag
//
// Ed25519→X25519 conversion (Ed25519ToX25519 + Fe) uses GF(2^255-19)
// arithmetic ported from TweetNaCl (public domain); the conformance kit's
// crypto class cross-checks both directions against CryptoKit.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

sealed class E2EEError(message: String) : Exception(message) {
    object InvalidHex : E2EEError("Invalid hex string")
    object InvalidUTF8 : E2EEError("Decrypted data is not valid UTF-8")
    object InvalidKeyLength : E2EEError("Invalid key length (expected 32 bytes)")
    object DecryptionFailed : E2EEError("Decryption failed")
    /** An Ed25519→X25519 conversion produced a small-order/degenerate point. Fail-closed at construction: no peer exists, so no send is possible under the E2EE promise. */
    object LowOrderPublicKey : E2EEError("Model public key converts to a low-order point")
    /** A request that promised sealing could not be encrypted. Fail-closed: the transport throws this BEFORE anything touches the wire — plaintext is never sent as a fallback under an E2EE promise. */
    object EncryptionFailed : E2EEError("Encryption failed — the request was not sent")
}

// MARK: - E2EE peer

/** The client's key material paired with one model's public identity — everything needed to encrypt requests to, and decrypt responses from, a specific attested model. */
class E2EEPeer private constructor(
    /** Client Ed25519 seed (public key goes in X-Client-Pub-Key). */
    private val signingSeed: ByteArray,
    /** Client X25519 private key derived from the Ed25519 seed (used to decrypt responses). */
    val agreementPrivateKey: ByteArray,
    /** Model's X25519 public key (converted from Ed25519). */
    val modelX25519PubKey: ByteArray,
    /** Model's Ed25519 public key hex for the X-Model-Pub-Key header. */
    val modelPubKeyHex: String,
) {
    /** Ed25519 public key hex for the X-Client-Pub-Key header. */
    val clientPubKeyHex: String = ByteArray(32).also { Ed25519.generatePublicKey(signingSeed, 0, it, 0) }.toHex()
    /** The client's X25519 public key — what the model encrypts responses to. */
    val agreementPublicKey: ByteArray get() = Ed25519ToX25519.x25519PublicKey(agreementPrivateKey)

    constructor(modelEd25519PubKey: ByteArray) : this(
        signingSeed = ByteArray(32).also { SecureRandom().nextBytes(it) },
        modelEd25519PubKey = modelEd25519PubKey)

    private constructor(signingSeed: ByteArray, modelEd25519PubKey: ByteArray) : this(
        signingSeed, Ed25519ToX25519.privateKey(signingSeed), Ed25519ToX25519.publicKey(modelEd25519PubKey), modelEd25519PubKey.toHex())

    companion object {
        /** TEST SEAM: a peer around a raw X25519 model key, BYPASSING the conversion guard, so the exploit suite can pin the layer below it. */
        fun bypassingConversion(modelX25519Key: ByteArray, modelPubKeyHex: String): E2EEPeer {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return E2EEPeer(seed, Ed25519ToX25519.privateKey(seed), modelX25519Key, modelPubKeyHex)
        }
    }

    /** HTTP headers for E2EE requests. */
    val headers: Map<String, String>
        get() = mapOf("X-Signing-Algo" to "ed25519", "X-Client-Pub-Key" to clientPubKeyHex, "X-Model-Pub-Key" to modelPubKeyHex,
            "X-Encryption-Version" to "2", "X-Encrypt-All-Fields" to "true")

    /** Encrypts plaintext for the model. Returns hex-encoded wire format. */
    fun encrypt(plaintext: String): String = E2EEEnvelope.seal(plaintext.toByteArray(Charsets.UTF_8), modelX25519PubKey).toHex()

    /** Decrypts hex-encoded wire format from the model. */
    fun decrypt(hex: String): String {
        val data = hex.hexToBytesOrNull() ?: throw E2EEError.InvalidHex
        val decrypted = E2EEEnvelope.open(data, agreementPrivateKey)
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decrypted)).toString() } catch (_: Exception) { throw E2EEError.InvalidUTF8 }
    }

    /**
     * Encrypts every field near.ai's E2EE spec puts behind
     * `X-Encrypt-All-Fields: true`, which this client always sends: message
     * `content`, `name`, `refusal`, `tool_calls[].function.{name, arguments}`,
     * `tools[].function.{name, description, parameters}`, and
     * `tool_choice.function.name`.
     *
     * This used to seal only `tools[].function.name`, which bought nothing:
     * `description` went out in the clear, so the gateway could identify the
     * tool exactly despite the encrypted name. Asserting the header while
     * leaving the fields it names in plaintext claims a property it does not have.
     *
     * `parameters` is a JSON schema, not a string: the spec says to serialize
     * it and encrypt it whole — key-by-key would leave its shape readable.
     * Serialised with sorted keys so the ciphertext is not gratuitously
     * different per request for an identical schema.
     *
     * A body that does not parse, or has no `messages` array, is returned
     * UNCHANGED — the stream codec turns that into a hard failure.
     */
    fun encryptRequestBody(body: ByteArray): ByteArray {
        val root = runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return body
        val messages = root["messages"] as? JsonArray ?: return body
        fun enc(s: String) = JsonPrimitive(encrypt(s))
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun encFunction(fn: JsonObject, parameters: Boolean): JsonObject {
            val m = fn.toMutableMap()
            str(fn, "name")?.let { m["name"] = enc(it) }
            str(fn, "arguments")?.let { m["arguments"] = enc(it) }
            if (parameters) {
                str(fn, "description")?.let { m["description"] = enc(it) }
                (fn["parameters"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { m["parameters"] = enc(Json.encodeToString(JsonElement.serializer(), sortedKeys(it))) }
            }
            return JsonObject(m)
        }
        val out = root.toMutableMap()
        out["messages"] = JsonArray(messages.map { msgEl ->
            val msg = msgEl as? JsonObject ?: return@map msgEl
            val m = msg.toMutableMap()
            str(msg, "content")?.let { m["content"] = enc(it) }
            str(msg, "name")?.let { m["name"] = enc(it) }
            str(msg, "refusal")?.let { m["refusal"] = enc(it) }
            (msg["tool_calls"] as? JsonArray)?.let { calls ->
                m["tool_calls"] = JsonArray(calls.map { tcEl ->
                    val tc = tcEl as? JsonObject ?: return@map tcEl
                    val fn = tc["function"] as? JsonObject ?: return@map tcEl
                    JsonObject(tc.toMutableMap().also { it["function"] = encFunction(fn, parameters = false) })
                })
            }
            JsonObject(m)
        })
        (root["tools"] as? JsonArray)?.let { tools ->
            out["tools"] = JsonArray(tools.map { tEl ->
                val t = tEl as? JsonObject ?: return@map tEl
                val fn = t["function"] as? JsonObject ?: return@map tEl
                JsonObject(t.toMutableMap().also { it["function"] = encFunction(fn, parameters = true) })
            })
        }
        (root["tool_choice"] as? JsonObject)?.let { choice ->
            val fn = choice["function"] as? JsonObject
            val name = fn?.let { str(it, "name") }
            if (fn != null && name != null) out["tool_choice"] = JsonObject(choice.toMutableMap().also { it["function"] = JsonObject(fn.toMutableMap().also { f -> f["name"] = enc(name) }) })
        }
        return Json.encodeToString(JsonElement.serializer(), JsonObject(out)).toByteArray(Charsets.UTF_8)
    }

    private fun sortedKeys(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.keys.sorted().associateWith { sortedKeys(e.getValue(it)) })
        is JsonArray -> JsonArray(e.map { sortedKeys(it) })
        else -> e
    }
}

/**
 * Thin wrapper around [E2EEPeer] for the streaming code paths. [decryptField]
 * returns null on failure; the generation engine treats that as fatal and
 * fails the stream — ciphertext is never rendered as content.
 */
class E2EEStreamCodec(val peer: E2EEPeer) {
    val headers: Map<String, String> get() = peer.headers
    fun decryptField(ciphertext: String): String? = runCatching { peer.decrypt(ciphertext) }.getOrNull()
    /** Null when encryption left the body unchanged — callers never send E2EE headers alongside a plaintext body. */
    fun encryptedBodyIfChanged(body: ByteArray): ByteArray? {
        val encrypted = peer.encryptRequestBody(body)
        return if (encrypted.contentEquals(body)) null else encrypted
    }
}

// MARK: - E2EE envelope (wire format)

/** The near.ai v2 wire envelope: ephemeral-key hybrid encryption around XChaChaPoly. `ephemeral_pub (32) || nonce (24) || ciphertext+tag`. */
object E2EEEnvelope {
    private const val HKDF_INFO = "ed25519_encryption"

    fun seal(plaintext: ByteArray, recipientPubKey: ByteArray): ByteArray {
        if (recipientPubKey.size != 32) throw E2EEError.InvalidKeyLength
        val ephemeral = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephemeralPub = Ed25519ToX25519.x25519PublicKey(ephemeral)
        val shared = ByteArray(32)
        // A low-order recipient yields an all-zero secret: fail closed, nothing touches the wire.
        if (!X25519.calculateAgreement(ephemeral, 0, recipientPubKey, 0, shared, 0)) throw E2EEError.EncryptionFailed
        val key = deriveKey(shared)
        // XChaChaPoly.seal draws its nonce from the CSPRNG and throws on RNG failure — a predictable nonce is unrepresentable.
        val box = XChaChaPoly.seal(plaintext, key)
        return ephemeralPub + box.combined
    }

    fun open(wireFormat: ByteArray, privateKey: ByteArray): ByteArray {
        if (wireFormat.size < 32 + 24 + 16) throw E2EEError.DecryptionFailed
        val ephPub = wireFormat.copyOfRange(0, 32)
        val box = XChaChaPoly.SealedBox.fromCombined(wireFormat.copyOfRange(32, wireFormat.size))
        val shared = ByteArray(32)
        if (!X25519.calculateAgreement(privateKey, 0, ephPub, 0, shared, 0)) throw E2EEError.DecryptionFailed
        return try { XChaChaPoly.open(box, deriveKey(shared)) } catch (_: Exception) { throw E2EEError.DecryptionFailed }
    }

    /** HKDF-SHA256(shared, salt: empty, info: "ed25519_encryption") → 32 bytes. */
    fun deriveKey(shared: ByteArray): ByteArray = Hkdf.sha256(shared, salt = ByteArray(0), info = HKDF_INFO.toByteArray(), length = 32)
}

/** RFC 5869 HKDF over HMAC-SHA256. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length); var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos); t.copyInto(out, pos, 0, n); pos += n; i++
        }
        return out
    }
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

// MARK: - Ed25519 → X25519 key conversion

object Ed25519ToX25519 {
    /** Converts an Ed25519 seed to an X25519 private key via SHA-512 + clamping (RFC 8032 §5.1.5 scalar derivation). */
    fun privateKey(seed: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 248).toByte(); h[31] = (h[31].toInt() and 127).toByte(); h[31] = (h[31].toInt() or 64).toByte()
        return h
    }

    /** The X25519 public key for a private scalar. */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray = ByteArray(32).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

    /**
     * Converts an Ed25519 public key to X25519 via the birational map
     * u = (1+y)/(1-y) mod p (RFC 7748 §4.1).
     *
     * Small-order / degenerate results are REJECTED here, explicitly. ECDH
     * against any of these yields an all-zero or attacker-fixed shared secret;
     * a peer must fail to EXIST rather than fail to seal. `pack()` reduces mod
     * p, so only the canonical encodings below can ever appear.
     */
    fun publicKey(edPub: ByteArray): ByteArray {
        if (edPub.size != 32) throw E2EEError.InvalidKeyLength
        val bytes = edPub.copyOf(); bytes[31] = (bytes[31].toInt() and 0x7f).toByte()   // clear sign bit → y coordinate
        val y = Fe.unpack(bytes)
        val u = Fe.pack(Fe.mul(Fe.add(Fe.one, y), Fe.invert(Fe.sub(Fe.one, y))))
        if (u.toHex() in lowOrderUHex) throw E2EEError.LowOrderPublicKey
        return u
    }

    /** Canonical low-order X25519 u-coordinates (libsodium's blocklist, canonical-encoding subset): u = 0, u = 1, the two order-8 points, and p − 1 (order 2). */
    private val lowOrderUHex = setOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    )
}

/** Minimal GF(2^255-19) arithmetic for the public-key conversion. Ported from TweetNaCl's 16×Int64-limb representation (public domain). */
internal object Fe {
    val one: LongArray get() = LongArray(16).also { it[0] = 1 }

    fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) or ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        carry(t); carry(t); carry(t)
        repeat(2) {
            val m = LongArray(16)
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) { m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1); m[i - 1] = m[i - 1] and 0xffff }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = (m[15] shr 16) and 1
            m[14] = m[14] and 0xffff
            for (i in 0 until 16) t[i] = t[i] * b + m[i] * (1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) { o[2 * i] = t[i].toByte(); o[2 * i + 1] = (t[i] shr 8).toByte() }
        return o
    }

    fun add(a: LongArray, b: LongArray) = LongArray(16) { a[it] + b[it] }
    fun sub(a: LongArray, b: LongArray) = LongArray(16) { a[it] - b[it] }

    fun mul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 30 downTo 16) t[i - 16] += 38 * t[i]
        val o = t.copyOf(16)
        carry(o); carry(o)
        return o
    }

    /** a^(p-2) mod p via square-and-multiply. p-2 = 2^255-21, bits 2 and 4 are zero. */
    fun invert(a: LongArray): LongArray {
        var c = a
        for (i in 253 downTo 0) { c = mul(c, c); if (i != 2 && i != 4) c = mul(c, a) }
        return c
    }

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            o[i] -= c shl 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
        }
    }
}
