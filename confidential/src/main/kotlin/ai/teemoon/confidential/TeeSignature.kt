// ports: teemoon/Confidential/TEESignatureVerifier.swift @ v1.0.1 (SignedExchange, ContentBinding, TEEMessageSignature,
//        and the verifier's decision over injected fetch seams — TEEContext and AttestationService.fetchSigningAddress
//        are the AttestationService row)
package ai.teemoon.confidential

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlinx.coroutines.delay

/** The request/response content a signature is supposed to cover, captured by GenerationEngine as the bytes actually sent and received. */
data class SignedExchange(
    /** Candidate request bodies: the bytes put on the wire, plus (under E2EE) the plaintext body — the signer may hash either. */
    val requestBodyCandidates: List<ByteArray>,
    /** Raw response text: streaming = each received line re-joined with "\n" (including blank SSE separators); non-streaming = the whole raw body. */
    val responseText: String,
)

/** The outcome of comparing a signature's signed hashes against the exchange teemoon observed. */
data class ContentBinding(val requestHashMatches: Boolean, val responseHashMatches: Boolean) {
    val isBound: Boolean get() = requestHashMatches && responseHashMatches

    companion object {
        /** Parses `text` ("req_sha256:resp_sha256", optionally prefixed with the model name) and recomputes both hashes. null when the text does not have the expected shape. */
        fun check(text: String, exchange: SignedExchange): ContentBinding? {
            val parts = text.split(":")
            if (parts.size != 2 && parts.size != 3) return null
            val serverRequestHash = parts[parts.size - 2].lowercase(); val serverResponseHash = parts[parts.size - 1].lowercase()
            val requestHashes = exchange.requestBodyCandidates.map { sha256Hex(it) }
            val responseHash = sha256Hex(exchange.responseText.toByteArray(Charsets.UTF_8))
            return ContentBinding(serverRequestHash in requestHashes, responseHash == serverResponseHash)
        }
        private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    }
}

@Serializable
data class TEEMessageSignature(
    /** "sha256_of_request:sha256_of_response" */
    val text: String,
    /** ECDSA signature hex (0x-prefixed) */
    val signature: String,
    @SerialName("signing_address") val signingAddress: String,
    @SerialName("signing_algo") val signingAlgo: String,
)

/**
 * Verifies per-response ECDSA signatures from the near.ai TEE. All three are
 * required for [ResponseVerification.Verified]: content binding, ecrecover of
 * the signer from the EIP-191 signature (never the server-asserted address),
 * and a match against an attested signing address.
 *
 * The network is injected: [fetchSignature] answers `GET /v1/signature/{id}`
 * with (status, body); [refetchSigningAddress] re-reads a node's attestation
 * when the key may have rotated.
 */
object TEESignatureVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verify(
        chatID: String,
        attestedSigningAddress: String?,
        attestedGpuSigningAddress: String?,
        gatewayQuoteVerified: Boolean,
        exchange: SignedExchange?,
        fetchSignature: suspend (chatID: String) -> Pair<Int, ByteArray>,
        refetchSigningAddress: suspend () -> List<String?> = { emptyList() },
        sleepMs: suspend (Long) -> Unit = { delay(it) },
    ): ResponseVerification {
        // The gateway proxies completions to a GPU node TEE; either may sign. The GPU node may sign even when its attestation wasn't fetched.
        val trusted = listOfNotNull(attestedSigningAddress, attestedGpuSigningAddress).filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
        if (trusted.isEmpty()) return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureUnavailable)

        // Wait 2s — the TEE stores signatures asynchronously after stream completion. Two attempts: immediately, then after 4s.
        sleepMs(2_000)
        for (attempt in 1..2) {
            try {
                val (status, data) = fetchSignature(chatID)
                if (status == 404) { if (attempt == 1) sleepMs(4_000); continue }
                val sig = json.decodeFromString(TEEMessageSignature.serializer(), String(data, Charsets.UTF_8))

                // Content binding: decisive regardless of who signed — the signature covers different content than this exchange.
                if (exchange != null) {
                    val binding = ContentBinding.check(sig.text, exchange)
                        ?: return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.ContentMismatch("unrecognized signed-text format"))
                    if (!binding.isBound) {
                        val what = if (binding.requestHashMatches) "response" else if (binding.responseHashMatches) "request" else "request and response"
                        return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.ContentMismatch("signed $what hash does not match observed content"))
                    }
                }
                // ecrecover: the signer identity comes from the signature itself, not the server-asserted string.
                val got = if (sig.signingAlgo.lowercase() == "ecdsa") {
                    EthereumSignature.recoverAddress(sig.text, sig.signature)
                        ?: return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureMismatch(trusted.joinToString(" or "), "unrecoverable signature"))
                } else sig.signingAddress.lowercase()
                if (got in trusted) return ResponseVerification.Verified(got)
                // Mismatch — the signing key may have rotated. Re-fetch the node's attestation; a fresh match is a confirmed rotation.
                for (fresh in refetchSigningAddress()) if (fresh?.lowercase() == got) return ResponseVerification.Verified(got)
                // The signature came through the attested gateway — if its quote verified, the address it reports is authentic even if the GPU node is not directly attestable.
                if (gatewayQuoteVerified) return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.GatewayTrustOnly(got))
                return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureMismatch(trusted.joinToString(" or "), got))
            } catch (e: Exception) {
                return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.FetchFailed(e))
            }
        }
        return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureUnavailable)
    }
}
