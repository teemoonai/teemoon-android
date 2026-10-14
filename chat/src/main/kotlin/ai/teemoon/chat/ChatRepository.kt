package ai.teemoon.chat

import ai.teemoon.chat.search.ChatMessageRecord
import ai.teemoon.chat.search.ChatSearchService
import kotlinx.coroutines.flow.Flow

/**
 * Conversations, persisted. The only thing above this layer that knows Room exists.
 * [search] takes the incremental index hooks at the two sites that mutate chat
 * data here; the launch reconcile is what guarantees correctness, so a missed
 * hook is a stale index until next launch, never lost data.
 */
class ChatRepository(private val dao: ChatDao, private val search: ChatSearchService? = null) {

    fun threads(): Flow<List<ThreadEntity>> = dao.observeThreads()

    /** The chats list: threads with at least one message (see [ChatDao.observeThreadsWithMessages]). */
    fun visibleThreads(): Flow<List<ThreadEntity>> = dao.observeThreadsWithMessages()

    /**
     * "new chat" while the current thread is still empty is a no-op on iOS (there is no thread to leave). Reuse the
     * empty one instead of minting another, so a launch or a tap never piles up invisible rows.
     */
    suspend fun newOrCurrentThread(currentId: String?, now: Long, newId: () -> String): String {
        if (currentId != null && dao.messages(currentId).isEmpty()) return currentId
        return createThread(newId(), now).id
    }
    fun messages(threadId: String): Flow<List<MessageEntity>> = dao.observeMessages(threadId)
    fun previews(): Flow<List<ThreadPreview>> = dao.observePreviews()
    /** Only when the index cannot answer: a plain substring scan over message content. */
    suspend fun fallbackThreadsMatching(query: String): Set<String> = dao.threadIdsWithContent(query).toSet()

    suspend fun createThread(id: String, now: Long): ThreadEntity =
        ThreadEntity(id = id, title = null, timestamp = now).also { dao.upsertThread(it) }

    suspend fun addMessage(
        id: String, threadId: String, role: String, content: String, now: Long,
        sourcesJson: String? = null, isE2EE: Boolean = false, generatingTimeMs: Long? = null,
    ): MessageEntity = MessageEntity(
        id = id, threadId = threadId, role = role, content = content, timestamp = now,
        sourcesJson = sourcesJson, isE2EE = isE2EE, generatingTimeMs = generatingTimeMs,
    ).also {
        dao.upsertMessage(it)
        search?.didWrite(ChatMessageRecord(it.id, it.threadId, it.role, it.timestamp, it.content))
    }

    suspend fun updateContent(id: String, content: String) = dao.updateContent(id, content)

    /**
     * The thread title is the first user message, trimmed to a line. iOS derives
     * it rather than asking, so an unnamed thread is still findable.
     */
    suspend fun titleFromFirstMessage(threadId: String, text: String) {
        val title = text.trim().lineSequence().firstOrNull().orEmpty().take(60)
        if (title.isNotEmpty()) dao.setTitle(threadId, title)
    }

    /** iOS ChatView.retry(from:): the messages from [sinceMs] on are dropped so the turn can be generated again. Returns the dropped ids. */
    suspend fun deleteMessagesSince(threadId: String, sinceMs: Long): List<String> {
        val dropped = dao.messages(threadId).filter { it.timestamp >= sinceMs }.map { it.id }
        dao.deleteMessagesSince(threadId, sinceMs)
        if (dropped.isNotEmpty()) search?.didDelete(dropped)
        return dropped
    }

    /** "delete all chats": the store and the search index together (iOS ChatsSettingsView.deleteChats). */
    suspend fun deleteAll() {
        dao.deleteAllThreads(); dao.deleteAllMessages()
        search?.didDeleteAllChats()
    }

    suspend fun delete(threadId: String) {
        dao.deleteThread(threadId)
        search?.didDeleteThread(threadId)
    }
}
