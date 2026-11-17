// ports: teemoon/Inference/LLMError.swift @ v1.1.0 (the error, its fields, providerMessage, groundingMessage),
//        teemoon/Inference/StreamDebugHelpers.swift @ v1.1.0 (apiErrorMessage + ProblemDetails; the pretty-printers are ToolCallDebugFormat)
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Structured error from one generation: a user-facing line plus the
 * request/response context the debug card shows.
 *
 * The engine keys one decision off it: a tool that fails with 401/402/403 is
 * an AUTH problem and propagates, so the user is told about their key; every
 * other tool failure becomes tool-result text the model can read and recover
 * from. See `GenerationEngine.executeTool`.
 */
class LlmError(
    val source: Source,
    val userMessage: String,
    val httpStatus: Int? = null,
    val url: String? = null,
    val requestHeaders: Map<String, String>? = null,
    val requestBodyJson: String? = null,
    val messageHistory: String? = null,
    val responseBody: String? = null,
    val underlyingError: Throwable? = null,
) : Exception(userMessage, underlyingError) {

    /**
     * The key itself is wrong, missing rights or out of credit: a call the model retries with a rephrased query
     * cannot succeed, so the turn stops with the cause instead of three identical failures. 401/402/403 by status;
     * a malformed Brave key is its 422 SUBSCRIPTION_TOKEN_INVALID, read from the body.
     *
     * STATUS-BASED AND TOOL-AGNOSTIC, like iOS's `isKeyRejection`. This was `braveKeyRejected` and checked
     * `source == BraveGrounding` first, which is identical today only because Brave is the only registered tool
     * (B8). The day a second one lands, a 401 from it would have ended the turn on iOS and been swallowed here,
     * then retried for the whole round budget — three identical failures and a wasted turn, which is the exact
     * thing this property exists to prevent. The 422 clause stays Brave-shaped because the envelope is Brave's;
     * another vendor's malformed-key body would need its own reader, and until one exists that is a guess.
     */
    val isKeyRejection: Boolean
        get() = httpStatus == 401 || httpStatus == 402 || httpStatus == 403 ||
            (httpStatus == 422 && source == Source.BraveGrounding && braveTokenInvalid(responseBody.orEmpty()))

    sealed interface Source {
        data class Provider(val name: String) : Source
        object BraveGrounding : Source
    }

    companion object {
        /** iOS LLMError.providerMessage: the user-facing line for a bare HTTP status. */
        fun providerMessage(httpStatus: Int, provider: String): String = when (httpStatus) {
            401 -> "$provider rejected the request (http 401). your api key may be invalid or missing."
            402 -> "$provider has no credits, or the balance is too low (http 402). add credits, then retry."
            403 -> "$provider denied access (http 403). your api key may not have permission for this model."
            404 -> "$provider endpoint not found (http 404). check the server address in settings → places & keys."
            422 -> "$provider rejected the request format (http 422). the model name may be invalid."
            429 -> "$provider rate limit reached (http 429). try again in a moment."
            in 500..599 -> "$provider server error (http $httpStatus). the service may be down for a moment."
            else -> "$provider returned an error (http $httpStatus)."
        }

        /**
         * StreamDebugHelpers.apiErrorMessage: the provider's own `error.message` /
         * `error.detail` / `error.meta.errors[].msg`, when the body carries one —
         * the status line alone hides "model not found" behind a bare 404.
         */
        fun apiErrorMessage(responseBody: String, httpStatus: Int, provider: String): String {
            val json = runCatching { Json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
                ?: return providerMessage(httpStatus, provider)
            problemDetailsMessage(json, httpStatus, provider)?.let { return it }
            val error = json["error"] as? JsonObject
                ?: return providerMessage(httpStatus, provider)
            fun str(key: String): String? = (error[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val parts = ArrayList<String>()
            str("message")?.let { parts += it }
            str("detail")?.let { parts += it }
            ((error["meta"] as? JsonObject)?.get("errors") as? JsonArray)?.forEach { e ->
                ((e as? JsonObject)?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let { parts += it }
            }
            if (parts.isEmpty()) return providerMessage(httpStatus, provider)
            return "$provider (http $httpStatus): " + parts.joinToString(" — ")
        }

        /**
         * A body with a top-level `detail` and no `error` envelope — NVIDIA's:
         * `{"status":404,"title":"Not Found","detail":"Function '…': Not found for account '…'"}`. Their catalog lists
         * models the account cannot call (20 of 28 sampled, 2026-09-18), and that 404 is the MODEL, not the URL: the
         * generic 404 line sent the user to check a provider URL that was fine. The function id and account id in the
         * detail are not repeated.
         */
        fun problemDetailsMessage(json: JsonObject, httpStatus: Int, provider: String): String? {
            if (json["error"] != null) return null
            val detail = (json["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            if (httpStatus == 404 && detail.startsWith("Function ")) return "$provider doesn't serve this model on your account (http 404). pick another model in where."
            return "$provider (http $httpStatus): $detail"
        }

        /**
         * Brave's error body is `{"error":{"code":…,"detail":…,"meta":{"component":…}}}`. The status alone hid
         * the one that mattered: a malformed key is a 422 SUBSCRIPTION_TOKEN_INVALID, which read as "returned an
         * error (HTTP 422)" and was retried three times a turn (2026-09-17, a 16-character key on the Pixel).
         */
        fun groundingMessage(httpStatus: Int, responseBody: String = ""): String = when {
            httpStatus == 401 -> "brave web search failed (http 401). check your brave api key in settings → search."
            httpStatus == 402 -> "brave web search failed (http 402). your brave api subscription has run out of credits."
            httpStatus == 403 -> "brave web search denied (http 403). your api key may not have access to this endpoint."
            httpStatus == 429 -> "brave web search rate limit reached (http 429). try again in a moment."
            braveTokenInvalid(responseBody) -> "brave web search rejected the api key (http $httpStatus): " +
                (braveErrorDetail(responseBody) ?: "the key isn't valid.") + " check your brave api key in settings → search."
            else -> "brave web search returned an error (http $httpStatus)" + (braveErrorDetail(responseBody)?.let { ": $it" } ?: ".")
        }

        /** `error.code == SUBSCRIPTION_TOKEN_INVALID`: Brave could not parse the key at all. Nothing to retry. */
        fun braveTokenInvalid(responseBody: String): Boolean = braveError(responseBody)?.let { e ->
            (e["code"] as? JsonPrimitive)?.content == "SUBSCRIPTION_TOKEN_INVALID"
        } ?: false

        /** Brave's `error.detail` sentence, when the body is Brave's error shape. */
        fun braveErrorDetail(responseBody: String): String? = braveError(responseBody)?.let { e ->
            (e["detail"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        }

        private fun braveError(responseBody: String): JsonObject? = runCatching {
            (Json.parseToJsonElement(responseBody) as? JsonObject)?.get("error") as? JsonObject
        }.getOrNull()
    }
}
