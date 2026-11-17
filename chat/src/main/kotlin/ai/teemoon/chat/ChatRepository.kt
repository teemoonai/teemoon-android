package ai.teemoon.chat

import ai.teemoon.chat.imports.ImportStore
import ai.teemoon.chat.imports.IngestedImage
import ai.teemoon.chat.search.ChatMessageRecord
import ai.teemoon.chat.search.ChatSearchService
import kotlinx.coroutines.flow.Flow

/**
 * Conversations, persisted. The only thing above this layer that knows Room exists.
 * [search] takes the incremental index hooks at the two sites that mutate chat
 * data here; the launch reconcile is what guarantees correctness, so a missed
 * hook is a stale index until next launch, never lost data.
 */
/** What the store does after a delete so the deleted text does not stay readable in its files (ChatDatabase.maintenance). */
fun interface StoreMaintenance { suspend fun afterDelete(all: Boolean) }

class ChatRepository(
    private val dao: ChatDao, private val search: ChatSearchService? = null, private val maintenance: StoreMaintenance? = null,
    /** Image files (IMAGES.md §1); null where nothing stores images (most tests). */
    private val files: AttachmentFiles? = null,
) : ImportStore {
    /** Where this store keeps image files, for the code that writes them (the import, the composer). */
    val attachmentFiles: AttachmentFiles? get() = files


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
        /** A reply's [MessageStatus], written by ReplyMachine's persistRow; null for a question. */
        status: String? = null, sentBeforeConfirmed: String? = null,
    ): MessageEntity = MessageEntity(
        id = id, threadId = threadId, role = role, content = content, timestamp = now,
        sourcesJson = sourcesJson, isE2EE = isE2EE, generatingTimeMs = generatingTimeMs, status = status, sentBeforeConfirmed = sentBeforeConfirmed,
    ).also {
        dao.upsertMessage(it)
        search?.didWrite(ChatMessageRecord(it.id, it.threadId, it.role, it.timestamp, it.content))
    }

    suspend fun updateContent(id: String, content: String) = dao.updateContent(id, content)

    /**
     * The thread title is the first user message, trimmed to a line. iOS derives
     * it rather than asking, so an unnamed thread is still findable.
     *
     * Only when the thread has none (the maintainer, 2026-10-25, "a"): until 1.1.0 every send rewrote it, so the list showed the
     * newest question rather than the first, and continuing an imported near chat replaced its own title.
     */
    suspend fun titleFromFirstMessage(threadId: String, text: String) {
        titleFrom(text)?.let { dao.setTitleIfNone(threadId, it) }
    }

    /**
     * SentBeforeConfirmed.settle (iOS a27f0621): when [recordKey]'s verdict settles, every row still pending on it is
     * rewritten — pass clears the mark, a hard fail turns it into "failed|". Unsettled (or a soft outcome) changes nothing.
     */
    suspend fun settleSentBeforeConfirmed(recordKey: String, outcome: SentBeforeConfirmed.Outcome): Int {
        if (outcome == SentBeforeConfirmed.Outcome.UNSETTLED) return 0
        return dao.rewriteSentBeforeConfirmed(SentBeforeConfirmed.pending(recordKey),
            SentBeforeConfirmed.resolved(SentBeforeConfirmed.pending(recordKey), recordKey, outcome))
    }

    /** ReplyMachine's deleteRows: replies replaced or discarded, by id, with every delete's scrub. */
    suspend fun deleteReplies(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.deleteMessages(ids)
        search?.didDelete(ids)
        afterDelete(all = false)
    }

    /** iOS ChatView.retry(from:): the messages from [sinceMs] on are dropped so the turn can be generated again. Returns the dropped ids. */
    suspend fun deleteMessagesSince(threadId: String, sinceMs: Long): List<String> {
        val dropped = dao.messages(threadId).filter { it.timestamp >= sinceMs }.map { it.id }
        dao.deleteMessagesSince(threadId, sinceMs)
        if (dropped.isNotEmpty()) {
            search?.didDelete(dropped)
            // A retry deletes text too: the same scrub as every other delete (audit gardener, Pass 4-D — this path alone
            // skipped it), and the dropped messages' image files.
            afterDelete(all = false)
        }
        return dropped
    }

    /** Every delete ends here: image files whose rows cascaded away go, then the store's own scrub. */
    private suspend fun afterDelete(all: Boolean) {
        files?.sweep { dao.attachmentPaths() }
        maintenance?.afterDelete(all)
    }

    /** A thread's images, by message (IMAGES.md §5). */
    fun attachments(threadId: String): Flow<List<AttachmentEntity>> = dao.observeAttachments(threadId)

    /** One image as the composer hands it over: pixels the isolated decoder re-encoded, and their thumbnail. */
    class NewAttachment(val mime: String, val width: Int, val height: Int, val bytes: ByteArray, val thumbnail: ByteArray)

    /**
     * A sent message's images (IMAGES.md §4): each file written to pending/ first, the rows inserted, then the files moved
     * into place. Ids are random for a new send; [messageId]'s row must exist. Returns the rows as stored.
     */
    suspend fun addAttachments(messageId: String, images: List<NewAttachment>): List<AttachmentEntity> {
        val store = files ?: return emptyList()
        if (images.isEmpty()) return emptyList()
        val rows = ArrayList<AttachmentEntity>(); val pending = ArrayList<Pair<java.io.File, String>>()
        images.forEachIndexed { i, img ->
            val ext = if (img.mime == "image/png") "png" else "jpg"
            val id = java.util.UUID.randomUUID().toString()
            val path = id.replace("-", "") + "." + ext
            pending += store.newPending(ext).apply { writeBytes(img.bytes) } to path
            rows += AttachmentEntity(id, messageId, i, img.mime, img.width, img.height, path, img.thumbnail)
        }
        dao.insertAttachments(rows)
        for ((f, p) in pending) store.commit(f, p)
        return rows
    }

    /** A stored image's bytes, or null when its file is gone. */
    fun attachmentBytes(path: String): ByteArray? = files?.resolve(path)?.takeIf { it.isFile }?.readBytes()

    /** At launch: files no row names, and anything an interrupted import or send left pending. */
    suspend fun sweepAttachmentFiles(): Int = files?.sweep(includePending = true) { dao.attachmentPaths() } ?: 0

    /**
     * Think Harder's new reply in place of the old (iOS ChatViewModel, ReplySupersession .replace, 14b2974): the old
     * reply is deleted, the question restamped to when it was asked again, and the new reply saved, in one
     * transaction — a kill between them must leave the chat with an answer, the old one or the new.
     */
    suspend fun supersede(oldIds: List<String>, question: MessageEntity, askedAt: Long, reply: MessageEntity) {
        dao.supersede(oldIds, question.id, askedAt, reply)
        search?.didDelete(oldIds)
        search?.didWrite(ChatMessageRecord(question.id, question.threadId, question.role, askedAt, question.content))
        search?.didWrite(ChatMessageRecord(reply.id, reply.threadId, reply.role, reply.timestamp, reply.content))
        afterDelete(all = false)
    }

    /** "delete all chats": the store and the search index together (iOS ChatsSettingsView.deleteChats). */
    suspend fun deleteAll() {
        dao.deleteAllThreads(); dao.deleteAllMessages()
        search?.didDeleteAllChats()
        afterDelete(all = true)
    }

    override suspend fun hasThread(id: String): Boolean = dao.threadExists(id)

    /** An imported chat, whole, in one transaction; then the search index hears of each message, as [addMessage]'s do. */
    override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>) {
        dao.insertChat(thread, messages)
        for (m in messages) search?.didWrite(ChatMessageRecord(m.id, m.threadId, m.role, m.timestamp, m.content))
    }

    /**
     * An imported chat with images (IMAGES.md §3): every row in one transaction, then each image's pending file moved to
     * the path its row names. A kill between the two leaves rows whose file is still pending — the launch sweep clears
     * pending files, and the image reads as missing; never a half-written chat.
     */
    override suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>, attachments: List<AttachmentEntity>, files: List<IngestedImage>) {
        val store = checkNotNull(this.files) { "a repository without attachment files cannot keep images" }
        dao.insertChat(thread, messages, attachments)
        for (f in files) store.commit(f.pending, f.relativePath)
        for (m in messages) search?.didWrite(ChatMessageRecord(m.id, m.threadId, m.role, m.timestamp, m.content))
    }

    suspend fun delete(threadId: String) {
        dao.deleteThread(threadId)
        search?.didDeleteThread(threadId)
        afterDelete(all = false)
    }

    companion object {
        /** A chat's title from a message: its first line, trimmed, at most 60 characters; null when that is empty. */
        fun titleFrom(text: String): String? = text.trim().lineSequence().firstOrNull().orEmpty().take(60).takeIf { it.isNotEmpty() }

        /**
         * A message of images alone, where a title or a preview needs words (IMAGES.md §5, approved): "image" / "N images".
         * The app's ImageCopy.preview says the same; it lives here so the import writer, which knows no app strings, can
         * name a chat that opens with a picture.
         */
        fun imagesTitle(n: Int): String = if (n == 1) "image" else "$n images"
    }
}
