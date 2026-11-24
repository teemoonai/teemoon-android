// ports: teemoonTests/CandidateListFetchTests.swift @ bf762777 (1.1.1 audit A15: aRefusalCarryingAListIsNotAdoptedAndATransportErrorIsRetried; theRequestCarries...,
//        aServerErrorIsRetriedAndA4xxIsNot from 86c7c6ce's suite, without its caps) and AttestationLegIndependenceTests.swift @ bf762777 (theListIsCheckedAgainstTheNonceItWasAskedWith)
// ports: teemoonTests/ModelCandidatesTests.swift @ 878ab019 (1.1.1 audit A13: the key-service tests and anEnclaveWithAnotherKeyServiceBlocks); @ 7814a0fd (both suites: "Every listed enclave (C3a)" and "Every listed enclave in the gate (C3a)")
//
// SDK track C3a (docs/NEARAI_SDK_C.md §C3): every enclave near.ai lists for a model holds the key a sealed request goes to,
// so each must pass the checks the one teemoon read always passed. Live capture 2026-11-19: three glm-5.3-flash enclaves,
// one key, three compose hashes. teemoon checks every LISTED enclave; enclaves the key service admits but near.ai doesn't
// list stay a residual (the key is derived from app_id). Never read this as a closure.
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

internal object LiveCandidates {
    const val FIXTURE = "/fixtures/nearai-trust/nearai_live_glm53flash_candidates_20261119.json"
    /** The 822 KB capture is public data, copied byte-identical from teemoon-ios release/1.1.1-patch 7814a0fd (a pick of fe3fa623) teemoonTests/Fixtures. */
    const val FIXTURE_SHA256 = "c04451458797cfbaebc045239e1b510a41336e18c3a63193ef0db55f17935253"

    class Capture(val body: ByteArray, val nonce: String, val list: List<JsonObject>) {
        val key: ByteArray get() = list[0].getValue("signing_public_key").jsonPrimitive.content.hexToBytes()
    }

    fun load(): Capture {
        val root = Json.parseToJsonElement(LiveCandidates::class.java.getResource(FIXTURE)!!.readText()).jsonObject
        val report = root.getValue("report").jsonObject
        return Capture(report.toString().toByteArray(), root.getValue("nonce").jsonPrimitive.content,
            report.getValue("model_attestations").jsonArray.map { it.jsonObject })
    }

    fun editTcb(c: JsonObject, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): JsonObject {
        val info = c.getValue("info").jsonObject
        val tcb = info.getValue("tcb_info").jsonObject.toMutableMap().also(edit)
        return JsonObject(c + ("info" to JsonObject(info + ("tcb_info" to JsonObject(tcb)))))
    }
}

class ModelCandidatesTest {
    @Test fun theFixtureIsTheBytesIosPins() {
        val bytes = LiveCandidates::class.java.getResourceAsStream(LiveCandidates.FIXTURE)!!.readBytes()
        assertEquals(LiveCandidates.FIXTURE_SHA256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    @Test fun theLiveListIsThreeEnclavesWithOneKey() {
        val c = LiveCandidates.load()
        val list = ModelCandidates.list(c.body)
        assertEquals(3, list.size)
        assertEquals(1, list.map { it["signing_public_key"]?.jsonPrimitive?.content }.toSet().size)
    }

    @Test fun everyLiveEnclavePasses() {
        val c = LiveCandidates.load()
        for (candidate in c.list) assertEquals(ModelCandidates.Result.Clean, ModelCandidates.check(candidate, c.key, c.nonce))
    }

    @Test fun anEnclaveWithAnotherKeyIsEvidence() {
        val c = LiveCandidates.load()
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.KEY_DIFFERS), ModelCandidates.check(c.list[1], ByteArray(32) { 0xAB.toByte() }, c.nonce))
    }

