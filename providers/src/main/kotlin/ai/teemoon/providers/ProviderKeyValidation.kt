// ports: teemoon/Providers/ProviderKeyValidator.swift @ v1.0.4 (validate; the URLs and enums are ProviderKeyValidator.kt)
package ai.teemoon.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Brave web-search endpoint used to validate a subscription token. */
const val BRAVE_WEB_SEARCH_URL = "https://api.search.brave.com/res/v1/web/search"

private val validatorClient: OkHttpClient by lazy { TeemoonHttp.builder().callTimeout(10, TimeUnit.SECONDS).build() }

/** GET with headers → HTTP status. Throws on transport failure. The production seam is OkHttp. */
typealias StatusFetch = suspend (url: String, headers: Map<String, String>) -> Int

val liveStatusFetch: StatusFetch = { url, headers ->
    withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).get(); headers.forEach { (k, v) -> b.header(k, v) }
        validatorClient.newCall(b.build()).execute().use { it.code }
    }
}

/** Performs a lightweight authenticated request against the endpoint and maps the HTTP status to a result. */
suspend fun ProviderKeyValidator.validate(key: String, endpoint: ProviderKeyValidator.Endpoint, fetch: StatusFetch = liveStatusFetch): ProviderKeyValidator.ValidationResult {
    val (url, headers) = when (endpoint) {
        ProviderKeyValidator.Endpoint.NEAR_AI -> ProviderKeyValidator.NEAR_AI_MODELS_URL to mapOf("Authorization" to "Bearer $key")
        ProviderKeyValidator.Endpoint.BRAVE_SEARCH -> "$BRAVE_WEB_SEARCH_URL?q=test&count=1" to mapOf("X-Subscription-Token" to key)
        ProviderKeyValidator.Endpoint.OPEN_ROUTER -> ProviderKeyValidator.OPEN_ROUTER_AUTH_KEY_URL to mapOf("Authorization" to "Bearer $key")
    }
    val status = try { fetch(url, headers) } catch (e: CancellationException) { throw e } catch (_: Exception) { return ProviderKeyValidator.ValidationResult.OTHER_FAILURE }
    return when (status) {
        200 -> ProviderKeyValidator.ValidationResult.SUCCESS
        401, 403 -> ProviderKeyValidator.ValidationResult.UNAUTHORIZED
        402 -> ProviderKeyValidator.ValidationResult.PAYMENT_REQUIRED
        429 -> ProviderKeyValidator.ValidationResult.RATE_LIMITED
        else -> ProviderKeyValidator.ValidationResult.OTHER_FAILURE
    }
}
