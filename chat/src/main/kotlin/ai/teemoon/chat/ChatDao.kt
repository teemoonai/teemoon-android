package ai.teemoon.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM threads ORDER BY timestamp DESC")
    fun observeThreads(): Flow<List<ThreadEntity>>

    /**
     * ChatsListView never shows an empty thread: on iOS "new chat" clears the current thread and a Thread is
     * only created by the first send. Here a row exists from "new chat" on (the chat screen needs an id), so the
     * LIST reads only threads that have a message.
     */
    @Query("SELECT * FROM threads WHERE id IN (SELECT DISTINCT threadId FROM messages) ORDER BY timestamp DESC")
    fun observeThreadsWithMessages(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun observeMessages(threadId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    suspend fun messages(threadId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThread(thread: ThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("UPDATE threads SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("DELETE FROM threads WHERE id = :id")
    suspend fun deleteThread(id: String)

    /** ThreadDeletion.deleteAll: threads and messages both — messages cascade, the explicit sweep is belt and braces. */
    @Query("DELETE FROM threads")
    suspend fun deleteAllThreads()
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    /** Everything from a moment on — a retry regenerates from the user turn, so the old reply goes. */
    @Query("DELETE FROM messages WHERE threadId = :threadId AND timestamp >= :sinceMs")
    suspend fun deleteMessagesSince(threadId: String, sinceMs: Long)

    // MARK: chat search (ChatSearchReconciler's two fetches — identity first, then content for the missing rows only)

    /** Each thread's latest assistant reply — the list row's preview line (iOS ChatRowView.preview). */
    @Query("SELECT threadId, content FROM messages WHERE role = 'assistant' AND id IN (SELECT id FROM messages WHERE role = 'assistant' GROUP BY threadId HAVING MAX(timestamp)) ")
    fun observePreviews(): Flow<List<ThreadPreview>>

    /** The no-index fallback: threads with a message containing the text (iOS ChatsListView.fallbackFilteredThreads). */
    @Query("SELECT DISTINCT threadId FROM messages WHERE content LIKE '%' || :needle || '%'")
    suspend fun threadIdsWithContent(needle: String): List<String>

    /** Ids of every message that still belongs to a thread. The join is the orphan filter, done by the store. */
    @Query("SELECT m.id FROM messages m JOIN threads t ON t.id = m.threadId")
    suspend fun indexableMessageIds(): List<String>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun messagesByIds(ids: List<String>): List<MessageEntity>

    @Transaction
    suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {
        upsertThread(thread)
        upsertMessage(first)
    }
}
