package ai.teemoon.app

// android-only: no Swift counterpart (the maintainer, 2026-10-19: "yes residue fix"). Deleted chat text must not stay readable
// in the database files: the Room store (teemoon-chat.db, its -wal and -shm) and the search index's sidecar
// (chat-search-index.sqlite and its -wal and -shm). A GENERATED store in a scratch directory, opened with the app's own
// builder (ChatDatabase.builder) and search service; two chats carry a marker. After deleting one chat, then all, every
// byte of every file is searched for the markers, and the delete times are reported (sendStatus).

import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.search.ChatSearchService
import ai.teemoon.chat.search.RoomChatSearchSource
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletedTextResidueTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) {
        Log.w("teemoon", "[residue] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("residue", s) })
    }

    private val words = "the quote names enclave model loaded collateral platform measurement verifier attestation key reply token".split(" ")
    private fun prose(r: Random, n: Int) = (1..n).joinToString(" ") { words[r.nextInt(words.size)] }
    private inline fun ms(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }

    /** Every file in [dir] that holds [marker] (UTF-8 or UTF-16LE) anywhere in its bytes. */
    private fun holders(dir: File, marker: String): List<String> {
        val needles = listOf(marker.toByteArray(Charsets.UTF_8), marker.toByteArray(Charsets.UTF_16LE))
        return dir.listFiles().orEmpty().filter { it.isFile }.filter { f ->
            val b = f.readBytes()
            needles.any { n -> (0..b.size - n.size).any { i -> n.indices.all { j -> b[i + j] == n[j] } } }
        }.map { "${it.name} (${it.length()} B)" }
    }
    private fun sizes(dir: File) = dir.listFiles().orEmpty().sortedBy { it.name }.joinToString(", ") { "${it.name} ${it.length() / 1024} KB" }

    /** The store's scrub runs in the background (ChatDatabase.maintenance); a timed delete waits for it. */
    private suspend fun settle(scope: CoroutineScope) { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() } }

    @Test fun deletedChatTextIsNotLeftInTheFiles() = runBlocking {
        val dir = File(ctx.cacheDir, "residue").also { it.deleteRecursively(); it.mkdirs() }
        val file = File(dir, "residue-chat.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.builder(ctx, file.path).build()
        val search = ChatSearchService(scope).also { it.configure(file, isStoredInMemoryOnly = false) { } }
        try {
            val dao = db.chatDao(); val repo = ChatRepository(dao, search, maintenance = ChatDatabase.maintenance(db, scope))
            val r = Random(7); val base = 1_770_000_000_000L
            // 300 chats × 20 messages; chats 7 and 8 carry the markers in every message
            for (t in 0 until 300) db.withTransaction {
                dao.upsertThread(ThreadEntity(id = "r-$t", title = "thread $t", timestamp = base - t * 60_000L))
                for (i in 0 until 20) {
                    val mark = when (t) { 7 -> " RESIDUEMARKSEVEN"; 8 -> " RESIDUEMARKEIGHT"; else -> "" }
                    dao.upsertMessage(MessageEntity(id = "r-$t-$i", threadId = "r-$t", role = if (i % 2 == 0) "user" else "assistant",
                        content = prose(r, 40) + mark, timestamp = base - t * 60_000L + i))
                }
            }
            search.reconcile(RoomChatSearchSource(dao)); search.drainPendingWork()
            log("before: ${sizes(dir)} · SEVEN in ${holders(dir, "RESIDUEMARKSEVEN")}")

            val one = ms { repo.delete("r-7"); search.drainPendingWork(); settle(scope) }
            val sevenAfterOne = holders(dir, "RESIDUEMARKSEVEN")
            log("delete one chat: $one ms · SEVEN still in ${sevenAfterOne.ifEmpty { listOf("none") }} · ${sizes(dir)}")

            val all = ms { repo.deleteAll(); search.drainPendingWork(); settle(scope) }
            val eightAfterAll = holders(dir, "RESIDUEMARKEIGHT"); val sevenAfterAll = holders(dir, "RESIDUEMARKSEVEN")
            log("delete all: $all ms · EIGHT still in ${eightAfterAll.ifEmpty { listOf("none") }} · SEVEN in ${sevenAfterAll.ifEmpty { listOf("none") }} · ${sizes(dir)}")

            assertEquals("a deleted chat's text is still readable in", emptyList<String>(), sevenAfterOne)
            assertEquals("deleted chats' text is still readable after delete all in", emptyList<String>(), eightAfterAll + sevenAfterAll)
        } finally {
            scope.cancel(); db.close(); dir.deleteRecursively()
        }
    }

    /**
     * iOS e871b7a: a delete that had not reached the disk when the app was killed came back on the next launch. Here the
     * Room delete commits before `delete` returns; the index removal and the scrub are queued jobs. This kills the
     * process "between the two": the queue's dispatcher drops every job, and nothing is closed (a close would
     * checkpoint, which a kill does not). Then a fresh launch: new connections, reconcile. The chat must stay deleted,
     * leave the index, and leave no readable text in the files.
     */
    @Test fun aChatDeletedJustBeforeAKillStaysDeletedAndUnreadable() = runBlocking {
        val dir = File(ctx.cacheDir, "residue-kill").also { it.deleteRecursively(); it.mkdirs() }
        val file = File(dir, "residue-chat.db")
        val dead = CoroutineScope(SupervisorJob() + object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {}   // the process is gone
        })
        val live = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = ChatDatabase.builder(ctx, file.path).build()
        val beforeSearch = ChatSearchService(live).also { it.configure(file, isStoredInMemoryOnly = false) { } }
        val after = ChatDatabase.builder(ctx, file.path).build()
        try {
            val dao = before.chatDao(); val r = Random(11); val base = 1_770_000_000_000L
            for (t in 0 until 50) before.withTransaction {
                dao.upsertThread(ThreadEntity(id = "k-$t", title = "thread $t", timestamp = base - t * 60_000L))
                for (i in 0 until 20) dao.upsertMessage(MessageEntity(id = "k-$t-$i", threadId = "k-$t", role = if (i % 2 == 0) "user" else "assistant",
                    content = prose(r, 40) + if (t == 3) " KILLMARKTHREE" else "", timestamp = base - t * 60_000L + i))
            }
            beforeSearch.reconcile(RoomChatSearchSource(dao)); beforeSearch.drainPendingWork()
            // the launch that deletes: its index hooks and its scrub run on the dead scope
            val dying = ChatSearchService(dead).also { it.configure(file, isStoredInMemoryOnly = false) { } }
            ChatRepository(dao, dying, maintenance = ChatDatabase.maintenance(before, dead)).delete("k-3")
            log("killed right after delete: KILLMARKTHREE in ${holders(dir, "KILLMARKTHREE").ifEmpty { listOf("none") }}")

            // the next launch
            val afterDao = after.chatDao()
            val search = ChatSearchService(live).also { it.configure(file, isStoredInMemoryOnly = false) { } }
            search.reconcile(RoomChatSearchSource(afterDao)); search.drainPendingWork()
            val back = afterDao.messages("k-3").size
            val hits = search.search("KILLMARKTHREE")
            val left = holders(dir, "KILLMARKTHREE")
            log("next launch: messages back $back · search hits ${hits?.size} · KILLMARKTHREE in ${left.ifEmpty { listOf("none") }} · ${sizes(dir)}")
            assertEquals("the deleted chat came back", 0, back)
            assertEquals("the deleted chat is still searchable", 0, hits?.size ?: 0)
            assertEquals("the deleted chat's text is readable after the next launch in", emptyList<String>(), left)
        } finally {
            dead.cancel(); live.cancel(); before.close(); after.close(); dir.deleteRecursively()
        }
    }
}
