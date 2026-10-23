// ports: teemoon/Confidential/TEESignatureVerifier.swift @ 441cecf (SignedExchange, ContentBinding, TEEMessageSignature,
//        SignedText, Ed25519Signature, and the verifier's decision over an injected fetch seam — TEEContext is the
//        AttestationService row)
package ai.teemoon.confidential

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

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
        internal fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    }
}

@Serializable
data class TEEMessageSignature(
    /** provider_tee: "<request model>:<req sha256>:<resp sha256>"; gateway: "<req sha256>:<resp sha256>" */
    val text: String,
    /** ECDSA (0x-prefixed) or Ed25519 signature hex */
    val signature: String,
    @SerialName("signing_address") val signingAddress: String,
    @SerialName("signing_algo") val signingAlgo: String,
    /** Absent on older servers; the SDK reads absent as `provider_tee`. */
    @SerialName("signature_kind") val signatureKind: String? = null,
) {
    enum class Kind(val raw: String) { PROVIDER_TEE("provider_tee"), GATEWAY("gateway"), UNKNOWN("unknown") }

    val kind: Kind get() = (signatureKind ?: "provider_tee").lowercase().let { k -> Kind.entries.firstOrNull { it != Kind.UNKNOWN && it.raw == k } ?: Kind.UNKNOWN }
}

/**
 * The exact text each signature kind must sign (near.ai inference-sdk `core/chat.py`): `provider_tee` =
 * "<request model>:<req sha256>:<resp sha256>", `gateway` = "<req sha256>:<resp sha256>". Compared whole, never split.
 */
object SignedText {
    private val json = Json { ignoreUnknownKeys = true }

    fun expected(kind: TEEMessageSignature.Kind, exchange: SignedExchange): List<String> {
        val resp = ContentBinding.sha256Hex(exchange.responseText.toByteArray(Charsets.UTF_8))
        return exchange.requestBodyCandidates.mapNotNull { body ->
            val req = ContentBinding.sha256Hex(body)
            when (kind) {
                TEEMessageSignature.Kind.GATEWAY -> "$req:$resp"
                TEEMessageSignature.Kind.PROVIDER_TEE -> {
                    val model = runCatching { (json.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject)?.get("model")?.jsonPrimitive?.content }.getOrNull()
                    model?.takeIf { it.isNotEmpty() }?.let { "$it:$req:$resp" }
                }
                TEEMessageSignature.Kind.UNKNOWN -> null
            }
        }
    }

    /** null when [text] is exactly what [kind] must sign for this exchange. */
    fun mismatch(text: String, kind: TEEMessageSignature.Kind, exchange: SignedExchange): String? {
        if (text in expected(kind, exchange)) return null
        val binding = ContentBinding.check(text, exchange) ?: return "unrecognized signed-text format"
        if (binding.isBound) return "signed text has the wrong prefix for a ${kind.raw} signature"
        val what = if (binding.requestHashMatches) "response" else if (binding.responseHashMatches) "request" else "request and response"
        return "signed $what hash does not match observed content"
    }
}

object Ed25519Signature {
    fun isValid(text: String, signatureHex: String, publicKey: ByteArray): Boolean {
        val sig = signatureHex.removePrefix("0x").hexToBytesOrNull()?.takeIf { it.size == 64 } ?: return false
        if (publicKey.size != 32) return false
        return runCatching {
            Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey, 0)); text.toByteArray(Charsets.UTF_8).let { update(it, 0, it.size) } }.verifySignature(sig)
        }.getOrDefault(false)
    }
}

/**
 * Verifies per-response signatures from near.ai (inference-sdk `core/chat.py`):
 * `GET /v1/signature/{id}?model={model}&signing_algo=ed25519|ecdsa` →
 * `{ text, signature, signing_address, signing_algo, signature_kind: "provider_tee" | "gateway" }`.
 *
 * [ResponseVerification.Verified] needs all of: the exact text for the kind (provider_tee carries the request's model
 * prefix, gateway does not); the signer recovered (ecdsa) or checked (ed25519) from the signature itself; and that
 * signer being a model-node key bound to its quote's report_data. A gateway signature is at most
 * [ResponseVerification.UnverifiedReason.GatewayTrustOnly].
 *
 * Before 1.0.6 this returned Verified for any non-ecdsa signature on the server-asserted address, counted the gateway's
 * key as a model signer, and accepted a "key rotation" address read from an unverified report — all three removed.
 *
 * The network is injected: [verify]'s fetchSignature answers the signature GET for one `signing_algo` with (status, body).
 * ed25519 is asked FIRST when the model key is attested: a model-node signature then checks against the key the model
 * quote binds, the same key E2EE seals to, with no extra leg. Live 2026-10-19 (iOS 441cecf, glm-5.3-flash, E2EE): a sealed
 * reply comes back signature_kind=provider_tee; its ecdsa signer is the model node's address, which only the GPU leg
 * reports — on main it passed through the deleted rotation path. A gateway-kind answer is refetched as ecdsa: the
 * gateway's ed25519 key is not attested. Plaintext requests come back kind=gateway.
 */
object TEESignatureVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun verify(
        chatID: String,
        attestation: AttestationRecord,
        exchange: SignedExchange?,
        fetchSignature: suspend (chatID: String, algo: String) -> Pair<Int, ByteArray>,
        sleepMs: suspend (Long) -> Unit = { delay(it) },
    ): ResponseVerification {
        val attestedSigners = listOfNotNull(attestation.signingAddress, attestation.gpuSigningAddress).filter { it.isNotEmpty() }
        if (attestedSigners.isEmpty() && attestation.modelEd25519PubKey == null) return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.SignatureUnavailable)
        exchange ?: return ResponseVerification.Unverified(ResponseVerification.UnverifiedReason.ContentMismatch("no captured exchange to bind"))

        // Wait 2s — the TEE stores signatures asynchronously after stream completion.
        sleepMs(2_000)
        val first = if (attestation.modelEd25519PubKey != null) "ed25519" else "ecdsa"
        var fetched = fetch(chatID, first, fetchSignature, sleepMs)
        if (fetched is Fetched.Success && fetched.sig.kind == TEEMessageSignature.Kind.GATEWAY && fetched.sig.signingAlgo.lowercase() != "ecdsa") {
            fetched = fetch(chatID, "ecdsa", fetchSignature, sleepMs)
        }
        return when (fetched) {
            is Fetched.Success -> evaluate(fetched.sig, attestation, exchange)
            is Fetched.Failure -> fetched.verdict
        }
    }

    private sealed interface Fetched {
        data class Success(val sig: TEEMessageSignature) : Fetched
        data class Failure(val verdict: ResponseVerification) : Fetched
    }

    /** One `signing_algo`. Two attempts: immediately, then after 4s. */
    private suspend fun fetch(chatID: String, algo: String, fetchSignature: suspend (String, String) -> Pair<Int, ByteArray>, sleepMs: suspend (Long) -> Unit): Fetched {
        fun failure(reason: ResponseVerification.UnverifiedReason) = Fetched.Failure(ResponseVerification.Unverified(reason))
        for (attempt in 1..2) {
            try {
                val (status, data) = fetchSignature(chatID, algo)
                if (status == 404) { if (attempt == 1) sleepMs(4_000); continue }
                val text = String(data, Charsets.UTF_8)
                // A 2xx `{error_code, message}` envelope is "not available", not a parse failure.
                val sig = runCatching { json.decodeFromString(TEEMessageSignature.serializer(), text) }.getOrElse { e ->
                    val envelope = runCatching { json.parseToJsonElement(text).jsonObject.containsKey("error_code") }.getOrDefault(false)
                    return if (envelope) failure(ResponseVerification.UnverifiedReason.SignatureUnavailable) else failure(ResponseVerification.UnverifiedReason.FetchFailed(e))
                }
                return Fetched.Success(sig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failure(ResponseVerification.UnverifiedReason.FetchFailed(e))
            }
        }
        return failure(ResponseVerification.UnverifiedReason.SignatureUnavailable)
    }

    /**
     * Pure verdict for one signature. Verified only for a model-node (`provider_tee`) signature whose signer is a key
     * bound to its quote; a gateway signature is never more than GatewayTrustOnly. Never trust `signing_address`
     * without checking the signature against it.
     */
    fun evaluate(sig: TEEMessageSignature, att: AttestationRecord, exchange: SignedExchange): ResponseVerification {
        fun unverified(reason: ResponseVerification.UnverifiedReason) = ResponseVerification.Unverified(reason)
        fun mismatch(expected: String, got: String) = unverified(ResponseVerification.UnverifiedReason.SignatureMismatch(expected, got))
        val kind = sig.kind
        if (kind == TEEMessageSignature.Kind.UNKNOWN) return mismatch("a provider_tee or gateway signature", "signature_kind ${sig.signatureKind.orEmpty()}")
        SignedText.mismatch(sig.text, kind, exchange)?.let { return unverified(ResponseVerification.UnverifiedReason.ContentMismatch(it)) }

        val signer = when (sig.signingAlgo.lowercase()) {
            "ecdsa" -> EthereumSignature.recoverAddress(sig.text, sig.signature) ?: return mismatch("a valid ecdsa signature", "unrecoverable signature")
            "ed25519" -> {
                // Only the model's Ed25519 key is attested (and quote-bound); a gateway Ed25519 key never is.
                val key = att.modelEd25519PubKey?.takeIf { it.size == 32 }
                if (kind != TEEMessageSignature.Kind.PROVIDER_TEE || key == null || att.e2eeKeyBoundToModelTEE != true ||
                    sig.signingAddress.lowercase() != key.toHex() || !Ed25519Signature.isValid(sig.text, sig.signature, key)) {
                    return mismatch("a valid ed25519 signature by the attested model key", sig.signingAddress.lowercase())
                }
                return ResponseVerification.Verified(key.toHex())
            }
            else -> return mismatch("ecdsa or ed25519", "unsupported signing_algo ${sig.signingAlgo}")
        }

        return when (kind) {
            TEEMessageSignature.Kind.PROVIDER_TEE -> {
                val model = att.gpuSigningAddress?.lowercase()
                when {
                    model != null && signer == model && att.gpuSigningKeyBoundToHardware == true -> ResponseVerification.Verified(signer)
                    att.gpuSigningKeyBoundToHardware != true && model == signer -> unverified(ResponseVerification.UnverifiedReason.SignerUnattested(signer))
                    else -> mismatch(model ?: "an attested model key", signer)
                }
            }
            TEEMessageSignature.Kind.GATEWAY -> {
                val gw = att.signingAddress?.lowercase()
                if (gw != null && signer == gw && att.signingKeyBoundToHardware == true && att.quoteVerification?.isVerified == true) {
                    unverified(ResponseVerification.UnverifiedReason.GatewayTrustOnly(signer))
                } else mismatch(gw ?: "an attested gateway key", signer)
            }
            TEEMessageSignature.Kind.UNKNOWN -> mismatch("a known signature_kind", "unknown")
        }
    }
}
