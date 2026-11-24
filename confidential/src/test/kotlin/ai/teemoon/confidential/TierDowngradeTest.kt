// ports: teemoonTests/TierDowngradeTests.swift @ d2d70d45 (1.1.1 item 1, docs/TIER_DOWNGRADE.md, 1.1.0 audit LOW)
//
// A near.ai model a send went to sealed, now listed third-party or proxied, asks once before its first plain send.
package ai.teemoon.confidential

import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.NearAIModelCatalog.Confidentiality
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TierDowngradeTest {
    @Before fun offline() { NearAIModelCatalog.resetTierCache() }

    /** One id per test: the tier cache is process-wide. */
    private val model = "z-ai/downgrade-${UUID.randomUUID().toString().take(8).lowercase()}"

    /** near.ai with [model], its tier forced through the catalogue cache the capability guard reads. */
    private fun session(tier: Confidentiality, sealed: SealedModels, record: AttestationRecord? = null): ConfidentialSession {
        val provider = Provider.nearAI.copy(id = record?.providerID ?: Provider.nearAI.id, model = model)
        NearAIModelCatalog.recordTiers(mapOf(model to tier))
        val services = if (record == null) ConfidentialSession.Services() else ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt })
        return ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { provider }, services = services, sealedModels = sealed).also { s -> record?.let { s.attestation = it } }
    }

    @Test fun aListThatDemotesASealedModelMakesTheNextSendAsk() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        val s = session(Confidentiality.TEE_THIRD_PARTY, sealed)
        assertTrue(s.sealedModelWentPlain)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TierDowngrade.title(ai.teemoon.providers.ModelCatalog.compactName(model)), s.tierDowngradeAsk?.title)
    }

    @Test fun proxiedIsADowngradeToo() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        assertEquals(TrustSendPolicy.CONFIRM, session(Confidentiality.PROXIED, sealed).sendPolicy)
    }

    @Test fun answeringOnceDoesNotAskAgain() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        val s = session(Confidentiality.TEE_THIRD_PARTY, sealed)
        s.rememberConfirmation(pending = false, downgradeAskShown = true)   // "send without encryption"
        assertFalse(s.sealedModelWentPlain)
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicy)
        assertTrue(sealed.ids.isEmpty())
    }

    @Test fun dontSendLeavesTheModelArmed() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        val s = session(Confidentiality.TEE_THIRD_PARTY, sealed)
        // "don't send" remembers nothing, so the next send asks again.
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals(setOf(model.lowercase()), sealed.ids)
    }

    @Test fun aSecondSealingReArmsAndASecondDowngradeAsks() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        session(Confidentiality.TEE_THIRD_PARTY, sealed).rememberConfirmation(pending = false, downgradeAskShown = true)
        session(Confidentiality.TEE_OWN, sealed); sealed.arm(model)   // near.ai lists it own-fleet again; a sealed request reached the wire (SealedArmingTest)
        assertEquals(TrustSendPolicy.CONFIRM, session(Confidentiality.TEE_THIRD_PARTY, sealed).sendPolicy)
    }

    /** Rule 5: any send path with no one to ask refuses on a non-ALLOW policy; the downgrade is one, so it never goes silently. */
    @Test fun aNonUiSendPathReadingSendPolicyIsRefusedWhileTheAskIsOwed() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        assertNotEquals(TrustSendPolicy.ALLOW, session(Confidentiality.TEE_THIRD_PARTY, sealed).sendPolicy)
    }

    @Test fun aModelNeverSealedNeverAsks() {
        val s = session(Confidentiality.TEE_THIRD_PARTY, SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        assertFalse(s.sealedModelWentPlain)
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicy)
    }

    @Test fun anotherProviderNeverAsks() {
        val fw = Provider.fireworks.copy(model = model)
        assertFalse(TierDowngrade.owed(fw, setOf(model.lowercase())))
    }

    @Test fun theStoreHoldsModelIdsOnly() {
        val store = InMemorySealedModelStore()
        val sealed = SealedModels(store)
        sealed.arm("Z-AI/GLM-5.3-Flash")
        assertEquals(setOf("z-ai/glm-5.3-flash"), store.read())
        sealed.clear()
        assertEquals(null, store.read())
    }

    /** An own-fleet model's record whose Intel collateral couldn't be read: the "still checking" soft ask (iOS TierDowngradeTests.softAsk). */
    private fun softAskSession(sealed: SealedModels): ConfidentialSession {
        val record = ai.teemoon.confidential.AttestationRecord.preview.copy(model = model)   // the read gate hides a record stamped for another model
        val s = session(Confidentiality.TEE_OWN, sealed, record)
        s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        s.dcapVerification = RecordDcapVerification(gateway = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", ""),
            model = DcapVerifier.collateralUnavailable(java.io.IOException("pcs unreachable")))
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        s.nearScopeOverride = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked)
        return s
    }

    /**
     * 1.1.1 audit B1 read (iOS 217dc4cb): a confirmation given before the flip, on the record the session still holds, never
     * answers the downgrade ask; only its own "send without encryption" does. A flip refreshes nothing, so the record stays.
     */
    @Test fun anEarlierConfirmationNeverAnswersTheDowngradeAsk() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        val s = softAskSession(sealed)
        assertEquals("precondition: the soft ask", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
        assertTrue("precondition: the still-encrypted ask", s.softButSealed)
        val earlierKey = s.confirmationKey(pending = false)!!
        s.rememberConfirmation(pending = false)                      // "send anyway" on record R
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicyAfterConfirmations)
        assertEquals("a soft confirm is not the downgrade consent", setOf(model.lowercase()), sealed.ids)

        NearAIModelCatalog.recordTiers(mapOf(model to Confidentiality.TEE_THIRD_PARTY))   // near.ai flips it while R is held
        assertNotNull("the flip leaves the record in place", s.attestation)
        assertTrue(s.sealedModelWentPlain)
        assertEquals("the flip clears no confirmation either", setOf(earlierKey), s.confirmedDegrades)
        // The reason half of the key moves (a plain model has no degrade reason), so this one answer misses; the guard must
        // not rest on that. Any confirmation for this record and reason, then:
        assertNotEquals(earlierKey, s.confirmationKey(pending = false))
        s.confirmationKey(pending = false)?.let { s.confirmedDegrades += it }
        s.confirmationKey(pending = true)?.let { s.confirmedDegrades += it }
        assertEquals("the composer asks", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
        assertNotNull("and the ask is the downgrade sheet", s.tierDowngradeAsk)
        for (pending in listOf(false, true)) assertFalse("hasConfirmed($pending)", s.hasConfirmed(pending))
        s.rememberConfirmation(pending = false, downgradeAskShown = true)   // the downgrade ask's own answer
        assertTrue(sealed.ids.isEmpty())
        assertEquals(TrustSendPolicy.ALLOW, s.sendPolicyAfterConfirmations)
    }

    /**
     * 1.1.1 audit cut review item 1: the answer carries WHICH ask was shown. A soft ask (or the still-checking ask) is open,
     * the tier flips to plain while it is open, and the user taps "send anyway": that tap answered the soft ask, so it is not
     * the downgrade consent. Nothing is accepted, the model stays armed, and the downgrade ask is what the next send shows.
     */
    @Test fun aTapOnAnotherAsksSendAnywayNeverAcceptsTheDowngrade() {
        for (pending in listOf(false, true)) {
            val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
            val s = softAskSession(sealed)
            assertEquals("precondition: the soft ask is open", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
            NearAIModelCatalog.recordTiers(mapOf(model to Confidentiality.TEE_THIRD_PARTY))   // flips while it is open
            assertTrue(s.sealedModelWentPlain)
            s.rememberConfirmation(pending = pending, downgradeAskShown = false)             // "send anyway" on the soft ask
            assertEquals("pending=$pending: the soft ask's answer accepted the downgrade", setOf(model.lowercase()), sealed.ids)
            assertTrue(s.sealedModelWentPlain)
            assertEquals("the downgrade ask is what comes next", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
            assertNotNull(s.tierDowngradeAsk)
            s.rememberConfirmation(pending = pending, downgradeAskShown = true)              // its own answer still works
            assertTrue(sealed.ids.isEmpty())
        }
    }

    /**
     * Card tier-downgrade-ask v4 + follow-ups (iOS 073489a1, approved 2026-11-19T06:26:42Z), word for word: the model is named
     * once, in the title. (1.1.1 audit B1.) The way through, "send without encryption", is pinned in app's SendChoicesTest.
     */
    @Test fun theAskIsTheApprovedCopyExactly() {
        val sealed = SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()); sealed.arm(model)
        val ask = session(Confidentiality.TEE_THIRD_PARTY, sealed).tierDowngradeAsk!!
        val name = ai.teemoon.providers.ModelCatalog.compactName(model)
        assertEquals("$name isn't end-to-end encrypted anymore", ask.title)
        assertEquals("near.ai now lists it as running outside its own enclaves, so this message would reach near.ai over ordinary TLS. your earlier messages to it were sealed.", ask.message)
        assertFalse(ask.message.contains(name))
    }
}
