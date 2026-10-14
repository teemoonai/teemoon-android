// ports: teemoonTests/EndpointProbeTests.swift @ v1.0.4, + the two key-check cases @ v1.0.4
package ai.teemoon.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointProbeTest {

    private val base = "https://example.com/v1"

    private fun request(
        apiKey: String = "",
        userInitiated: Boolean = false,
        stickyKind: LocalServerKind = LocalServerKind.UNKNOWN,
        isSelfHosted: Boolean = false,
        currentModel: String = "",
        preferred: String? = null,
        existing: List<KnownModel> = emptyList(),
        rule: ModelDefaultRule? = null,
        validation: ProviderKeyValidator.Endpoint? = null,
    ) = EndpointProbe.Request(
        baseURL = base,
        host = "example.com",
        apiKey = apiKey,
        authHeaderName = null,
        isSelfHosted = isSelfHosted,
        stickyKind = stickyKind,
        currentModel = currentModel,
        preferredModel = preferred,
        userInitiated = userInitiated,
        existingModels = existing,
        defaultModelRule = rule,
        keyValidationEndpoint = validation,
    )

    private fun catalog(
        detect: LocalServerKind = LocalServerKind.UNKNOWN,
        listed: EndpointModelCatalog.ProbeResult =
            EndpointModelCatalog.ProbeResult.Failed(EndpointModelCatalog.FailureKind.OFFLINE),
        loaded: Set<String> = emptySet(),
        validate: ProviderKeyValidator.ValidationResult =
            ProviderKeyValidator.ValidationResult.OTHER_FAILURE,
    ) = EndpointProbe.Catalog(
        detectKind = { detect },
        listOllama = { listed },
        listLMStudio = { listed },
        liveCatalog = { _, _, _, _ -> listed },
        loadedOllama = { loaded },
        loadedLMStudio = { loaded },
        validateKey = { _, _ -> validate },
    )

    private val kimi = listOf(KnownModel("moonshotai/kimi-k3", "Kimi K3", "Moonshot", ""))

    /**
     * OpenRouter's /models answers 200 to any bearer, so listing cannot vouch for a key; a preset with a real key
     * endpoint asks it first, and a rejection stands even though the list would have "connected".
     */
    @Test fun aRejectedKeyIsNotRescuedByAModelListThatIgnoresKeys() = runTest {
        val result = EndpointProbe.run(request(apiKey = "sk-or-v1-bad", userInitiated = true, validation = ProviderKeyValidator.Endpoint.OPEN_ROUTER),
            catalog(listed = EndpointModelCatalog.ProbeResult.Connected(kimi), validate = ProviderKeyValidator.ValidationResult.UNAUTHORIZED))
        assertEquals(EndpointProbe.Outcome.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED), result.outcome)
    }

    /** A key endpoint that cannot answer (rate limited, offline) is not a verdict: the list decides, as it did before. */
    @Test fun aTransientKeyCheckLetsTheListDecide() = runTest {
        val result = EndpointProbe.run(request(apiKey = "sk-or-v1-good", userInitiated = true, validation = ProviderKeyValidator.Endpoint.OPEN_ROUTER),
            catalog(listed = EndpointModelCatalog.ProbeResult.Connected(kimi), validate = ProviderKeyValidator.ValidationResult.RATE_LIMITED))
        assertEquals(EndpointProbe.Outcome.Connected, result.outcome)
    }

    /** Android-only: the preset carries the endpoint, through matchingPreset — a saved setup has a fresh id. */
    @Test fun openRouterSetupsAskItsKeyEndpoint() {
        assertEquals(ProviderKeyValidator.Endpoint.OPEN_ROUTER, Provider.openRouter.keyValidationEndpoint)
        assertEquals(ProviderKeyValidator.Endpoint.OPEN_ROUTER, Provider.openRouter.copy(id = "saved-later").keyValidationEndpoint)
        assertEquals(ProviderKeyValidator.Endpoint.BRAVE_SEARCH, Provider.braveAnswers.keyValidationEndpoint)
        org.junit.Assert.assertNull(Provider.nvidia.keyValidationEndpoint)
    }

    /** Android-only: the check asks /auth/key with the bearer, and maps 401 to a rejection. */
    @Test fun theOpenRouterCheckAsksAuthKey() = runTest {
        var asked = ""; var auth: String? = null
        val r = ProviderKeyValidator.validate("sk-or-v1-x", ProviderKeyValidator.Endpoint.OPEN_ROUTER) { url, headers -> asked = url; auth = headers["Authorization"]; 401 }
        assertEquals("https://openrouter.ai/api/v1/auth/key", asked); assertEquals("Bearer sk-or-v1-x", auth)
        assertEquals(ProviderKeyValidator.ValidationResult.UNAUTHORIZED, r)
    }

    @Test fun unauthorizedWithoutKeyOnAutomaticProbeIsIdle() = runTest {
        val result = EndpointProbe.run(
            request(),
            catalog = catalog(
                listed = EndpointModelCatalog.ProbeResult.Failed(
                    EndpointModelCatalog.FailureKind.UNAUTHORIZED,
                ),
            ),
        )
        assertEquals(EndpointProbe.Outcome.Idle, result.outcome)
        assertTrue(result.authNeeded)
    }

    @Test fun unauthorizedWithoutKeyOnUserTapIsFailed() = runTest {
        val result = EndpointProbe.run(
            request(userInitiated = true),
            catalog = catalog(
                listed = EndpointModelCatalog.ProbeResult.Failed(
                    EndpointModelCatalog.FailureKind.UNAUTHORIZED,
                ),
            ),
        )
        assertEquals(
            EndpointProbe.Outcome.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
            result.outcome,
        )
        assertTrue(result.authNeeded)
    }

    @Test fun unauthorizedWithKeyIsFailed() = runTest {
        val result = EndpointProbe.run(
            request(apiKey = "sk-test"),
            catalog = catalog(
                listed = EndpointModelCatalog.ProbeResult.Failed(
                    EndpointModelCatalog.FailureKind.UNAUTHORIZED,
                ),
            ),
        )
        assertEquals(
            EndpointProbe.Outcome.Failed(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
            result.outcome,
        )
    }

    @Test fun timeoutKeepsCachedList() = runTest {
        val cached = listOf(KnownModel(id = "m1", displayName = "m1", vendor = "", price = ""))
        val result = EndpointProbe.run(
            request(existing = cached),
            catalog = catalog(
                listed = EndpointModelCatalog.ProbeResult.Failed(
                    EndpointModelCatalog.FailureKind.OFFLINE,
                ),
            ),
        )
        assertEquals(EndpointProbe.Outcome.Connected, result.outcome)
        assertEquals(cached, result.models)
    }

    @Test fun fixedPresetWithoutValidationConnectsWhenKeyPresent() = runTest {
        val result = EndpointProbe.run(
            request(apiKey = "token", rule = ModelDefaultRule.Fixed("brave")),
            catalog = catalog(),
        )
        assertEquals(EndpointProbe.Outcome.Connected, result.outcome)
        assertEquals("brave", result.selectedModel)
        assertTrue(result.models.isEmpty())
    }

    @Test fun fixedPresetWithoutKeyStaysIdle() = runTest {
        val result = EndpointProbe.run(
            request(
                rule = ModelDefaultRule.Fixed("brave"),
                validation = ProviderKeyValidator.Endpoint.BRAVE_SEARCH,
            ),
            catalog = catalog(),
        )
        assertEquals(EndpointProbe.Outcome.Idle, result.outcome)
        assertEquals("brave", result.selectedModel)
    }

    @Test fun stickyKindSkipsDetection() = runTest {
        val listed = EndpointModelCatalog.ProbeResult.Connected(
            listOf(KnownModel(id = "llama", displayName = "llama", vendor = "", price = "")),
        )
        val result = EndpointProbe.run(
            request(stickyKind = LocalServerKind.OLLAMA, isSelfHosted = true),
            catalog = catalog(detect = LocalServerKind.LM_STUDIO, listed = listed),
        )
        assertEquals(LocalServerKind.OLLAMA, result.kind)
        assertEquals(EndpointProbe.Outcome.Connected, result.outcome)
        assertEquals("llama", result.selectedModel)
    }

    @Test fun preferredModelWinsOnPrefix() = runTest {
        val models = listOf(
            KnownModel(id = "gemma4:e2b", displayName = "e2b", vendor = "", price = ""),
            KnownModel(id = "gemma4:e4b", displayName = "e4b", vendor = "", price = ""),
        )
        val result = EndpointProbe.run(
            request(preferred = "gemma4:e2b"),
            catalog = catalog(listed = EndpointModelCatalog.ProbeResult.Connected(models)),
        )
        assertEquals("gemma4:e2b", result.selectedModel)
        assertTrue(result.shouldAutofillLabel)
        assertTrue(result.dismissEndpointFocus)
    }

    @Test fun failureMessageNamesUnauthorized() {
        assertEquals(
            "key rejected — check your api key",
            EndpointProbe.failureMessage(EndpointModelCatalog.FailureKind.UNAUTHORIZED),
        )
    }
}
