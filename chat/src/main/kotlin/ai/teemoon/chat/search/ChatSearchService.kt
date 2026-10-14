// ports: teemoon/Chat/Search/ChatSearchService.swift @ v1.0.1
//
// The app's single entry point to the chat search index: owns it, hardens it,
// reconciles it at launch, and takes the incremental hooks from the sites
// that mutate chat data.
//
// HOOKS ARE BEST-EFFORT AND SILENT ON FAILURE, BY DESIGN. Every hook is a
// queued job whose error is discarded, because reconcile() at next launch is
// what actually guarantees correctness. A hook that threw into the send path
// would risk a message for the sake of an index that repairs itself.
//
// NO INDEX MEANS NO SEARCH, NOT AN EMPTY SEARCH. search() returns null when
// the index is unavailable so the caller can fall back to the in-memory
// filter. Returning [] would state that the user's history does not contain
// the term, which is a different — and false — claim.
package ai.teemoon.chat.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class ChatSearchService(
    private val scope: CoroutineScope,
    index: ChatSearchIndexing? = null,
    private val log: (String) -> Unit = {},
) {
    var index: ChatSearchIndexing? = index
        private set

    /** True once a launch reconcile has finished, so the UI can tell "no matches" apart from "not indexed yet". */
    var isReady: Boolean = index != null
        private set

    // MARK: - Lifecycle

    /**
     * Creates the sidecar for a store, or deliberately does nothing. The
     * hardening call is not optional: this file is a full second copy of every
     * message in plaintext, so it needs the same backup exclusion as the store
     * it shadows — otherwise adding search quietly undoes the store's hardening.
     */
    fun configure(storeFile: File, isStoredInMemoryOnly: Boolean, harden: (File) -> Unit) {
        val file = ChatSearchIndex.sidecarFile(storeFile, isStoredInMemoryOnly)
        if (file == null) { index = null; isReady = false; return }
        index = ChatSearchIndex(file, warn = log)
        harden(file)
    }

    /** Converges the index onto the store. Safe to call on every launch. */
    suspend fun reconcile(source: ChatSearchSource) {
        val index = index ?: return
        try {
            val stats = ChatSearchReconciler(index, log).reconcile(source)
            isReady = true
            if (!stats.isConverged) log("chat index converged: +${stats.inserted} -${stats.removed}")
        } catch (e: Exception) {
            // Leave isReady false: the list falls back to the in-memory filter rather than showing a confidently empty result.
            log("chat index reconcile failed: $e")
        }
    }

    /** Re-hardens the sidecar. The sqlite file is created lazily by the first write, so a reharden before any write finds no file and no-ops. */
    fun rehardenIfNeeded(harden: (File) -> Unit) {
        val file = (index as? ChatSearchIndex)?.databaseFile ?: return
        if (file.exists()) harden(file)
    }

    // MARK: - Hooks

    /**
     * Hooks run in the order they were called, one at a time. NOT a bare
     * launch per hook, which was the first version and was wrong: unordered
     * jobs let a write immediately followed by a delete of the same message —
     * exactly what retry truncation does — apply in either order and leave a
     * deleted message searchable until the next launch.
     */
    private var tail: Job = Job().also { it.complete() }

    private fun enqueue(work: suspend () -> Unit) {
        val previous = tail
        tail = scope.launch { previous.join(); work() }
    }

    /** Joins the hook queue. Tests only — production never needs to wait. */
    suspend fun drainPendingWork() { tail.join() }

    /**
     * A message was written or grown. Upserts, so calling it repeatedly while a
     * reply streams is correct and cheap. [isPersisted] gates out detached
     * messages — a turn built on a thread that is never inserted must not be
     * indexed under a thread id no row can open. Detached in, nothing out.
     */
    fun didWrite(record: ChatMessageRecord, isPersisted: Boolean = true) {
        val index = index ?: return
        if (!isPersisted || record.threadID.isEmpty()) return
        enqueue { runCatching { index.index(listOf(record)) } }
    }

    /** Retry truncation deletes the messages after a given turn. */
    fun didDelete(messageIDs: List<String>) {
        val index = index ?: return
        if (messageIDs.isEmpty()) return
        enqueue { runCatching { index.remove(messageIDs) } }
    }

    /** A thread was deleted; the index must drop its rows either way. */
    fun didDeleteThread(threadID: String) {
        val index = index ?: return
        enqueue { runCatching { index.removeThread(threadID) } }
    }

    /** "delete all chats". Empties the index outright: leaving it would keep a searchable plaintext copy of everything the user just deleted. */
    fun didDeleteAllChats() {
        val index = index ?: return
        enqueue { runCatching { index.removeAll() } }
    }

    // MARK: - Query

    /** Ranked results, or null when there is no usable index. null is not "no matches". */
    suspend fun search(query: String, granularity: ChatSearchGranularity = ChatSearchGranularity.THREADS, limit: Int = 50): List<ChatSearchResult>? {
        val index = index ?: return null
        if (!isReady) return null
        return try { index.search(query, granularity, limit) } catch (e: Exception) {
            // The TYPE only: an FTS5 syntax error quotes the expression it choked on — the user's search words — and the
            // system log is readable over adb and in bug reports (iOS logs these `.private`; Android has no equivalent).
            log("chat search failed: ${e.javaClass.simpleName}"); null
        }
    }
}
