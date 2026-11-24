// ports: teemoon/Confidential/ModelCandidates.swift @ 976543da (1.1.1 audit A10/A12/A13, card trust-copy-sweep-111 v4: candidatesReason reads ListedEnclaveReason)
// ports: teemoon/Confidential/AttestationRecord.swift @ v1.0.6 (the data model, its bindings, and the
//        two preview records; the fetcher is AttestationService.kt)
//        teemoon/Confidential/ModelCandidates.swift @ bf762777 (candidatesReason's copy-card marker)
//        teemoon/Confidential/AttestationRecord.swift @ 7814a0fd (1.1.1 C3a: modelCandidates, modelCandidatesListFailed, candidatesBroken, candidatesReason)
package ai.teemoon.confidential

import java.security.MessageDigest
import java.time.Instant

/**
 * Cryptographic attestation data returned by the near.ai attestation endpoint:
 * what one fetch produced, plus the pass/fail bindings derived from it.
 * Interpretation for the sheet lives in [AttestationSummary].
 */
data class AttestationRecord(
    /** Docker Compose hash pinning the gateway service images (gateway_attestation.info.compose_hash). */
    val composeHash: String,
    /** Intel TDX MRTD measurement of the gateway TEE (gateway_attestation.info.tcb_info.mrtd). */
    val mrtd: String,
    /** OS image hash of the GATEWAY node (gateway_attestation.info.os_image_hash). */
    val osImageHash: String,
    /** Full Intel TDX quote hex from the gateway TEE (gateway_attestation.intel_quote). */
    val intelQuote: String,
    /** Raw docker-compose.yaml manifest from the gateway TEE (tcb_info.app_compose). */
    val composeManifest: String?,
    /** GPU architecture string from the model TEE (nvidia_payload.arch), e.g. "HOPPER". */
    val gpuArch: String?,
    /** TEE-measured compose hash of the GPU inference node (info.compose_hash). */
    val gpuNodeComposeHash: String?,
    /** SHA256 of the model-specific compose YAML file (compose_manager_attestation.actions[last].file_sha256). */
    val modelFileHash: String?,
    /** Ethereum-style address of the gateway TEE signing key. Used when inference goes through the gateway router. */
    val signingAddress: String?,
    /** Ethereum-style address of the GPU node TEE signing key. Used when inference bypasses the gateway. */
    val gpuSigningAddress: String?,
    /** Model's Ed25519 public key (32 bytes) for E2EE encryption. */
    val modelEd25519PubKey: ByteArray?,
    /** On-device TDX quote verification for the gateway (null if not yet checked or quote was empty). */
    val quoteVerification: TdxVerificationResult?,
    /** On-device TDX quote verification for the GPU node (null if no GPU node or quote unavailable). */
    val gpuQuoteVerification: TdxVerificationResult?,
    /**
     * On-device TDX quote verification for the model TEE (model_attestations[0].intel_quote).
     * This is the strongest E2EE guarantee: the Ed25519 key is bound to a verified TDX quote.
     */
    val modelQuoteVerification: TdxVerificationResult?,
    /** When this record was fetched from the endpoint. */
    val fetchedAt: Instant,
    /** Provider id this was fetched for — used for stale detection. */
    val providerID: String,
    /**
     * OS image hash of the MODEL node — the guest image the enclave that actually
     * touches your plaintext boots on. Distinct from [osImageHash]. Ephemeral.
     */
    val modelOSImageHash: String? = null,
    /** Full Intel TDX quote hex from the model TEE, retained raw so real DCAP verification can run on it. */
    val modelIntelQuote: String? = null,
    /** Full Intel TDX quote hex from the direct GPU node, retained raw for DCAP. */
    val gpuIntelQuote: String? = null,
    /** Random nonces teemoon sent with each attestation request; each quote must echo its nonce in report_data[32..64]. */
    val gatewayNonce: String? = null,
    val modelNonce: String? = null,
    val gpuNonce: String? = null,
    /** Raw `nvidia_payload` JSON from the model attestation, retained for NRAS verification. */
    val nvidiaPayload: String? = null,
    /** The report_data each leg's JSON advertises beside its quote; DcapService fails a quote whose own differs (S37 B2). */
    val gatewayAdvertisedReportData: String? = null,
    val modelAdvertisedReportData: String? = null,
    val gpuAdvertisedReportData: String? = null,
    /** Every enclave near.ai listed for the model and each one's result (C3a); null when no list was read. */
    val modelCandidates: ModelCandidates.Listed? = null,
    /** The list was asked for and never came: a couldn't-check, never "verified". */
    val modelCandidatesListFailed: Boolean = false,
    /** The model leg's measured compose (its info.tcb_info.app_compose), read from the attestation its quote came from (S37 B3). */
    val modelComposeManifest: String? = null,
    /** Raw docker-compose.yaml manifest the GPU node measured at boot — the model enclave's own container set. */
    val gpuNodeComposeManifest: String? = null,
    /** Repo-relative path, git commit, and tag of the model compose YAML, from the same compose-manager action. */
    val modelComposePath: String? = null,
    val modelComposeCommit: String? = null,
    val modelComposeTag: String? = null,
    /** The model node's host the report came from: the chat path is resolved for this host (iOS b3d90fac). */
    val gpuNodeHost: String? = null,
    /**
     * ISO8601 times of the newest and previous engine-layer `compose_up`, read
     * from compose-manager's signed action log (the operator's clock, inside the
     * signed log — label as log data, never as proven time). Never persisted.
     */
    val modelDeployedAt: String? = null,
    val modelPreviouslyDeployedAt: String? = null,
    /**
     * The model id this record was fetched FOR. The session's read gate refuses
     * to expose a record whose model doesn't match the active provider — making
     * stale-model display structurally impossible.
     */
    val model: String? = null,    /** The GPU node's report as it answered, for the trust scope's checks over its action log and boot log (NearTrustScope). */
    val gpuNodeReport: kotlinx.serialization.json.JsonObject? = null,
) {
    /**
     * One expert-sheet caption line for the model enclave's deployment recency:
     * "config deployed 2026-07-16 · previous 2026-07-12 · per the signed action
     * log". Dates only. null when the action log carried no timestamps.
     */
    val deploymentCaption: String? get() = deploymentCaption(signed = true)

    /** The same, saying "signed" only once (a) has bound the log to the hardware (near-trust-scope, the maintainer 2026-11-06). */
    fun deploymentCaption(signed: Boolean): String? {
        val latest = modelDeployedAt?.takeIf { it.length >= 10 } ?: return null
        var line = "config deployed ${latest.take(10)}"
        modelPreviouslyDeployedAt?.takeIf { it.length >= 10 }?.let { line += " · previous ${it.take(10)}" }
        return "$line · per the ${if (signed) "signed " else ""}action log"
    }

    /**
     * Whether the gateway signing address is cryptographically bound to the TDX quote's report_data.
     *
     * Canonical layout: the 20-byte Ethereum signing address occupies the first
     * 20 bytes of report_data — the same layout address *extraction* uses
     * elsewhere. Previously this check compared 32 zero-padded bytes, which
     * silently also asserted that bytes 20..<32 are zero — a different, stricter
     * layout than extraction assumed.
     */
    val signingKeyBoundToHardware: Boolean?
        get() {
            val qv = quoteVerification ?: return null
            val addr = signingAddress?.takeIf { it.isNotEmpty() } ?: return null
            val reportData = qv.measurements.reportData
            val addrBytes = addr.removePrefix("0x").hexToBytesOrNull()
            if (addrBytes == null || addrBytes.size != 20 || reportData.size < 20) return false
            return reportData.copyOfRange(0, 20).contentEquals(addrBytes)
        }

    /** C3a: a listed enclave failed a check. Evidence wherever it is read. (The listed enclaves only: see [ModelCandidates].) */
    val candidatesBroken: Boolean get() = modelCandidates?.enclaves?.any { it.result != ModelCandidates.Result.Clean } == true

    /** The worst listed enclave's reason. Never the read enclave's lines: the reader must learn it was another enclave (1.1.1 audit A10/A12/A13, iOS 976543da). */
    val candidatesReason: String?
        get() {
            val bad = modelCandidates?.enclaves?.firstOrNull { it.result != ModelCandidates.Result.Clean }?.result as? ModelCandidates.Result.Mismatch ?: return null
            return when (bad.why) {
                ModelCandidates.Why.KEY_DIFFERS -> ListedEnclaveReason.OTHER_KEY
                ModelCandidates.Why.KEY_PROVIDER_DIFFERS -> ListedEnclaveReason.OTHER_KEY_SERVICE
                ModelCandidates.Why.BOOT_LOG -> ListedEnclaveReason.BOOT_LOG
                else -> ListedEnclaveReason.ENCLAVE_FAILED
            }
        }

    /**
     * Same binding for the model node's ECDSA address against the GPU-leg quote (iOS 97b2ca9).
     * The report's `signing_address` JSON field alone proves nothing. Nor does an address in a quote that did not
     * verify: report_data is only as good as the quote's own signature and PCK chain, so a reply signed by that key read
     * "verified" on a forged GPU quote (1.0.6 audit INFO; the same rule as [e2eeKeyBoundToModelTEE]'s T6, and iOS's
     * red test cdfaccde on near-import-s7-lows).
     */
    val gpuSigningKeyBoundToHardware: Boolean?
        get() {
            val qv = gpuQuoteVerification ?: return null
            val addr = gpuSigningAddress?.takeIf { it.isNotEmpty() } ?: return null
            val reportData = qv.measurements.reportData
            val addrBytes = addr.removePrefix("0x").hexToBytesOrNull()
            if (addrBytes == null || addrBytes.size != 20 || reportData.size < 20) return false
            return reportData.copyOfRange(0, 20).contentEquals(addrBytes) && qv.isVerified
        }

    /**
     * Whether the E2EE Ed25519 key is cryptographically bound to the model TEE's TDX quote: the key is in the first
     * 32 bytes of report_data AND the quote verified. A key in a quote that didn't verify is not bound (T6,
     * trust-quote-failed, the maintainer 2026-11-06; iOS e2eeKeyBoundToModelTEE with its T6 change).
     */
    val e2eeKeyBoundToModelTEE: Boolean?
        get() {
            val inReportData = e2eeKeyInModelReportData ?: return null
            return inReportData && modelQuoteVerification?.isVerified == true
        }

    /** Only that the key bytes appear in the model quote's report_data, whether or not the quote verified. */
    val e2eeKeyInModelReportData: Boolean?
        get() {
            val mqv = modelQuoteVerification ?: return null
            val key = modelEd25519PubKey?.takeIf { it.size == 32 } ?: return null
            val reportData = mqv.measurements.reportData
            if (reportData.size < 32) return false
            return reportData.copyOfRange(0, 32).contentEquals(key)
        }

    /**
     * Whether the code the gateway enclave actually booted matches the manifest
     * we were shown — i.e. the "running code" binding.
     *
     * Two links, both required:
     *  1. `SHA256(app_compose) == compose_hash` — the displayed manifest hashes
     *     to the reported compose hash (manifest integrity).
     *  2. `mr_config == "01" ‖ compose_hash` — the gateway quote's MRCONFIGID
     *     register commits to that hash, proving the enclave booted *this*
     *     manifest and not a substituted one (dstack tags MRCONFIGID with a
     *     leading `01` version byte; near.ai's own verifier checks the same).
     *
     * null when the inputs aren't present. This binding is only as strong as
     * the quote it reads MRCONFIGID from — full strength requires real DCAP
     * quote verification. It does NOT verify that the manifest's image digests
     * came from near.ai's source — that is the separate provenance check.
     */
    /**
     * The model leg's and the GPU node's quotes each commit to their own app_compose (dstack MRCONFIGID; S37 B3, iOS
     * d5e40fee); null when neither has a quote and a compose to compare.
     */
    val modelNodesBootedTheirCompose: Boolean?
        get() {
            fun binds(qv: TdxVerificationResult?, manifest: String?): Boolean? {
                if (qv == null || manifest.isNullOrEmpty()) return null
                return QuoteChecks.mrConfigCommits(qv.measurements.mrConfigId, sha256Hex(manifest))
            }
            val legs = listOfNotNull(binds(modelQuoteVerification, modelComposeManifest), binds(gpuQuoteVerification, gpuNodeComposeManifest))
            return if (legs.isEmpty()) null else legs.all { it }
        }

    val codeIdentityVerified: Boolean?
        get() {
            // every model-node quote commits to the compose it served (S37 B3): never drop the model nodes from this
            if (modelNodesBootedTheirCompose == false) return false
            val manifest = composeManifest?.takeIf { it.isNotEmpty() } ?: return null
            if (composeHash.isEmpty()) return null
            val qv = quoteVerification ?: return null
            val computed = sha256Hex(manifest)
            if (computed != composeHash.lowercase()) return false
            return QuoteChecks.mrConfigCommits(qv.measurements.mrConfigId, composeHash)
        }

    /**
     * Whether every fetched quote echoes the nonce teemoon sent with its
     * attestation request in report_data[32..64] — proving the quote was
     * generated for THIS request, not replayed from an earlier one.
     *
     * false if any checkable pair mismatches; true if at least one pair checked
     * and none mismatched; null when nothing is checkable.
     */
    val nonceEchoed: Boolean?
        get() {
            val checked = listOfNotNull(
                nonceMatches(gatewayNonce, quoteVerification),
                nonceMatches(modelNonce, modelQuoteVerification),
                nonceMatches(gpuNonce, gpuQuoteVerification),
            )
            if (checked.isEmpty()) return null
            return checked.all { it }
        }

    /** Human-readable GPU model name derived from [gpuArch]. */
    val gpuModelName: String?
        get() = when (val arch = gpuArch?.uppercase()) {
            null -> null
            "HOPPER" -> "NVIDIA H100"
            "AMPERE" -> "NVIDIA A100"
            "ADA" -> "NVIDIA L40S"
            "BLACKWELL" -> "NVIDIA B200"
            else -> "NVIDIA " + arch.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }

    companion object {
        private fun nonceMatches(nonce: String?, verification: TdxVerificationResult?): Boolean? {
            if (nonce == null || verification == null) return null
            val reportData = verification.measurements.reportData
            if (reportData.size < 64) return false
            return reportData.copyOfRange(32, 64).toHex() == nonce.lowercase()
        }

        internal fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        // MARK: - Mock TDX data for previews

        /** A mock verification with the given report_data and verification flags. */
        private fun mockVerification(
            reportData: ByteArray,
            mrConfigId: ByteArray = ByteArray(48),
            signatureValid: Boolean = true,
            certChainValid: Boolean = true,
            certChainError: String? = null,
        ) = TdxVerificationResult(TdxMeasurements.stub(reportData, mrConfigId), signatureValid, certChainValid, certChainError)

        /** Report_data with the signing address in the first 20 bytes, right-padded to 64. */
        private fun reportDataWithAddress(hex: String): ByteArray {
            val data = ByteArray(64)
            hex.removePrefix("0x").hexToBytesOrNull()?.let { it.copyInto(data, 0, 0, minOf(it.size, 32)) }
            return data
        }

        /** Report_data with a 32-byte key in the first 32 bytes. */
        private fun reportDataWithKey(key: ByteArray): ByteArray {
            val data = ByteArray(64)
            key.copyInto(data, 0, 0, minOf(key.size, 32))
            return data
        }

        private const val PREVIEW_SIGNING_ADDRESS = "0x4a8b3f2e9d1c7a5e2b0f8d3c6e1a4b7f2e9d1c7a"
        private const val NEAR_AI_PRESET_ID = "A0000000-0000-0000-0000-000000000001"

        // MARK: - Preview records

        /** Preview record without Ed25519 key — simulates degraded (no E2EE) state. */
        val previewDegraded: AttestationRecord by lazy {
            AttestationRecord(
                composeHash = "3a5b9747bc6731c5de6c6d6740c3c37cc4522a935b1655e2b757d81089df31f0",
                mrtd = "f06dfda6dce1cf904d4e2bab1dc370634cf95cefa2ceb2de2eee127c9382698090d7a4a13e14c536",
                osImageHash = "da9a3d5cc196a1a76d953fb27069be428ddf60a1ce10b0534c3cf968d3053fde",
                modelOSImageHash = "9b69bb1698bacbb6985409a2c272bcb892e09cdcea63d5399c6768b67d3ff677",
                intelQuote = "04020000000000000a0013000000000043414c00000000005c5c9eb4e7ff3b2a",
                composeManifest = null,
                gpuArch = "HOPPER",
                gpuNodeComposeHash = "242a62724303cc32f364da0fc92738706b0078e7587821b7ba3e75488223797b",
                modelFileHash = "ae5fa3a8ee2e826bf2a089dadda7270032dd358b8c2af67844e143951baeee5e",
                signingAddress = PREVIEW_SIGNING_ADDRESS,
                gpuSigningAddress = "0x614bc66ff0407dbb70b9c7ca1f5e983e4a02c921",
                modelEd25519PubKey = null,
                quoteVerification = mockVerification(reportDataWithAddress(PREVIEW_SIGNING_ADDRESS)),
                gpuQuoteVerification = null,   // GPU unreachable
                modelQuoteVerification = null, // No model attestation without E2EE
                fetchedAt = Instant.now(),
                providerID = NEAR_AI_PRESET_ID,
                model = "zai-org/GLM-5.1-FP8",
            )
        }

        val preview: AttestationRecord by lazy {
            val e2eeKey = ByteArray(32) { 0xAB.toByte() }
            // Manifest, its hash, and the matching MRCONFIGID (0x01 ‖ hash ‖ pad),
            // computed together so the code-identity check binds in previews.
            val manifest = "services:\n  cloud-api:\n    image: nearaidev/cloud-api@sha256:ac8a539ce1ac9ae3ebd0dfcffdba18effaaedfa69685fce220e3b3a35357e326\n  dstack-service-mesh:\n    image: nearaidev/dstack-vpc@sha256:03bd4b222d07059af91fc3d2aa851026cecbecf719ba7423616058272db57b2c\n    ports:\n      - \"443:443\"\n  ingress:\n    image: nearaidev/cvm-ingress@sha256:2d5d9d4fe317ae6f04fe8a46f61fecfadc14bb75dceca06ae51dc2e9621fc34d"
            val manifestHash = sha256Hex(manifest)
            val mrConfig = ("01$manifestHash").hexToBytes() + ByteArray(15)
            // Model-node OUTER app_compose: production shape — the management
            // harness only (compose-manager, launcher, telemetry). It carries NO
            // plaintext-toucher signals; the engine + E2EE proxy live in the INNER
            // model-layer compose. Planting engine services here would mask the
            // wrong-document bug PlaintextExposure fixed.
            val modelCompose = """
                services:
                  compose-manager:
                    image: nearaidev/compose-manager@sha256:b487f391aabbccddeeff001122334455667788990011223344556677889900aa
                    pid: host
                  launcher:
                    image: nearaidev/compose-manager-launcher@sha256:6e035c8faabbccddeeff001122334455667788990011223344556677889900bb
                  datadog:
                    image: datadog/agent:7.55.0
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock:ro
                      - /var/lib/docker/containers:/var/lib/docker/containers:ro
                  otelcol:
                    image: otel/opentelemetry-collector-contrib:0.98.0
                    volumes:
                      - /var/lib/docker/containers:/var/lib/docker/containers:ro
            """.trimIndent()
            AttestationRecord(
                composeHash = manifestHash,
                mrtd = "f06dfda6dce1cf904d4e2bab1dc370634cf95cefa2ceb2de2eee127c9382698090d7a4a13e14c536",
                osImageHash = "da9a3d5cc196a1a76d953fb27069be428ddf60a1ce10b0534c3cf968d3053fde",
                modelOSImageHash = "9b69bb1698bacbb6985409a2c272bcb892e09cdcea63d5399c6768b67d3ff677",
                intelQuote = "04020000000000000a0013000000000043414c00000000005c5c9eb4e7ff3b2a",
                composeManifest = manifest,
                gpuArch = "HOPPER",
                gpuNodeComposeHash = "242a62724303cc32f364da0fc92738706b0078e7587821b7ba3e75488223797b",
                gpuNodeComposeManifest = modelCompose,
                modelFileHash = "ae5fa3a8ee2e826bf2a089dadda7270032dd358b8c2af67844e143951baeee5e",
                modelComposePath = "prod/GLM-5.1-SGL-AWQ-TP4.yaml",
                modelComposeCommit = "c545c95545dba47d8bea293aaae317089ea52f4d",
                modelComposeTag = "v0.0.296",
                modelDeployedAt = "2026-07-16T22:16:29.773189510+00:00",
                modelPreviouslyDeployedAt = "2026-07-12T09:41:03.000000000+00:00",
                signingAddress = PREVIEW_SIGNING_ADDRESS,
                gpuSigningAddress = "0x614bc66ff0407dbb70b9c7ca1f5e983e4a02c921",
                modelEd25519PubKey = e2eeKey,
                quoteVerification = mockVerification(reportDataWithAddress(PREVIEW_SIGNING_ADDRESS), mrConfig),
                gpuQuoteVerification = null,  // GPU unreachable — shows as gray dash
                modelQuoteVerification = mockVerification(reportDataWithKey(e2eeKey)),
                fetchedAt = Instant.now(),
                providerID = NEAR_AI_PRESET_ID,
                model = "zai-org/GLM-5.1-FP8",
            )
        }
    }
}

/** The GPU node's compose-manager quote, when its report carries the action log's attestation. */
val AttestationRecord.managerQuote: String?
    get() = ((gpuNodeReport?.get("compose_manager_attestation") as? kotlinx.serialization.json.JsonObject)?.get("quote") as? kotlinx.serialization.json.JsonPrimitive)?.content
