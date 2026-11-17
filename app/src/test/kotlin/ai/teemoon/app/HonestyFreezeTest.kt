// ports: teemoonTests/HonestyFreezeTests.swift @ 123d0b28
//
// Freeze item A, "honesty" (the maintainer, 2026-11-08). Each test pins the CONDITION a line must meet in the state that
// shows it, not its words: the words went through the review page.
//
// A1: the TLS probe (TlsAttestationVerifier) is a separate, unauthenticated connection to the model node's direct host. The
// chat itself goes to near.ai's gateway, and that connection is not pinned. A line that says the chat's connection ends
// inside the enclave overclaims.
// A2: the GPU row is NVIDIA's verdict; it showed the GPU node's Intel TDX text once NVIDIA passed.
// A3: "signed" (or "attested") action log only once (a) is bound — applied everywhere. Android's measured-config note
// said "attested action log" whatever the binding.
package ai.teemoon.app

import ai.teemoon.confidential.AttestationRecord
import ai.teemoon.confidential.AttestationState
import ai.teemoon.confidential.AttestationSummary
import ai.teemoon.confidential.BootLogReplay
import ai.teemoon.confidential.ConfidentialSession
import ai.teemoon.confidential.DeployLogBinding
import ai.teemoon.confidential.LauncherIndex
import ai.teemoon.confidential.ModelLayerVerification
import ai.teemoon.confidential.StepState
import ai.teemoon.confidential.verificationRun
import ai.teemoon.confidential.NearTrustScope
import ai.teemoon.confidential.NrasVerification
import ai.teemoon.confidential.TlsAttestation
import ai.teemoon.confidential.TrustScopeText
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestyFreezeTest {
    private val record = AttestationRecord.preview.copy(nvidiaPayload = "{}")

    private fun summary(nras: NrasVerification? = null, tls: TlsAttestation? = null) = AttestationSummary(
        attestation = record, state = AttestationState.OK, timedOut = false, provider = Provider.nearAI, lastRequestUsedE2EE = true,
        lastE2EEFailReason = null, verifiedResponseCount = 0, mismatchedResponseCount = 0, gatewayTrustResponseCount = 0,
        attestationFetchFailed = false, nrasVerification = nras, tlsAttestation = tls)

    private fun session(): ConfidentialSession {
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        return ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, now = { record.fetchedAt }))
            .also { it.attestation = record }
    }

    /** Phrases that put the chat's own connection inside the enclave. */
    private val chatConnectionClaims = listOf("connection terminates", "terminates in the enclave", "terminates inside", "your connection",
        "your encrypted connection", "ends inside", "into the enclave")

    // A1

    /** A verified probe says what was checked and that the chat travels through near.ai's gateway. */
    @Test fun theSealedTransportRowNeverClaimsTheChatConnectionIsPinned() {
        val s = session(); s.tlsAttestation = TlsAttestation.Verified
        val row = ExpertSections.make(s, summary(tls = TlsAttestation.Verified), "glm", "near.ai").transport
        assertNotNull(row)
        assertEquals(StepState.DONE, row!!.state)
        val card = listOf(ExpertSections.TRANSPORT_TITLE, ExpertSections.TRANSPORT_SUBTITLE, row.label, row.detail).joinToString(" ").lowercase()
        for (claim in chatConnectionClaims) assertFalse("the card claims the chat connection: $claim", claim in card)
        assertTrue("the verified card must say the chat goes through near.ai's gateway", "gateway" in card)
    }

    /** The transport row in every state it shows: what the probe is, never the chat's connection. */
    @Test fun everyTransportRowSpeaksOfTheModelNodeProbe() {
        for (tls in listOf<TlsAttestation>(TlsAttestation.Failed("x"), TlsAttestation.Inconclusive("y"))) {
            val s = session(); s.tlsAttestation = tls
            val row = ExpertSections.make(s, summary(tls = tls), "glm", "near.ai").transport!!
            val text = (row.label + " " + row.detail).lowercase()
            for (claim in chatConnectionClaims) assertFalse("$tls: $claim", claim in text)
            assertTrue("$tls: ${row.detail}", "model node" in text)
        }
    }

    /** The verification run's TLS section: same condition, in its "why" and every step it can show. */
    @Test fun theVerificationRunSaysWhatTheProbeChecked() {
        for (tls in listOf<TlsAttestation?>(null, TlsAttestation.Verified, TlsAttestation.Failed("x"), TlsAttestation.Inconclusive("y"))) {
            val section = summary(tls = tls).verificationRun.firstOrNull { it.id == "tls" } ?: error("no tls section for $tls")
            val text = (listOf(section.why) + section.steps.map { it.label + " " + it.detail }).joinToString(" ").lowercase()
            for (claim in chatConnectionClaims) assertFalse("$tls: $claim", claim in text)
            assertTrue("the why must name the gateway path", "gateway" in section.why.lowercase())
        }
    }

    /** A probe that failed or couldn't finish is about the model node's key, never the chat's connection. */
    @Test fun aTlsProbeFailureNeverSpeaksOfTheChatConnection() {
        val s = session(); s.gpuAttestation = NrasVerification.Verified
        for (tls in listOf<TlsAttestation>(TlsAttestation.Failed("live TLS certificate does not match the attested fingerprint"),
                TlsAttestation.Inconclusive("TLS attestation request failed"))) {
            s.tlsAttestation = tls
            val reason = s.e2eeDegradedReason.orEmpty()
            assertTrue("precondition: $tls degrades and says why", reason.isNotEmpty())
            assertFalse("$tls: $reason", "connection" in reason.lowercase())
        }
    }

    // A2

    /** The GPU row's detail is NVIDIA's result in every NVIDIA state — never the GPU node's Intel TDX line. */
    @Test fun theGpuRowIsNvidiasResultInEveryState() {
        for (nras in listOf<NrasVerification?>(null, NrasVerification.Verified, NrasVerification.Failed("NVIDIA attestation verdict: FAIL"),
                NrasVerification.Inconclusive("NVIDIA attestation service unreachable"))) {
            val s = summary(nras = nras)
            assertEquals("$nras", s.nrasDetail, s.gpuRowDetail)
        }
        // A node that sent no NVIDIA evidence says exactly that; the banner keeps S37's reason line.
        assertEquals("no nvidia evidence in this attestation", AttestationSummary.NO_NVIDIA_EVIDENCE)
        assertEquals(AttestationSummary.NO_NVIDIA_EVIDENCE, summary(nras = NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT)).gpuRowDetail)
        val passed = summary(nras = NrasVerification.Verified).gpuRowDetail.lowercase()
        assertFalse(passed, "tdx" in passed)
        assertTrue(passed, "nvidia" in passed)
    }

    // A3

    private val unbound = listOf<DeployLogBinding?>(null, DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), DeployLogBinding.Unchecked,
        DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH), DeployLogBinding.Mismatch(DeployLogBinding.Check.QUOTE))

    private fun claimsSigned(s: String) = s.lowercase().let { "signed" in it || "attested action log" in it }

    /** The measured-config note, the recipe caption and the no-pin notice: "signed" only once bound. */
    @Test fun signedOnlyOnceBoundInEveryActionLogCaption() {
        val lines = listOf<Pair<String, (DeployLogBinding?) -> String>>(
            "measured-config note" to TrustScopeText::measuredBindingNote,
            "recipe caption" to TrustScopeText::recipeDeclaresCaption,
            "no-pin notice" to { _ -> TrustScopeText.NO_ACTION_LOG_DETAIL })
        for ((name, line) in lines) for (b in unbound) assertFalse("$name under $b: ${line(b)}", claimsSigned(line(b)))
        assertTrue("bound keeps its claim", claimsSigned(TrustScopeText.measuredBindingNote(DeployLogBinding.Bound)))
    }
    // the recipe hash-mismatch banner: confidential/HonestyBannerTest (it sets the session's internal trust scope)
}
