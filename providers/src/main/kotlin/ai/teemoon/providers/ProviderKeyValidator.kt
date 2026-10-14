// ports: teemoon/Providers/ProviderKeyValidator.swift @ v1.0.4 (Endpoint + ValidationResult, and the OpenRouter key URL)
package ai.teemoon.providers

/**
 * Key-validation outcomes. The live HTTP `validate(key:endpoint:)` is an
 * adapter and is not this file — EndpointProbe injects [Catalog.validateKey].
 */
object ProviderKeyValidator {
    /** near.ai's public model list — no key required (§4 host: cloud-api.near.ai). */
    const val NEAR_AI_MODELS_URL = "https://cloud-api.near.ai/v1/models"
    /** Answers 401 to a bad key where /models answers 200 to any bearer (§4 host: openrouter.ai). */
    const val OPEN_ROUTER_AUTH_KEY_URL = "https://openrouter.ai/api/v1/auth/key"

    enum class Endpoint {
        NEAR_AI,
        BRAVE_SEARCH,
        OPEN_ROUTER,
    }

    enum class ValidationResult {
        /** HTTP 200 — the key works. */
        SUCCESS,
        /** HTTP 401 / 403 — the key was rejected. */
        UNAUTHORIZED,
        /** HTTP 402 — the account has no credits. */
        PAYMENT_REQUIRED,
        /** HTTP 429 — usage cap or rate limit hit. */
        RATE_LIMITED,
        /** Any other status code, or a transport-level error. */
        OTHER_FAILURE,
    }
}