    @Test fun anEnclaveThatDidNotAnswerOurNonceIsEvidence() {
        val c = LiveCandidates.load()
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.NONCE), ModelCandidates.check(c.list[2], c.key, "00".repeat(32)))
    }

    @Test fun aQuoteThatDoesNotVerifyIsEvidence() {
        val c = LiveCandidates.load()
        val quote = c.list[0].getValue("intel_quote").jsonPrimitive.content.toCharArray()
        quote[700] = if (quote[700] == '0') '1' else '0'   // inside the TD report body: the signature no longer covers it
        val bad = JsonObject(c.list[0] + ("intel_quote" to JsonPrimitive(String(quote))))
        assertNotEquals(ModelCandidates.Result.Clean, ModelCandidates.check(bad, c.key, c.nonce))
    }

    @Test fun aComposeTheQuoteDoesNotCommitToIsEvidence() {
        val c = LiveCandidates.load()
        val bad = LiveCandidates.editTcb(c.list[1]) { it["app_compose"] = JsonPrimitive(it.getValue("app_compose").jsonPrimitive.content + " ") }
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.MR_CONFIG), ModelCandidates.check(bad, c.key, c.nonce))
    }

    @Test fun aBootLogThatDoesNotReplayIsEvidence() {
        val c = LiveCandidates.load()
        val bad = LiveCandidates.editTcb(c.list[2]) { tcb ->
            val events = tcb.getValue("event_log").jsonArray.toMutableList()
            events.removeAt(events.indexOfFirst { it.jsonObject["event"]?.jsonPrimitive?.content == "instance-id" })
            tcb["event_log"] = JsonArray(events)
        }
        assertEquals(ModelCandidates.Result.Mismatch(ModelCandidates.Why.BOOT_LOG), ModelCandidates.check(bad, c.key, c.nonce))
    }

    /** The fetch keeps the list and each enclave's result; the expert view (C4) reads instance, compose and key service. */
    @Test fun aFetchKeepsEveryEnclave() {
        val c = LiveCandidates.load()
        val record = AttestationService.adoptCandidates(c.body, c.nonce, c.key)
        assertEquals(3, record?.enclaves?.size)
        assertTrue(record!!.enclaves.all { it.result == ModelCandidates.Result.Clean })
        assertEquals(3, record.enclaves.map { it.composeHash }.toSet().size)
        assertTrue(record.enclaves.all { it.mrKMS?.isNotEmpty() == true })
        // record-only: named, never judged
        assertTrue(record.enclaves.all { it.instanceID.isNotEmpty() && it.appID.isNotEmpty() })
    }

    // one key, one key service (audit 1.1.1 A13; iOS 878ab019)

    private fun enclave(provider: String?, kms: String?, result: ModelCandidates.Result = ModelCandidates.Result.Clean) =
        ModelCandidates.Enclave("i", "c", "a", provider, kms, result)

    private fun results(enclaves: List<ModelCandidates.Enclave>) = ModelCandidates.sameKeyService(enclaves).map { it.result }

    private val clean = ModelCandidates.Result.Clean
    private val differs = ModelCandidates.Result.Mismatch(ModelCandidates.Why.KEY_PROVIDER_DIFFERS)

    /** Live 2026-11-19: the three glm-5.3-flash enclaves name one key provider (near.ai's KMS, a P-256 key) and one mr-kms. */
    @Test fun everyLiveEnclaveNamesOneKeyService() {
        val c = LiveCandidates.load()
        val listed = AttestationService.adoptCandidates(c.body, c.nonce, c.key)!!
        assertEquals(1, listed.enclaves.map { it.keyProviderID }.toSet().size)
        assertEquals(1, listed.enclaves.map { it.mrKMS }.toSet().size)
        assertTrue(listed.enclaves[0].keyProviderID!!.startsWith("3059301306072a8648ce3d0201"))
        assertEquals("22a6652c0ce74e50b549c0f9c21d53c5861598ec4d58cf3286d158104923bc56", listed.enclaves[0].mrKMS)
        assertTrue(listed.enclaves.all { it.result == clean })
    }

    @Test fun anEnclaveWithAnotherKeyServiceIsEvidence() {
        assertEquals(listOf(clean, clean, differs), results(listOf(enclave("kms", "aa"), enclave("kms", "aa"), enclave("other", "aa"))))
        assertEquals(listOf(clean, differs), results(listOf(enclave("kms", "aa"), enclave("kms", "bb"))))
    }

    @Test fun aSplitListMarksEveryEnclaveUnlikeTheFirst() {
        assertEquals(listOf(clean, differs, differs), results(listOf(enclave("a", "k"), enclave("b", "k"), enclave("b", "k"))))
        assertEquals(listOf(clean, clean, differs, differs), results(listOf(enclave("a", "k"), enclave("a", "k"), enclave("b", "k"), enclave("b", "k"))))
    }

    @Test fun anEnclaveMissingTheKeyServiceTheOthersNameIsEvidence() {
        assertEquals(listOf(clean, differs), results(listOf(enclave("kms", "aa"), enclave(null, "aa"))))
        assertEquals(listOf(clean, differs), results(listOf(enclave("kms", "aa"), enclave("kms", null))))
        assertEquals(listOf(clean, differs, differs), results(listOf(enclave(null, null), enclave("kms", "aa"), enclave("kms", "aa"))))
    }

    @Test fun noKeyServiceOnAnyEnclaveIsNothingToCompare() {
        assertEquals(listOf(clean, clean, clean), results(listOf(enclave(null, null), enclave(null, null), enclave(null, null))))
    }

    @Test fun anEnclaveAlreadyBrokenKeepsItsReason() {
        val mrConfig = ModelCandidates.Result.Mismatch(ModelCandidates.Why.MR_CONFIG)
        assertEquals(listOf(clean, mrConfig), results(listOf(enclave("a", "k"), enclave("b", "k", mrConfig))))
    }

    /** Through the list as served: the first enclave's mr-kms event rewritten, the other two no longer agree with it. */
    @Test fun aLiveListWhoseFirstEnclaveNamesAnotherKmsMarksTheRest() {
        val c = LiveCandidates.load()
        val list = c.list.toMutableList()
        list[0] = LiveCandidates.editTcb(list[0]) { tcb ->
            val events = tcb.getValue("event_log").jsonArray.map { it.jsonObject }.toMutableList()
            val i = events.indexOfLast { it["event"]?.jsonPrimitive?.content == "mr-kms" }
            events[i] = JsonObject(events[i] + ("event_payload" to JsonPrimitive("00".repeat(32))))
            tcb["event_log"] = JsonArray(events)
        }
        val body = JsonObject(mapOf("model_attestations" to JsonArray(list))).toString().toByteArray()
        val listed = ModelCandidates.enclaves(body, c.nonce, c.key)!!
        assertEquals("00".repeat(32), listed.enclaves[0].mrKMS)
        assertEquals(differs, listed.enclaves[1].result)
        assertEquals(differs, listed.enclaves[2].result)
    }

    companion object {
        fun listedFor(results: List<ModelCandidates.Result>) = ModelCandidates.Listed(results.mapIndexed { i, r ->
            ModelCandidates.Enclave("i$i", "c$i", "a", null, null, r) })
    }

    @Test fun noListIsNoRecord() {
        assertEquals(null, ModelCandidates.enclaves("{}".toByteArray(), "00".repeat(32), ByteArray(32)))
    }
}

