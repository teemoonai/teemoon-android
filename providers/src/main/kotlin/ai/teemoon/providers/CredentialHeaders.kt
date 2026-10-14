// android-only (for now): the header names keys were actually sent under. iOS's DebugHeaderRedaction masks a fixed
// list of four; a provider configured with its own header name had its key copied in full from a debug card on both
// (the local audit, an INFO since 3910, 2026-10-13).
package ai.teemoon.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * Every header name a transport has put a key under, lowercased. [OpenAiTransport] registers its custom
 * `authHeaderName` when it is built with a key; the debug copy path masks any registered name as well as the standard
 * four. Names only — never a value. Process-lifetime: a card copied later in the session still masks it.
 */
object CredentialHeaders {
    private val names: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun register(name: String) { if (name.isNotBlank()) names += name.trim().lowercase() }

    /**
     * The one way a key goes into request headers: under [authHeaderName] as-is (registered), else as
     * `Authorization: Bearer`. Every key-sending site calls this, so nothing sends a key under a name the redirect
     * filter and the copy mask do not know — only the chat transport registered at first, and the /models fetch, the
     * probes and OpenRouter's lists did not (the local audit of c0ed24c, 2026-10-13).
     */
    fun attach(headers: MutableMap<String, String>, apiKey: String, authHeaderName: String?) {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        if (authHeaderName != null) { register(authHeaderName); headers[authHeaderName] = key } else headers["Authorization"] = "Bearer $key"
    }

    fun isCredential(name: String): Boolean = name.trim().lowercase() in names

    /** The standard key-carrying names, which every transport may use without registering. */
    val STANDARD = setOf("authorization", "x-subscription-token", "x-api-key", "api-key")

    /** Every name a key may ride under right now: the standard ones and the registered ones. */
    fun all(): Set<String> = STANDARD + names
}
