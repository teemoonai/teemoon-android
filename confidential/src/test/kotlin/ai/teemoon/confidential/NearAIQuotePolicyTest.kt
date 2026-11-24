// ports: teemoonTests/NearAIQuotePolicyTests.swift @ d5e40fee
//
// Four checks from near.ai's inference-sdk ff591054 policies (freeze item B, the maintainer 2026-11-08), run through the
// real fetch on today's glm-5.3-flash reports (iOS's captured fixtures, 2026-11-08T14:51-14:55Z, byte-identical):
// B1 a TD whose TDATTRIBUTES debug bit (byte 0, bit 0, TUD.DEBUG) is set is evidence, on every quote, decided by teemoon
//    itself (dcap-qvl rejects it too, but the outcome must not hang on that);
// B2 an advertised `report_data` that is not the quote's is evidence;
// B3 a model node's mr_config must be 0x01 ‖ sha256(app_compose) of the compose it served, as the gateway's;
// B4 a near.ai model node that sent no `nvidia_payload` couldn't be checked: soft (asks), never verified.
// Intel is unreachable in every fetch test here, so a passing quote reads Unchecked, never Failed.
package ai.teemoon.confidential

import ai.teemoon.confidential.KitFixtures.s
import ai.teemoon.providers.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** One captured report per leg, editable before it is served. */
internal class LiveGlm53Reports(var gateway: JsonObject, var model: JsonObject, var gpu: JsonObject) {
    companion object {
        const val MODEL = "z-ai/glm-5.3-flash"
        const val GATEWAY = "https://cloud-api.near.ai/v1"
        const val NODE = "https://glm-5-3-flash.completions.near.ai/v1"

        fun load(): LiveGlm53Reports {
            fun json(leg: String) = Json.parseToJsonElement(LiveGlm53Reports::class.java.getResource("/fixtures/nearai-trust/nearai_live_glm53flash_20261108_$leg.json")!!.readText()).jsonObject
            return LiveGlm53Reports(json("gateway"), json("model"), json("gpu"))
        }

        /** The quote hex with TDATTRIBUTES byte 0 bit 0 (TUD.DEBUG) set: header 48 + body offset 120. */
        fun withDebugBit(quoteHex: String): String {
            val b = quoteHex.hexToBytesOrNull()!!
            b[168] = (b[168].toInt() or 0x01).toByte()
            return b.toHex()
        }

        /** One hex digit changed. */
        fun flipped(hex: String) = (if (hex.first() == '0') "1" else "0") + hex.drop(1)
    }

    fun s(o: JsonObject, vararg path: String): String = path.dropLast(1).fold(o) { acc, k -> acc.getValue(k).jsonObject }.getValue(path.last()).jsonPrimitive.content
    fun with(o: JsonObject, value: JsonElement, vararg path: String): JsonObject {
        if (path.size == 1) return JsonObject(o + (path[0] to value))
        return JsonObject(o + (path[0] to with(o.getValue(path[0]).jsonObject, value, *path.drop(1).toTypedArray())))
    }

    /** The record AttestationService.fetch builds from these reports: ed25519 is the model leg, the gateway host the gateway, the rest the GPU node. */
    suspend fun fetch(): AttestationRecord {
        val http: AttestationHttp = { url, _ ->
            val body = when { "signing_algo=ed25519" in url -> model; url.startsWith(GATEWAY) -> gateway; else -> gpu }
            200 to body.toString().toByteArray()
        }
        return AttestationService.fetch(GATEWAY, "", MODEL, "p", NODE, http = http, directBase = { NODE }, now = { Instant.ofEpochSecond(1_762_600_000) })
    }
}

class NearAIQuotePolicyTest {
    /** Intel PCS unreachable: a quote teemoon's own checks pass reads Unchecked. */
    private val dcap = DcapService({ _ -> throw IOException("pcs unreachable") }, { _, _, _ -> error("dcap-qvl is never reached without collateral") })

    // B1

    /** TDATTRIBUTES is little-endian: byte 0 is TUD, bit 0 DEBUG. Today's quotes carry 0x10 in byte 3 (SEPT_VE_DISABLE). */
    @Test fun theDebugBitIsTudBitZero() {
        assertTrue(QuoteChecks.debugEnabled(byteArrayOf(0x01, 0, 0, 0x10, 0, 0, 0, 0)))
        assertFalse(QuoteChecks.debugEnabled(byteArrayOf(0, 0, 0, 0x10, 0, 0, 0, 0)))
        assertFalse(QuoteChecks.debugEnabled(byteArrayOf(0xFE.toByte(), -1, -1, -1, -1, -1, -1, -1)))
        assertTrue("an unreadable field is never read as off", QuoteChecks.debugEnabled(ByteArray(0)))
    }

