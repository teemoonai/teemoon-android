// ports: teemoon/Inference/SessionAffinity.swift @ v1.1.0
package ai.teemoon.inference

import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Providers keep a prompt cache per server; a routing id sends a chat's turns to one server so the cached prefix is
 * found. Fireworks `x-session-affinity` (docs.fireworks.ai/guides/prompt-caching), xAI `x-grok-conv-id` (docs.x.ai,
 * prompt caching), OpenRouter `x-session-id` (its sticky routing; ignored there if `provider.order` is ever set). Sent
 * only where the provider documents it.
 *
 * The value is HMAC-SHA256(per-install secret, thread id), first 16 bytes as hex (iOS ead8674): the same chat keeps its
 * id across relaunches, two chats never share one, and nobody without the secret can tie it to the thread or to another
 * install. Measured on iOS after a relaunch: xAI 80–84% cached against 4%, OpenRouter 92–94% against 0%. Until 1.0.6
 * build 45 the id was random per thread and lost at relaunch (68aebea).
 *
 * The secret lives in the Android Keystore as a non-extractable HMAC key; the app installs [keyedMac] at startup
 * (AppState). With none installed — the JVM tests, or a Keystore that fails — a random id for this launch only, as iOS
 * falls back when its Keychain fails.
 */
object SessionAffinity {
    private val ids = ConcurrentHashMap<String, String>()

    /** HMAC-SHA256 under the per-install secret, or null when the secret is unavailable. Set once by the app. */
    @Volatile var keyedMac: ((ByteArray) -> ByteArray?)? = null

    fun id(threadID: String): String = ids.getOrPut(threadID) {
        keyedMac?.invoke(threadID.lowercase().toByteArray(Charsets.UTF_8))?.let(::hex16) ?: UUID.randomUUID().toString().lowercase()
    }

    /** The derivation with the secret in hand (the tests'; the app's secret never leaves the Keystore). */
    fun derive(threadID: String, secret: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return hex16(mac.doFinal(threadID.lowercase().toByteArray(Charsets.UTF_8)))
    }

    private fun hex16(mac: ByteArray) = mac.take(16).joinToString("") { "%02x".format(it) }

    /** The header to send, or null where the provider documents none. */
    fun header(endpoint: String): String? = when (runCatching { URI(endpoint).host?.lowercase() }.getOrNull()) {
        "api.fireworks.ai" -> "x-session-affinity"
        "api.x.ai" -> "x-grok-conv-id"
        "openrouter.ai" -> "x-session-id"
        else -> null
    }

    /** Tests only: forget the ids this process minted. */
    internal fun resetForTests() = ids.clear()
}