/** The gate: a verified near.ai record with one change, the listed enclaves (SendGateRuleTest's baseline, as iOS's). */
class ModelCandidatesGateTest {
    private fun listed(results: List<ModelCandidates.Result>) = ModelCandidatesTest.listedFor(results)

    private fun session(candidates: ModelCandidates.Listed?, listFailed: Boolean = false) =
        SendGateRuleTest().also { it.offline() }.baselineSession(AttestationRecord.preview.copy(modelCandidates = candidates, modelCandidatesListFailed = listFailed))

    private val clean = ModelCandidates.Result.Clean
    private fun bad(w: ModelCandidates.Why) = ModelCandidates.Result.Mismatch(w)

    @Test fun everyCleanEnclaveAllows() {
        assertEquals(TrustSendPolicy.ALLOW, session(listed(listOf(clean, clean, clean))).sendPolicy)
    }

    @Test fun oneBrokenEnclaveBlocks() {
        val s = session(listed(listOf(clean, bad(ModelCandidates.Why.MR_CONFIG), clean)))
        assertTrue(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
    }

    /** Audit 1.1.1 A12 (iOS 976543da; card trust-copy-sweep-111 v4): an enclave near.ai lists with another key keeps blocking, and says so. */
    @Test fun anEnclaveWithAnotherKeyBlocksAndSaysSo() {
        val s = session(listed(listOf(clean, bad(ModelCandidates.Why.KEY_DIFFERS))))
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("near.ai lists an enclave for this model that holds a different key.", s.e2eeDegradedReason)
    }

    /** Audit 1.1.1 A10: each check a listed enclave fails on its own names the listed enclave, never the read one. */
    @Test fun aListedEnclavesOwnCheckNamesTheListedEnclave() {
        for (why in listOf(ModelCandidates.Why.QUOTE_INVALID, ModelCandidates.Why.KEY_NOT_IN_QUOTE, ModelCandidates.Why.NONCE,
            ModelCandidates.Why.QUOTE_POLICY, ModelCandidates.Why.MR_CONFIG)) {
            val s = session(listed(listOf(clean, bad(why))))
            assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
            assertEquals("$why", "another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
        }
    }

    /** Audit 1.1.1 A10: a listed enclave's DCAP rejection names the listed enclave; the read enclave's keeps its line. */
    @Test fun aListedEnclavesDcapFailureNamesTheListedEnclave() {
        val s = session(listed(listOf(clean, clean)))
        val ok = DcapVerification.Verified(DcapTcbStatus.UP_TO_DATE, "", "")
        s.dcapVerification = s.dcapVerification!!.copy(listed = listOf(ok, DcapVerification.Failed("signature invalid")))
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("another enclave near.ai lists for this model failed verification.", s.e2eeDegradedReason)
        s.dcapVerification = s.dcapVerification!!.copy(model = DcapVerification.Failed("signature invalid"))
        assertEquals("the hardware quote failed intel dcap verification.", s.e2eeDegradedReason)
    }

    @Test fun anEnclaveWhoseBootLogFailsNamesTheBootLogBanner() {
        val s = session(listed(listOf(bad(ModelCandidates.Why.BOOT_LOG))))
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("another enclave near.ai lists for this model runs code its boot log doesn't match.", s.e2eeDegradedReason)
    }

    /** The list couldn't be fetched: an outage, so the send asks; never blocks, never reads verified. */
    /** Audit 1.1.1 A13: listed enclaves that hold one key but name different key services block like any other mismatch. */
    @Test fun anEnclaveWithAnotherKeyServiceBlocks() {
        val s = session(listed(listOf(clean, bad(ModelCandidates.Why.KEY_PROVIDER_DIFFERS), clean)))
        assertTrue(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.BLOCK, s.sendPolicy)
        assertEquals("near.ai's enclaves for this model name different key services for the same key.", s.e2eeDegradedReason)
    }

    /** The list couldn't be fetched. Audit 1.1.1 A10: the reason names the list, not the model's hardware. */
    @Test fun aListThatNeverCameAsks() {
        val s = session(null, listFailed = true)
        assertFalse(s.degradeIsHardFailure)
        assertEquals(TrustSendPolicy.CONFIRM, s.sendPolicy)
        assertEquals("teemoon couldn't load near.ai's list of enclaves for this model.", s.e2eeDegradedReason)
    }
}

/**
 * The list is asked only once the model leg yields a key (iOS AttestationService.fetch: "a leg that gave nothing costs no
 * extra request"; AttestationLegRetryTaxonomyTests pins the blackout). Same gateway host as every other attestation fetch.
 */
class CandidateListFetchTest {
    private val base = "https://cloud-api.near.ai/v1"
    private val model = "zai-org/GLM-5.1-FP8"
    private val direct: suspend (String) -> String? = { "https://glm-5-1.completions.near.ai/v1" }
    private val keyReport = """{"signing_public_key":"${"ab".repeat(32)}","info":{"os_image_hash":"x"}}"""

    /** Requests the gateway host saw for [model] with ed25519: the model leg's own gateway source, plus the list when one is asked. */
    private fun gatewayListRequests(requests: List<String>) = requests.filter { it.startsWith("$base/attestation/report?") && "model=" in it && "signing_algo=ed25519" in it }

    @Test fun theListIsAskedOnTheGatewayOnceAKeyExists() = runTest {
        val live = LiveCandidates.load()
        val requests = ArrayList<String>()
        val http: AttestationHttp = { url, _ ->
            synchronized(requests) { requests += url }
            when { gatewayListRequests(listOf(url)).isNotEmpty() -> 200 to live.body; "completions.near.ai" in url -> 200 to keyReport.encodeToByteArray(); else -> 503 to ByteArray(0) }
        }
        val record = AttestationService.fetch(base, "sk", model, "id", null, http = http, directBase = direct)
        assertEquals(2, gatewayListRequests(requests).size)   // the model leg's gateway source, and the list
        val nonces = gatewayListRequests(requests).map { Regex("nonce=([0-9a-f]+)").find(it)!!.groupValues[1] }
        assertEquals("a fresh nonce for the list", 2, nonces.toSet().size)
        assertEquals(3, record.modelCandidates?.enclaves?.size)
        assertFalse(record.modelCandidatesListFailed)
        // the live key is not this test's key, and our nonce is not the capture's: both are evidence, the gate would block
        assertTrue(record.candidatesBroken)
    }

    /** Audit 1.1.1 A15 (iOS bf762777 theListIsCheckedAgainstTheNonceItWasAskedWith): the list fetch() adopts is checked against the nonce fetch() asked it with. */
    @Test fun theListIsCheckedAgainstTheNonceItWasAskedWith() = runTest {
        val live = LiveCandidates.load()
        val requests = ArrayList<String>()
        val http: AttestationHttp = { url, _ ->
            synchronized(requests) { requests += url }
            when { gatewayListRequests(listOf(url)).isNotEmpty() -> 200 to live.body; "completions.near.ai" in url -> 200 to keyReport.encodeToByteArray(); else -> 503 to ByteArray(0) }
        }
        val record = AttestationService.fetch(base, "sk", model, "id", null, http = http, directBase = direct)
        val listed = record.modelCandidates!!
        assertFalse(record.modelCandidatesListFailed)
        val nonce = listed.nonce!!
        assertTrue("not the capture's nonce", nonce != live.nonce)
        assertTrue("a nonce some request carried", requests.any { "nonce=$nonce" in it })
    }

    @Test fun noKeyCostsNoExtraRequest() = runTest {
        val requests = ArrayList<String>()
        val http: AttestationHttp = { url, _ ->
            synchronized(requests) { requests += url }
            if (url.contains("/attestation/report?signing_algo=ecdsa")) 200 to """{"gateway_attestation":{"signing_address":"0xg"}}""".encodeToByteArray() else 404 to ByteArray(0)
        }
        val record = AttestationService.fetch(base, "sk", model, "id", null, http = http, directBase = direct)
        assertEquals("only the model leg's own gateway source", 1, gatewayListRequests(requests).size)
        assertEquals(null, record.modelCandidates)
        assertFalse("nothing asked, nothing failed", record.modelCandidatesListFailed)
    }

    @Test fun aListThatNeverComesIsAnOutageNotAVerdict() = runTest {
        val http: AttestationHttp = { url, _ ->
            when { gatewayListRequests(listOf(url)).isNotEmpty() -> 404 to ByteArray(0); "completions.near.ai" in url -> 200 to keyReport.encodeToByteArray(); else -> 503 to ByteArray(0) }
        }
        val record = AttestationService.fetch(base, "sk", model, "id", null, http = http, directBase = direct)
        assertEquals(null, record.modelCandidates)
        assertTrue(record.modelCandidatesListFailed)
        assertFalse(record.candidatesBroken)
    }

    // the request itself (iOS CandidateListFetchTests @ 86c7c6ce, A15 pins @ bf762777); 86c7c6ce's caps and cancellation are not part of this commit

    private fun body(entries: Int) = JsonObject(mapOf("model_attestations" to JsonArray(List(entries) { JsonObject(mapOf("signing_public_key" to JsonPrimitive("ab"))) }))).toString().toByteArray()

    @Test fun theRequestCarriesTheModelTheKeyAndTheNonceItReturns() = runTest {
        val seen = ArrayList<Pair<String, Map<String, String>>>()
        val got = AttestationService.fetchCandidateList(base, "z-ai/glm-5.3-flash", "k") { url, headers -> seen += url to headers; 200 to body(3) }!!
        assertEquals(1, seen.size)
        val (url, headers) = seen[0]
        assertTrue(url, url.startsWith("$base/attestation/report?"))
        assertTrue(url, "model=z-ai%2Fglm-5.3-flash" in url)
        assertTrue(url, "signing_algo=ed25519" in url)
        assertTrue("the nonce checked is the nonce sent: $url", "nonce=${got.second}" in url)
        assertEquals("Bearer k", headers["Authorization"])
    }

    @Test fun aServerErrorIsRetriedAndA4xxIsNot() = runTest {
        var flaky = 0
        assertTrue(AttestationService.fetchCandidateList(base, "m", "k") { _, _ -> if (++flaky == 1) 503 to ByteArray(0) else 200 to body(1) } != null)
        assertEquals(2, flaky)
        var refused = 0
        assertEquals(null, AttestationService.fetchCandidateList(base, "m", "k") { _, _ -> refused++; 403 to ByteArray(0) })
        assertEquals(1, refused)
        assertEquals(null, AttestationService.fetchCandidateList(base, "m", "k") { _, _ -> 200 to body(0) })
    }

    /** Audit 1.1.1 A15: a refusal is final even when its body reads as a list; a thrown transport error is retried. */
    @Test fun aRefusalCarryingAListIsNotAdoptedAndATransportErrorIsRetried() = runTest {
        var refused = 0
        assertEquals(null, AttestationService.fetchCandidateList(base, "m", "k") { _, _ -> refused++; 403 to body(3) })
        assertEquals(1, refused)
        var dropped = 0
        assertTrue(AttestationService.fetchCandidateList(base, "m", "k") { _, _ -> if (++dropped == 1) throw java.io.IOException("connection lost"); 200 to body(1) } != null)
        assertEquals(2, dropped)
    }
}
