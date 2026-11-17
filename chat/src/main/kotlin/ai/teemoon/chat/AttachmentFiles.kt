// IMAGES.md §1 (iOS b2c2ea2): Android keeps an attachment's bytes as a FILE beside the store, where iOS uses SwiftData's
// external storage. Room's transaction covers rows, not files, so the order is what keeps them in step: a file is
// written under pending/, the rows commit, then it is moved into place; a sweep removes any file no row names.
package ai.teemoon.chat

import java.io.File
import java.util.UUID

/**
 * The attachments directory (app-private, outside backups: the manifest's allowBackup=false and data_extraction_rules
 * cover the whole data dir). Files hold only re-encoded pixels — never bytes as they arrived.
 */
class AttachmentFiles(val dir: File) {
    private val pending = File(dir, "pending")

    /** A fresh pending file for bytes about to be stored; its name is not a path anyone else chose. */
    fun newPending(extension: String): File {
        pending.mkdirs()
        return File(pending, UUID.randomUUID().toString() + "." + extension.filter { it.isLetterOrDigit() }.take(4))
            .also { writing += it.canonicalPath }
    }

    /** After the rows commit: the pending file moves to [relativePath] (the row's `path`). */
    fun commit(file: File, relativePath: String): Boolean {
        val target = resolve(relativePath) ?: return false
        target.parentFile?.mkdirs()
        return file.renameTo(target).also { writing -= file.canonicalPath }
    }

    /** The file a row names, or null for a path that would leave the directory. */
    fun resolve(relativePath: String): File? {
        val f = File(dir, relativePath).canonicalFile
        return f.takeIf { it.path.startsWith(dir.canonicalFile.path + File.separator) }
    }

    /**
     * Every file no row names goes: after any delete (the rows cascaded with their messages), and at launch, when
     * [includePending] also clears what an interrupted import or send left under pending/.
     *
     * The files are listed BEFORE [keep] reads the rows. A writer inserts its rows and only then moves its file into
     * place, so any file in the listing has its row committed before the read — if it is live, [keep] names it. Read the
     * rows first and an import committing on the application's scope while a chat is deleted (its rows after the read,
     * its file before the walk) loses an image it has just stored.
     *
     * A pending file this process is still writing is never "left by an interrupted import": the import runs on the
     * application's scope and outlives the activity, and a new activity (back, then the launcher, the process alive)
     * runs the launch sweep again. Only a pending file no live writer in this process made goes.
     */
    suspend fun sweep(includePending: Boolean = false, keep: suspend () -> Collection<String>): Int {
        val listed = dir.walkTopDown().filter { it.isFile }.toList()
        val kept = keep().mapNotNull { resolve(it)?.path }.toSet()
        var removed = 0
        listed.forEach { f ->
            val isPending = f.parentFile?.canonicalPath == pending.canonicalPath
            if (isPending && (!includePending || f.canonicalPath in writing)) return@forEach
            if (isPending || f.canonicalPath !in kept) { if (f.delete()) removed++ }
        }
        return removed
    }

    private companion object {
        /** Pending files made in this process and not yet committed: per process, because each activity has its own instance. */
        val writing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
