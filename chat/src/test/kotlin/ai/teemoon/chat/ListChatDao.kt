package ai.teemoon.chat

import kotlinx.coroutines.flow.flowOf

/** The store, as lists: enough of Room's behaviour for repository tests that run without a device. */
class ListChatDao : ChatDao {
    val threads = mutableListOf<ThreadEntity>(); val messages = mutableListOf<MessageEntity>()
    override fun observeThreads() = flowOf(threads.toList())
    override fun observeThreadsWithMessages() = flowOf(threads.filter { t -> messages.any { it.threadId == t.id } })
    override fun observeMessages(threadId: String) = flowOf(messages.filter { it.threadId == threadId })
    override suspend fun messages(threadId: String) = messages.filter { it.threadId == threadId }.sortedBy { it.timestamp }
    override suspend fun upsertThread(thread: ThreadEntity) { threads.removeAll { it.id == thread.id }; threads += thread }
    override suspend fun upsertMessage(message: MessageEntity) { messages.removeAll { it.id == message.id }; messages += message }
    override suspend fun updateContent(id: String, content: String) { messages.replaceAll { if (it.id == id) it.copy(content = content) else it } }
    override suspend fun setTitle(id: String, title: String) { threads.replaceAll { if (it.id == id) it.copy(title = title) else it } }
    override suspend fun setTitleIfNone(id: String, title: String) { threads.replaceAll { if (it.id == id && it.title.isNullOrEmpty()) it.copy(title = title) else it } }
    override suspend fun deleteThread(id: String) { threads.removeAll { it.id == id }; messages.removeAll { it.threadId == id }; cascade() }
    /** ON DELETE CASCADE, as SQLite does it: an attachment goes with its message. */
    private fun cascade() { attachments.removeAll { a -> messages.none { it.id == a.messageId } } }
    override suspend fun deleteAllThreads() { threads.clear() }
    override suspend fun deleteAllMessages() { messages.clear(); cascade() }
    override suspend fun deleteMessagesSince(threadId: String, sinceMs: Long) { messages.removeAll { it.threadId == threadId && it.timestamp >= sinceMs }; cascade() }
    override suspend fun deleteMessages(ids: List<String>) { messages.removeAll { it.id in ids }; cascade() }
    override suspend fun rewriteSentBeforeConfirmed(from: String, to: String?): Int { var n = 0; messages.replaceAll { if (it.sentBeforeConfirmed == from) { n++; it.copy(sentBeforeConfirmed = to) } else it }; return n }
    override suspend fun setTimestamp(id: String, timestamp: Long) { messages.replaceAll { if (it.id == id) it.copy(timestamp = timestamp) else it } }
    override fun observePreviews() = flowOf(emptyList<ThreadPreview>())
    override suspend fun threadIdsWithContent(needle: String) = messages.filter { needle in it.content }.map { it.threadId }.distinct()
    override suspend fun indexableMessageIds() = messages.filter { m -> threads.any { it.id == m.threadId } }.map { it.id }
    override suspend fun messagesByIds(ids: List<String>) = messages.filter { it.id in ids }
    override suspend fun threadExists(id: String) = threads.any { it.id == id }
    override suspend fun insertThread(thread: ThreadEntity) { check(threads.none { it.id == thread.id }); threads += thread }
    val attachments = mutableListOf<AttachmentEntity>()
    override suspend fun insertAttachments(attachments: List<AttachmentEntity>) { for (a in attachments) { check(messages.any { it.id == a.messageId }) { "no message ${a.messageId}" }; check(this.attachments.none { it.id == a.id }) }; this.attachments += attachments }
    override suspend fun attachmentsFor(messageIds: List<String>) = attachments.filter { it.messageId in messageIds }.sortedWith(compareBy({ it.messageId }, { it.idx }))
    override fun observeAttachments(threadId: String) = flowOf(attachments.filter { a -> messages.any { it.id == a.messageId && it.threadId == threadId } })
    override suspend fun attachmentPaths() = attachments.map { it.path }
    override suspend fun insertMessages(messages: List<MessageEntity>) { for (m in messages) check(this.messages.none { it.id == m.id }); this.messages += messages }
}
