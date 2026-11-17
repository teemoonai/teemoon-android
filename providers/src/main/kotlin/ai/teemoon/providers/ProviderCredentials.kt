// ports: teemoon/Providers/ProviderStore+Credentials.swift @ v1.1.0 (adds presetsWithoutKey)
//        teemoon/Providers/ProviderStore.swift @ v1.0.4 (endpointKey, and the key halves of connectProvider/removeProvider)
//
// Where a provider key lives in the Keystore, and how it is read and written.
// ProviderStore itself is a human row; this is its credential half over an
// injected provider list, so the account scheme and the migration are the
// same on both platforms.
package ai.teemoon.providers

/** The credential ACCOUNT scheme — the contract shared with iOS. */
object ProviderAccounts {
    /** The key one record per place is matched on. Trimmed, case-folded, and trailing slashes dropped, so `…/v1` and `…/V1/ ` are one endpoint. */
    fun endpointKey(endpoint: String): String = endpoint.trim().lowercase().trimEnd('/')

    /**
     * The Keystore account a provider's key lives under: its ENDPOINT, normalized.
     *
     * It used to be the record's id. That is *a* server id, but not the server's
     * IDENTITY — a record can be recreated, merged, or written by a screen that
     * minted a fresh id, and the secret then sat under an id nothing pointed at.
     * The symptom shipped twice in one evening: browsing a keyed provider fetched
     * unauthenticated, and the edit sheet showed an EMPTY key field for a setup
     * that had one. If the endpoint is what actually answers, the endpoint is
     * the account. Keying on it makes "one key per server" true by construction.
     *
     * null for on-device providers, which share the literal endpoint "on-device"
     * and have no key — without that guard every local model would address one account.
     */
    fun keyAccount(provider: Provider): String? = keyAccount(provider.endpoint)

    fun keyAccount(endpoint: String): String? {
        val key = endpointKey(endpoint)
        if (key.isEmpty() || key == "on-device") return null
        // Prefixed so it can never collide with a legacy record-id account.
        return "endpoint:$key"
    }
}

