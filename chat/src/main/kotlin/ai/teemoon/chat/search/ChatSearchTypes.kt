// ports: teemoon/Chat/Search/ChatSearchIndex.swift @ v1.0.1 (the value types and the ChatSearchIndexing seam)
package ai.teemoon.chat.search

/** A message flattened into a value. Snapshot on the caller's side, hand the values over. */
data class ChatMessageRecord(
    val messageID: String,
    val threadID: String,
    /** "user" | "assistant" | "system" | "tool" */
    val role: String,
    val timestampMs: Long,
    val content: String,
)

/**
 * What granularity a search answers at. Both modes run the SAME match, ranking
 * and snippet — only the terminal grouping differs, and that grouping happens
 * in Kotlin because FTS5 will not allow it in SQL. Do not fork the query
 * builder to add a third.
 */
enum class ChatSearchGranularity {
    /** One row per thread: its single best-matching message. */
    THREADS,
    /** One row per matching message. */
    MESSAGES,
}

/**
 * How a query's tokens combine. ALL_TERMS is the list UI's mode: every token is
 * a conjunct, so results narrow as the user types. ANY_TERM is the RECALL mode
 * for the model tool's second pass: every token prefixed and OR'd, ranked by
 * bm25. A model queries in concept words — measured live, GLM asked for
 * "grocery list recommendations store" — and under AND semantics each extra
 * word is one more reason to return nothing.
 */
enum class ChatSearchMatchMode { ALL_TERMS, ANY_TERM }

/** One hit. Carries full provenance even though the thread-list card renders only the title and snippet. */
data class ChatSearchResult(
    val messageID: String,
    val threadID: String,
    val role: String,
    val timestampMs: Long,
    /** The matching passage, with [ChatSearchIndex.HIGHLIGHT_OPEN]/[ChatSearchIndex.HIGHLIGHT_CLOSE] wrapped around the matched terms. */
    val snippet: String,
) {
    val id: String get() = messageID
}

/** What a reconcile changed. Returned rather than logged so a test can assert convergence. */
data class ChatSearchReconcileStats(val inserted: Int = 0, val removed: Int = 0, val unchanged: Int = 0) {
    val isConverged: Boolean get() = inserted == 0 && removed == 0
}

class ChatSearchIndexException(val operation: String, message: String) : Exception("chat index $operation failed: $message")

/** The seam tests stub. Production is [ChatSearchIndex]. */
interface ChatSearchIndexing {
    suspend fun index(records: List<ChatMessageRecord>)
    suspend fun remove(messageIDs: List<String>)
    suspend fun removeThread(threadID: String)
    suspend fun removeAll()
    suspend fun indexedMessageIDs(): Set<String>

    /**
     * [sinceMs] and [excludingThread] are applied IN SQL, before [limit].
     * Filtering rank-ordered rows after an over-fetch is not equivalent: a
     * common term with a deep history fills any fixed window with rows the
     * filter then discards, and the caller reads that as "no matches".
     */
    suspend fun search(
        query: String,
        granularity: ChatSearchGranularity = ChatSearchGranularity.THREADS,
        limit: Int = 50,
        sinceMs: Long? = null,
        excludingThread: String? = null,
        mode: ChatSearchMatchMode = ChatSearchMatchMode.ALL_TERMS,
    ): List<ChatSearchResult>

    /**
     * The stored message directly after [afterMs] in a thread, or null at the
     * thread's end. Exists because bm25 finds the message that SHARES the
     * query's words — usually the question — while the answer beside it rarely
     * repeats them (measured: a grocery list matched on nothing).
     */
    suspend fun following(threadID: String, afterMs: Long): ChatSearchResult?

    /**
     * Content-led rows around [aroundMs] in a thread, ascending: the row AT the
     * moment, up to [before] messages preceding it and [after] following.
     * `(0,0)` is "this message, whole". Rows carry FULL stored content in
     * `snippet` (callers cap) — a model reconstructing a discussion needs the words.
     */
    suspend fun window(threadID: String, aroundMs: Long, before: Int, after: Int): List<ChatSearchResult>
}
