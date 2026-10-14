// ports: teemoon/Chat/Search/ChatSearchReconciler.swift @ v1.0.1
//
// Brings the FTS5 sidecar back in line with the Room store.
//
// THE RECONCILER IS AUTHORITATIVE AND HOOKS ARE ONLY AN OPTIMISATION. There
// are six places that mutate chat data, and a hook-only design silently rots
// on at least two of them (thread delete, bulk delete, and any path that
// appends messages outside the view model). A full rebuild of the reference
// store (7,209 indexable messages) measured 0.23 s cold on iOS. Correctness is
// that cheap — never trade it for cleverness.
//
// ORPHANS ARE NEVER INDEXED. On iOS deleting a Thread nullifies its messages;
// on Android the foreign key cascades, but the source's join keeps the
// invariant in the store rather than in trust: a result that cannot be opened
// is not a result.
package ai.teemoon.chat.search

import ai.teemoon.chat.ChatDao

/** The store side of the diff: identifiers first (cheap), then content for the missing rows ONLY. */
interface ChatSearchSource {
    /** Ids of every message that still belongs to a thread. */
    suspend fun indexableMessageIDs(): Set<String>
    /** Snapshots the given messages; drops anything whose thread vanished between the two fetches. */
    suspend fun records(ids: Set<String>): List<ChatMessageRecord>
}

/** The Room store as a [ChatSearchSource]. `IN (:ids)` is chunked under sqlite's variable limit. */
class RoomChatSearchSource(private val dao: ChatDao) : ChatSearchSource {
    override suspend fun indexableMessageIDs(): Set<String> = dao.indexableMessageIds().toSet()
    override suspend fun records(ids: Set<String>): List<ChatMessageRecord> {
        val live = indexableMessageIDs()
        return ids.chunked(500).flatMap { chunk -> dao.messagesByIds(chunk) }
            .filter { it.id in live }
            .map { ChatMessageRecord(it.id, it.threadId, it.role, it.timestamp, it.content) }
    }
}

/** Diffs the store against the index and applies the difference. */
class ChatSearchReconciler(private val index: ChatSearchIndexing, private val log: (String) -> Unit = {}) {

    /**
     * Converges the index onto the store, and returns what it took. Three
     * passes, in this order for a reason: identifiers first (cheap), then the
     * set difference, then content for the missing rows ONLY. Fetching every
     * message with its text would pull ~12 MB through the store on launch to
     * discover that nothing changed.
     */
    suspend fun reconcile(source: ChatSearchSource): ChatSearchReconcileStats {
        val liveIDs = source.indexableMessageIDs()
        val indexedIDs = index.indexedMessageIDs()
        val missing = liveIDs - indexedIDs
        val stale = indexedIDs - liveIDs
        if (stale.isNotEmpty()) index.remove(stale.toList())
        if (missing.isNotEmpty()) index.index(source.records(missing))
        val stats = ChatSearchReconcileStats(inserted = missing.size, removed = stale.size, unchanged = liveIDs.size - missing.size)
        if (!stats.isConverged) log("chat index reconciled: +${stats.inserted} -${stats.removed}")
        return stats
    }
}
