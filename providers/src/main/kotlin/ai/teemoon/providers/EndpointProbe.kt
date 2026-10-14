// ports: teemoon/Providers/EndpointProbe.swift @ v1.0.4
// ports: teemoon/Providers/EndpointProbe.swift @ v1.0.4 (runCatalog asks a real key endpoint before listing)
package ai.teemoon.providers

/**
 * What the add/edit provider form learns from one probe: kind, models, warm
 * set, selected id, connection. The view applies the result; it does not
 * decide 401-without-key vs rejected key. See EndpointProbeTest.
 */
object EndpointProbe {

    data class Request(
        val baseURL: String,
        val host: String?,
        val apiKey: String,
        val authHeaderName: String?,
        val isSelfHosted: Boolean,
        /**
         * Already-detected kind. Non-[LocalServerKind.UNKNOWN] skips re-detection
         * so a timeout mid-pull cannot hide Ollama's download button.
         */
        val stickyKind: LocalServerKind,
        val currentModel: String,
        val preferredModel: String?,
        val userInitiated: Boolean,
        val existingModels: List<KnownModel>,
        val defaultModelRule: ModelDefaultRule?,
        val keyValidationEndpoint: ProviderKeyValidator.Endpoint?,
    )

    sealed class Outcome {
        data object Idle : Outcome()
        data object Connected : Outcome()
        data class Failed(val kind: EndpointModelCatalog.FailureKind) : Outcome()
    }

    data class Result(
        val outcome: Outcome,
        val models: List<KnownModel>,
        val kind: LocalServerKind,
        val loaded: Set<String>,
        val selectedModel: String,
        val authNeeded: Boolean,
        val dismissEndpointFocus: Boolean,
        val shouldAutofillLabel: Boolean,
    )

    /** Network surfaces the probe calls. Tests inject; AddEditProviderModel.liveCatalog() wires the adapters. */
    data class Catalog(
        val detectKind: suspend (String) -> LocalServerKind,
        val listOllama: suspend (String) -> EndpointModelCatalog.ProbeResult,
        val listLMStudio: suspend (String) -> EndpointModelCatalog.ProbeResult,
        val liveCatalog: suspend (String?, String, String, String?) -> EndpointModelCatalog.ProbeResult,
        val loadedOllama: suspend (String) -> Set<String>,
        val loadedLMStudio: suspend (String) -> Set<String>,
        val validateKey: suspend (String, ProviderKeyValidator.Endpoint) -> ProviderKeyValidator.ValidationResult,
    )

    /** True when the view should show `.testing` before `run` returns. */
    fun needsNetwork(request: Request): Boolean {
        if (request.defaultModelRule is ModelDefaultRule.Fixed) {
            val key = request.apiKey.trim()
            return request.keyValidationEndpoint != null && key.isNotEmpty()
        }
        return true
    }

    fun failureMessage(kind: EndpointModelCatalog.FailureKind): String = when (kind) {
        EndpointModelCatalog.FailureKind.NOTHING_LISTENING ->
            "nothing listening there — is the server running?"
        EndpointModelCatalog.FailureKind.NO_MODELS_ENDPOINT ->
            "reachable, but no /v1/models — check the path"
        EndpointModelCatalog.FailureKind.HTTP_BLOCKED ->
            "android blocked http — use https, or a tailscale name"   // the cleartext gate is the network-security-config here, not ATS
        EndpointModelCatalog.FailureKind.UNAUTHORIZED ->
            "key rejected — check your api key"
        EndpointModelCatalog.FailureKind.FORBIDDEN ->
            "server refused the request — ollama rejects a proxied host header; set your proxy to rewrite Host to localhost"
        EndpointModelCatalog.FailureKind.PAYMENT_REQUIRED ->
            "no credits on this account"
        EndpointModelCatalog.FailureKind.RATE_LIMITED ->
            "rate limited — try again shortly"
        EndpointModelCatalog.FailureKind.BAD_RESPONSE ->
            "connected, but no models were returned"
        EndpointModelCatalog.FailureKind.OFFLINE ->
            "couldn't reach that endpoint"
    }

    suspend fun run(request: Request, catalog: Catalog): Result {
        val rule = request.defaultModelRule
        if (rule is ModelDefaultRule.Fixed) {
            return runFixed(id = rule.id, request = request, catalog = catalog)
        }
        return runCatalog(request, catalog)
    }

    // MARK: - Fixed (Brave: no /models)

