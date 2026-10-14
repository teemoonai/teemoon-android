package ai.teemoon.app

// ports: teemoon/Views/Chat/ChatsListView.swift @ v1.0.1 (an empty thread is never listed; "new chat" with nothing typed is a no-op)
import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.ThreadPreview
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadCreationTest {
    private class Dao : ChatDao {
        val threads = mutableListOf<ThreadEntity>(); val messages = mutableListOf<MessageEntity>()
        override fun observeThreads() = flowOf(threads.toList())
        override fun observeThreadsWithMessages() = flowOf(threads.filter { t -> messages.any { it.threadId == t.id } })
        override fun observeMessages(threadId: String) = flowOf(messages.filter { it.threadId == threadId })
        override suspend fun messages(threadId: String) = messages.filter { it.threadId == threadId }
        override suspend fun upsertThread(thread: ThreadEntity) { threads.removeAll { it.id == thread.id }; threads += thread }
        override suspend fun upsertMessage(message: MessageEntity) { messages += message }
        override suspend fun updateContent(id: String, content: String) {}
        override suspend fun setTitle(id: String, title: String) {}
        override suspend fun deleteThread(id: String) { threads.removeAll { it.id == id } }
        override suspend fun deleteAllThreads() { threads.clear() }
        override suspend fun deleteAllMessages() { messages.clear() }
        override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) { messages.removeAll { it.threadId == threadId && it.timestamp >= sinceMs } }
        override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
        override suspend fun threadIdsWithContent(needle: String) = emptyList<String>()
        override suspend fun indexableMessageIds() = emptyList<String>()
        override suspend fun messagesByIds(ids: List<String>) = emptyList<MessageEntity>()
        override suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {}
    }

    @Test fun newChatReusesAnEmptyCurrentThreadAndMintsOneOtherwise() = runTest {
        val dao = Dao(); val repo = ChatRepository(dao); var n = 0
        val first = repo.newOrCurrentThread(null, 1L) { "t-${++n}" }
        assertEquals("t-1", first)
        // Nothing typed yet: "new chat" again is the same thread, not a second empty row.
        assertEquals("t-1", repo.newOrCurrentThread(first, 2L) { "t-${++n}" })
        assertEquals(1, dao.threads.size)
        dao.upsertMessage(MessageEntity(id = "m1", threadId = first, role = "user", content = "hi", timestamp = 3L))
        assertEquals("t-2", repo.newOrCurrentThread(first, 4L) { "t-${++n}" })
    }
}
