// An in-memory ChatDao that keeps Room's foreign key: a message for a thread that isn't there throws, and deleting a
// thread deletes its messages (CASCADE). Shared by the store-facing view-model tests.
package ai.teemoon.app

import ai.teemoon.chat.ChatDao
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.ThreadPreview
import kotlinx.coroutines.flow.flowOf

/** Room's FOREIGN KEY, kept: see the file comment. */
internal class FakeChatDao : ChatDao {
    override suspend fun hasMessages(threadId: String): Boolean = messages(threadId).isNotEmpty()
    val threads = mutableListOf<ThreadEntity>(); val messages = mutableListOf<MessageEntity>()
    override fun observeThreads() = flowOf(threads.toList())
    override fun observeThreadsWithMessages() = flowOf(threads.filter { t -> messages.any { it.threadId == t.id } })
    override fun observeMessages(threadId: String) = flowOf(messages.filter { it.threadId == threadId })
    override suspend fun messages(threadId: String) = messages.filter { it.threadId == threadId }.sortedBy { it.timestamp }
    override suspend fun upsertThread(thread: ThreadEntity) { threads.removeAll { it.id == thread.id }; threads += thread }
    override suspend fun upsertMessage(message: MessageEntity) {
        // Room: the message's thread is a FOREIGN KEY (CASCADE on delete); a write to a deleted thread throws
        check(threads.any { it.id == message.threadId }) { "FOREIGN KEY constraint failed" }
        messages.removeAll { it.id == message.id }; messages += message }
    override suspend fun updateContent(id: String, content: String) {}
    override suspend fun setTitle(id: String, title: String) {}
    override suspend fun deleteThread(id: String) { threads.removeAll { it.id == id }; messages.removeAll { it.threadId == id } }
    override suspend fun deleteAllThreads() { threads.clear() }
    override suspend fun deleteAllMessages() { messages.clear() }
    override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) { messages.removeAll { it.threadId == threadId && it.timestamp >= sinceMs } }
    override suspend fun deleteMessages(ids: List<String>) { messages.removeAll { it.id in ids } }
    override suspend fun rewriteSentBeforeConfirmed(from: String, to: String?): Int { var n = 0; messages.replaceAll { if (it.sentBeforeConfirmed == from) { n++; it.copy(sentBeforeConfirmed = to) } else it }; return n }
    override suspend fun setTimestamp(id: String, timestamp: Long) { messages.replaceAll { if (it.id == id) it.copy(timestamp = timestamp) else it } }
    override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
    override suspend fun threadIdsWithContent(needle: String) = emptyList<String>()
    override suspend fun indexableMessageIds() = emptyList<String>()
    override suspend fun messagesByIds(ids: List<String>) = messages.filter { it.id in ids }
    override suspend fun startThread(thread: ThreadEntity, first: MessageEntity) {}
    override suspend fun setTitleIfNone(id: String, title: String) {}
    override suspend fun insertAttachments(attachments: List<ai.teemoon.chat.AttachmentEntity>) {}
    override suspend fun attachmentsFor(messageIds: List<String>) = emptyList<ai.teemoon.chat.AttachmentEntity>()
    override fun observeAttachments(threadId: String) = kotlinx.coroutines.flow.flowOf(emptyList<ai.teemoon.chat.AttachmentEntity>())
    override suspend fun attachmentPaths() = emptyList<String>()
    override suspend fun threadExists(id: String) = threads.any { it.id == id }
    override suspend fun insertThread(thread: ThreadEntity) { threads += thread }
    override suspend fun insertMessages(messages: List<MessageEntity>) { this.messages += messages }
}