    private suspend fun runFixed(id: String, request: Request, catalog: Catalog): Result {
        val key = request.apiKey.trim()
        val empty = Result(
            outcome = if (key.isEmpty()) Outcome.Idle else Outcome.Connected,
            models = emptyList(),
            kind = LocalServerKind.UNKNOWN,
            loaded = emptySet(),
            selectedModel = id,
            authNeeded = false,
            dismissEndpointFocus = false,
            shouldAutofillLabel = false,
        )
        val validation = request.keyValidationEndpoint
        if (validation == null || key.isEmpty()) return empty
        val result = catalog.validateKey(key, validation)
        var outcome: Outcome = Outcome.Connected
        var authNeeded = false
        when (result) {
            ProviderKeyValidator.ValidationResult.SUCCESS ->
                outcome = Outcome.Connected
            ProviderKeyValidator.ValidationResult.UNAUTHORIZED -> {
                authNeeded = true
                outcome = Outcome.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED)
            }
            ProviderKeyValidator.ValidationResult.PAYMENT_REQUIRED ->
                outcome = Outcome.Failed(EndpointModelCatalog.FailureKind.PAYMENT_REQUIRED)
            ProviderKeyValidator.ValidationResult.RATE_LIMITED ->
                outcome = Outcome.Failed(EndpointModelCatalog.FailureKind.RATE_LIMITED)
            ProviderKeyValidator.ValidationResult.OTHER_FAILURE ->
                outcome = Outcome.Failed(EndpointModelCatalog.FailureKind.OFFLINE)
        }
        return empty.copy(outcome = outcome, authNeeded = authNeeded)
    }

    // MARK: - Live catalogue

    private suspend fun runCatalog(request: Request, catalog: Catalog): Result {
        val kind: LocalServerKind = when {
            request.stickyKind != LocalServerKind.UNKNOWN -> request.stickyKind
            request.isSelfHosted -> catalog.detectKind(request.baseURL)
            else -> LocalServerKind.UNKNOWN
        }

        // A host whose /models answers 200 to any bearer (OpenRouter) cannot vouch for the key by listing; when it
        // has a real key endpoint, ask that first and let a rejection stand. Without this a mistyped OpenRouter key
        // listed 379 models, saved, and failed only at the first message.
        if (kind == LocalServerKind.UNKNOWN) request.keyValidationEndpoint?.let { validation ->
            val key = request.apiKey.trim()
            if (key.isNotEmpty()) when (catalog.validateKey(key, validation)) {
                ProviderKeyValidator.ValidationResult.UNAUTHORIZED -> return interpretFailure(EndpointModelCatalog.FailureKind.UNAUTHORIZED, kind, emptySet(), request)
                ProviderKeyValidator.ValidationResult.PAYMENT_REQUIRED -> return interpretFailure(EndpointModelCatalog.FailureKind.PAYMENT_REQUIRED, kind, emptySet(), request)
                // the list decides; a transient check is not a verdict
                ProviderKeyValidator.ValidationResult.SUCCESS, ProviderKeyValidator.ValidationResult.RATE_LIMITED, ProviderKeyValidator.ValidationResult.OTHER_FAILURE -> Unit
            }
        }

        val listed: EndpointModelCatalog.ProbeResult = when (kind) {
            LocalServerKind.OLLAMA -> catalog.listOllama(request.baseURL)
            LocalServerKind.LM_STUDIO -> catalog.listLMStudio(request.baseURL)
            LocalServerKind.UNKNOWN -> catalog.liveCatalog(
                request.host, request.baseURL, request.apiKey, request.authHeaderName,
            )
        }

        val loaded: Set<String> = when (kind) {
            LocalServerKind.OLLAMA -> catalog.loadedOllama(request.baseURL)
            LocalServerKind.LM_STUDIO -> catalog.loadedLMStudio(request.baseURL)
            LocalServerKind.UNKNOWN -> emptySet()
        }

        return when (listed) {
            is EndpointModelCatalog.ProbeResult.Connected -> Result(
                outcome = Outcome.Connected,
                models = listed.models,
                kind = kind,
                loaded = loaded,
                selectedModel = pickModel(from = listed.models, request = request),
                authNeeded = false,
                dismissEndpointFocus = true,
                shouldAutofillLabel = true,
            )
            is EndpointModelCatalog.ProbeResult.Failed ->
                interpretFailure(listed.kind, kind = kind, loaded = loaded, request = request)
        }
    }

    /**
     * 401 with no key on an automatic probe is a missing key, not a rejected one.
     * Do not skip the probe when the key is empty — near.ai's list is public.
     */
    private fun interpretFailure(
        failure: EndpointModelCatalog.FailureKind,
        kind: LocalServerKind,
        loaded: Set<String>,
        request: Request,
    ): Result {
        val keyEntered = request.apiKey.trim().isNotEmpty()
        val authNeeded = failure == EndpointModelCatalog.FailureKind.UNAUTHORIZED
        val idleMissingKey = failure == EndpointModelCatalog.FailureKind.UNAUTHORIZED &&
            !keyEntered && !request.userInitiated
        val outcome: Outcome = when {
            idleMissingKey -> Outcome.Idle
            request.existingModels.isEmpty() ||
                failure == EndpointModelCatalog.FailureKind.UNAUTHORIZED ->
                Outcome.Failed(failure)
            else -> Outcome.Connected
        }
        return Result(
            outcome = outcome,
            models = request.existingModels,
            kind = kind,
            loaded = loaded,
            selectedModel = request.currentModel,
            authNeeded = authNeeded,
            dismissEndpointFocus = false,
            shouldAutofillLabel = false,
        )
    }

    private fun pickModel(from: List<KnownModel>, request: Request): String {
        val preferred = request.preferredModel?.lowercase()
        if (preferred != null) {
            val match = from.firstOrNull { it.id.lowercase() == preferred }
                ?: from.firstOrNull { it.id.lowercase().startsWith(preferred) }
            if (match != null) return match.id
        }
        val rule = request.defaultModelRule
        if (rule != null && from.none { it.id == request.currentModel }) {
            rule.resolve(from)?.let { return it }
        }
        if (request.currentModel.trim().isEmpty()) {
            return from.firstOrNull()?.id ?: request.currentModel
        }
        return request.currentModel
    }
}
