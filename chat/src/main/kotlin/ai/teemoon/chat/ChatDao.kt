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

    /** A chat is named once, by its first question; a title already there (an imported chat's own) stays. */
    @Query("UPDATE threads SET title = :title WHERE id = :id AND (title IS NULL OR title = '')")
    suspend fun setTitleIfNone(id: String, title: String)

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

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<String>)

    /** SentBeforeConfirmed.settle: every row still marked [from] takes [to]. Returns how many changed. */
    @Query("UPDATE messages SET sentBeforeConfirmed = :to WHERE sentBeforeConfirmed = :from")
    suspend fun rewriteSentBeforeConfirmed(from: String, to: String?): Int

    @Query("UPDATE messages SET timestamp = :timestamp WHERE id = :id")
    suspend fun setTimestamp(id: String, timestamp: Long)

    /** Think Harder's new reply lands (iOS ReplySupersession .replace): the old one goes, in the same save. */
    @Transaction
    suspend fun supersede(oldIds: List<String>, questionId: String, askedAt: Long, reply: MessageEntity) {
        deleteMessages(oldIds)
        setTimestamp(questionId, askedAt)
        upsertMessage(reply)
    }

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

    @Query("SELECT EXISTS(SELECT 1 FROM threads WHERE id = :id)")
    suspend fun threadExists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessages(messages: List<MessageEntity>)

    /**
     * An imported chat (chat.imports): the thread and every message, or none of them. ABORT, not REPLACE: the writer
     * checked the thread is not here, and a clash would be a bug to surface, never a row to overwrite.
     */
    @Transaction
    suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>) {
        insertThread(thread)
        insertMessages(messages)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertThread(thread: ThreadEntity)

    // MARK: images (IMAGES.md §1). Rows go with their message by ON DELETE CASCADE; files go by the store's sweep.

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE message_id IN (:messageIds) ORDER BY message_id, idx")
    suspend fun attachmentsFor(messageIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE message_id IN (SELECT id FROM messages WHERE threadId = :threadId) ORDER BY message_id, idx")
    fun observeAttachments(threadId: String): Flow<List<AttachmentEntity>>

    /** Every file a row still names: what the sweep keeps. */
    @Query("SELECT path FROM attachments")
    suspend fun attachmentPaths(): List<String>

    /** An imported chat with its images: the thread, every message and every attachment row, or none of them. */
    @Transaction
    suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>, attachments: List<AttachmentEntity>) {
        insertThread(thread)
        insertMessages(messages)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
    }

    @Transaction
    suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {
        upsertThread(thread)
        upsertMessage(first)
    }
}
