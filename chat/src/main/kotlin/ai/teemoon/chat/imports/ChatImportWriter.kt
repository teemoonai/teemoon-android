// ports: teemoon/Chat/Import/ChatImportWriter.swift @ 672a7ee, with the dry run of cdea210c — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
package ai.teemoon.chat.imports

import ai.teemoon.chat.AttachmentEntity
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.imports.ImportedMessage.Part
import java.net.URI

/** Where imported chats land: [ChatRepository] in the app, a map in the tests. */
interface ImportStore {
    suspend fun hasThread(id: String): Boolean
    /** The thread and all its messages in ONE transaction: an interrupted import leaves whole chats or nothing. */
    suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>)
    /**
     * With images (IMAGES.md §3): the rows in one transaction, then each pending file moved to its row's path. A store
     * without images keeps the default, which only takes text.
     */
    suspend fun insertChat(thread: ThreadEntity, messages: List<MessageEntity>, attachments: List<AttachmentEntity>, files: List<IngestedImage>) {
        check(attachments.isEmpty()) { "this store keeps no images" }
        insertChat(thread, messages)
    }
}

/**
 * Turns an image's payload into a stored image: the app's isolated decoder behind it (IMAGES.md §2). Null: it did not
 * decode, and the part stays a placeholder line. Source-neutral: it sees a payload, never a source.
 */
fun interface ImageIngest {
    suspend fun ingest(payload: String, attachmentId: String): IngestedImage?
}

/** A decoded, re-encoded image waiting in [pending] for its row to commit; [relativePath] is the row's `path`. */
class IngestedImage(
    val mime: String, val width: Int, val height: Int, val thumbnail: ByteArray,
    val pending: java.io.File, val relativePath: String,
)

/** A cited page as this app stores it (GroundingSource's fields; the encoder is the app's own, passed in). */
data class StoredSource(val url: String, val domain: String, val title: String)

/**
 * The shared half (IMPORT_NEAR.md §0): source-neutral chats in, rows out. Knows no source — every rule here is how
 * THIS app stores a chat, whichever app it came from (§2, §3.5, §3.7–3.10, §6). reference_import.py `write` is the
 * definition.
 *
 * Keys are the contract both platforms share: thread `<source>:<chat sourceId>`, message
 * `<source>:<chat sourceId>:<message sourceId>` (a message id may be unique only within its chat). Android stores the
 * key string itself as the row id; iOS derives a v5 UUID from the same string. So the source lives in the id prefix,
 * and no column — and no schema change — is needed for this source or the next.
 */
