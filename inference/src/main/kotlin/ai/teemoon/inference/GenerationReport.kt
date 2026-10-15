// ports: teemoon/Inference/GenerationTransport.swift @ v1.0.1 (TurnReport)
//        teemoon/Inference/GenerationEvents.swift @ v1.0.1 (RequestResult)
//        teemoon/Inference/LastRequestDebugInfo.swift @ v1.0.1 (ToolCallRecord)
package ai.teemoon.inference

import ai.teemoon.confidential.ResponseVerification
import java.time.Instant

/**
 * The silent-context-truncation signal. Ollama (and anything else with a
 * fixed context window) does not error when the prompt is too long: it drops
 * the FRONT — the system prompt and the question — and answers the tail. The
 * only trace is `usage.prompt_tokens` coming back far below what was sent.
 */
data class PromptBudget(
    /** Characters sent over four — a crude estimate, which is why the ratio below is generous. */
    val sentEstimate: Int,
    /** What the server says it evaluated. */
    val evaluated: Int,
) {
    val looksTruncated: Boolean
        get() = sentEstimate >= MINIMUM_ESTIMATE_TO_JUDGE && evaluated < sentEstimate * SUSPICIOUS_RATIO

    companion object {
        /** Short prompts are never judged: the estimate is too rough to call a 300-token turn truncated. */
        const val MINIMUM_ESTIMATE_TO_JUDGE = 1_000
        /** A tokenizer disagreement is 20–30%; discarding the front of the prompt is far more. */
        const val SUSPICIOUS_RATIO = 0.6

        /** Message content plus every tool call's name and arguments, over four. */
        fun estimateSentTokens(messages: List<Map<String, Any?>>): Int {
            var characters = 0
            for (message in messages) {
                characters += (message["content"] as? String)?.length ?: 0
                for (call in (message["tool_calls"] as? List<*>) ?: emptyList<Any>()) {
                    val function = (call as? Map<*, *>)?.get("function") as? Map<*, *> ?: continue
                    characters += (function["name"] as? String)?.length ?: 0
                    characters += (function["arguments"] as? String)?.length ?: 0
                }
            }
            return characters / 4
        }
    }
}

/** One executed tool call, as the developer panel lists it. */
data class ToolCallRecord(
    val name: String,
    val arguments: String,
    val result: String,
) {
    /**
     * What the debug row DISPLAYS. The copy path keeps the full payload; the
     * mounted row must stay bounded — an 80-source sweep once mounted a
     * 100k-character row inside a lazy list and blanked the screen. The
     * trailer says how much exists so the truncation is honest.
     */
    val displayResult: String
        get() = if (result.length <= DISPLAY_CAP) result
        else result.take(DISPLAY_CAP) + "\n… [display truncated — $DISPLAY_CAP of ${result.length} chars; copy has the full payload]"

    companion object { const val DISPLAY_CAP = 1500 }
}

/**
 * What a transport can say about the exchange it just performed.
 *
 * Every field is optional because on-device generation has no request to
 * report and no signature to verify — the developer panel simply shows less,
 * rather than the engine growing a "was this local?" branch.
 *
 */
data class TurnReport(
    val url: String? = null,
    val requestHeaders: Map<String, String>? = null,
    val requestBodyJson: String? = null,

    /**
     * The body as it actually left the device, when E2EE sealed it.
     *
     * [requestBodyJson] is the PLAINTEXT the request was built from, which is
     * what a developer wants to read. Under E2EE that is not what went out,
     * and the debug panel was showing only the former under a green
     * "encrypted" label — the one surface whose whole job is to show the wire.
     */
    val sealedBodyJson: String? = null,

    /** Whether this exchange used application-layer E2EE. */
    val isE2eeActive: Boolean = false,

    /**
     * When the first token of ANY kind arrived, and when the first VISIBLE
     * one did. The gap is thinking.
     */
    val firstTokenAt: Instant? = null,
    val firstVisibleTokenAt: Instant? = null,

    /** Input-token accounting, when the server reported `usage.prompt_tokens`. Absent under E2EE (the estimate would measure ciphertext). */
    val promptBudget: PromptBudget? = null,

    /**
     * Who answered, for the warnings the engine raises about this turn.
     *
     * iOS's HTTPTransport has `provider.name` in scope where it logs the truncated-prompt line and names it; the
     * Kotlin warning crosses into the engine, which does not, and so said "the server" about whichever of several
     * configured places had just discarded the front of the prompt. Null for a transport with no provider to name.
     */
    val providerName: String? = null,

    /**
     * Tool calls the TRANSPORT ran itself, rather than handing back for the
     * engine to run.
     *
     * Only the on-device transport populates this: it owns the tool loop, so
     * `toolCalls` comes back empty and the engine has nothing to record.
     * Without this the developer panel showed no tools at all for on-device
     * chats that had plainly just searched — the sources rail said "5 sources"
     * while the debug card listed none.
     */
    val executedToolCalls: List<ToolCallRecord> = emptyList(),

    /**
     * Verifies this turn's response signature, if the transport has an
     * attestation story at all. null for local inference and for plain
     * OpenAI-compatible endpoints — absence means "not applicable", which is
     * distinct from a verification that ran and failed.
     */
    val verify: (suspend () -> ResponseVerification?)? = null,

    /** What the on-device engine did this turn, for the developer card's second line. Null for any network transport. */
    val localStats: LocalTurnStats? = null,
)

/**
 * The on-device runtime's own account of a turn: where it ran, whether the drafter was on, and how fast it read the
 * prompt. No load time: the engine loads when the model is chosen (the warm-up), not per turn. Decode speed is not here — the card already derives it from the turn's own
 * clock, for every transport alike.
 */
data class LocalTurnStats(
    val backend: String,
    val speculative: Boolean,
    val prefillTokens: Int,
    val prefillTokensPerSecond: Double,
)

/** Result delivered on successful stream completion. */
data class RequestResult(
    val url: String?,
    val requestHeaders: Map<String, String>?,
    val requestBodyJson: String?,
    /** The sealed body, when E2EE was active. See [TurnReport.sealedBodyJson]. */
    val sealedBodyJson: String? = null,
    val responseBody: String?,
    val toolCalls: List<ToolCallRecord>,
    val outputTokens: Int?,
    /** When the first token of ANY kind arrived, and when the first VISIBLE one did. */
    val firstTokenAt: Instant? = null,
    val firstVisibleTokenAt: Instant? = null,
    val promptBudget: PromptBudget? = null,
    /** TEE signature verification result. null when the provider doesn't support attestation. */
    val teeVerification: ResponseVerification?,
    /** Whether this request/response was encrypted with application-layer E2EE. */
    val isE2eeActive: Boolean,
    /** See [TurnReport.localStats]. */
    val localStats: LocalTurnStats? = null,
)
