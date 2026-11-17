package ai.teemoon.app

// android-only: the file card's name, size and date (the Safari-style import page, pm 2026-11-01) as the file's
// provider reports them, and whether the done step may offer to delete it. iOS reads the same from the file's URL
// resource values (isWritable) and deletes it coordinated (478b3bc3).

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log

object ImportFileInfo {
    private fun <T> query(resolver: ContentResolver, uri: Uri, columns: Array<String>, read: (android.database.Cursor) -> T?): T? = runCatching {
        resolver.query(uri, columns, null, null, null)?.use { c -> if (c.moveToFirst()) read(c) else null }
    }.getOrNull()

    /** The provider says the document can be deleted ([flags], its COLUMN_FLAGS) and we hold a write grant on it. */
    fun deletable(flags: Int?, writeGranted: Boolean) = writeGranted && flags != null && flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0

    /**
     * The delete for a file the user PICKED (ACTION_OPEN_DOCUMENT), or null when it may not be offered: not a document
     * URI, its provider doesn't support delete, or no write grant. An export that arrived by share or open-with is never
     * asked (pm 2026-11-01): the caller doesn't. The delete reports false when it didn't happen, so the page says so.
     */
    fun deleter(context: Context, uri: Uri): (() -> Boolean)? {
        if (!runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) return null
        val flags = query(context.contentResolver, uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS)) { c ->
            c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getInt) }
        val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!deletable(flags, granted)) return null
        return {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                .onFailure { Log.w("teemoon", "import: couldn't delete the export: ${it.javaClass.simpleName}") }.getOrDefault(false)
        }
    }

    /**
     * What [uri]'s provider says about it. Any part it does not give is null and the card leaves it out; a provider
     * that refuses the query gives an empty [ImportFile], never a failed import. The date is COLUMN_LAST_MODIFIED,
     * which only a documents provider (the picker's) carries, so it is asked for separately. Off the main thread: a
     * cloud provider may answer slowly.
     */
    fun of(resolver: ContentResolver, uri: Uri): ImportFile {
        fun <T> query(columns: Array<String>, read: (android.database.Cursor) -> T?): T? = query(resolver, uri, columns, read)
        val (name, bytes) = query(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) { c ->
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            val b = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            n to b
        } ?: (null to null)
        // a provider that is not a documents provider may reject the unknown column: that query alone fails, caught
        val modified = query(arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) { c -> c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)?.takeIf { it > 0 } }
        return ImportFile(name, bytes, modified)
    }
}
