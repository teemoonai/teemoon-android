// ports: teemoon/Confidential/ModelCandidates.swift @ 976543da (1.1.1 audit A10/A12/A13: ListedEnclaveReason, the card's five lines verbatim)
// ports: teemoon/Confidential/ModelCandidates.swift @ c5e329f2 (1.1.1 audit A5: Enclave.osImageHash, trustScopes' host); @ 878ab019 (1.1.1 audit A13: sameKeyService, Why.KEY_PROVIDER_DIFFERS); @ 1fe69e3d (1.1.1 audit A4: Enclave.report, trustScopes); @ ceb05738 (1.1.1 audit A3: Enclave.nvidiaPayload, Listed.nonce); before that @ ba0842d1 (1.1.1 audit A1: each Enclave carries its own quote and advertised report_data
//        for DcapService); before that @ 7814a0fd (SDK track C3a: list, check, enclaves; the AttestationRecord
//        extension's candidatesBroken / candidatesReason are AttestationRecord.kt)
//
// SDK track C3 (docs/NEARAI_SDK_C.md §C3): every enclave near.ai lists for a model (gateway `?model=` →
// `model_attestations[]`) holds the key a sealed request goes to, so each passes the checks the read one passes.
//
// Residual, never to be called closed: the key is derived from app_id, so enclaves near.ai's key service admits but
// doesn't list hold it too. teemoon checks every enclave near.ai LISTED; no comment, doc or screen may say this closes
// the gap (audit, 2026-11-19).
package ai.teemoon.confidential

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object ModelCandidates {
    enum class Why { KEY_DIFFERS, QUOTE_INVALID, KEY_NOT_IN_QUOTE, NONCE, QUOTE_POLICY, MR_CONFIG, BOOT_LOG, KEY_PROVIDER_DIFFERS }

    sealed interface Result {
        data object Clean : Result
        data class Mismatch(val why: Why) : Result
    }

    /** One listed enclave, as the expert view (C4) shows it. [keyProviderID] and [mrKMS] are record-only. */
    data class Enclave(val instanceID: String, val composeHash: String, val appID: String, val keyProviderID: String?, val mrKMS: String?, val result: Result,
        /** The enclave's own quote and the report_data it advertises: DcapService runs them as it runs the model leg's. */
        val quoteHex: String? = null, val advertisedReportData: String? = null,
        /** The enclave's GPU evidence, checked with NRAS against the list's nonce like the model leg's (1.1.1 audit A3). */
        val nvidiaPayload: String? = null,
        /** The entry as served: the trust scope reads it as it reads the model node's report (1.1.1 audit A4). */
        val report: JsonObject? = null,
        /** The guest OS its boot measured: the reviewed row looks its page up as it does the read enclave's (1.1.1 audit A5). */
        val osImageHash: String? = null)

    /** [nonce] is the one the list was asked with: each enclave's quote and GPU evidence must echo it. */
    data class Listed(val enclaves: List<Enclave>, val nonce: String? = null)

    /** `model_attestations[]` from the gateway's `?model=` answer; empty when there is none. */
    fun list(body: ByteArray): List<JsonObject> =
        runCatching { (Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["model_attestations"] as? JsonArray)?.map { it.jsonObject } }.getOrNull() ?: emptyList()

    /**
     * The checks the read enclave always passes, on one listed enclave, no network. DCAP, NRAS and the compose manager per
     * enclave are C3b. Every failure is evidence: the key a sealed request goes to reaches this enclave.
     */
    fun check(c: JsonObject, sealedKey: ByteArray, nonceHex: String): Result {
        val key = hex(c["signing_public_key"])
        if (key == null || !key.contentEquals(sealedKey)) return Result.Mismatch(Why.KEY_DIFFERS)
        val quoteHex = c.str("intel_quote") ?: return Result.Mismatch(Why.QUOTE_INVALID)
        val qv = runCatching { TdxQuoteVerifier.verify(quoteHex) }.getOrNull()?.takeIf { it.isVerified } ?: return Result.Mismatch(Why.QUOTE_INVALID)
        val rd = qv.measurements.reportData
        if (rd.size < 64 || !rd.copyOfRange(0, 32).contentEquals(key)) return Result.Mismatch(Why.KEY_NOT_IN_QUOTE)
        val nonce = hexString(nonceHex)
        if (nonce == null || !rd.copyOfRange(32, 64).contentEquals(nonce)) return Result.Mismatch(Why.NONCE)
        if (QuoteChecks.failure(quoteHex, c.str("report_data")) != null) return Result.Mismatch(Why.QUOTE_POLICY)
        val info = c["info"] as? JsonObject
        val tcb = tcbInfo(info)
        val compose = tcb?.str("app_compose")
        if (compose == null || !QuoteChecks.mrConfigCommits(qv.measurements.mrConfigId, AttestationRecord.sha256Hex(compose))) return Result.Mismatch(Why.MR_CONFIG)
        // the existing replay (NearTrustScope (b)): what is not a replay failure (no log, no node quote) is not evidence here
        if (Rtmr3Replay.check(tcb, quoteHex, info.str("os_image_hash")) is BindingCheck.Failed) return Result.Mismatch(Why.BOOT_LOG)
        // an absent log is not clean: the enclave's trust scope (trustScopes) reads it as couldn't-check (1.1.1 audit A4)
        return Result.Clean
    }

    /** Each listed enclave with its result and what the expert view names it by; null when the answer lists none. */
    fun enclaves(body: ByteArray, nonce: String, sealedKey: ByteArray): Listed? {
        val list = list(body)
        if (list.isEmpty()) return null
        return Listed(sameKeyService(list.map { c ->
            val info = c["info"] as? JsonObject
            val events = (tcbInfo(info)?.get("event_log") as? JsonArray)?.let { Rtmr3Replay.entries(it) } ?: emptyList()
            fun payload(name: String) = events.lastOrNull { it.imr == 3 && it.event == name }?.payload
            val provider = payload("key-provider")?.let { runCatching { Json.parseToJsonElement(String(it, Charsets.UTF_8)).jsonObject }.getOrNull() }
            Enclave(info.str("instance_id").orEmpty(), info.str("compose_hash").orEmpty(), info.str("app_id").orEmpty(),
                provider?.str("id"), payload("mr-kms")?.toHex(), check(c, sealedKey, nonce),
                c.str("intel_quote"), c.str("report_data"), payloadString(c["nvidia_payload"]), c, info.str("os_image_hash"))
        }), nonce)
    }

    /** `nvidia_payload` arrives as a JSON string; an object is re-encoded so NRAS reads the same bytes either way. */
    private fun payloadString(v: JsonElement?): String? = when (v) {
        is JsonPrimitive -> if (v.isString) v.content else null
        is JsonObject, is JsonArray -> v.toString()
        else -> null
    }

    /**
     * One key, one key service (1.1.1 audit A13): every listed enclave holds the sealed key, so the key provider and mr-kms each names
     * must agree. An enclave whose pair differs from the FIRST listed enclave's is evidence (a split list marks every enclave unlike
     * the first); the pair absent on every enclave is nothing to compare; an enclave already failing a check keeps its own reason.
     */
    fun sameKeyService(enclaves: List<Enclave>): List<Enclave> {
        val first = enclaves.firstOrNull() ?: return enclaves
        return enclaves.map { e ->
            if (e.result == Result.Clean && (e.keyProviderID != first.keyProviderID || e.mrKMS != first.mrKMS)) e.copy(result = Result.Mismatch(Why.KEY_PROVIDER_DIFFERS)) else e
        }
    }

    /**
     * The read enclave's trust scope on each listed enclave (iOS ModelCandidates.trustScopes, 1.1.1 audit A4): the entry's own
     * quote and compose manager's quote (their DCAP verdicts are [dcap]'s `listed` and `listedManagers`) and the list's nonce.
     * An entry it can't read is not bound with no boot log: couldn't check, never clean. Running pairs read [fetched]; the chat path resolves
     * against [host], the host the app connects to (a list entry carries none: iOS passes record.gpuNodeHost, A5), from [bodies]: unmatched, it is ambiguous
     * and every LEAKS page counts.
     */
    fun trustScopes(listed: Listed, dcap: RecordDcapVerification, fetched: Map<Pair<String, String>, ComposeFileFetch> = emptyMap(),
                    bodies: Map<DeployLog.Source, String> = emptyMap(), host: String? = null): List<NearTrustScope> =
        listed.enclaves.mapIndexed { i, enclave ->
            val report = enclave.report
            val quote = enclave.quoteHex
            if (report == null || quote.isNullOrEmpty()) NearTrustScope(DeployLogBinding.NotBound(DeployLogBinding.Reason.NO_LOG), BootLogReplay.Absent, null, emptyList(), LauncherIndex.NotChecked)
            else NearTrustScope.fromDcap(report, listed.nonce.orEmpty(), quote, dcap.listed.getOrNull(i), dcap.listedManagers.getOrNull(i), fetched, bodies, host)
        }

    private fun tcbInfo(info: JsonObject?): JsonObject? = when (val t = info?.get("tcb_info")) {
        is JsonObject -> t
        is JsonPrimitive -> t.contentOrNull?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        else -> null
    }

    private fun JsonObject?.str(k: String): String? = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun hex(v: JsonElement?): ByteArray? = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::hexString)
    private fun hexString(s: String): ByteArray? = s.removePrefix("0x").hexToBytesOrNull()
}

/**
 * Card trust-copy-sweep-111 v4 (A10, A12, A13), verbatim: why a send is held over the enclaves near.ai lists
 * (iOS ListedEnclaveReason, 976543da). The read enclave's own lines are NOT these: a reader must learn it was another one.
 */
object ListedEnclaveReason {
    const val LIST_FAILED = "teemoon couldn't load near.ai's list of enclaves for this model."
    const val ENCLAVE_FAILED = "another enclave near.ai lists for this model failed verification."
    const val BOOT_LOG = "another enclave near.ai lists for this model runs code its boot log doesn't match."
    const val OTHER_KEY = "near.ai lists an enclave for this model that holds a different key."
    const val OTHER_KEY_SERVICE = "near.ai's enclaves for this model name different key services for the same key."
}
