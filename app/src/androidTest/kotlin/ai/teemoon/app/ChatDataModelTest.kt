package ai.teemoon.app

// ports: teemoonTests/DataModelTests.swift @ v1.0.1 — the persistence half, over a real Room database on the device
// (Room needs Android; Robolectric is not on the dependency list). The grounding-sources cases are :inference's
// GroundingSourceTest.
//
// ONE DELIBERATE DIFFERENCE, pinned rather than papered over. SwiftData's thread→messages relation has no delete rule,
// so a bare `delete(thread)` ORPHANS its messages (threadDelete_leavesMessagesBehindAsOrphans) and iOS cascades in app
// code (ThreadDeletion). Here the cascade is the SCHEMA's: MessageEntity's foreign key is ON DELETE CASCADE, and Room's
// generated ChatDatabase_Impl runs `PRAGMA foreign_keys = ON` at open. So the iOS rule "delete means delete — no
// orphans" holds at the lowest layer, and the cases below are what would catch that pragma, or that foreign key,
// going missing — the day a delete would start leaving the user's words behind, unsearchable and undeletable.

import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDataModelTest {
    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ChatDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
    }
    @After fun close() = db.close()

    /**
     * Every row in `messages`, read straight from SQLite. NOT the DAO's indexableMessageIds: that joins `threads`,
     * which is exactly what would hide an orphan from the case looking for one.
     */
    private data class Row(val content: String)
    private fun allMessages(): List<Row> = db.openHelper.readableDatabase.query("SELECT content FROM messages ORDER BY timestamp").use { c ->
        buildList { while (c.moveToNext()) add(Row(c.getString(0))) }
    }

    @Test fun aMessageStartsWithTheDefaults() = runBlocking {
        repo.createThread("t", 1)
        val m = repo.addMessage("m", "t", "user", "Hello", 1)
        assertEquals("user", m.role); assertEquals("Hello", m.content)
        assertNull(m.generatingTimeMs); assertNull(m.sourcesJson); assertFalse(m.isE2EE)
    }

    /** message_persistenceRoundtrip: the e2ee flag and the thread link survive the store. */
    @Test fun aMessageRoundTripsWithItsE2eeFlagAndThread() = runBlocking {
        repo.createThread("t", 1)
        repo.addMessage("m", "t", "user", "Persist me", 1, isE2EE = true)
        val back = repo.messages("t").first().single()
        assertEquals("Persist me", back.content); assertTrue(back.isE2EE); assertEquals("t", back.threadId)
    }

    /** thread_sortedMessages_ordersByTimestamp: 100, 200, 150 reads back 100, 150, 200 — insertion order is not the order. */
    @Test fun aThreadsMessagesReadInTimestampOrder() = runBlocking {
        repo.createThread("t", 1)
        repo.addMessage("a", "t", "user", "First", 100)
        repo.addMessage("b", "t", "assistant", "Second", 200)
        repo.addMessage("c", "t", "user", "Third", 150)
        assertEquals(listOf("First", "Third", "Second"), repo.messages("t").first().map { it.content })
    }

    /** thread_initGeneratesUniqueID, in the form this app mints ids: two new threads never share one. */
    @Test fun twoNewThreadsNeverShareAnId() = runBlocking {
        var n = 0
        val a = repo.newOrCurrentThread(null, 1) { "t-${n++}" }
        val b = repo.newOrCurrentThread(null, 2) { "t-${n++}" }
        assertNotEquals(a, b)
    }

    /** threadDeletion_deletesItsMessages: delete means delete — no orphans — and the other thread is untouched. */
    @Test fun deletingAThreadDeletesItsMessagesAndNothingElse() = runBlocking {
        repo.createThread("doomed", 1); repo.createThread("kept", 2)
        repo.addMessage("1", "doomed", "user", "going", 1)
        repo.addMessage("2", "doomed", "assistant", "also going", 2)
        repo.addMessage("3", "kept", "user", "staying", 3)
        repo.delete("doomed")
        assertEquals(listOf("staying"), allMessages().map { it.content })
    }

    /**
     * The SCHEMA cascades, not only the repository: a raw thread delete — the path iOS pins as orphaning — leaves no
     * message behind here. This is the case that fails if the foreign key or `PRAGMA foreign_keys` ever goes.
     */
    @Test fun aBareThreadDeleteCascadesAtTheSchema() = runBlocking {
        repo.createThread("t", 1)
        repo.addMessage("1", "t", "user", "first", 1); repo.addMessage("2", "t", "user", "second", 2)
        db.chatDao().deleteThread("t")
        assertTrue("messages outlived their thread", allMessages().isEmpty())
    }

    /**
     * The CONTROL for the case above: with `PRAGMA foreign_keys` off, the same delete leaves every message behind. It is
     * what makes the cascade case evidence rather than coincidence — if this ever passes WITHOUT orphans, SQLite is not
     * the thing enforcing the cascade and the case above proves nothing.
     */
    @Test fun withoutThePragmaTheSameDeleteOrphans() = runBlocking {
        repo.createThread("t", 1); repo.addMessage("1", "t", "user", "left behind", 1)
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        db.chatDao().deleteThread("t")
        assertEquals(listOf("left behind"), allMessages().map { it.content })
    }

    /** threadDeletion_deleteAllSweepsThreadsAndMessages. */
    @Test fun deleteAllSweepsThreadsAndMessages() = runBlocking {
        repo.createThread("t", 1); repo.addMessage("1", "t", "user", "gone", 1)
        repo.deleteAll()
        assertTrue(repo.threads().first().isEmpty()); assertTrue(allMessages().isEmpty())
    }
}
