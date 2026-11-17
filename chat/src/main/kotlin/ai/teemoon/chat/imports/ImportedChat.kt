// ports: teemoon/Chat/Import/ImportedChat.swift @ 8d0b76c — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// spec: docs/IMPORT_NEAR.md draft 8 (teemoon-ios 9ad6ebd, sha256 c540b5fe…0d41), §0's layering: scan → source →
//       intermediate → writer. Android-native code; the Swift types it mirrors (ImportFileScanner, ChatImportSource,
//       ImportedChat, ChatImportWriter) are iOS's own, not a port source.
//
// Importing another app's chats, in halves that never meet: a SOURCE reads one app's export into the source-neutral
// chats below and chooses the branch; the shared [ChatImportWriter] turns them into rows. The writer knows no source,
// so the next one (ChatGPT, another assistant's) is a new [ChatImportSource] and a fixture folder under
// conformance/vendor/import/<source>/, never a writer change or a schema change.
//
// NO NETWORK, NO DECODING: nothing in this package opens a connection, resolves a URL, or decodes an image. An export's
// image is a placeholder; its URL or `data:` bytes are never fetched or decoded (ChatImportNoNetworkTest).
package ai.teemoon.chat.imports

/**
 * One chat as a source read it, already on its chosen branch. [sourceId] is the source's own conversation id (or a
 * stand-in it derives, such as near's `chat:<root>`); the writer builds the stored key `<source>:<sourceId>`.
 * [title] null means the source had none (near's empty or "New Conversation"): the writer applies the app's own rule.
 * [itemsSkipped] and [mayBeIncomplete] count only if the chat is written: an "already here" chat counts nothing (§2).
 */
data class ImportedChat(
    val source: String,
    val sourceId: String,
    val title: String?,
    val messages: List<ImportedMessage>,
    val itemsSkipped: Int = 0,
    val mayBeIncomplete: Boolean = false,
)

/**
 * One message, in the order it is to be shown. [createdAtMs] is the source's own time, not yet made strictly
 * increasing — that is the writer's job, because it is how THIS app orders a transcript, not a fact of the source.
 */
data class ImportedMessage(
    val sourceId: String,
    val role: Role,
    /** In order. The writer joins them into the stored text; a message with none is dropped and counted there. */
    val parts: List<Part>,
    val createdAtMs: Long,
    /** In the order the source gives them, not deduped: the writer dedupes by URL, first wins. */
    val sources: List<ImportedSource> = emptyList(),
) {
    enum class Role(val stored: String) { USER("user"), ASSISTANT("assistant") }

    sealed interface Part {
        data class Text(val text: String) : Part
        /**
         * Something not imported: an image or a file. It stays a line in the text, so the later attachments pass can
         * fill it from the same file by message key (§1) — which is why keys never change.
         */
        data class Placeholder(val kind: Kind, val name: String? = null, val image: ImportedImage? = null) : Part
        enum class Kind { IMAGE, FILE }
    }

    val placeholders: List<Part.Placeholder> get() = parts.filterIsInstance<Part.Placeholder>()
}

/**
 * An image's payload as the source found it — for near, a `data:` URL — read only when the writer decodes it
 * (IMAGES.md §3: one at a time, never the whole chat's images at once). Null from [payload]: it cannot be read.
 */
class ImportedImage(val payload: () -> String?)

/** A cited web page. [title] null: the writer shows the page's domain instead. */
data class ImportedSource(val url: String, val title: String?)

/** What a source hands the writer, one conversation at a time, so a big file never sits in memory whole. */
sealed interface ImportEvent {
    data class Chat(val chat: ImportedChat) : ImportEvent
    /** Something that will never be a chat: an unreadable element, an oversized or too-deep conversation. */
    data class Skipped(val count: Int) : ImportEvent
}

/**
 * One app's export format. [detect] sees only the FIRST top-level element's keys: earlier conversations are committed
 * before a later one is read, so a file's kind is decided once, up front (§4).
 */
interface ChatImportSource {
    /** The id prefix, `near` for near.ai. It is how an imported row knows where it came from: no column holds it. */
    val prefix: String
    fun detect(firstElementKeys: Set<String>): Boolean
    /**
     * The events for the whole file. [first] is the element [detect] saw, already read off the scanner; [overCap] are
     * the elements before it that were over a cap, read past unparsed.
     */
    fun parse(overCap: List<ScannedElement>, first: ScannedElement.Value, rest: TopLevelArrayScanner): Sequence<ImportEvent>
}

/** The summary sheet's counts (§6). */
data class ImportSummary(
    val chatsImported: Int = 0,
    val messagesImported: Int = 0,
    val chatsAlreadyHere: Int = 0,
    val itemsSkipped: Int = 0,
    val mayBeIncomplete: Boolean = false,
    /** IMAGES.md §3: images stored as attachments, and image parts that stayed a placeholder line. */
    val imagesImported: Int = 0,
    val imagesSkipped: Int = 0,
)

/** The whole file is not one the app can read: not JSON, not an array, a broken first element, or no source's kind. */
class NotAnExportException(message: String) : Exception(message)