    @Test fun aDebugTdIsEvidenceOnTheGatewayModelAndGpuQuotes() = runTest {
        for (leg in listOf("gateway", "model", "gpu")) {
            val r = LiveGlm53Reports.load()
            when (leg) {
                "gateway" -> r.gateway = r.with(r.gateway, JsonPrimitive(LiveGlm53Reports.withDebugBit(r.s(r.gateway, "gateway_attestation", "intel_quote"))), "gateway_attestation", "intel_quote")
                "model" -> r.model = r.with(r.model, JsonPrimitive(LiveGlm53Reports.withDebugBit(r.s(r.model, "intel_quote"))), "intel_quote")
                else -> r.gpu = r.with(r.gpu, JsonPrimitive(LiveGlm53Reports.withDebugBit(r.s(r.gpu, "intel_quote"))), "intel_quote")
            }
            val result = dcap.verify(r.fetch())
            val outcome = when (leg) { "gateway" -> result.gateway; "model" -> result.model; else -> result.gpu }
            assertTrue("$leg: $outcome", outcome is DcapVerification.Failed)
            assertTrue(leg, result.hasHardFailure)
        }
    }

    /** The compose manager's quote goes through the same service (RecordDcapVerification.manager): a debug TD there is evidence too. */
    @Test fun aDebugTdIsEvidenceOnTheComposeManagerQuote() = runTest {
        val r = LiveGlm53Reports.load()
        val quote = r.s(r.gpu, "compose_manager_attestation", "quote")
        r.gpu = r.with(r.gpu, JsonPrimitive(LiveGlm53Reports.withDebugBit(quote)), "compose_manager_attestation", "quote")
        val record = r.fetch()
        assertNotNull("precondition: the record carries the manager quote", record.managerQuote)
        val result = dcap.verify(record)
        assertTrue("${result.manager}", result.manager is DcapVerification.Failed)
    }

    // B2

    @Test fun anAdvertisedReportDataThatIsNotTheGatewayQuotesIsEvidence() = runTest {
        val r = LiveGlm53Reports.load()
        r.gateway = r.with(r.gateway, JsonPrimitive(LiveGlm53Reports.flipped(r.s(r.gateway, "gateway_attestation", "report_data"))), "gateway_attestation", "report_data")
        val result = dcap.verify(r.fetch())
        assertTrue("${result.gateway}", result.gateway is DcapVerification.Failed)
        assertTrue(result.hasHardFailure)
    }

    @Test fun anAdvertisedReportDataThatIsNotTheModelQuotesIsEvidence() = runTest {
        val r = LiveGlm53Reports.load()
        val rd = QuoteParser.parseHex(r.s(r.model, "intel_quote")).body.reportData.toHex()
        r.model = r.with(r.model, JsonPrimitive(LiveGlm53Reports.flipped(rd)), "report_data")
        val result = dcap.verify(r.fetch())
        assertTrue("${result.model}", result.model is DcapVerification.Failed)
    }

    @Test fun aModelNodeAdvertisingItsQuotesOwnReportDataPasses() = runTest {
        val r = LiveGlm53Reports.load()
        val rd = QuoteParser.parseHex(r.s(r.model, "intel_quote")).body.reportData.toHex()
        r.model = r.with(r.model, JsonPrimitive(rd.uppercase()), "report_data")
        val record = r.fetch()
        assertNotNull(record.modelAdvertisedReportData)
        assertTrue("${dcap.verify(record).model}", dcap.verify(record).model?.isUnchecked == true)
    }

    /** With Intel answering, the advertised value is held to the quote dcap-qvl authenticated. */
    @Test fun anAuthenticatedQuoteIsHeldToItsAdvertisedReportData() = runTest {
        val engine = DcapQvlEngine { _, _, _ ->
            DcapVerifiedQuote(KitFixtures.realQuote.s("tcbStatus")!!, KitFixtures.realQuote.s("dcapMrConfigIdHex")!!, KitFixtures.realQuote.s("dcapReportDataHex")!!)
        }
        val service = DcapService(IntelPcsCollateralProvider(KitFixtures.StubHttp()), engine)
        val parse = TdxQuoteVerifier.verify(KitFixtures.quoteHex)
        fun record(advertised: String) = AttestationRecord(composeHash = "", mrtd = "", osImageHash = "", intelQuote = KitFixtures.quoteHex, composeManifest = null,
            gpuArch = null, gpuNodeComposeHash = null, modelFileHash = null, signingAddress = null, gpuSigningAddress = null, modelEd25519PubKey = null,
            quoteVerification = parse, gpuQuoteVerification = null, modelQuoteVerification = null,
            fetchedAt = Instant.ofEpochSecond(KitFixtures.nowSecs), providerID = "p", gatewayAdvertisedReportData = advertised)
        val rd = parse.measurements.reportData.toHex()
        assertTrue(service.verify(record(rd)).gateway is DcapVerification.Verified)
        assertEquals(DcapVerification.Failed(QuoteChecks.REPORT_DATA_DISAGREES), service.verify(record(LiveGlm53Reports.flipped(rd))).gateway)
    }

