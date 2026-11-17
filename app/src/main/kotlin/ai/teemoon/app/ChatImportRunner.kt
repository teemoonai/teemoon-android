// ports: teemoon/Chat/Import/ChatImportModel.swift + ChatImportText.swift @ 1440e4b, with the import page of cdea210c — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// AHEAD OF iOS (pm 2026-11-01, the maintainer approved the Safari-style layout): the file card's name/date/size, progress in
// chats and the v2 placeholder strings are built from pm's canvas before iOS lands them; reconcile with iOS's commit.
// The offer to delete the export after the import, and the card's empty note on "nothing new", are iOS 478b3bc3's
// (local on iOS's near-import, not pushed yet, pm 2026-11-01): bump this file's sha in ports.json when it lands.
// spec: docs/IMPORT_NEAR.md draft 8 §5, §6 and §8 (teemoon-ios 9ad6ebd): the entry point, the summary and its
//       strings. The logic is chat.imports; this runs it off the main thread and words the result.
package ai.teemoon.app

import ai.teemoon.chat.imports.ChatImport
import ai.teemoon.chat.imports.ChatImportWriter
import ai.teemoon.chat.imports.ImageIngest
import ai.teemoon.chat.imports.ImportStore
import ai.teemoon.chat.imports.ImportSummary
import ai.teemoon.chat.imports.NotAnExportException
import ai.teemoon.chat.imports.StoredSource
import ai.teemoon.inference.GroundingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * The chosen file as its provider describes it, for the file card: each part null when the provider gives none
 * (OpenableColumns.DISPLAY_NAME and SIZE; DocumentsContract's COLUMN_LAST_MODIFIED, document URIs only).
 */
data class ImportFile(val name: String? = null, val bytes: Long? = null, val modified: Long? = null)

/** What the import page shows (iOS ChatImportModel.State, cdea210c). */
sealed interface ChatImportUi {
    data object Idle : ChatImportUi
    /** The dry run is reading [file]. */
    data class Checking(val file: ImportFile = ImportFile()) : ChatImportUi
    /** What the import will write; nothing is written yet. */
    data class Confirm(val preview: ImportSummary, val file: ImportFile = ImportFile()) : ChatImportUi
    /** [read] of [total] chats saved: the total is the dry run's chats to import (Safari-style page, pm 2026-11-01). */
    data class Importing(val read: Int, val total: Int) : ChatImportUi
    data class Finished(val summary: ImportSummary) : ChatImportUi
    data class Failed(val reason: Reason) : ChatImportUi {
        enum class Reason { WRONG_FILE, CANT_OPEN, STOPPED }
    }
}

/**
 * The question about the export (iOS ChatImportModel.offersDelete/deleteFailed, 478b3bc3): earned by an import that
 * wrote chats from a file the user picked and we may delete, and asked only once the page is closed by "done" or "show
 * chats", never over "import finished" (import-page-strings v9, D4); cleared by either answer. [Failed] when the delete
 * didn't happen, which an alert says (pm 2026-11-01).
 */
sealed interface ExportDelete {
    data class Offer(val name: String?) : ExportDelete
    data object Failed : ExportDelete
    /** Answered "delete" and running: no alert, but the question isn't settled, so "show chats" still waits. */
    data object Deleting : ExportDelete
}

/**
 * One import at a time, on the application's scope, so turning the phone or leaving the screen does not stop it. Chats
 * land as they are read: the list fills while it runs, and a stop part way keeps every whole chat (§4).
 */
class ChatImportRunner(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val _state = MutableStateFlow<ChatImportUi>(ChatImportUi.Idle)
    val state: StateFlow<ChatImportUi> = _state

    /** The file being checked or confirmed, and where it goes: read twice, once to count and once to write. */
    private class File(val open: () -> InputStream, val store: ImportStore, val images: ImageIngest?, val spillDir: java.io.File?,
                       val about: ImportFile, val delete: (() -> Boolean)?)
    private var file: File? = null

    private val _exportDelete = MutableStateFlow<ExportDelete?>(null)
    val exportDelete: StateFlow<ExportDelete?> = _exportDelete
    /** The offer an import earned, held until the page closes ([close]); and the delete the shown offer runs. */
    private var earned: ExportDelete.Offer? = null
    private var offered: (() -> Boolean)? = null

    val isBusy: Boolean get() = _state.value.let { it is ChatImportUi.Checking || it is ChatImportUi.Importing }

    /** What opened the import flow: Settings › chats, or a file arriving by share or open-with (over the main screen). */
    enum class Opener { SETTINGS, ARRIVAL }
    private var opener = Opener.SETTINGS

    /** The flow was opened from [from]: "done" closes it back there. */
    fun opened(from: Opener) { if (!isBusy) opener = from }

    /**
     * "done" — after an import, or on "nothing new" (chatsImported == 0, iOS isNothingNew): the whole flow closes, back
     * to what opened it (iOS NearImportView: done → dismissResult, then dismiss; the maintainer on the Pixel 2026-11-07,
     * "shouldnt this pop the stack instead?"). Returns where to go, or null while busy. An error's "ok" is not this: it
     * leaves the page open to pick another file.
     */
    fun done(): Opener? {
        if (isBusy) return null
        close()
        return opener
    }

    /**
     * Reads the file without writing and moves to [ChatImportUi.Confirm] with the counts, or to [ChatImportUi.Failed].
     * [open] gives the chosen file's bytes (a content resolver read: local, no network of ours). [delete] removes the
     * user's own file, given only for one we may delete (ImportFileInfo.deleter): an arrival by share or open-with
     * never gets one (pm 2026-11-01).
     */
    fun check(open: () -> InputStream, store: ImportStore, images: ImageIngest? = null, spillDir: java.io.File? = null, about: ImportFile = ImportFile(),
              delete: (() -> Boolean)? = null) {
        if (isBusy) return
        file = File(open, store, images, spillDir, about, delete)
        _state.value = ChatImportUi.Checking(about)
        scope.launch { _state.value = run(open, store, images = images, spillDir = spillDir, dryRun = true).let { if (it is ChatImportUi.Confirm) it.copy(file = about) else it } }
    }

    /** Writes what [ChatImportUi.Confirm] counted. */
    fun confirm() {
        val preview = _state.value as? ChatImportUi.Confirm ?: return
        if (preview.preview.chatsImported == 0) return   // "import" is greyed: nothing new
        val f = file ?: return
        _state.value = ChatImportUi.Importing(0, preview.preview.chatsImported)
        scope.launch {
            val done = run(f.open, f.store, { n -> (_state.value as? ChatImportUi.Importing)?.let { _state.value = it.copy(read = n) } }, f.images, f.spillDir)
            _state.value = done
            if (done is ChatImportUi.Finished && offersDelete(done.summary, f.delete != null)) earned = ExportDelete.Offer(f.about.name)
        }
    }

    /** "not now", or the alert dismissed. */
    fun keepExport() { if (_exportDelete.value is ExportDelete.Offer) { _exportDelete.value = null; offered = null } }

    /** "delete": the offer goes, and a delete that didn't happen comes back as [ExportDelete.Failed], never silence. */
    fun deleteExport() {
        val delete = offered
        if (_exportDelete.value !is ExportDelete.Offer || delete == null) return
        _exportDelete.value = ExportDelete.Deleting; offered = null
        scope.launch { _exportDelete.value = if (runCatching(delete).getOrDefault(false)) null else ExportDelete.Failed }
    }

    fun dismissDeleteFailure() { if (_exportDelete.value == ExportDelete.Failed) _exportDelete.value = null }

    /** The flow ends with nothing asked: a new file arriving, a failure acknowledged. */
    fun dismiss() { if (!isBusy) { _state.value = ChatImportUi.Idle; file = null; earned = null; _exportDelete.value = null; offered = null } }

    /** The page is closed ("done", "show chats", back): the flow ends, and an offer the import earned is asked now (D4). */
    fun close() {
        if (isBusy) return
        val offer = earned; val delete = file?.delete
        dismiss()
        if (offer != null && delete != null) { offered = delete; _exportDelete.value = offer }
    }

    /**
     * The page is left — its back button or the system back gesture, which must agree. Not busy: the flow ends, and an
     * arrival's cached copy in [cacheDir] goes with it (iOS 7a259ec5). Busy: the import keeps running on this scope and
     * the copy stays until it ends; coming back shows its progress or its summary.
     */
    fun leave(cacheDir: java.io.File) { if (!isBusy) { close(); IncomingExport.discard(cacheDir) } }

    /**
     * "cancel" or system back: the page is left ([leave]) back to what opened it, as iOS's sheet does — at every step,
     * and mid-import too (the import runs on). An arrival never opened settings › chats: leaving it there drew a blank
     * screen (the maintainer's Pixel, 5824bd84).
     */
    fun cancel(cacheDir: java.io.File): Opener { leave(cacheDir); return opener }

    companion object {
        /** Only after an import that wrote chats, and only for a file we may delete (iOS ChatImportModel.offersDelete, 478b3bc3). */
        fun offersDelete(after: ImportSummary, deletable: Boolean) = after.chatsImported > 0 && deletable

        /** The app's own source encoding: an imported answer's sources read back exactly as a searched one's. */
        val encodeSources: (List<StoredSource>) -> String? = { list ->
            GroundingSource.encodedJSON(list.map { GroundingSource(url = it.url, domain = it.domain, title = it.title, snippet = "") })
        }

        /**
         * One pass over the file. [dryRun]: count and write nothing, ending at [ChatImportUi.Confirm]; otherwise write,
         * ending at [ChatImportUi.Finished]. [progress] gets the chats saved so far, once per chat saved.
         */
        suspend fun run(open: () -> InputStream, store: ImportStore, progress: (Int) -> Unit = {},
                        images: ImageIngest? = null, spillDir: java.io.File? = null, dryRun: Boolean = false): ChatImportUi {
            val input = try { open() } catch (_: Exception) { return ChatImportUi.Failed(ChatImportUi.Failed.Reason.CANT_OPEN) }
            return try {
                input.use {
                    var saved = 0
                    val writer = ChatImportWriter(store, encodeSources, images = images, dryRun = dryRun,
                        onProgress = { s -> if (s.chatsImported != saved) { saved = s.chatsImported; progress(saved) } })
                    val summary = ChatImport.run(it, writer, spillDir = spillDir)
                    if (dryRun) ChatImportUi.Confirm(summary) else ChatImportUi.Finished(summary)
                }
            } catch (_: NotAnExportException) {
                ChatImportUi.Failed(ChatImportUi.Failed.Reason.WRONG_FILE)
            } catch (e: Exception) {
                // Disk full, the file's provider gone mid-read: what was written stays, and the same file finishes it.
                ChatImportUi.Failed(ChatImportUi.Failed.Reason.STOPPED)
            }
        }
    }
}

/**
 * §8's strings, APPROVED by the maintainer as proposed (review page, 2026-10-25 14:24Z: all 14, the footer unchanged, the
 * deleted-chat line shown) — all of them here, so a later edit is one file. Source-neutral except the
 * footer, so a second source changes one line. Lowercase, the house style. The placeholder lines stored in chats are
 * ChatImportWriter's: they are pinned by the corpus, not by this file.
 */
object ImportCopy {
    const val ROW = "import chats"
    // the maintainer's approved footer (pm review page, 2026-10-25), now on its own: v5 dropped "how to export" and its sheet
    // (pm default, the maintainer approved 2026-11-01), since the import page names the export path.
    const val FOOTER = "from near.ai private chat. it stays on this phone."

    // The import page, Safari style (the maintainer approved the layout, pm 2026-11-01). APPROVED by the maintainer as written:
    // import-page-strings v5 on pm's review page (2026-11-01 17:32Z, no edits), the export path and the delete lines
    // included. A later edit lands here and nowhere else.
    // v2 dropped v1's numbered steps, "ready to import", "reading the file…" and the deleted-chat line.
    const val PAGE_TITLE = "import from near.ai"
    const val CANCEL = "cancel"
    const val BODY = "bring your chats from near.ai private chat into teemoon. they're saved only on this phone."
    const val EXPORT_PATH = "in near.ai private chat: settings → chats → export chats."
    const val CHOOSE_FILE = "choose file…"
    const val CHOOSE_ANOTHER = "choose another file…"
    const val IMPORT = "import"
    // "nothing new" is a result of its own, like done (the maintainer approved, pm 2026-11-01; import-page-strings v7): a title,
    // a body, the file card with the file's own count, and "done" at the bottom. v7 dropped v5's "every chat in this
    // file is already here." card line.
    const val NOTHING_NEW_TITLE = "nothing new to import"
    const val NOTHING_NEW_BODY = "every chat in this file is already in teemoon."
    // A file with no chats in it is the same kind of result (the maintainer approved, pm 2026-11-01; v8), replacing v5's "there's
    // nothing to import in this file." card line and the greyed import.
    const val EMPTY_TITLE = "nothing to import"
    const val EMPTY_BODY = "this file has no chats in it."
    const val IMPORTING_TITLE = "importing…"
    // iOS's wording. Android's import finishes in the background when the page is left (ChatImportRunner.leave), but
    // closing the app stops it, so the line still holds (flagged to pm, 2026-11-01).
    const val IMPORTING_BODY = "keep teemoon open until it's done."
    const val TITLE = "import finished"
    const val DONE = "done"
    // The offer to delete the export after an import that wrote chats (pm 2026-11-01; approved in v5, v9: the button
    // names the file, "not now" replaces "keep", and the body says it can't be undone).
    const val DELETE_TITLE = "delete the export file?"
    fun deleteMessage(name: String?) = "${name ?: "the file"} holds your chats unencrypted. teemoon has its own copy now. this can't be undone."
    fun deleteButton(name: String?) = if (name == null) "delete file" else "delete \u201C$name\u201D"
    const val NOT_NOW = "not now"
    const val DELETE_FAILED_TITLE = "couldn't delete the file"
    const val DELETE_FAILED = "it's still where it was. you can delete it in Files."
    const val MAY_BE_INCOMPLETE = "part of this chat may be missing: the file was cut short or holds only its first 100 messages. " +
        "if you have the full export, import that too."

    /** "6 chats, 25 messages and 1 image": zero messages or images get no part. */
    fun counts(s: ImportSummary): String {
        val parts = listOfNotNull(chats(s.chatsImported), messages(s.messagesImported).takeIf { s.messagesImported > 0 },
            count(s.imagesImported, "image").takeIf { s.imagesImported > 0 })
        return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /** True when the file is all chats teemoon already has: the page shows [NOTHING_NEW_TITLE]'s result, not the confirm. */
    fun nothingNew(s: ImportSummary) = s.chatsImported == 0 && s.chatsAlreadyHere > 0
    /** True when the file holds no chats at all: the page shows [EMPTY_TITLE]'s result. */
    fun empty(s: ImportSummary) = s.chatsImported == 0 && s.chatsAlreadyHere == 0

    /** The file card's main line: what will come in, or the chats the file holds when all are here already; none for an empty file (the body says it). */
    fun cardLine(s: ImportSummary): String? = when {
        s.chatsImported > 0 -> counts(s)
        s.chatsAlreadyHere > 0 -> chats(s.chatsAlreadyHere)
        else -> null
    }

    /** The file card's small line, only the parts that apply; null when none do, or nothing is new (iOS 478b3bc3). */
    fun cardNote(s: ImportSummary): String? = if (s.chatsImported == 0) null else listOfNotNull(
        when { s.chatsAlreadyHere == 0 -> null; s.chatsAlreadyHere == 1 -> "1 chat already here stays as it is."
               else -> "${s.chatsAlreadyHere} chats already here stay as they are." },
        if (s.itemsSkipped == 0) null else "${count(s.itemsSkipped, "item")} can't be read and will be skipped.",
    ).joinToString(" ").ifEmpty { null }

    /** "oct 15 · 24 KB": the file's date (with the year when it isn't this one) and size, each when the provider gives it. */
    fun fileMeta(f: ImportFile, now: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String? {
        val date = f.modified?.let { m ->
            fun year(t: Long) = java.util.Calendar.getInstance(zone).apply { timeInMillis = t }.get(java.util.Calendar.YEAR)
            java.text.SimpleDateFormat(if (year(m) == year(now)) "MMM d" else "MMM d, yyyy", java.util.Locale.ENGLISH)
                .apply { timeZone = zone }.format(java.util.Date(m)).lowercase()
        }
        return listOfNotNull(date, f.bytes?.let(::size)).joinToString(" · ").ifEmpty { null }
    }

    /** A size as iOS's ByteCountFormatter writes a file's (decimal units): "812 bytes", "24 KB", "1.4 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1_000 -> if (bytes == 1L) "1 byte" else "$bytes bytes"
        bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    }

    /** "4 of 6 chats" under the bar. */
    fun progress(read: Int, total: Int) = "$read of ${chats(total)}"
    /** The progress bar's fill: chats saved over the chats to import, never past the end. */
    fun fraction(read: Int, total: Int): Float = if (total <= 0) 0f else minOf(1f, read.toFloat() / total)

    /** The done page's body. */
    fun doneBody(s: ImportSummary) = "teemoon imported ${counts(s)}."

    /**
     * The done page's small lines, only if any. v2 names the skipped line; the image-skip and may-be-incomplete lines
     * stay from v1's approved summary because each says something is missing (flagged to pm, 2026-11-01).
     */
    fun doneNotes(s: ImportSummary): List<String> = buildList {
        if (s.imagesSkipped > 0) add(ai.teemoon.app.imaging.ImageCopy.importSkipped(s.imagesSkipped))
        if (s.itemsSkipped > 0) add(if (s.itemsSkipped == 1) "1 item couldn't be read and was skipped." else "${s.itemsSkipped} items couldn't be read and were skipped.")
        if (s.mayBeIncomplete) add(MAY_BE_INCOMPLETE)
    }
    // the maintainer's approved wording (pm review page, 2026-10-25): the title says what happened, the message why.
    const val WRONG_FILE_TITLE = "this file can't be imported"
    const val WRONG_FILE = "it isn't a chat export teemoon can read."
    /** An error dialog's one button: it acknowledges (Material), where the summary's "done" closes a result. */
    const val ACKNOWLEDGE = "ok"
    // Each failure's title says what happened (pm 2026-10-25; the maintainer approved, ui-audit-1.1.md), as the approved wrong-file one does.
    const val CANT_OPEN_TITLE = "couldn't open this file"
    const val STOPPED_TITLE = "the import stopped"
    const val STOPPED = "chats already imported are kept; import the same file again to finish."

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    private fun chats(n: Int) = count(n, "chat")
    private fun messages(n: Int) = count(n, "message")

    fun failureTitle(r: ChatImportUi.Failed.Reason) = when (r) {
        ChatImportUi.Failed.Reason.WRONG_FILE -> WRONG_FILE_TITLE
        ChatImportUi.Failed.Reason.CANT_OPEN -> CANT_OPEN_TITLE
        ChatImportUi.Failed.Reason.STOPPED -> STOPPED_TITLE
    }

    /** The dialog's message under the title; null where the title says it all. */
    fun failureMessage(r: ChatImportUi.Failed.Reason): String? = when (r) {
        ChatImportUi.Failed.Reason.WRONG_FILE -> WRONG_FILE
        ChatImportUi.Failed.Reason.CANT_OPEN -> null
        ChatImportUi.Failed.Reason.STOPPED -> STOPPED
    }
}
