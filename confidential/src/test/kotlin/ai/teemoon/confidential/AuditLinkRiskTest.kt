// pm's audit-index sweep, 2026-11-06 (#4): each risk as a test that fails while the risk is real. The fixes change
// what the ladder shows, so they wait for pm's review page; until then the failing cases are @Ignore'd with the reason.
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuditLinkRiskTest {
    private val rec = AttestationRecord.preview
    private val path = rec.modelComposePath!!
    private val fileSha = rec.modelFileHash!!
    private val audit = AuditIndex(fixedIndex = AuditIndex.Index(schema = 1, images = emptyMap(),
        manifests = mapOf("${MODEL_COMPOSE_REPO}/$path" to listOf(fileSha)),
        measured = listOfNotNull(rec.gpuNodeComposeHash), os = emptyList()))
    private fun model(record: AttestationRecord = rec, verification: ModelLayerVerification?) =
        EnclaveGrouping(record, null, null, verification, audit).groups().first { it.primary }

    /**
     * (a) The recipe's review is of the file with THIS file_sha256. Under a hash mismatch, or before the file is
     * checked, the running recipe is not known to be that file, and "reviewed · this exact file" claims it is.
     */
    @Test fun theRecipeReviewIsLinkedOnlyForAVerifiedRecipe() {
        assertNotNull("control: a verified recipe links its review", model(verification = ModelLayerVerification.VERIFIED).recipe?.auditURL)
        assertNull(model(verification = ModelLayerVerification.HASH_MISMATCH).recipe?.auditURL)
        assertNull(model(verification = null).recipe?.auditURL)
        assertNull(model(verification = ModelLayerVerification.FETCH_FAILED).recipe?.auditURL)
    }

    /** (b) The config review is of the measurement in the quote; a quote that didn't verify measures nothing. */
    @Test fun theConfigReviewIsLinkedOnlyForAVerifiedQuote() {
        assertNotNull("control", model(verification = ModelLayerVerification.VERIFIED).configAuditURL)
        val mqv = rec.modelQuoteVerification!!
        val failed = rec.copy(modelQuoteVerification = TdxVerificationResult(mqv.measurements, signatureValid = false, certChainValid = mqv.certChainValid, certChainError = null))
        assertEquals(false, model(failed, ModelLayerVerification.VERIFIED).quoteVerified)
        assertNull(model(failed, ModelLayerVerification.VERIFIED).configAuditURL)
    }

    /** (e) The reviewer's deployed-state is matched as written: "ON" sank a live finding to latent. */
    @Test fun aFindingMarkedOnInAnyCaseIsLive() {
        val f = NodeFinding(id = "f", severity = "high", deployed = "ON", qualifier = null, title = "prompt logged", source = "sglang", url = "https://x")
        assertEquals(listOf("f"), splitNodeFindings(listOf(f)).live.map { it.id })
    }
}
