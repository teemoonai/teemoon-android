// ports: teemoonTests/SendGateRuleTests.swift @ 6b99b42a (theSettleTimeoutAsksOnlyWhenTheSendWouldBeSealed,
//        theGateNeverReadsTheAuditsIndex, aConfirmationIsScopedToItsRecordAndReason) — the halves that live in the app module; the vectors are SendGateRuleTest.
package ai.teemoon.app

import ai.teemoon.confidential.TrustSendPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SendGateTimeoutTest {
    private val kit = Json.parseToJsonElement(File(root(), "conformance/vendor/sendgate.json").readText()).jsonObject
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }.first { File(it, "conformance/vendor").isDirectory }

    @Test fun theSettleTimeoutAsksOnlyWhenTheSendWouldBeSealed() {
        for (t in kit.getValue("settleTimeout").jsonArray.map { it.jsonObject }) {
            val got = postPrepare(TrustSendPolicy.ALLOW, t.getValue("accepted").jsonPrimitive.boolean, verdictsPending = true, sealed = t.getValue("sealed").jsonPrimitive.boolean)
            val name = when (got) { PostPrepare.Proceed -> "proceed"; PostPrepare.ConfirmPending -> "confirmPending"; is PostPrepare.Refuse -> "refuse" }
            assertEquals(t.getValue("name").jsonPrimitive.content, t.getValue("outcome").jsonPrimitive.content, name)
        }
        assertNotEquals("evidence still blocks at the timeout", PostPrepare.Proceed, postPrepare(TrustSendPolicy.BLOCK, acceptedDegrade = true, verdictsPending = true, sealed = true))
    }

    /** F4: a confirmation counts only for the (record, reason) the gate reads after prepareTurn. */
    @Test fun aConfirmationIsScopedToItsRecordAndReason() {
        val rows = kit.getValue("confirmScope").jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 6)
        for (row in rows) {
            val confirmed = row.getValue("confirmed").jsonObject; val gate = row.getValue("gate").jsonObject
            fun key(o: kotlinx.serialization.json.JsonObject) = o.getValue("record").jsonPrimitive.content to o.getValue("reason").jsonPrimitive.content
            val policy = when (gate.getValue("policy").jsonPrimitive.content) { "block" -> TrustSendPolicy.BLOCK; "confirm" -> TrustSendPolicy.CONFIRM; else -> TrustSendPolicy.ALLOW }
            val got = postPrepare(policy, acceptedDegrade = key(confirmed) == key(gate),
                verdictsPending = gate.getValue("pending").jsonPrimitive.boolean, sealed = gate.getValue("sealed").jsonPrimitive.boolean)
            val name = when (got) { PostPrepare.Proceed -> "proceed"; PostPrepare.ConfirmPending -> "confirmPending"; is PostPrepare.Refuse -> "refuse" }
            assertEquals(row.getValue("name").jsonPrimitive.content, row.getValue("outcome").jsonPrimitive.content, name)
        }
    }

    /**
     * S30 (sendgate.json 0.6.0 pendingSources; iOS SendGateRuleTests.everyPendingSourceHoldsTheSendAndAsksItsQuestion @ 38647a07):
     * each pending kind holds the send; provenance alone asks its own question and is confirmed as "provenancePending", any
     * hardware kind as "pending".
     */
    @Test fun everyPendingSourceHoldsTheSendAndAsksItsQuestion() = kotlinx.coroutines.test.runTest {
        val sources = kit.getValue("pendingSources").jsonArray.map { it.jsonObject }
        assertTrue(sources.size >= 4)
        val upToDate = ai.teemoon.confidential.DcapVerification.Verified(ai.teemoon.confidential.DcapTcbStatus.UP_TO_DATE, "", "")
        for (source in sources) {
            val name = source.getValue("name").jsonPrimitive.content
            val pending = source.getValue("pending").jsonArray.map { it.jsonPrimitive.content }
            val model = "zai-org/S30-PENDING-$name"
            val tierKnown = "tier" !in pending
            ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = if (tierKnown) null else false
            if (tierKnown) ai.teemoon.providers.NearAIModelCatalog.recordTiers(mapOf(model to ai.teemoon.providers.NearAIModelCatalog.Confidentiality.TEE_OWN))
            val record = ai.teemoon.confidential.AttestationRecord.preview.copy(model = model)
            val near = ai.teemoon.providers.Provider.nearAI.copy(id = record.providerID, model = model)
            val stale = "staleRefetch" in pending
            var fetches = 0
            // a stale record is the session's own first fetch, past its age; the send's refetch is held
            val live = ai.teemoon.confidential.ConfidentialSession(backgroundScope, { near }, credentialFor = { "" },
                services = ai.teemoon.confidential.ConfidentialSession.Services(skipLiveAttestation = !stale, directBase = { "https://model.example" },
                    fetchRecord = { _, _, _, _, _ -> if (++fetches == 1) record else kotlinx.coroutines.awaitCancellation() },
                    verifyManifests = if ("provenance" in pending) ({ _ -> kotlinx.coroutines.awaitCancellation() }) else null,
                    fetchModelLayerManifest = null,
                    verifyTls = { if ("hardware" in pending) kotlinx.coroutines.awaitCancellation() else ai.teemoon.confidential.TlsAttestation.Verified },
                    now = { if (stale) record.fetchedAt.plusSeconds(ai.teemoon.confidential.ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1) else record.fetchedAt }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
            if (stale) { live.refreshAttestation(); testScheduler.runCurrent() } else live.attestation = record
            live.lastRequestUsedE2EE = true
            live.imageProvenance = ai.teemoon.confidential.ManifestProvenance.AllVerified(emptyList(), emptyList())
            live.dcapVerification = ai.teemoon.confidential.RecordDcapVerification(gateway = upToDate, model = upToDate)
            live.gpuAttestation = ai.teemoon.confidential.NrasVerification.Verified; live.tlsAttestation = ai.teemoon.confidential.TlsAttestation.Verified
            for (kind in pending) when (kind) {
                "hardware" -> { live.tlsAttestation = null; live.verifyTls(near) }
                "staleRefetch" -> live.refreshAttestationIfStale()
                "provenance" -> live.verifyImageProvenance(record)
                "tier" -> Unit
                else -> throw AssertionError("unknown pending kind $kind")
            }
            testScheduler.runCurrent()
            try {
                assertTrue("$name: nothing reads pending", live.sendPending)
                val ask = E2EEAsk.make(hardFailure = false, pendingAsk = live.pendingKind, provenanceUnchecked = false, softButSealed = live.softButSealed, provenanceBlocked = false, cause = null)
                val askName = when (ask.title) { E2EEAsk.PROVENANCE_PENDING_TITLE -> "provenancePending"; E2EESendChoice.STILL_CHECKING_TITLE -> "stillChecking"; else -> ask.title }
                assertEquals("$name: asked $askName", source.getValue("ask").jsonPrimitive.content, askName)
                assertEquals(name, listOf("send anyway", "wait"), ask.choices.map { it.label })
                live.rememberConfirmation(pending = true)
                val key = live.confirmationKey(pending = true) ?: ""
                assertTrue("$name: confirmed as $key", key.endsWith("|" + source.getValue("confirmReason").jsonPrimitive.content))
                assertTrue(name, live.hasConfirmed(pending = true))
            } finally {
                live.clearDerivedState(resetCounts = true)
                ai.teemoon.providers.NearAIModelCatalog.tierListSeenOverride = null; ai.teemoon.providers.NearAIModelCatalog.resetTierCache()
            }
        }
    }

    /** the maintainer asked whether the audits could block sends: they can't, and this keeps it so (iOS's tripwire). */
    @Test fun theGateNeverReadsTheAuditsIndex() {
        for (path in listOf("confidential/src/main/kotlin/ai/teemoon/confidential/ConfidentialSession.kt", "app/src/main/kotlin/ai/teemoon/app/ChatViewModel.kt",
                            "app/src/main/kotlin/ai/teemoon/app/SendChoices.kt", "chat/src/main/kotlin/ai/teemoon/chat/SendPrep.kt")) {
            val source = File(root(), path).readText()
            for (word in listOf("AuditIndex", "auditState", "verdictClass")) assertFalse("$path reads the audits index ($word)", source.contains(word))
        }
    }
}
