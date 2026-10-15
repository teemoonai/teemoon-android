// ports: teemoon/Providers/Provider.swift @ v1.0.4
// ports: teemoon/Providers/Provider+Presets.swift @ 54617ce (the four presets + presets array; Fireworks leads with kimi-k3)
// ports: teemoon/Providers/WhereLocality.swift @ v1.0.4 (isFixedAnswerService; prefersSearchFirstBrowse is gone, df74f65)
// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (inferenceBaseUrl only: the host from EndpointDirectory, c93914c)
// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (the grok and openrouter fallback models; matchingPreset by match key)
package ai.teemoon.providers

import kotlinx.serialization.Serializable
import java.util.UUID
import java.net.URI

/**
 * A configured place + model. Disk persistence is ConfigStore; this is what the
 * rest of the app speaks.
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val endpoint: String,
    val model: String,
    /** null → "Authorization: Bearer <key>"; set → used as-is, e.g. "X-Subscription-Token". */
    val authHeaderName: String? = null,
    val requiresAPIKey: Boolean = true,
    val supportsModelBrowsing: Boolean = false,
    val maxMessages: Int? = null,
    /** True when the provider performs its own web search grounding (e.g. Brave Answers). */
    val hasBuiltInGrounding: Boolean = false,
    val omitSystemPrompt: Boolean = false,
    val extraParams: Map<String, String> = emptyMap(),
    /**
     * When set, this provider runs **on this device** and the value is the
     * HuggingFace repo id of the local weights. `endpoint` is then a display-only
     * placeholder — there is no server.
     */
    val localModelID: String? = null,
    val equippedModels: List<String>? = null,
    /**
     * Capabilities of the *selected* `model`. **null = unknown**. Optional so
     * old stored JSON decodes to null, never wiping saved providers.
     */
    val modelCapabilities: ModelCapabilities? = null,
) {
    /**
     * The same setup running [modelID]. A MOVE drops [modelCapabilities] (Provider.model's didSet, iOS 9e47a63): they
     * describe the model, and carried across they were written into the NEW model's row on disk
     * (ProviderConfigProjection speaks for the active row) — a vision model's bit landing on a text one. Null means
     * "unknown"; the app refills it from the new model's own row (ConfigStore.capabilities). Every model change goes
     * through here.
     */
    fun withModel(modelID: String): Provider = if (modelID == model) this else copy(model = modelID, modelCapabilities = null)

    /** Equips [modelID] (appending if new) and makes it the active model. */
    fun equipping(modelID: String): Provider {
        val set = equipped.toMutableList()
        if (modelID !in set) set.add(modelID)
        return copy(equippedModels = set).withModel(modelID)
    }

    /** Endpoint identity: "is this saved record the same server as that preset?" */
    val presetMatchKey: String get() = presetMatchKey(endpoint)

    /** Whether two records point at the same server, however each is spelled. */
    fun sameEndpoint(asOther: Provider): Boolean = presetMatchKey == asOther.presetMatchKey

    /** The base URL an OpenAI-compatible client should POST to. */
    val chatCompletionsUrl: String
        get() {
            val s = endpoint.trim().trimEnd('/')
            return if (s.endsWith("/chat/completions")) s else "$s/chat/completions"
        }

    /** The OpenAI-compatible base: the endpoint minus `/chat/completions`. null when not an absolute URL — a schemeless "host:port/v1" is a typo, not a server. */
    val openAIBaseUrl: String?
        get() {
            val s = endpoint.trim().removeSuffix("/chat/completions")
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            return if (uri.scheme != null && uri.host != null) s else null
        }

    /** A record worth saving: a name, an endpoint that is a URL, and a model. */
    val isValid: Boolean
        get() = name.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank() && openAIBaseUrl != null

    /**
     * Base URL for inference. For near.ai providers, returns the model's direct
     * completions URL when available, bypassing the gateway TEE. Falls back to
     * the provider's configured endpoint for all other cases.
     *
     * The host comes from near.ai's own /endpoints ([EndpointDirectory]'s saved
     * copy — synchronous, because this is read on every send), never from a
     * compiled table (c93914c). The table this used to read was a frozen fetch:
     * it listed glm-5.2's retired host and had no row at all for glm-5.3-flash,
     * so that model's sealed turns went to the gateway instead of its own TEE.
     * A model the directory does not list falls back to the gateway, and the
     * session — not this getter — decides by tier whether that degrades.
     */
    val inferenceBaseUrl: String?
        get() {
            if (ProviderCapability.ATTESTATION in capabilities) {
                EndpointDirectory.persistedBase(model)?.let { return it }
            }
            return openAIBaseUrl
        }

    /**
     * Direct GPU-node URL when the model exposes one distinct from the
     * gateway — used for parallel GPU attestation and signing-key re-fetch.
     */
    val directGPUNodeUrl: String?
        get() {
            val inference = inferenceBaseUrl ?: return null
            return if (inference != openAIBaseUrl) inference else null
        }

    /**
     * True when inference happens on-device. The single thing callers should
     * branch on — never test the endpoint string.
     */
    val isLocal: Boolean get() = localModelID != null

    /**
     * True when the endpoint points at a **private / self-hosted** host — a
     * laptop/server on the LAN, over Tailscale, `.local`, or loopback — rather
     * than a public cloud provider.
     */
    val isSelfHosted: Boolean
        get() {
            val host = openAIHost() ?: return false
            return isPrivateHost(host)
        }

    /**
     * The equipped set, always non-empty for a valid provider. Reading `null`
     * as `[model]` is the whole migration — old JSON needs no rewrite.
     */
    val equipped: List<String>
        get() {
            val set = equippedModels
            if (set.isNullOrEmpty()) return if (model.isEmpty()) emptyList() else listOf(model)
            return if (model in set || model.isEmpty()) set else listOf(model) + set
        }

    /**
     * The service's name, independent of what the row's label was auto-filled
     * with. CANONICAL, not `name`. Surfaces that report where a request went
     * must use this, not the stored name.
     */
    val canonicalName: String
        get() {
            presets.firstOrNull { it.sameEndpoint(asOther = this) }?.let {
                return it.name.lowercase()
            }
            // A HOME machine is its HOST, not its record label.
            if (isSelfHosted) {
                val host = openAIHost()
                if (host != null) return HostLabel.friendly(host).lowercase()
            }
            return name.lowercase()
        }

    /**
     * One fixed id, no catalog (Brave Answers). Not a browse row.
     */
    val isFixedAnswerService: Boolean
        get() = answersSingleTurnOnly && hasBuiltInGrounding

    /**
     * True when the endpoint answers ONE question at a time and cannot see the
     * conversation. `maxMessages: 1` plus the matched preset's fill-in.
     */
    val answersSingleTurnOnly: Boolean
        get() = (effectiveMaxMessages ?: Int.MAX_VALUE) <= 1

    val effectiveMaxMessages: Int?
        get() = maxMessages ?: matchingPreset?.maxMessages

    /** The preset this setup was made from, by endpoint — the same match key everywhere, so a spelling of the URL cannot hide it. */
    val matchingPreset: Provider?
        get() = presets.firstOrNull { it.sameEndpoint(this) }

    /** WhereLocality.isNearAI: this setup talks to near.ai's own hosts (Provider.isNearAIHost). */
    val isNearAI: Boolean get() = isNearAIHost(hostOf(endpoint).ifEmpty { null })

    /**
     * Whether the selected model can call tools. **Unknown (null) → optimistic
     * `true`**: we only *withhold* tools when we positively know the model can't
     * use them.
     */
    val modelSupportsTools: Boolean
        get() = modelCapabilities?.contains(ModelCapabilities.TOOLS) ?: true

    /**
     * What this provider can do. The single source of truth for behavior gating —
     * never test the endpoint string or a preset UUID to decide behavior.
     *
     * Computed (not stored) so it works for providers saved before a capability existed.
     */
    val capabilities: Set<ProviderCapability>
        get() {
            val caps = mutableSetOf<ProviderCapability>()
            // near.ai runs SOME models in TEEs (its own fleet + attested third-party)
            // but PROXIES others (Claude, GPT-5.x, Gemini) with no enclave. Claim
            // attestation/E2EE only for the model actually running confidentially —
            // else the UI implies proof that isn't there. Tier comes from the live
            // /v1/models `owned_by` (cached), with an id heuristic before it loads.
            // TEE_OWN ONLY: near.ai exposes no confidential endpoint for the
            // `attested 3p` tier (Chutes), so there is no E2EE path *via near.ai*
            // and nothing teemoon can attest end-to-end on that route. (Chutes'
            // own stack runs TDX with per-instance keys — the limitation is the
            // routing, not the hardware.) Claiming either capability here would
            // still be an overclaim.
            // isNearAI, not `endpoint.contains("near.ai")` — that was true for api.linear.ai and near.ai.evil.com,
            // and handed them both capabilities (iOS daf3486, "one near.ai host rule").
            if (isNearAI &&
                NearAIModelCatalog.confidentiality(model) == NearAIModelCatalog.Confidentiality.TEE_OWN
            ) {
                caps += ProviderCapability.ATTESTATION
                caps += ProviderCapability.END_TO_END_ENCRYPTION
            }
            if (hasBuiltInGrounding) caps += ProviderCapability.BUILT_IN_GROUNDING
            if (supportsModelBrowsing) caps += ProviderCapability.MODEL_BROWSING
            return caps
        }

    private fun openAIHost(): String? {
        var s = endpoint
        if (s.endsWith("/chat/completions")) s = s.removeSuffix("/chat/completions")
        if (!s.contains("://")) return null
        val host = hostOf(s).lowercase()
        return host.takeIf { it.isNotEmpty() }
    }

    companion object {
        /**
         * THE SAME SERVER CAN BE SPELLED SEVERAL WAYS and every spelling is one a
         * user can legitimately end up with, because the base URL accepts an
         * endpoint with or without a trailing `/chat/completions`. Compared
         * literally they are different servers, and a row that asks one question
         * with two different rules disagrees with itself.
         *
         * Deliberately NOT host-only: two presets can share a host and differ by
         * path, and collapsing those matches the wrong one. Deliberately NOT the
         * keychain account key either — that one addresses stored secrets and must
         * never change, because rewriting it relocates every stored key.
         */
        fun presetMatchKey(endpoint: String): String {
            // Trailing slashes come off FIRST: ".../chat/completions/" has to lose the suffix too, or one spelling of
            // a preset stops matching itself (iOS daf3486).
            var s = endpoint.trim().lowercase().trimEnd('/')
            if (s.endsWith("/chat/completions")) s = s.removeSuffix("/chat/completions")
            return s.trimEnd('/')
        }

        /**
         * The host rule behind every E2EE claim (WhereLocality.isNearAIHost): near.ai itself or a subdomain of it,
         * by host — never a substring of the endpoint.
         */
        fun isNearAIHost(host: String?): Boolean = EndpointModelCatalog.Source.resolve(host) == EndpointModelCatalog.Source.NEAR_AI

        fun hostOf(endpoint: String): String =
            endpoint.substringAfter("://").substringBefore('/').substringBefore(':')

        /**
         * Private-network hosts: loopback, RFC-1918 ranges, link-local, the
         * Tailscale CGNAT range (100.64.0.0/10), and the `.local` / `.ts.net` /
         * `localhost` suffixes.
         *
         * This is the RUNTIME half of the cleartext gate. Android's
         * network-security-config cannot express RFC-1918, so the XML opens what
         * it must and this decides what teemoon will actually talk to in the
         * clear — the ATS-parity trap from docs/RUNNING_LOCAL_INFERENCE.md.
         */
        fun isPrivateHost(host: String): Boolean {
            if (host == "localhost" || host == "::1" ||
                host.endsWith(".local") || host.endsWith(".ts.net")
            ) return true
            // The emulator's alias for the host machine is the dev-loop case.
            if (host == "10.0.2.2") return true
            val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            if (octets.size != 4 || host.split('.').size != 4) return false
            val (a, b) = octets[0] to octets[1]
            return when {
                a == 127 -> true                    // 127.0.0.0/8 loopback
                a == 10 -> true                     // 10.0.0.0/8
                a == 192 && b == 168 -> true        // 192.168.0.0/16
                a == 172 && b in 16..31 -> true     // 172.16.0.0/12
                a == 169 && b == 254 -> true        // 169.254.0.0/16 link-local
                a == 100 && b in 64..127 -> true    // 100.64.0.0/10 CGNAT (Tailscale)
                else -> false
            }
        }

        // Fixed UUIDs ensure saved provider selections survive app updates.
        val nearAI = Provider(
            id = "A0000000-0000-0000-0000-000000000001",
            name = "near.ai",
            endpoint = "https://cloud-api.near.ai/v1/chat/completions",
            // The E2EE flagship near.ai still serves. 5.2 and 5.1 were RETIRED on 2026-09-12 and their direct
            // hosts stopped answering, so a fresh setup defaulting to 5.2 could not establish E2EE at all — the
            // send gate refused every first message with "attestation unreachable". iOS moved in 5567da4; this
            // followed on 2026-09-22, which is twelve days a new Android user could not have used the feature the
            // product is named for.
            //
            // classify() reads this as TEE_OWN (it matches no proxied namespace), and the direct host resolves
            // live from /endpoints rather than from the shipped snapshot — which is why this is one line and not
            // a catalog regeneration.
            model = "z-ai/glm-5.3-flash",
            supportsModelBrowsing = true,
        )

        val braveAnswers = Provider(
            id = "A0000000-0000-0000-0000-000000000002",
            name = "Brave Answers",
            endpoint = "https://api.search.brave.com/res/v1/chat/completions",
            model = "brave",
            authHeaderName = "X-Subscription-Token",
            extraParams = mapOf("enable_citations" to "true"),
            maxMessages = 1,
            hasBuiltInGrounding = true,
            omitSystemPrompt = true,
        )

        val grok = Provider(
            id = "A0000000-0000-0000-0000-000000000003",
            name = "Grok",
            endpoint = "https://api.x.ai/v1/chat/completions",
            model = "grok-4.7",   // the fallback before a key exists; live picks newest-first
            supportsModelBrowsing = true,
        )

        val fireworks = Provider(
            id = "A0000000-0000-0000-0000-000000000004",
            name = "Fireworks",
            endpoint = "https://api.fireworks.ai/inference/v1/chat/completions",
            // kimi-k2p6 left serverless 2026-10-15; k3 is its successor and the OpenRouter preset already leads with it
            // (iOS 54617ce).
            model = "accounts/fireworks/models/kimi-k3",
            supportsModelBrowsing = true,
        )

        val openRouter = Provider(
            id = "A0000000-0000-0000-0000-000000000005",
            name = "OpenRouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            model = "moonshotai/kimi-k3",   // open weights first: the fallback before a key exists
            supportsModelBrowsing = true,
        )

        val nvidia = Provider(
            id = "A0000000-0000-0000-0000-000000000006",
            name = "NVIDIA",
            endpoint = "https://integrate.api.nvidia.com/v1/chat/completions",
            model = "nvidia/nemotron-3-super-120b-a12b",
            supportsModelBrowsing = true,
        )

        /** Order here controls the button order in the quick-start row. */
        val presets: List<Provider> = listOf(nearAI, grok, fireworks, openRouter, nvidia, braveAnswers)

        /**
         * A provider that runs [model] on this device.
         *
         * No key, no endpoint, no browsing. The endpoint string is a human-readable
         * placeholder so anything that displays it has something sane to show; it is
         * never dialled, because [isLocal] short-circuits first.
         */
        fun local(model: LocalModel): Provider = Provider(
            id = UUID.randomUUID().toString(),
            name = model.displayName,
            endpoint = "on-device",
            model = model.id,
            requiresAPIKey = false,
            supportsModelBrowsing = false,
            localModelID = model.id,
        )
    }
}

enum class ProviderCapability {
    ATTESTATION,
    END_TO_END_ENCRYPTION,
    BUILT_IN_GROUNDING,
    MODEL_BROWSING,
}

/**
 * What teemoon can honestly say about a place. The UI renders these values; it
 * never composes trust language of its own (§9.5).
 */
enum class TrustLabel { LOCAL, TLS_ONLY, ATTESTED }

fun Provider.trustLabel(): TrustLabel = when {
    isLocal || isSelfHosted -> TrustLabel.LOCAL
    // Attestation is a near.ai-fleet property proven at runtime (ConfidentialSession.attestationState),
    // never inferred from a URL — so this static label never claims it.
    else -> TrustLabel.TLS_ONLY
}
