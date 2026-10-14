// ports: teemoon/Views/Chat/DebugHeaderRedaction.swift @ v1.0.1
//
// Credentials never leave the device through a copied debug card. The display
// path is verbatim except under the UI-test host; the COPY path always masks.
package ai.teemoon.app

import java.net.URLDecoder

object DebugHeaderRedaction {
    /** The UI-test host's display masking; production shows headers verbatim on screen. */
    @Volatile var displayMaskingActive: Boolean = false
    /**
     * The keys the app holds (AppState sets it): a copied header or query value containing one is masked whatever it is
     * called. Names alone missed a provider's own header name, and the catalogue fetch, probes and Brave attach keys
     * outside the chat transport (the local audit, 2026-10-13). Read only when something is copied.
     */
    @Volatile var storedSecrets: () -> Collection<String> = { emptyList() }

    /**
     * Free text as copied — a card's body, message, tool result or error: every stored key in it is masked. A provider
     * that echoes the key in a 401 body put it on the clipboard (the local audit of c0ed24c, 2026-10-13).
     */
    fun scrubStoredSecrets(text: String): String =
        runCatching { storedSecrets() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.length >= 8 }
            .sortedByDescending { it.length }.fold(text) { acc, secret -> if (acc.contains(secret)) acc.replace(secret, mask(secret)) else acc }

    private fun carriesStoredSecret(value: String): Boolean =
        runCatching { storedSecrets() }.getOrDefault(emptyList()).any { it.length >= 8 && value.contains(it.trim()) }
    private val secretHeaders = ai.teemoon.providers.CredentialHeaders.STANDARD
    private val secretQueryParams = setOf("key", "api_key", "apikey", "api-key", "access_token", "accesstoken", "token", "auth", "auth_token", "subscription_token", "password", "secret")

    /** The on-screen value. */
    fun value(value: String, forName: String): String {
        if (!displayMaskingActive || forName.lowercase() !in secretHeaders) return value
        if (value.contains(' ')) return value.substringBefore(' ') + " ••••••••••••••••"
        return "••••••••••••••••"
    }

    /**
     * The value as copied: a credential keeps its scheme and its last four characters, nothing else. A credential is one
     * of the standard four names, or a header a transport sent a key under (a provider's own `authHeaderName`).
     */
    fun copyValue(value: String, forName: String): String =
        if (forName.lowercase() in secretHeaders || ai.teemoon.providers.CredentialHeaders.isCredential(forName) || carriesStoredSecret(value)) mask(value) else value

    private fun mask(value: String): String {
        var scheme = ""; var secret = value
        if (value.contains(' ')) { val head = value.substringBefore(' '); scheme = "$head "; secret = value.drop(head.length + 1) }
        val trimmed = secret.trim()
        if (trimmed.length < 8) return "$scheme••••"
        return "$scheme••••…${trimmed.takeLast(4)}"
    }

    fun copyHeaderBlock(headers: Map<String, String>): String =
        headers.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}: ${copyValue(it.value, it.key)}" }

    /** A URL as copied: credential-shaped query parameters are masked, everything else byte-identical. */
    fun copyURLString(urlString: String): String {
        val mark = urlString.indexOf('?'); if (mark < 0) return urlString
        val base = urlString.substring(0, mark)
        var query = urlString.substring(mark + 1); var fragment = ""
        val hash = query.indexOf('#'); if (hash >= 0) { fragment = query.substring(hash); query = query.substring(0, hash) }
        var touched = false
        val pairs = query.split('&').map { pair ->
            val eq = pair.indexOf('='); if (eq < 0 || eq == pair.length - 1) return@map pair
            val rawName = pair.substring(0, eq); val rawValue = pair.substring(eq + 1)
            val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            if (name.lowercase() !in secretQueryParams && !carriesStoredSecret(value)) return@map pair
            touched = true
            "$rawName=${mask(value)}"
        }
        if (!touched) return urlString
        return base + "?" + pairs.joinToString("&") + fragment
    }
}
