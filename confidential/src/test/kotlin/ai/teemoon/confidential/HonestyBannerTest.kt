// ports: teemoonTests/HonestyFreezeTests.swift @ 123d0b28 (theRecipeMismatchBannerSaysSignedOnlyOnceBound; the rest is app/HonestyFreezeTest)
package ai.teemoon.confidential

import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestyBannerTest {
    /** A3 (the maintainer 2026-11-08): the recipe hash-mismatch banner says "signed" only once (a) binds the log. */
    @Test fun theRecipeMismatchBannerSaysSignedOnlyOnceBound() {
        val record = AttestationRecord.preview
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.attestation = record
        s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
        for (b in listOf<DeployLogBinding?>(null, DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), DeployLogBinding.Unchecked,
                DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), DeployLogBinding.Mismatch(DeployLogBinding.Check.QUOTE))) {
            s.nearScopeOverride = b?.let { NearTrustScope(it, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked) }
            val reason = s.e2eeDegradedReason.orEmpty()
            assertTrue("precondition: the recipe or the binding names the cause: $reason", "recipe" in reason || reason == NearTrustScope.ACTION_LOG_BROKEN)
            assertFalse("$b: $reason", reason.lowercase().let { "signed" in it || "attested action log" in it })
        }
    }
}
