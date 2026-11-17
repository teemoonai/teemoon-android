// IMAGES.md §1 (iOS b2c2ea2) and §6's residue case, the half the JVM can hold: rows cascade with their message, and
// every delete path sweeps the files no row names. ChatMigrationTest (device) holds the real SQLite cascade.
package ai.teemoon.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AttachmentFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun attachment(message: String, idx: Int, path: String) =
        AttachmentEntity(id = "$message#$idx", messageId = message, idx = idx, mime = "image/jpeg", width = 2, height = 1, path = path, thumbnail = byteArrayOf(1))

    private suspend fun stored(dao: ListChatDao, files: AttachmentFiles, message: String, threadId: String, idx: Int = 0): String {
        val path = "$message-$idx.jpg"
        val pending = files.newPending("jpg").apply { writeBytes(byteArrayOf(9, 9)) }
        dao.insertAttachments(listOf(attachment(message, idx, path)))
        assertTrue(files.commit(pending, path))
        return path
    }

    @Test fun aPathCannotLeaveTheDirectory() {
        val files = AttachmentFiles(tmp.newFolder("attachments"))
        assertNull(files.resolve("../outside.jpg"))
        // java.io.File(dir, "/etc/passwd") is a CHILD of dir, so an absolute-looking path stays inside.
        assertTrue(files.resolve("/etc/passwd")!!.path.startsWith(files.dir.canonicalPath))
        assertTrue(files.resolve("a.jpg") != null)
    }

    @Test fun deletingAChatTakesItsImageFilesWithIt() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments")); var scrubs = 0
        val repo = ChatRepository(dao, maintenance = { scrubs++ }, files = files)
        dao.insertChat(ThreadEntity("t", null, 1), listOf(MessageEntity("m", "t", "user", "look", 1)))
        dao.insertChat(ThreadEntity("u", null, 1), listOf(MessageEntity("n", "u", "user", "keep", 1)))
        val gone = stored(dao, files, "m", "t"); val kept = stored(dao, files, "n", "u")
        repo.delete("t")
        assertFalse(files.resolve(gone)!!.exists())
        assertTrue(files.resolve(kept)!!.exists())
        assertEquals(1, scrubs)
    }

    /** A retry's truncation deletes text and images like any delete — and, until 1.1.0, skipped the store's scrub. */
    @Test fun aRetrysTruncationSweepsAndScrubsLikeEveryOtherDelete() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments")); var scrubs = 0
        val repo = ChatRepository(dao, maintenance = { scrubs++ }, files = files)
        dao.insertChat(ThreadEntity("t", null, 1), listOf(MessageEntity("q", "t", "user", "first", 1), MessageEntity("r", "t", "assistant", "reply", 5)))
        val path = stored(dao, files, "r", "t")
        repo.deleteMessagesSince("t", 5)
        assertFalse(files.resolve(path)!!.exists())
        assertEquals("the scrub runs after a retry's delete too", 1, scrubs)
    }

    @Test fun deletingAllChatsLeavesNoImageFile() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments")); var scrubs = 0
        val repo = ChatRepository(dao, maintenance = { scrubs++ }, files = files)
        dao.insertChat(ThreadEntity("t", null, 1), listOf(MessageEntity("m", "t", "user", "look", 1)))
        dao.insertChat(ThreadEntity("u", null, 1), listOf(MessageEntity("n", "u", "user", "also", 1)))
        stored(dao, files, "m", "t"); stored(dao, files, "m", "t", idx = 1); stored(dao, files, "n", "u")
        repo.deleteAll()
        assertEquals(emptyList<File>(), files.dir.walkTopDown().filter { it.isFile }.toList())
        assertEquals(1, scrubs)
    }

    /** Think Harder replaces a reply: the old reply's images go, the question's — restamped, not deleted — stay. */
    @Test fun aSupersededReplysImagesGoAndTheQuestionsStay() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments"))
        val repo = ChatRepository(dao, files = files)
        val question = MessageEntity("q", "t", "user", "what is this", 1)
        dao.insertChat(ThreadEntity("t", null, 1), listOf(question, MessageEntity("r", "t", "assistant", "a cat", 5)))
        val asked = stored(dao, files, "q", "t"); val old = stored(dao, files, "r", "t")
        repo.supersede(listOf("r"), question, askedAt = 7, reply = MessageEntity("r2", "t", "assistant", "a lynx", 8))
        assertFalse(files.resolve(old)!!.exists())
        assertTrue(files.resolve(asked)!!.exists())
    }

    /**
     * A kill between a delete's rows and its sweep (the rows are gone, the files are not): the next launch's sweep
     * finishes it, and keeps every file a row still names.
     */
    @Test fun theLaunchSweepFinishesAnInterruptedDelete() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments"))
        dao.insertChat(ThreadEntity("t", null, 1), listOf(MessageEntity("m", "t", "user", "look", 1)))
        dao.insertChat(ThreadEntity("u", null, 1), listOf(MessageEntity("n", "u", "user", "keep", 1)))
        val gone = stored(dao, files, "m", "t"); val kept = stored(dao, files, "n", "u")
        dao.deleteThread("t")   // the rows only: the process died before ChatRepository.delete reached its sweep
        assertTrue(files.resolve(gone)!!.exists())
        val relaunched = ChatRepository(dao, files = AttachmentFiles(files.dir))
        assertEquals(1, relaunched.sweepAttachmentFiles())
        assertFalse(files.resolve(gone)!!.exists())
        assertTrue("a live row's file is never swept", files.resolve(kept)!!.exists())
    }

    /**
     * An import commits on the application's scope while a chat is deleted: its rows land after the sweep has read the
     * rows, its file before the sweep walks the directory. That file is live and must stay.
     */
    @Test fun aSweepNeverTakesAFileCommittedWhileItReadTheRows() = runTest {
        val list = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments"))
        list.insertChat(ThreadEntity("t", null, 1), listOf(MessageEntity("m", "t", "user", "look", 1)))
        list.insertChat(ThreadEntity("i", null, 1), listOf(MessageEntity("near:i:1", "i", "user", "imported", 1)))
        stored(list, files, "m", "t")
        var imported: String? = null
        val dao = object : ChatDao by list {
            override suspend fun attachmentPaths(): List<String> = list.attachmentPaths().also {
                if (imported == null) imported = stored(list, files, "near:i:1", "i")
            }
        }
        ChatRepository(dao, files = files).delete("t")
        assertTrue("the import's committed image survives the delete's sweep", files.resolve(imported!!)!!.exists())
    }

    /**
     * The import runs on the application's scope, so it outlives the activity; a new activity's launch sweep (the app
     * left with back and opened again, the process alive) must not take the pending files it is still writing.
     */
    @Test fun aRelaunchedActivityLeavesARunningImportsPendingFiles() = runTest {
        val dao = ListChatDao(); val dir = tmp.newFolder("attachments")
        val importing = AttachmentFiles(dir)
        dao.insertChat(ThreadEntity("i", null, 1), listOf(MessageEntity("near:i:1", "i", "user", "imported", 1)))
        val pending = importing.newPending("jpg").apply { writeBytes(byteArrayOf(9)) }
        assertEquals(0, ChatRepository(dao, files = AttachmentFiles(dir)).sweepAttachmentFiles())
        assertTrue(pending.exists())
        dao.insertAttachments(listOf(attachment("near:i:1", 0, "near-i-1.jpg")))
        assertTrue("the import still commits its image", importing.commit(pending, "near-i-1.jpg"))
    }

    @Test fun launchClearsWhatAnInterruptedImportLeftPending() = runTest {
        val dao = ListChatDao(); val files = AttachmentFiles(tmp.newFolder("attachments"))
        val repo = ChatRepository(dao, files = files)
        // left by a process that died mid-import: under pending/, but no writer in this process made it
        val orphan = File(files.dir, "pending/left.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val stray = files.resolve("nobody.jpg")!!.apply { writeBytes(byteArrayOf(1)) }
        files.sweep { emptyList() }
        assertTrue("an ordinary sweep leaves pending files: an import may be writing them", orphan.exists())
        assertFalse(stray.exists())
        assertEquals(1, repo.sweepAttachmentFiles())
        assertFalse(orphan.exists())
    }
}
