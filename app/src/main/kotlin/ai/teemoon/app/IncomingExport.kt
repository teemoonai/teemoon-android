package ai.teemoon.app

// android-only (pm 2026-10-30, import redesign (f)): a near.ai export opened from Files ("open with") or shared to
// teemoon arrives as an ACTION_VIEW or ACTION_SEND intent. iOS receives the same file through its document types; the
// Android half is the intent filter, so there is no Swift source. docs/ANDROID_NATIVE.md, "Opening an export".

import java.io.File
import java.io.InputStream

/**
 * The export an intent hands teemoon: application/json only (pm 2026-10-30) — no octet-stream, no other type, no
 * `file:` or web link. It is COPIED TO THE CACHE ON ARRIVAL: a content URI's read grant lasts only as long as the
 * receiving activity, and a recreated activity (rotation, process death, a cold start that has not drawn yet) would
 * find it gone before the import read it.
 */
object IncomingExport {
    const val MIME = "application/json"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    /** Under the cache dir: the system may clear it, and nothing here outlives the import that reads it. */
    const val DIR = "import-incoming"

    /** Whether an intent with this action, MIME type and URI scheme is an export to import. */
    fun accepts(action: String?, type: String?, scheme: String?): Boolean =
        (action == ACTION_VIEW || action == ACTION_SEND) && type?.substringBefore(';')?.trim()?.lowercase() == MIME && scheme == "content"

    /**
     * Copies the export into [cacheDir]/[DIR] and returns the copy, under a name of its own. It removes nothing: an
     * earlier copy may be the file an import is still reading (the confirm reads it a second time to write), so only
     * [receive] — once this arrival is taken — clears what came before. Deleting earlier copies here was the bug: a
     * second "open with" during a dry run deleted the file being checked, and was then itself dropped.
     */
    fun copy(open: () -> InputStream, cacheDir: File): File {
        val dir = File(cacheDir, DIR).apply { mkdirs() }
        val target = File.createTempFile("export-", ".json", dir)
        try {
            open().use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) { target.delete(); throw e }
        return target
    }

    /**
     * Whether an arrival is taken (iOS ContentView.onOpenURL: `guard !chatImport.isBusy`). While an import is
     * checking or writing, the arrival is dropped and its own [copy] goes; the running import's file is untouched.
     * Taken, every earlier copy goes: one arrival at a time, and nothing an earlier import left behind lingers.
     */
    fun receive(copy: File?, busy: Boolean, cacheDir: File): Boolean {
        if (busy) { copy?.delete(); return false }
        File(cacheDir, DIR).listFiles()?.filter { it != copy }?.forEach { it.delete() }
        return true
    }

    /** An export as it reached teemoon: how to read it, what its provider says of it, and our copy (null: the copy failed). */
    data class Arrival(val open: () -> InputStream, val about: ImportFile, val copy: File?)

    /** The held copy goes when the user declines it. */
    fun discard(cacheDir: File) { File(cacheDir, DIR).listFiles()?.forEach { it.delete() } }
}