class ChatImportWriter(
    private val store: ImportStore,
    /** The app's own source encoder (GroundingSource.encodedJSON); null when there is nothing to store. */
    private val encodeSources: (List<StoredSource>) -> String?,
    /** After each conversation is handled, with the running counts: the progress line. */
    private val onProgress: (ImportSummary) -> Unit = {},
    /** Images (IMAGES.md §3); null: every image is a placeholder line, as before 1.1.0's images. */
    private val images: ImageIngest? = null,
    /**
     * Count what would be written and write nothing: the import page's confirm step (iOS cdea210c). Images are still
     * decoded, so the count is the import's, and each decoded file is dropped at once.
     */
    private val dryRun: Boolean = false,
    /** The file's top-level elements read so far, skips included: the progress bar's position. */
    private val onElements: (Int) -> Unit = {},
) {
    /** Elements the scan read: after a dry run, the progress total for the import that follows. */
    var elementsRead = 0
        private set
    /** What a dry run has counted, so a chat twice in one file counts the way the import treats it. */
    private val counted = HashSet<String>()

    suspend fun write(events: Sequence<ImportEvent>): ImportSummary {
        var s = ImportSummary()
        for (event in events) {
            s = when (event) {
                is ImportEvent.Skipped -> s.copy(itemsSkipped = s.itemsSkipped + event.count)
                is ImportEvent.Chat -> chat(event.chat, s)
            }
            elementsRead += if (event is ImportEvent.Skipped) event.count else 1
            onProgress(s)
            onElements(elementsRead)
        }
        return s
    }

    private suspend fun chat(chat: ImportedChat, before: ImportSummary): ImportSummary {
        val threadId = "${chat.source}:${chat.sourceId}"
        // §2: re-import is a no-op. A chat already here — from an earlier import, or earlier in this same file — is
        // skipped WHOLE: nothing merged, nothing changed, and nothing read from it counted. A chat the user deleted is
        // not here, so it comes back with the same keys and content (the maintainer, 2026-10-25).
        if (store.hasThread(threadId) || (dryRun && threadId in counted)) return before.copy(chatsAlreadyHere = before.chatsAlreadyHere + 1)
        var s = before.copy(itemsSkipped = before.itemsSkipped + chat.itemsSkipped, mayBeIncomplete = before.mayBeIncomplete || chat.mayBeIncomplete)
        var last = 0L
        val messages = ArrayList<MessageEntity>()
        val attachments = ArrayList<AttachmentEntity>()
        val files = ArrayList<IngestedImage>()
        for (m in chat.messages) {
            val messageId = "$threadId:${m.sourceId}"
            val pieces = ArrayList<String>()
            val mine = ArrayList<AttachmentEntity>()
            var imageIndex = 0
            for (p in m.parts) when (p) {
                is Part.Text -> pieces += p.text
                is Part.Placeholder -> {
                    if (p.kind != Part.Kind.IMAGE || images == null) { pieces += line(p); continue }
                    // IMAGES.md §3: index = the part's place among the message's images; id = "<message key>#<index>".
                    val index = imageIndex++
                    val id = "$messageId#$index"
                    val stored = p.image?.payload?.invoke()?.let { images.ingest(it, id) }
                    if (stored == null) { pieces += line(p); s = s.copy(imagesSkipped = s.imagesSkipped + 1); continue }
                    mine += AttachmentEntity(id, messageId, index, stored.mime, stored.width, stored.height, stored.relativePath, stored.thumbnail)
                    files += stored
                }
            }
            val text = pieces.joinToString("\n\n")
            // §3.5: a message that comes out empty is skipped and counted once — unless it carries images.
            if (text.isEmpty() && mine.isEmpty()) { s = s.copy(itemsSkipped = s.itemsSkipped + 1); continue }
            attachments += mine
            // §3.7: the transcript is ordered by time, so a message not later than the one before it is placed 1 ms
            // after it (near has same-second replies; a legacy chat has three messages in one second).
            val at = if (m.createdAtMs > last) m.createdAtMs else last + 1
            last = at
            messages += MessageEntity(
                id = messageId,
                threadId = threadId,
                role = m.role.stored,
                content = text,
                timestamp = at,
                // §3.6: an imported message was never end-to-end encrypted here, and was not generated here.
                isE2EE = false,
                sourcesJson = encodeSources(stored(m.sources)),
                generatingTimeMs = null,
            )
        }
        // §3.10: nothing to keep, no thread; it counts one on top of its unreadable items.
        if (messages.isEmpty()) { files.forEach { it.pending.delete() }; return s.copy(itemsSkipped = s.itemsSkipped + 1) }
        // §3.9: the source's title whole; none → this app's own rule, the first user message's first line. The thread's
        // time is its last message's, so imported chats sort by their last activity.
        val first = messages.firstOrNull { it.role == "user" } ?: messages.first()
        val title = chat.title ?: ChatRepository.titleFrom(first.content)
            ?: attachments.count { it.messageId == first.id }.takeIf { it > 0 }?.let(ChatRepository::imagesTitle)
        val thread = ThreadEntity(id = threadId, title = title, timestamp = last)
        if (dryRun) {
            counted += threadId
            files.forEach { it.pending.delete() }
        } else if (attachments.isEmpty()) store.insertChat(thread, messages) else store.insertChat(thread, messages, attachments, files)
        return s.copy(chatsImported = s.chatsImported + 1, messagesImported = s.messagesImported + messages.size, imagesImported = s.imagesImported + attachments.size)
    }

    /** §3.8: first occurrence of a URL wins; the domain is the web-search path's own host rule. */
    private fun stored(sources: List<ImportedSource>): List<StoredSource> {
        val seen = HashSet<String>()
        return sources.filter { seen.add(it.url) }.map { s ->
            val domain = domainOf(s.url)
            StoredSource(s.url, domain, s.title?.takeIf { it.isNotEmpty() } ?: domain)
        }
    }

    companion object {
        /**
         * §8, the maintainer's wording to come. Stored in the chat, so changing them changes the expected results in cases.json
         * (not the fixture files). The named form is tolerated, never produced by a real export.
         */
        const val IMAGE_PLACEHOLDER = "[image not imported]"
        const val FILE_PLACEHOLDER = "[file not imported]"

        fun line(p: Part.Placeholder): String {
            val base = if (p.kind == Part.Kind.IMAGE) IMAGE_PLACEHOLDER else FILE_PLACEHOLDER
            return if (p.name != null) base.dropLast(1) + ": " + p.name + "]" else base
        }

        /**
         * BraveWebSearchTool.parseSources' rule, `hostOf(url) ?: url`: `java.net.URI`'s host, so `www.` stays and the
         * case is kept (a mixed-case host is stored as written, as iOS's `URL.host` keeps it). A host URI cannot read
         * (an underscore, some non-ASCII names) gives the whole URL — the accepted platform difference in §3.8. Parsing
         * only: no lookup, no connection.
         */
        fun domainOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
    }
}
