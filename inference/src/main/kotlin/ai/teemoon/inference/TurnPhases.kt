package ai.teemoon.inference

// ports: teemoon/Inference/TurnPhases.swift @ c76f3bd (the line and the cache share; Android wrote these first, iOS matched
//        the kinds, and the labels here now follow iOS so the two cards read the same)
//        + the cache line by host of 6fdcb43 (cacheReporting)
// Where a send's time goes, for
// every provider alike — "slow" is mostly reasoning and the search round trip, not the network (pm's Fireworks curl
// runs, 2026-10-19: first token under 1 s everywhere, first ANSWER token 2.5-6.5 s). On the device only: the
// developer card prints these; nothing is sent anywhere.

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** One step of a send, at a wall-clock instant. [request] is the model request it belongs to (1, then 2 after a tool round). Kinds match iOS's PhaseEvent.Kind. */
data class TurnPhase(val kind: Kind, val at: Instant, val request: Int = 1, val ms: Long? = null, val bytes: Int? = null, val tokens: Int? = null, val cachedTokens: Int? = null) {
    enum class Kind {
        /** The attestation gate settled (prepareTurn returned): the send can go out. */
        GATE,
        /** The request was handed to the transport. */
        SENT,
        /**
         * Response headers arrived (network transports only). [tokens] the server's prompt count and [cachedTokens] the
         * part it served from cache, when it reports them: a minute-resolution {{datetime}} at the top of the system prompt
         * moves the prefix every minute and the cache misses (pm, Fireworks: 3718 of 3847 cached, then 0 when only the minute changed).
         */
        RESPONSE,
        /** The first token of any kind — reasoning, if the model thinks first. */
        FIRST_TOKEN,
        /** The first token the reader sees. */
        FIRST_ANSWER,
        /** One tool call ran; [ms] its duration, [bytes] the result handed to the model (UTF-8), [tokens] ~bytes/4 ([tokenEstimate]). [at] is when it ended. */
        TOOL,
        /** The engine finished: the last token. */
        DONE,
        /** The reply-signature check answered (attested providers only), after DONE. */
        VERIFIED,
    }

    companion object {
        /**
         * The card's phase line, offsets from [start] (the tap), the same line iOS prints (TurnPhases.swift @ c76f3bd):
         * `gate 0.80 · sent 0.81 · resp 1.20 · first 1.31 · tool 1.2s 14KB ~3.6k tok · answer 5.90 · done 7.30 · signed 9.50 · cache 92%`.
         * A thinking gap reads as first → answer; a request's own network cost as sent → resp. The cache share is summed
         * over every request in the send (each RESPONSE carries its usage).
         */
        fun line(start: Instant, phases: List<TurnPhase>): String? {
            if (phases.isEmpty()) return null
            val parts = phases.sortedBy { it.at }.map { p ->
                val t = String.format(Locale.US, "%.2f", maxOf(0L, Duration.between(start, p.at).toMillis()) / 1000.0)
                when (p.kind) {
                    Kind.GATE -> "gate $t"
                    Kind.SENT -> "sent $t"
                    Kind.RESPONSE -> "resp $t"
                    Kind.FIRST_TOKEN -> "first $t"
                    Kind.FIRST_ANSWER -> "answer $t"
                    Kind.DONE -> "done $t"
                    Kind.VERIFIED -> "signed $t"
                    Kind.TOOL -> buildString {
                        append("tool ").append(String.format(Locale.US, "%.1fs", (p.ms ?: 0) / 1000.0))
                        p.bytes?.let { b -> append(if (b >= 1024) " ${b / 1024}KB" else " ${b}B") }
                        p.tokens?.let { k -> append(if (k >= 1000) String.format(Locale.US, " ~%.1fk tok", k / 1000.0) else " ~$k tok") }
                    }
                }
            }.toMutableList()
            cachePercent(phases)?.let { parts += "cache $it%" }
            return parts.joinToString(" · ")
        }

        /** The share of prompt tokens the provider served from its prompt cache, over every request; null without usage. */
        fun cachePercent(phases: List<TurnPhase>): Int? {
            val responses = phases.filter { it.kind == Kind.RESPONSE }
            val prompt = responses.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.sum() ?: return null
            val cached = responses.mapNotNull { it.cachedTokens }.takeIf { it.isNotEmpty() }?.sum() ?: return null
            if (prompt <= 0) return null
            return Math.round(minOf(cached, prompt).toDouble() / prompt * 100).toInt()
        }

        /**
         * The card's only figure from the turn (iOS ef5c272): `cache 92%`, or why a chat's first message has none —
         * nothing earlier could be in the provider's cache. A later message at 0% is a real miss and says so.
         */
        fun cacheLine(phases: List<TurnPhase>, firstMessage: Boolean, reporting: CacheReporting = CacheReporting.FIGURE_ONLY): String? {
            val responses = phases.filter { it.kind == Kind.RESPONSE }
            val prompt = responses.mapNotNull { it.tokens }.takeIf { it.isNotEmpty() }?.sum()
            val cached = responses.mapNotNull { it.cachedTokens }.takeIf { it.isNotEmpty() }?.sum()
                ?: if (reporting == CacheReporting.MISSING_IS_ZERO) 0 else null
            if (prompt == null || prompt <= 0 || cached == null) return if (reporting == CacheReporting.FIGURE_ONLY) null else "cache — not reported"
            val pct = Math.round(minOf(cached, prompt).toDouble() / prompt * 100).toInt()
            return if (pct == 0 && firstMessage) "cache — first message" else "cache $pct%"
        }

        /**
         * near.ai plaintext sends `prompt_tokens_details: null` on a miss; the other three send their figure only when
         * there is one. near.ai E2EE sent null on a repeat of the same 5.8k-token prompt (2026-10-21), so it is "not
         * reported" until the maintainer decides.
         */
        fun cacheReporting(host: String?, onDevice: Boolean, isE2EE: Boolean = false): CacheReporting {
            val h = host?.lowercase()
            if (onDevice || h == null) return CacheReporting.FIGURE_ONLY
            val nearAI = h == "near.ai" || h.endsWith(".near.ai")
            if (nearAI && isE2EE) return CacheReporting.MISSING_IS_UNREPORTED
            if (nearAI || h in setOf("api.fireworks.ai", "api.x.ai", "openrouter.ai")) return CacheReporting.MISSING_IS_ZERO
            return CacheReporting.MISSING_IS_UNREPORTED
        }

        /** ~4 bytes a token, as iOS: a size signal for the card, not a count. */
        fun tokenEstimate(bytes: Int) = bytes / 4
    }
}

/** What a missing cached figure means for the host that served the turn (TurnPhases.CacheReporting). */
enum class CacheReporting {
    /** Show a figure when there is one, nothing otherwise (on-device). */
    FIGURE_ONLY,
    /** Sends a figure on a hit and none on a miss: missing reads as 0. */
    MISSING_IS_ZERO,
    /** Never seen to send one: missing reads as "not reported". */
    MISSING_IS_UNREPORTED,
}