    // B3

    @Test fun aGpuNodeWhoseMrConfigIsNotItsComposeIsCodeIdentityEvidence() = runTest {
        val r = LiveGlm53Reports.load()
        r.gpu = r.with(r.gpu, JsonPrimitive(r.s(r.gpu, "info", "tcb_info", "app_compose") + " "), "info", "tcb_info", "app_compose")
        val record = r.fetch()
        assertEquals(false, record.codeIdentityVerified)
        assertEquals(false, record.modelNodesBootedTheirCompose)
    }

    @Test fun aModelLegWhoseMrConfigIsNotItsComposeIsCodeIdentityEvidence() = runTest {
        val r = LiveGlm53Reports.load()
        r.model = r.with(r.model, JsonPrimitive(r.s(r.model, "info", "tcb_info", "app_compose").replace("dstack-nvidia", "dstack-nvidiA")), "info", "tcb_info", "app_compose")
        val record = r.fetch()
        assertTrue(record.modelComposeManifest?.contains("dstack-nvidiA") == true)
        assertEquals(false, record.codeIdentityVerified)
    }

    // B4

    @Test fun aModelNodeThatSentNoGpuEvidenceCouldntBeChecked() = runTest {
        val r = LiveGlm53Reports.load()
        r.model = JsonObject(r.model - "nvidia_payload")
        val record = r.fetch()
        assertTrue(record.nvidiaPayload == null && record.modelIntelQuote?.isNotEmpty() == true)
        val s = ConfidentialSession(CoroutineScope(Dispatchers.Unconfined), { Provider.nearAI }, services = ConfidentialSession.Services(skipLiveAttestation = true,
            verifyNras = { _, _ -> error("no evidence goes to NVIDIA") }), sealedModels = ai.teemoon.confidential.SealedModels(ai.teemoon.confidential.InMemorySealedModelStore()))
        s.verifyGpu(record)
        assertEquals("nothing pending, couldn't-check", NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT), s.gpuAttestation)
        assertFalse(s.gpuAttestation!!.isHardFailure)
    }

    /** The GPU row shows the couldn't-check with no payload behind it, in the existing words, never "unreachable". */
    @Test fun theGpuRowAndReasonSayTheEvidenceIsUnavailable() {
        val summary = AttestationSummary(attestation = AttestationRecord.preview.copy(nvidiaPayload = null), state = AttestationState.DEGRADED, timedOut = false,
            provider = Provider.nearAI, lastRequestUsedE2EE = null, lastE2EEFailReason = null, verifiedResponseCount = 0, mismatchedResponseCount = 0,
            gatewayTrustResponseCount = 0, attestationFetchFailed = false, nrasVerification = NrasVerification.Inconclusive(NrasVerification.EVIDENCE_ABSENT))
        assertEquals(StepState.UNCHECKED, summary.nrasState)
        // A2 (iOS 123d0b28): the row says no evidence came; the degraded reason keeps "the model's hardware attestation is unavailable."
        assertEquals(AttestationSummary.NO_NVIDIA_EVIDENCE, summary.nrasDetail)
    }

    // today's record

    /** near.ai's SDK passed all four on these hosts at 13:39Z; teemoon must not block the same record. */
    @Test fun todaysGlm53FlashRecordPassesAllFour() = runTest {
        val r = LiveGlm53Reports.load()
        val record = r.fetch()
        val result = dcap.verify(record)
        for ((leg, outcome) in listOf("gateway" to result.gateway, "model" to result.model, "gpu" to result.gpu, "manager" to result.manager))
            assertTrue("$leg: $outcome", outcome?.isUnchecked == true)
        assertEquals(true, record.codeIdentityVerified)
        assertEquals("both model-node quotes were compared", true, record.modelNodesBootedTheirCompose)
        assertTrue(record.nvidiaPayload?.isNotEmpty() == true)
    }
}