class ProviderCredentials(
    private val keystore: Keystore,
    private val providers: () -> List<Provider>,
    private val log: (String) -> Unit = {},
) {
    /**
     * Reads a key, moving it to the endpoint account if it is still under the
     * record's id. Copy first, delete only after the write succeeds — a
     * migration that loses a key costs the user a trip to a vendor console.
     */
    fun migratedCredential(provider: Provider): String {
        val account = ProviderAccounts.keyAccount(provider) ?: return ""
        keystore.load(account)?.takeIf { it.isNotEmpty() }?.let { return it }
        val legacyAccount = provider.id
        val legacy = keystore.load(legacyAccount)?.takeIf { it.isNotEmpty() } ?: return ""
        try {
            keystore.save(legacy, account); keysChanged()
            runCatching { keystore.delete(legacyAccount) }
            log("[providers] moved a key to its endpoint account")
        } catch (e: Exception) {
            // Keep the legacy copy and keep working from it — a failed move must not look like a missing key.
            log("[providers] could not move a key to its endpoint account: $e")
        }
        return legacy
    }

    /** The stored API key for a provider, or empty when none is required/saved. */
    fun credential(provider: Provider): String = if (provider.requiresAPIKey) migratedCredential(provider) else ""

    /**
     * Whether a send to this provider will carry a key (ProviderStore+Credentials.hasCredential, iOS 148a131). The
     * SAME lookup as the send path ([credential]), so the gate and the wire cannot disagree: a keyed provider with
     * nothing stored goes out with no auth header, and the server's reply is a validation error naming a header the
     * user never heard of (Brave: HTTP 422 "Field required").
     */
    fun hasCredential(provider: Provider): Boolean = !provider.requiresAPIKey || facts(provider).byID
    // The sealed yes/no ([facts]), not the key: the composer's gate asks this on every return to the chat, and opening the
    // key there was a Keystore round trip a time (Perfetto, the maintainer's Pixel 2026-11-11). Every key write here
    // clears the facts, so the gate and the send's own read of the key still agree.

    /**
     * The key already saved for whichever configured provider points at this
     * endpoint, or null when none does. Defensive about keyless duplicate rows
     * written before the one-record-per-endpoint rule existed.
     */
    fun credential(endpoint: String): String? {
        val target = endpoint.trim().lowercase()
        if (target.isEmpty()) return null
        for (provider in providers()) {
            if (provider.endpoint.trim().lowercase() != target) continue
            val key = credential(provider)
            if (key.isNotEmpty()) return key
        }
        return null
    }

    /**
     * The presets the "add cloud key" picker offers: every preset with no key
     * saved against its endpoint.
     *
     * Matched on the ENDPOINT, not the preset's id, for the reason
     * `credential(endpoint)` exists at all — a key is stored against whichever
     * record was on screen when it was saved, and that record carries a fresh
     * id, while the preset's id is fixed. Filtering by id would offer a
     * provider the user has already set up.
     */
    fun presetsWithoutKey(): List<Provider> =
        Provider.presets.filter { credential(endpoint = it.endpoint) == null }

    /**
     * The key to reach a provider's CATALOGUE with — both lookups, by record and
     * then by endpoint. A single lookup that misses does not error: the fetch
     * goes out unauthenticated, the 401 comes back, and the caller silently
     * keeps the last saved list — one that is merely STALE — or, for a keyed
     * catalogue, files it under the wrong account's scope.
     */
    /**
     * What drawing needs to know about a provider's key — never the key (the maintainer's Pixel, 2026-11-11: Perfetto put
     * 213 main-thread binder calls to keystore2 in 9 s of the image flow, the where sheet reading every provider's key as
     * it drew, ~17 ms + ~6 ms each). [byID]: [credential] has one; [forBrowsing]: [browseCredential] has one; [browseScope]:
     * the saved list that key opens (LiveCatalogStore's salted hash). Kept as KeyPresence keeps the Brave key's (2026-11-07):
     * a yes/no and a hash, read once per provider, moved by any save or delete here. Requests still open the key itself.
     */
    data class KeyFacts(val byID: Boolean, val forBrowsing: Boolean, val browseScope: LiveCatalogStore.KeyScope?)

    /** LiveCatalogStore.scopeOf, set by the app; null leaves [KeyFacts.browseScope] null. */
    @Volatile var scopeOf: ((String) -> LiveCatalogStore.KeyScope)? = null
    private val facts = HashMap<String, KeyFacts>()
    private var writes = 0 // under facts' lock: a read that saw a write land mid-way is not kept

    fun facts(provider: Provider): KeyFacts {
        val at = provider.id + "|" + provider.endpoint + "|" + provider.requiresAPIKey
        val before = synchronized(facts) { facts[at]?.let { return it }; writes }
        val browse = browseCredential(provider)
        val read = KeyFacts(credential(provider).isNotEmpty(), browse.isNotEmpty(), scopeOf?.invoke(browse))
        synchronized(facts) { if (writes == before) facts[at] = read }
        return read
    }

    /**
     * Fills [facts] off the main thread at launch, so the first where open draws without the Keystore (the maintainer's
     * Pixel, 2026-11-12: after the facts landed, the one long frame left was that open, 162 ms, filling them). The facts
     * only: no key outlives the read.
     */
    fun warm(providers: List<Provider>) { for (p in providers) facts(p) }

    /** Any key written or removed: what drawing knew is read again. */
    private fun keysChanged() = synchronized(facts) { writes++; facts.clear() }

    fun browseCredential(provider: Provider): String {
        val byID = credential(provider).trim()
        if (byID.isNotEmpty()) return byID
        return (credential(endpoint = provider.endpoint) ?: "").trim()
    }

    /**
     * Stores (or clears, when empty) the API key for a provider id — resolved to
     * the ENDPOINT account through the stored provider, so the write and the read
     * agree. Throws when the write fails: swallowing it dismissed the screen as if
     * the key had been saved, and the failure only surfaced later as an
     * unauthorized request with no explanation.
     */
    fun setCredential(apiKey: String, providerID: String) {
        val endpoint = providers().firstOrNull { it.id == providerID }?.endpoint
        setCredential(apiKey, endpoint, legacyID = providerID)
    }

    /** The endpoint-keyed write. [legacyID] is cleared alongside, so a record that still had an id copy doesn't keep answering with a key the user just removed. */
    fun setCredential(apiKey: String, endpoint: String?, legacyID: String?) = try { writeCredential(apiKey, endpoint, legacyID) } finally { keysChanged() }

    private fun writeCredential(apiKey: String, endpoint: String?, legacyID: String?) {
        val trimmed = apiKey.trim()
        val account = endpoint?.let { ProviderAccounts.keyAccount(it) }
        if (trimmed.isEmpty()) {
            if (account != null) keystore.delete(account)
            if (legacyID != null) runCatching { keystore.delete(legacyID) }
        } else {
            // No endpoint to key on — an on-device row, or a record saved before its
            // endpoint was valid. Refuse rather than write a secret to an account nothing will read.
            if (account == null) throw KeystoreException.InvalidInput("no endpoint to store this key under")
            keystore.save(trimmed, account)
            if (legacyID != null) runCatching { keystore.delete(legacyID) }
        }
    }

    /**
     * Removes a provider's stored key — both accounts: the endpoint one this
     * record uses, and any legacy id copy that never got migrated. Leaving
     * either behind is an unreachable, unremovable secret.
     */
    fun removeSecrets(provider: Provider) {
        ProviderAccounts.keyAccount(provider)?.let { runCatching { keystore.delete(it) } }
        runCatching { keystore.delete(provider.id) }
        keysChanged()
    }
}
