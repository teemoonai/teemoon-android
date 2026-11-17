package ai.teemoon.inference

// android-only (1.0.6 performance): no Swift counterpart — iOS's runtime is not LiteRT-LM. Whether the on-device
// engine may KEEP last turn's Conversation and send only the new user message, instead of closing it and seeding a new
// one with the system prompt and the whole history. A seeded turn re-prefills everything: on a Pixel 10 Pro GPU the
// 531-token system prompt alone is ~1.5 s (311-376 tok/s, Test Lab 2026-10-19), and a chat's history grows that every
// turn. A kept Conversation already holds those tokens in its KV cache.
//
// The rule is EXACT EQUALITY with what this turn would seed — same system prompt text, same tool descriptions, same
// output cap and thinking setting, and a history that is the kept transcript, turn for turn — so the model sees what
// it would have seen. Anything else rebuilds as before: an edit, a regenerate, a trimmed history (which is also how a
// filling window shows up: trimmedHistory drops turns and the history no longer matches), a thread switch, a changed
// system prompt (since iOS 224ec87 {{datetime}} resolves to the DAY, so that is a new day, not a new minute). A backend
// change or memory pressure closes the engine handle and its Conversation with it (LocalEngineCache).
//
// No tolerated difference any more (iOS 634908e): every user turn carries "\n\n[Sent at 4:07 PM]" on the wire, built
// from THAT message's stored timestamp (ChatGeneration.timeNote), so the next seed spells an earlier question exactly
// as it was sent. The kept transcript holds the user turn AS SENT, and equality is plain equality. Until 1.0.6 build 45
// only the newest turn carried the minute and earlier ones were compared with the note stripped.
//
// Never kept: a turn that ran a tool (the KV then holds the call and its result, which the next seed would not carry —
// the app replays only user and assistant text), a turn with thinking on (the KV holds the thinking, the history does
// not), and a turn that failed or was cancelled (the runtime's state is unknown).

/** What a kept Conversation holds: the seed it was built from plus every turn since, as the next seed would spell it. */
data class KeptConversation(
    val systemMessage: String?,
    val toolDescriptions: List<String>,
    val maxOutputTokens: Int,
    val enableThinking: Boolean,
    val transcript: List<Pair<LocalRole, String>>,
)

object ConversationReuse {
    /**
     * IMAGES.md §4b @ 00f72739: a request with any image, the question's or an earlier one, prefills everything and keeps
     * nothing — the kept transcript is text, so a conversation that saw an image is never the next turn's seed.
     */
    fun reusable(requestImages: Int): Boolean = requestImages == 0

    /** True when [spec] would seed exactly what [kept] already holds, so only [LocalConversationSpec.userText] needs sending. */
    fun canReuse(kept: KeptConversation?, spec: LocalConversationSpec): Boolean =
        kept != null && kept.systemMessage == spec.systemMessage && kept.toolDescriptions == spec.tools.map { it.descriptionJson } &&
            kept.maxOutputTokens == spec.maxOutputTokens && kept.enableThinking == spec.enableThinking && kept.transcript == spec.history && reusable(spec.requestImages)

    /**
     * What the Conversation holds after a turn that answered [rawAnswer] — or null when it must not be kept. The raw
     * answer is what the KV holds; if the app later replays a different text for it (markup elided, thinking stripped)
     * the next history will not match and the turn rebuilds.
     */
    fun afterTurn(spec: LocalConversationSpec, rawAnswer: String, toolsRan: Int, completed: Boolean): KeptConversation? {
        if (!completed || toolsRan > 0 || spec.enableThinking || rawAnswer.isEmpty() || !reusable(spec.requestImages)) return null
        return KeptConversation(spec.systemMessage, spec.tools.map { it.descriptionJson }, spec.maxOutputTokens, spec.enableThinking,
            spec.history + (LocalRole.USER to spec.userText) + (LocalRole.MODEL to rawAnswer))
    }
}
