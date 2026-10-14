// ports: teemoonTests/ProviderCredentialTests.swift @ v1.0.4 ("Provider credentials" 8 of 16 — the upsert, heal and
//        connect tests exercise ProviderStore, a human row; "Keys are per server" all 8; "Auto-label healing" is ProviderStore's)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** The provider API-key lifecycle — the behavior that made keys look lost. */
class ProviderCredentialsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Store(val keystore: Keystore) {
        val providers = ArrayList<Provider>()
        val credentials = ProviderCredentials(keystore, { providers })
        fun add(p: Provider) { providers.removeAll { it.id == p.id }; providers.add(p) }
        fun remove(p: Provider) { providers.removeAll { it.id == p.id }; credentials.removeSecrets(p) }
    }
    private fun store() = Store(Keystore(File(tmp.root, "keys-${UUID.randomUUID()}"), InMemoryAesCipher()))
    private fun provider(endpoint: String = "https://api.example.com/v1") = Provider(id = UUID.randomUUID().toString(), name = "test", endpoint = endpoint, model = "m")
    private fun endpoint(suffix: String) = "https://api.example-$suffix-${UUID.randomUUID().toString().take(8)}.com/v1"

    // MARK: - Provider credentials

    @Test fun setCredentialRoundTripsThroughTheKeystore() {
        val s = store(); val p = provider(); s.add(p)
        s.credentials.setCredential("sk-secret-1", p.id); assertEquals("sk-secret-1", s.credentials.credential(p))
    }

    /** The clear path: an emptied field must DELETE, not no-op. Whitespace is not a key either. */
    @Test fun emptyCredentialDeletesTheStoredKey() {
        val s = store(); val p = provider(); s.add(p)
        s.credentials.setCredential("sk-secret-2", p.id); s.credentials.setCredential("", p.id); assertEquals("", s.credentials.credential(p))
        s.credentials.setCredential("sk-secret-2", p.id); s.credentials.setCredential("   ", p.id); assertEquals("", s.credentials.credential(p))
    }

    /** hasCredentialFollowsTheStoredKeyAndTheRequiresFlag (ProviderCredentialTests @ v1.0.4). */
    @Test fun hasCredentialFollowsTheStoredKeyAndTheRequiresFlag() {
        val s = store(); val p = provider(); s.add(p)
        assertFalse(s.credentials.hasCredential(p))
        s.credentials.setCredential("sk-secret-5", p.id); assertTrue(s.credentials.hasCredential(p))
        s.credentials.setCredential("", p.id); assertFalse(s.credentials.hasCredential(p))
        val keyless = provider("http://home-box.local:11434/v1").copy(requiresAPIKey = false)
        assertTrue(s.credentials.hasCredential(keyless))
    }

    @Test fun credentialsAreTrimmedOnWrite() {
        val s = store(); val p = provider(); s.add(p)
        s.credentials.setCredential("  sk-padded  ", p.id); assertEquals("sk-padded", s.credentials.credential(p))
    }

    /** THE fix for "the add screen forgot my key": found by endpoint, wherever it was saved from. */
    @Test fun credentialIsFoundByEndpointAcrossInstances() {
        val s = store(); val instance = Provider.nearAI.copy(id = UUID.randomUUID().toString()); s.add(instance)
        s.credentials.setCredential("sk-near-ai", instance.id)
        val unwritten = Provider.nearAI.copy(id = UUID.randomUUID().toString())
        assertEquals("sk-near-ai", s.credentials.credential(unwritten))   // the key is the SERVER's now
        assertEquals("sk-near-ai", s.credentials.credential(endpoint = Provider.nearAI.endpoint))
        assertEquals("sk-near-ai", s.credentials.credential(endpoint = "  " + Provider.nearAI.endpoint.uppercase() + " "))
    }

    @Test fun credentialByEndpointIsNullWhenNothingMatches() {
        val s = store(); val p = provider("https://api.example.com/v1"); s.add(p); s.credentials.setCredential("sk-a", p.id)
        assertNull(s.credentials.credential(endpoint = "https://other.example.com/v1")); assertNull(s.credentials.credential(endpoint = ""))
    }

    /** A provider with no key stored must not shadow one that has it — the legacy two-records shape. */
    @Test fun credentialByEndpointSkipsKeylessDuplicates() {
        val s = store(); val keyless = provider(); val withKey = provider()
        s.providers.add(keyless); s.providers.add(withKey)
        s.credentials.setCredential("sk-the-real-one", withKey.id)
        assertEquals(2, s.providers.size); assertEquals("sk-the-real-one", s.credentials.credential(endpoint = keyless.endpoint))
    }

    /** Deleting a provider must take its secret with it — the id is gone, so a key left behind is unreachable and unremovable. */
    @Test fun removingAProviderDeletesItsKey() {
        val s = store(); val p = provider(); s.add(p); s.credentials.setCredential("sk-doomed", p.id)
        assertEquals("sk-doomed", s.credentials.credential(p))
        s.remove(p)
        assertEquals("", s.credentials.credential(p)); assertNull(s.keystore.load(p.id))
    }

    /** A failed write must be reportable, not swallowed. */
    @Test fun setCredentialThrowsOnAnInvalidWrite() {
        val s = store()
        assertTrue(runCatching { s.keystore.save("", UUID.randomUUID().toString()) }.exceptionOrNull() is KeystoreException)
        assertEquals("", s.credentials.credential(provider()))
    }

    // MARK: - Keys are per server

    /** The account is the endpoint, normalized the same way records are deduplicated. */
    @Test fun theAccountIsTheNormalizedEndpoint() {
        val a = provider("https://API.Example.com/v1"); val b = provider("https://api.example.com/v1/")
        assertEquals(ProviderAccounts.keyAccount(a), ProviderAccounts.keyAccount(b)); assertTrue(ProviderAccounts.keyAccount(a)!!.startsWith("endpoint:"))
    }

    /** On-device providers all share the literal endpoint "on-device" and hold no key. */
    @Test fun onDeviceHasNoKeyAccount() {
        assertNull(ProviderAccounts.keyAccount(Provider.local(LocalModelCatalog.all[0]))); assertNull(ProviderAccounts.keyAccount("on-device"))
    }

    /** A key written under the old id account is found, moved, and the old copy removed — once, on first read. */
    @Test fun aLegacyKeyIsMigratedOnRead() {
        val s = store(); val p = provider(endpoint("legacy")); s.add(p)
        s.keystore.save("sk-legacy-value", p.id)   // exactly what an old build wrote
        assertEquals("sk-legacy-value", s.credentials.credential(p))
        assertEquals("sk-legacy-value", s.keystore.load(ProviderAccounts.keyAccount(p)!!)); assertNull("the id copy outlived the move", s.keystore.load(p.id))
    }

    @Test fun theInstanceLookupSeesTheSavedKey() {
        val s = store(); val p = provider(endpoint("send")).copy(requiresAPIKey = true); s.add(p)
        s.credentials.setCredential("sk-send-path", p.id); assertEquals("sk-send-path", s.credentials.credential(p))
    }

    /** A second record for the same endpoint reads the SAME key: the record id no longer decides whether a key is found. */
    @Test fun aRecreatedRecordFindsTheKey() {
        val s = store(); val shared = endpoint("recreated"); val first = provider(shared); s.add(first)
        s.credentials.setCredential("sk-shared", first.id)
        assertEquals("sk-shared", s.credentials.credential(provider(shared)))
    }

    /** Clearing the field removes BOTH accounts, so a stale id copy can't keep answering with a key the user just revoked. */
    @Test fun removingAKeyClearsTheLegacyCopyToo() {
        val s = store(); val p = provider(endpoint("revoke")); s.add(p)
        s.credentials.setCredential("sk-revoked", p.id); s.keystore.save("sk-stale-id-copy", p.id)   // an unmigrated leftover
        s.credentials.setCredential("", p.id)
        assertTrue(s.credentials.credential(p).isEmpty()); assertNull(s.keystore.load(p.id))
    }

    /** Deleting the setup takes the secret with it, under either account. */
    @Test fun deletingTheProviderLeavesNoSecret() {
        val s = store(); val p = provider(endpoint("gone")); s.add(p)
        s.credentials.setCredential("sk-doomed", p.id); s.keystore.save("sk-doomed-legacy", p.id)
        s.remove(p)
        assertNull(s.keystore.load(ProviderAccounts.keyAccount(p)!!)); assertNull(s.keystore.load(p.id))
    }

    /** A key can never be written to an account nothing will read. */
    @Test fun writingWithNoEndpointIsRefused() {
        assertTrue(runCatching { store().credentials.setCredential("sk-nowhere", "on-device", legacyID = null) }.exceptionOrNull() is KeystoreException.InvalidInput)
    }

    /** A failed migration keeps the legacy copy and keeps working from it — never a missing key. */
    @Test fun aFailedMoveStillAnswersFromTheLegacyCopy() {
        val dir = File(tmp.root, "keys-flaky"); val good = InMemoryAesCipher(); var fail = false
        val flaky = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = if (fail) throw IllegalStateException("no") else good.encrypt(plain)
            override fun decrypt(blob: ByteArray) = good.decrypt(blob)
        }
        val ks = Keystore(dir, flaky); val p = provider(endpoint("flaky")); val c = ProviderCredentials(ks, { listOf(p) })
        ks.save("sk-legacy", p.id); fail = true
        assertEquals("sk-legacy", c.credential(p)); assertEquals("sk-legacy", ks.load(p.id))
    }


    // MARK: - presetsWithoutKey (the "add cloud key" picker's offer)

    /** Nothing configured: every preset is on offer. */
    @Test fun withNoKeysEveryPresetIsOffered() {
        val s = store()
        assertEquals(Provider.presets.map { it.name }, s.credentials.presetsWithoutKey().map { it.name })
    }

    /**
     * A key saved against a preset's ENDPOINT takes it off the offer — even though the record carrying that key has
     * a fresh id and not the preset's fixed one. Matching by id would offer near.ai to somebody who had just set
     * near.ai up, which is the bug `credential(endpoint)` exists to prevent.
     */
    @Test fun aKeySavedUnderAFreshIdStillTakesItsPresetOffTheOffer() {
        val s = store()
        val saved = Provider.nearAI.copy(id = UUID.randomUUID().toString())   // as the add form writes it
        s.add(saved)
        s.credentials.setCredential("sk-near", saved.id)

        val offered = s.credentials.presetsWithoutKey()
        assertTrue("near.ai is set up and must not be offered", offered.none { it.id == Provider.nearAI.id })
        assertEquals(Provider.presets.size - 1, offered.size)
    }

    /** A configured record with NO key does not count as set up — that is exactly who needs this screen. */
    @Test fun aKeylessRecordIsStillOffered() {
        val s = store()
        s.add(Provider.grok.copy(id = UUID.randomUUID().toString()))
        assertTrue(s.credentials.presetsWithoutKey().any { it.id == Provider.grok.id })
    }

    /** Clearing the key puts the preset back on offer, so the screen can be used to re-key after a revoke. */
    @Test fun clearingAKeyPutsThePresetBack() {
        val s = store()
        val saved = Provider.fireworks.copy(id = UUID.randomUUID().toString())
        s.add(saved)
        s.credentials.setCredential("sk-fw", saved.id)
        assertTrue(s.credentials.presetsWithoutKey().none { it.id == Provider.fireworks.id })
        s.credentials.setCredential("", saved.id)
        assertTrue(s.credentials.presetsWithoutKey().any { it.id == Provider.fireworks.id })
    }
}
