package ai.teemoon.app

// IMAGES.md §1 and §6 (iOS b2c2ea2; schema approved by the maintainer 2026-10-25): the store's first migration, 1 → 2. A
// version-1 store with chats opens under version 2 with every row as it was, the new table matches Room's own schema,
// and an attachment goes with its message. the data-safety rule: a migration that cannot be written crashes; it never wipes.

import ai.teemoon.chat.AttachmentEntity
import ai.teemoon.chat.ChatDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMigrationTest {
    private val name = "migration-test.db"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ChatDatabase::class.java)

    @Test fun aVersionOneStoreOpensUnderVersionTwoWithEveryRow() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO threads (id, title, timestamp) VALUES ('t-1', 'why is the sky blue', 1000)")
            execSQL("INSERT INTO threads (id, title, timestamp) VALUES ('near:conv_lisbon', 'Two days in Lisbon', 2000)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('m-1', 't-1', 'user', 'why?', 1000, 0, NULL, NULL)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('m-2', 't-1', 'assistant', 'rayleigh', 1001, 1, '[{\"url\":\"https://x\"}]', 4200)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('near:conv_lisbon:msg_u1', 'near:conv_lisbon', 'user', 'lisbon?', 2000, 0, NULL, NULL)")
            close()
        }
        // Room checks the migrated schema against 2.json: the new table exactly as AttachmentEntity declares it.
        helper.runMigrationsAndValidate(name, 2, true, ChatDatabase.MIGRATION_1_2).close()

        val db = ChatDatabase.builder(InstrumentationRegistry.getInstrumentation().targetContext, name).build()
        try {
            val dao = db.chatDao()
            assertEquals(listOf("near:conv_lisbon", "t-1"), dao.observeThreads().first().map { it.id })
            val m = dao.messages("t-1")
            assertEquals(listOf("why?", "rayleigh"), m.map { it.content })
            assertTrue(m[1].isE2EE); assertEquals("[{\"url\":\"https://x\"}]", m[1].sourcesJson); assertEquals(4200L, m[1].generatingTimeMs)

            // The new table: an attachment goes with its message (ON DELETE CASCADE), and with its thread.
            dao.insertAttachments(listOf(AttachmentEntity("m-1#0", "m-1", 0, "image/jpeg", 4, 3, "m-1-0.jpg", byteArrayOf(1, 2))))
            assertEquals(1, dao.attachmentPaths().size)
            dao.deleteThread("t-1")
            assertTrue("the attachment row outlived its chat", dao.attachmentPaths().isEmpty())
            assertEquals(1, dao.messages("near:conv_lisbon").size)
        } finally { db.close() }
    }

    /**
     * G3 (the maintainer approved 2026-11-06): 2 → 3 adds `messages.status` and touches nothing else. A version-2 store with a
     * chat, a sealed reply with sources and an attachment opens under 3 with every field as it was, and status null —
     * which reads as complete. Room validates the result against 3.json.
     */
    @Test fun aVersionTwoStoreOpensUnderVersionThreeWithEveryRow() = runBlocking {
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO threads (id, title, timestamp) VALUES ('t-1', 'why is the sky blue', 1000)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('m-1', 't-1', 'user', 'why?', 1000, 0, NULL, NULL)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('m-2', 't-1', 'assistant', 'rayleigh', 1001, 1, '[{\"url\":\"https://x\"}]', 4200)")
            execSQL("INSERT INTO attachments (id, message_id, idx, mime, width, height, path, thumbnail) VALUES ('m-1#0', 'm-1', 0, 'image/jpeg', 4, 3, 'm-1-0.jpg', x'0102')")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, ChatDatabase.MIGRATION_2_3).close()

        val db = ChatDatabase.builder(InstrumentationRegistry.getInstrumentation().targetContext, name).build()
        try {
            val dao = db.chatDao()
            val m = dao.messages("t-1")
            assertEquals(listOf("why?", "rayleigh"), m.map { it.content })
            assertTrue(m[1].isE2EE); assertEquals("[{\"url\":\"https://x\"}]", m[1].sourcesJson); assertEquals(4200L, m[1].generatingTimeMs)
            assertTrue("no row is given a status by the migration", m.all { it.status == null })
            assertTrue("nor a sent-before-confirmed mark", m.all { it.sentBeforeConfirmed == null })
            assertEquals(listOf("m-1-0.jpg"), dao.attachmentPaths())
            // the new column holds what a turn writes
            dao.upsertMessage(m[1].copy(status = ai.teemoon.chat.MessageStatus.INTERRUPTED))
            assertEquals(ai.teemoon.chat.MessageStatus.INTERRUPTED, dao.messages("t-1")[1].status)
        } finally { db.close() }
    }

    /** A 1.0.6 store (version 1) opens under 3 through both migrations, every row kept. */
    @Test fun aVersionOneStoreOpensUnderVersionThree() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO threads (id, title, timestamp) VALUES ('t-1', 'why is the sky blue', 1000)")
            execSQL("INSERT INTO messages (id, threadId, role, content, timestamp, isE2EE, sourcesJson, generatingTimeMs) VALUES ('m-1', 't-1', 'user', 'why?', 1000, 0, NULL, NULL)")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, ChatDatabase.MIGRATION_1_2, ChatDatabase.MIGRATION_2_3).close()
        val db = ChatDatabase.builder(InstrumentationRegistry.getInstrumentation().targetContext, name).build()
        try {
            val m = db.chatDao().messages("t-1")
            assertEquals(listOf("why?"), m.map { it.content }); assertEquals(null, m.single().status); assertEquals(null, m.single().sentBeforeConfirmed)
        } finally { db.close() }
    }

    /**
     * Installing an older build over a newer store (pm, for what the maintainer was told 2026-11-06): it must stop, never wipe.
     * The real builder opens a store whose file is one version NEWER than the code — the same path an older build
     * takes on a v3 file — and Room, with no destructive (downgrade) fallback, throws for the missing migration. The
     * rows are still on disk afterwards. A second @Database class at version 2 would need Room's processor in this
     * module; raising the file's user_version exercises the identical open-helper path with the builder we ship.
     */
    @Test fun aStoreNewerThanTheCodeFailsToOpenAndKeepsEveryRow() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "downgrade-test.db"
        ctx.deleteDatabase(file)
        val db = ChatDatabase.builder(ctx, file).build()
        runBlocking {
            db.chatDao().upsertThread(ai.teemoon.chat.ThreadEntity(id = "t-1", title = "kept", timestamp = 1000))
            db.chatDao().upsertMessage(ai.teemoon.chat.MessageEntity(id = "m-1", threadId = "t-1", role = "user", content = "still here", timestamp = 1000))
        }
        db.close()
        val path = ctx.getDatabasePath(file).path
        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.version = 4   // a store written by a build one schema ahead of this one
        }

        val newer = ChatDatabase.builder(ctx, file).build()
        val thrown = runCatching { runBlocking { newer.chatDao().messages("t-1") } }.exceptionOrNull()
        newer.close()
        assertTrue("an older build must refuse a newer store, not open it", thrown != null)
        assertTrue("Room names the missing migration: ${thrown?.message}", thrown?.message?.contains("4 to 3") == true)

        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals("the store was not wiped", 4, raw.version)
            raw.rawQuery("SELECT content FROM messages WHERE id = 'm-1'", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("still here", c.getString(0))
            }
        }
        ctx.deleteDatabase(file)
    }
}
