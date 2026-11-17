// ports: teemoonTests/SendGateRuleTests.swift @ 1cef8d95 (everyVectorGivesItsPolicyAndAlert, aSealHoldingSoftStateNeverOffersSendUnencrypted,
//        aConfirmIsAskedOncePerRecordAndReason; the settle-timeout table and the audits tripwire are the app's, SendGateTimeoutTest)
//        + everyNVIDIAAndTLSCaseHasItsClass and the nras/tls malformed|teemoon vectors @ a09537fe (kit 0.5.10)
//
// near-send-gate-rule (the maintainer 2026-11-06), run from conformance/sendgate.json so both platforms hold the same table:
// outages (Intel PCS, NVIDIA, the TLS probe, GitHub, near.ai) ask or pass, never block; every evidence case blocks; a
// seal-holding soft state never reaches "send unencrypted".
package ai.teemoon.confidential

import ai.teemoon.providers.EndpointDirectory
import ai.teemoon.providers.NearAIModelCatalog
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class SendGateRuleTest {
    @Before fun offline() { NearAIModelCatalog.resetTierCache(); NearAIModelCatalog.endpointDirectory = EndpointDirectory(fetch = { null }) }

    private val kit = Json.parseToJsonElement(javaClass.getResourceAsStream("/sendgate.json")!!.reader().readText()).jsonObject
    private val upToDate = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")
    private val baselineScope = NearTrustScope(DeployLogBinding.Bound, BootLogReplay.Matches, null, emptyList(), LauncherIndex.NotChecked)

    /** The kit's baseline — a fully verified near.ai record — then the vector's one change. */
    private fun session(change: Map<String, String>): ConfidentialSession {
        val record = AttestationRecord.preview
        val stale = change["refetch"] == "failedStale"
        val now = if (stale) record.fetchedAt.plusSeconds(ConfidentialSession.ATTESTATION_MAX_AGE_SECONDS + 1) else record.fetchedAt
        val p = Provider.nearAI.copy(id = record.providerID, model = record.model ?: Provider.nearAI.model)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { p }, services = ConfidentialSession.Services(skipLiveAttestation = true, now = { now }))
        s.attestation = record; s.lastRequestUsedE2EE = true
        s.imageProvenance = ManifestProvenance.AllVerified(listOf(ImageRef("nearaidev/cloud-api", "a".repeat(64))), emptyList())
        var dcap = RecordDcapVerification(gateway = upToDate, model = upToDate)
        s.gpuAttestation = NrasVerification.Verified; s.tlsAttestation = TlsAttestation.Verified
        var scope = baselineScope
        fun pair(check: ComposeFileCheck) = ComposeFilePair("c", "f", "s", null, listOf("proxy"), check)
        for ((key, value) in change) when (key to value) {
            // as DcapVerifier answers a collateral fetch that failed: exercises the classification, not a hand-made verdict
            "dcap" to "unchecked" -> dcap = dcap.copy(model = DcapVerifier.collateralUnavailable(IOException("pcs unreachable")))
            "dcap" to "failed" -> dcap = dcap.copy(model = DcapVerification.Failed("signature invalid"))
            "dcap" to "revoked" -> dcap = dcap.copy(model = DcapVerification.Verified(DcapTcbStatus.REVOKED, "", ""))
            "nras" to "inconclusive" -> s.gpuAttestation = NrasVerification.Inconclusive("NRAS unreachable")
            "nras" to "failed" -> s.gpuAttestation = NrasVerification.Failed("NVIDIA rejected the evidence")
            // kit 0.5.9 (near-gate-nvidia-tls-split): malformed evidence is hard; teemoon's own fault is soft, with the approved line
            "nras" to "malformed" -> s.gpuAttestation = NrasVerification.Failed("GPU evidence payload unparseable")
            "nras" to "teemoon" -> s.gpuAttestation = NrasVerification.Inconclusive(NrasVerification.TEEMOON_FAULT)
            "tls" to "inconclusive" -> s.tlsAttestation = TlsAttestation.Inconclusive("probe unreachable")
            "tls" to "failed" -> s.tlsAttestation = TlsAttestation.Failed("fingerprint mismatch")
            "tls" to "malformed" -> s.tlsAttestation = TlsAttestation.Failed("attestation response missing TLS fields")
            "tls" to "teemoon" -> s.tlsAttestation = TlsAttestation.Inconclusive(NrasVerification.TEEMOON_FAULT)
            "binding" to "unchecked" -> scope = scope.copy(deployLogBinding = DeployLogBinding.Unchecked)
            "binding" to "mismatch" -> scope = scope.copy(deployLogBinding = DeployLogBinding.Mismatch(DeployLogBinding.Check.ACTIONS_HASH))
            "boot" to "mismatch" -> scope = scope.copy(bootLogReplay = BootLogReplay.Mismatch(BootLogReplay.What.RTMR3))
            "running" to "inconclusive" -> scope = scope.copy(composeFiles = listOf(pair(ComposeFileCheck.Inconclusive)))
            "running" to "unpublished" -> scope = scope.copy(composeFiles = listOf(pair(ComposeFileCheck.Unpublished)))
            "running" to "mismatch" -> scope = scope.copy(composeFiles = listOf(pair(ComposeFileCheck.Mismatch("0".repeat(64)))))
            "refetch" to "failedStale" -> s.attestationFetchFailed = true
            // F1 (iOS's TLA+ model, 2026-11-06): the unpublished launcher every near.ai model carries today, beside any change
            "image" to "unpublished" -> s.imageProvenance = ManifestProvenance.Incomplete(emptyList(), listOf(ProvenanceFailure(
                ImageRef("nearaidev/compose-manager-launcher", "d".repeat(64)), ImageProvenance.Unverified(ImageProvenance.Reason.FetchFailed("GitHub attestations HTTP 404")))), emptyList())
            // kit 0.5.13: the model recipe's hash mismatch (alone, and beside an unpublished image)
            "recipe" to "mismatch" -> s.modelLayerVerification = ModelLayerVerification.HASH_MISMATCH
            // kit 0.6.1 (S37): each through the check itself, on today's glm-5.3-flash quotes (iOS's captured reports)
            "quote" to "debug" -> { val q = LiveGlm53Reports.load().let { it.s(it.model, "intel_quote") }
                dcap = dcap.copy(model = DcapVerification.Failed(QuoteChecks.failure(LiveGlm53Reports.withDebugBit(q))!!)) }
            "reportData" to "mismatch" -> { val r = LiveGlm53Reports.load()
                dcap = dcap.copy(gateway = DcapVerification.Failed(QuoteChecks.failure(r.s(r.gateway, "gateway_attestation", "intel_quote"),
                    LiveGlm53Reports.flipped(r.s(r.gateway, "gateway_attestation", "report_data")))!!)) }
            "modelMrConfig" to "mismatch" -> s.attestation = record.copy(modelComposeManifest = "services: {}")
            "nras" to "absent" -> { val r = record.copy(modelIntelQuote = "aa", nvidiaPayload = null); s.attestation = r; s.verifyGpu(r) }
            // kit 0.6.0 (S30): the provenance lookup ended without an answer (T2b, GitHub or Docker Hub unreachable)
            "image" to "inconclusive" -> { s.imageProvenance = null; s.provenanceUnchecked = true }
            "audit" to "leaks" -> Unit   // nothing in the gate reads the audits index (SendGateTimeoutTest's tripwire)
            // kit 0.5.8: Intel answers every request but one, which gets the status — through the real DCAP verifier
            else -> if (key == "pcs") dcap = dcap.copy(model = intelAnswering(value.substringBefore(':'), value.substringAfter(':').toInt()))
                else throw AssertionError("unknown change $key=$value")
        }
        s.dcapVerification = dcap
        s.nearScopeOverride = scope
        return s
    }

    /** The kit's endpoints, as the URLs the snapshot answers name them. */
    private fun endpointOf(url: String) = when {
        "/tcb?" in url -> "tcb"; "/pckcrl" in url -> "pckcrl"; "/qe/identity" in url -> "qeIdentity"; "RootCA" in url || "rootcacrl" in url -> "rootCrl"; else -> "?"
    }

    /** The quote's DCAP verdict when Intel answers [endpoint] with [status] (-1: no response) and everything else from the kit's snapshot. */
    private fun intelAnswering(endpoint: String, status: Int): DcapVerification {
        val snapshot = KitFixtures.StubHttp()
        val http = CollateralHttpClient { url ->
            if (endpointOf(url) == endpoint) { val u = java.net.URI(url); throw CollateralHttpException(status, u.host, u.path) }
            snapshot.get(url)
        }
        val verifier = DcapVerifier(IntelPcsCollateralProvider(http = http), engine = { _, _, _ -> error("the collateral is incomplete; dcap-qvl is never reached") })
        return kotlinx.coroutines.runBlocking { verifier.verify(KitFixtures.quoteHex, KitFixtures.nowSecs) }
    }

    /** kit 0.5.8's pcsStatus table: every endpoint × status, through the real verifier (iOS everyIntelStatusHasItsClass). */
    @Test fun everyIntelStatusHasItsClass() {
        val rows = kit.getValue("pcsStatus").jsonArray.map { it.jsonObject }
        assertEquals(52, rows.size)
        for (r in rows) {
            val endpoint = r.getValue("endpoint").jsonPrimitive.content; val status = r.getValue("status").jsonPrimitive.content.toInt()
            val got = intelAnswering(endpoint, status)
            val cls = when { got.isUnchecked -> "unchecked"; got.isHardFailure -> "hard"; else -> "verified" }
            assertEquals("$endpoint $status", r.getValue("class").jsonPrimitive.content, cls)
        }
    }

    private fun alert(s: ConfidentialSession) = when (s.sendPolicy) {
        TrustSendPolicy.ALLOW -> "none"
        TrustSendPolicy.BLOCK -> "blocked"
        TrustSendPolicy.CONFIRM -> if (s.provenanceUncheckedAsks) "provenanceUnchecked" else if (s.softButSealed) "stillChecking" else "unavailable"
    }
    private fun policyName(p: TrustSendPolicy) = when (p) { TrustSendPolicy.ALLOW -> "allow"; TrustSendPolicy.CONFIRM -> "confirm"; TrustSendPolicy.BLOCK -> "block" }
    private val vectors = kit.getValue("vectors").jsonArray.map { it.jsonObject }
    private fun change(v: kotlinx.serialization.json.JsonObject) = v.getValue("change").jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test fun everyVectorGivesItsPolicyAndAlert() {
        assertTrue(vectors.size >= 19)
        for (v in vectors) {
            val name = v.getValue("name").jsonPrimitive.content
            val s = session(change(v))
            assertEquals(name, v.getValue("policy").jsonPrimitive.content, policyName(s.sendPolicy))
            assertEquals(name, v.getValue("alert").jsonPrimitive.content, alert(s))
            // kit 0.5.11: the degraded reason a vector pins (the teemoon-fault line)
            v["reasonLine"]?.jsonPrimitive?.content?.let { assertEquals("$name reason", it, s.e2eeDegradedReason) }
        }
    }

    /**
     * F1, found by iOS's TLA+ trust model (2026-11-06): an unpublished image skipped the ask whenever the key looked bound,
     * so a hard break or a PCS outage beside it went out unasked on iOS. Every near.ai model carries the unpublished
     * launcher today, so every vector must keep its policy with it added: hard stays BLOCK, soft stays CONFIRM.
     */
    @Test fun anUnpublishedImageNeverSoftensAnotherVerdict() {
        for (v in vectors) {
            val name = v.getValue("name").jsonPrimitive.content
            val want = v.getValue("policy").jsonPrimitive.content
            if ("image" in change(v)) continue   // the image is the vector's own change (0.6.0 outage/provenance-lookup)
            val got = policyName(session(change(v) + ("image" to "unpublished")).sendPolicy)
            if (want == "allow") continue   // the baseline vectors: the unpublished image alone neither asks nor blocks
            assertEquals("$name with an unpublished image", want, got)
        }
    }

    /**
     * near-gate-nvidia-tls-split (the maintainer 2026-11-06, iOS ee5d1b51 + a09537fe): every nrasTlsCases row, run through the
     * real verifier, lands in its class. outage = soft with its own reason, evidence = hard, teemoon = soft with the
     * approved fault line.
     */
    @Test fun everyNvidiaAndTlsCaseHasItsClass() = kotlinx.coroutines.runBlocking {
        fun stub(response: String?) = NrasHttpClient { _, _ -> response?.toByteArray() ?: throw IOException("unreachable") }
        fun keys(data: String?) = NrasKeySource { data?.toByteArray() ?: throw IOException("keys unreachable") }
        fun nras(response: String?, keySource: NrasKeySource = keys(NrasKit.jwks)) = NrasService(stub(response), keySource, now = { NrasKit.verifiedAt })
        suspend fun nrasResult(name: String): NrasVerification? = when (name) {
            "nras-unreachable" -> nras(null).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
            "jwks-unreachable" -> nras(NrasKit.nrasResponse, keys(null)).verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
            "payload-unparseable" -> nras(NrasKit.nrasResponse).verify("not json", NrasKit.requestNonce)
            "response-unrecognised" -> nras("{}").verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
            "token-unrecognised" -> nras("""[["JWT","not-a-token"],{}]""").verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
            "signing-key-unknown-after-refetch" -> {
                val refreshes = mutableListOf<Boolean>()
                val r = nras(NrasKit.nrasResponse, NrasKeySource { refresh -> refreshes += refresh; """{"keys":[]}""".toByteArray() })
                    .verify(NrasKit.nvidiaPayload, NrasKit.requestNonce)
                assertEquals("NVIDIA's key set is refetched once before giving up", 1, refreshes.count { it })
                r
            }
            "nonce-not-recorded" -> nras(NrasKit.nrasResponse).verify(NrasKit.nvidiaPayload, null)
            // kit 0.6.1 (S37 B4): a model node that sent no GPU evidence, through the session's own verifyGpu
            "payload-absent" -> ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { Provider.nearAI }, services = ConfidentialSession.Services(skipLiveAttestation = true))
                .also { it.verifyGpu(AttestationRecord.preview.copy(modelIntelQuote = "aa", nvidiaPayload = null)) }.gpuAttestation
            else -> null
        }
        fun tlsResult(name: String): TlsAttestation? = when (name) {
            "response-missing-fields" -> TlsAttestationVerifier.verifyResponse("""{"signing_address":"0x00"}""".toByteArray(), TlsKit.certDer, TlsKit.nonce).first
            "quote-unparseable" -> TlsAttestationVerifier.verifyBindings("00", TlsKit.signingAddress, TlsKit.signingAlgo, TlsKit.tlsCertFingerprint, TlsKit.certDer, TlsKit.nonce)
            "address-or-fingerprint-encoding" -> TlsAttestationVerifier.verifyBindings(TlsKit.intelQuote, "0xzz", TlsKit.signingAlgo, TlsKit.tlsCertFingerprint, TlsKit.certDer, TlsKit.nonce)
            "certificate-not-captured" -> TlsAttestationVerifier.verifyResponse("{}".toByteArray(), null, TlsKit.nonce).first
            "certificate-malformed" -> TlsAttestationVerifier.verifyBindings(TlsKit.intelQuote, TlsKit.signingAddress, TlsKit.signingAlgo, TlsKit.tlsCertFingerprint,
                byteArrayOf(0x30, 0x03, 0x02), TlsKit.nonce)
            "quote-unchecked" -> TlsAttestationVerifier.withQuoteCheck(TlsAttestation.Verified, DcapVerification.Unchecked("collateral: pcs unreachable"))
            else -> null
        }
        fun klass(hard: Boolean, reason: String?) = if (hard) "evidence" else if (reason == NrasVerification.TEEMOON_FAULT) "teemoon" else "outage"
        val rows = kit.getValue("nrasTlsCases").jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 13)
        for (row in rows) {
            val check = row.getValue("check").jsonPrimitive.content; val case = row.getValue("case").jsonPrimitive.content
            val got = when (check) {
                "nras" -> nrasResult(case)?.let { r ->
                    assertTrue(case, !r.isVerified)
                    klass(r.isHardFailure, (r as? NrasVerification.Failed)?.reason ?: (r as? NrasVerification.Inconclusive)?.reason)
                }
                "tls" -> tlsResult(case)?.let { r -> assertTrue(case, r.isUnverified); klass(r.isHardFailure, r.reason) }
                else -> null
            } ?: throw AssertionError("no driver for $check/$case")
            assertEquals("$check/$case", row.getValue("class").jsonPrimitive.content, got)
        }
    }

    @Test fun aSealHoldingSoftStateNeverOffersSendUnencrypted() {
        for (v in vectors.filter { it.getValue("policy").jsonPrimitive.content == "confirm" }) {
            val name = v.getValue("name").jsonPrimitive.content
            val s = session(change(v))
            assertNotNull("$name: precondition, the send would be sealed", s.currentTeeContext()?.e2eePeer)
            assertNotEquals("$name reached 'end-to-end encryption unavailable · send unencrypted'", "unavailable", alert(s))
        }
    }

    @Test fun aConfirmIsAskedOncePerRecordAndReason() {
        val s = session(mapOf("dcap" to "unchecked"))
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
        s.rememberConfirmation(pending = false)
        assertEquals("the same record and reason don't ask again", TrustSendPolicy.ALLOW, s.sendPolicyAfterConfirmations)
        s.gpuAttestation = NrasVerification.Inconclusive("NRAS unreachable")
        s.dcapVerification = RecordDcapVerification(model = upToDate)
        assertEquals("a different reason asks again", TrustSendPolicy.CONFIRM, s.sendPolicyAfterConfirmations)
    }

    /** iOS's warning: a non-hard DCAP outcome must not fall through to verified anywhere. */
    @Test fun anUncheckedQuoteNeverReadsVerified() {
        val s = session(mapOf("dcap" to "unchecked"))
        assertEquals(AttestationState.DEGRADED, s.attestationState)
        assertTrue(!s.e2eeBindingIntact)
        assertEquals(ConfidentialSession.INTEL_UNREACHABLE, s.e2eeDegradedReason)
        val tls = TlsAttestationVerifier.withQuoteCheck(TlsAttestation.Verified, DcapVerifier.collateralUnavailable(IOException("pcs unreachable")))
        assertTrue("TLS over an unchecked quote is inconclusive, not verified", tls is TlsAttestation.Inconclusive)
        // what stays hard: an unparseable quote, a missing PCK chain, anything unrecognised
        for (e in listOf(CollateralException.QuoteUnreadable("bad"), CollateralException.QuoteMissingPckChain, IllegalStateException("?")))
            assertTrue("$e must stay hard", DcapVerifier.collateralUnavailable(e).isHardFailure)
        for (e in listOf(CollateralHttpException(503, "api.trustedservices.intel.com", "/tcb"), CollateralException.MissingHeader("TCB-Info-Issuer-Chain"), CollateralException.MalformedResponse("tcb")))
            assertTrue("$e is couldn't-check", DcapVerifier.collateralUnavailable(e).isUnchecked)
    }

    /**
     * iOS e406bb96: a quote whose fields can't name its collateral is a fault in the quote, not in Intel — hard. On
     * Android the URL is always well formed (fmspc is hex of the certificate's bytes, ca one of two words), so the
     * check is the FMSPC's length: anything but 6 bytes names collateral Intel can only 404, which would ride the
     * outage path.
     */
    @Test fun aQuoteThatCannotNameItsCollateralStaysHard() {
        assertTrue(DcapVerifier.collateralUnavailable(CollateralException.QuoteFieldsInvalid("fmspc")).isHardFailure)
        PckCertInfo.requireCollateralFields("00906ED50000", "processor")
        for ((fmspc, ca) in listOf("00906ED5" to "processor", "00906ED500000000" to "platform", "00906ED50000" to "other"))
            assertTrue("$fmspc/$ca", runCatching { PckCertInfo.requireCollateralFields(fmspc, ca) }.exceptionOrNull() is CollateralException.QuoteFieldsInvalid)
    }

    /**
     * the maintainer 2026-11-06 ("yes block"): Intel answering 404 for a well-formed FMSPC is evidence against the quote — hard.
     * Only the network (an IOException, a timeout, no response), a 5xx, a 429 and an unreadable answer are an outage.
     */
    @Test fun intelsNotFoundIsEvidenceAndOnlyAnOutageIsUnchecked() {
        fun http(status: Int, path: String = "/tdx/certification/v4/tcb") = DcapVerifier.collateralUnavailable(CollateralHttpException(status, "api.trustedservices.intel.com", path))
        assertTrue(http(404).isHardFailure); assertTrue(http(404, "/sgx/certification/v4/pckcrl").isHardFailure)
        // a 404 on what Intel serves everyone (QE identity, the root CRL) is Intel's problem, not the quote's
        assertTrue(http(404, "/tdx/certification/v4/qe/identity").isUnchecked); assertTrue(http(404, "/sgx/certification/v4/rootcacrl").isUnchecked)
        // pm/the maintainer: 400/422 is hard only for a request built from the quote's own fields (the tcb by FMSPC, the PCK CRL
        // by CA) — QuoteFieldsInvalid's family; on any other endpoint it isn't the quote's doing.
        assertTrue(http(400).isHardFailure); assertTrue(http(422, "/sgx/certification/v4/pckcrl").isHardFailure)
        assertTrue(http(400, "/tdx/certification/v4/qe/identity").isUnchecked)
        // access and transport refused outside our control stay outages: never a block (the maintainer's rule)
        for (status in listOf(-1, 401, 403, 408, 429, 500, 502, 503, 504)) assertTrue("$status is an outage", http(status).isUnchecked)
        assertTrue(DcapVerifier.collateralUnavailable(java.net.SocketTimeoutException("timeout")).isUnchecked)
    }
}
