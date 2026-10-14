// ports: teemoon/Providers/Provider+Presets.swift @ v1.0.4 (what still ships once the catalogues are gone:
//        near.ai's attested-3p id set and Brave's one fixed row; fireworksPrices lives with FireworksAdapter.kt)
package ai.teemoon.providers

// teemoon ships NO model catalogue. The 42-row near.ai snapshot that lived here
// (with its E2EE hosts), the Grok name table and the rows synthesized from the
// Fireworks price table all went in c3fb76d: "they drifted within days of every
// release". On this side the drift was measured — a snapshot listing glm-5.2
// with a dead direct host and no glm-5.3-flash, so a fresh near.ai setup
// defaulted to a model near.ai had retired. A model list is whatever the server
// answered, kept by [LiveCatalogStore]; a direct host is whatever near.ai's
// /endpoints says, kept by [EndpointDirectory]. What remains here is what no
// API serves.

/**
 * near.ai's `attested 3p` ids — third-party (Chutes) hosting, attested but with
 * NO confidential endpoint, so no E2EE path via near.ai. APPEND-ONLY, and never
 * pruned: a user can still have a retired one equipped, and the offline
 * classifier must not promote it to E2EE-capable. Live tiers from `/v1/models`
 * override this the moment they arrive.
 *
 * An exact-id set because their lowercased ids collide with the own-fleet
 * namespace (`qwen/…` vs `Qwen/…`): it is the only safe offline discriminator,
 * and losing the tier would guess the id as own-fleet — the overclaiming direction.
 */
val KnownModel.Companion.nearAIAttestedThirdPartyIDs: Set<String>
    get() = NEAR_AI_ATTESTED_THIRD_PARTY_IDS

private val NEAR_AI_ATTESTED_THIRD_PARTY_IDS: Set<String> = setOf(
    "deepseek/deepseek-v3.2", "minimax/minimax-m2.5", "moonshotai/kimi-k2.5",
    "moonshotai/kimi-k2.6", "moonshotai/kimi-k3", "qwen/qwen3-32b",
    "qwen/qwen3.5-397b-a17b", "z-ai/glm-5",
)

/**
 * Brave Answers is a service, not a catalogue: one id, no `/models`. It still
 * gets a `ready now` row whose long press must reach something, so the row lives
 * here rather than in a model list.
 *
 * No `price`: Brave bills per REQUEST plus per token ($4/1,000 requests +
 * $5/1M), a shape `price` cannot express, and half of it rendered as a per-1M
 * rate would be wrong. It goes in the summary, in words.
 */
val KnownModel.Companion.braveAnswersModel: KnownModel
    get() = KnownModel(
        id = Provider.braveAnswers.model,
        displayName = "Brave Answers",
        vendor = "Brave",
        price = "",
        summary = "Single answers grounded in live web search, with citations. " +
            "Billed per request plus per token — $4 per 1,000 requests and " +
            "$5 per 1M input/output tokens — so it does not price like a model.",
        features = listOf("grounding", "citations"),
        modelPageURL = "https://api-dashboard.search.brave.com/documentation/services/answers",
    )
