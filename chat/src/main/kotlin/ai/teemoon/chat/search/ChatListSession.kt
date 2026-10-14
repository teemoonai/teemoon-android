// ports: teemoon/Chat/Search/ChatListSession.swift @ v1.0.1
package ai.teemoon.chat.search

/** The chat-list search box survives leaving the list; the app owns one instance (iOS: a shared singleton). */
class ChatListSearchMemory {
    var query: String = ""
}

/**
 * A search hit the user tapped: which message to land on, and how far into it.
 * One-shot and thread-scoped — a target the user navigated away from must not
 * fire on a later visit — and stamped, so a second tap on the same thread
 * re-fires even though the target is equal.
 */
class TranscriptDeepLink {
    data class Target(val threadID: String, val messageID: String, val fraction: Double)

    var stamp: Int = 0
        private set
    var pending: Target? = null
        private set

    fun deposit(threadID: String, messageID: String, fraction: Double = 0.0) {
        pending = Target(threadID, messageID, if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0)
        stamp += 1
    }

    fun consume(threadID: String): Target? {
        val target = pending
        pending = null
        return target?.takeIf { it.threadID == threadID }
    }

    companion object {
        private const val JUNK = "…"
        private fun isJunk(c: Char) = c.isWhitespace() || JUNK.indexOf(c) >= 0

        /** Where the highlighted term sits in the message, 0…1 — by locating the snippet's core text, then offsetting to the first highlight. */
        fun matchFraction(snippet: String, content: String): Double {
            if (content.isEmpty()) return 0.0
            val deClosed = snippet.replace(ChatSearchIndex.HIGHLIGHT_CLOSE, "")
            val open = deClosed.indexOf(ChatSearchIndex.HIGHLIGHT_OPEN)
            val prefixLength = if (open >= 0) open else 0
            val core = deClosed.replace(ChatSearchIndex.HIGHLIGHT_OPEN, "")
            val trimmedCore = core.trim(::isJunk)
            if (trimmedCore.isEmpty()) return 0.0
            val coreStart = content.indexOf(trimmedCore)
            if (coreStart < 0) return 0.0
            val headJunk = core.takeWhile(::isJunk).length
            val offsetInCore = maxOf(0, prefixLength - headJunk)
            return ((coreStart + offsetInCore).toDouble() / content.length).coerceIn(0.0, 1.0)
        }
    }
}
