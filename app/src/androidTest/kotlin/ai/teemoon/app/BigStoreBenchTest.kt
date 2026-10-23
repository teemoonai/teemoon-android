package ai.teemoon.app

// android-only: no Swift counterpart. 1.0.6 performance rows 13 and 14 (pm's "Performance scope", the maintainer 2026-10-19):
// chat search and the store's own costs on a BIG store, reported through sendStatus, not asserted.
//
// The store is GENERATED, never real: 1,000 threads × 50 messages of seeded random prose, in a scratch database of
// its own (cache/bench-big/, deleted before and after). The app's teemoon-chat.db is never
// opened. One rare word ("lighthouse") is planted in 5 messages so a selective query has something to find.

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BigStoreBenchTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun log(s: String) {
        Log.w("teemoon", "[bench] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("bench", s) })
    }

    private val words = ("the quote names enclave model loaded collateral platform current measurement binds code intent " +
        "verifier checked field expected attestation proves hardware vendor build reproducible chain source trust key " +
        "server reply stream token prompt search result summary weather region shipment late because note answer").split(" ")
    private fun prose(r: Random, n: Int) = (1..n).joinToString(" ") { words[r.nextInt(words.size)] }

    private inline fun ms(block: () -> Unit): Double { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6 }
    private fun List<Double>.median() = sorted()[size / 2]
    private fun List<Double>.p95() = sorted()[(size * 95 / 100).coerceAtMost(size - 1)]
    private fun mb(vararg f: File) = "%.1f".format(f.sumOf { if (it.exists()) it.length() else 0L } / 1_048_576.0)

    /** The store's scrub runs in the background (ChatDatabase.maintenance); a timed delete waits for it. */
    private suspend fun settle(scope: CoroutineScope) { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() } }

    @Test fun bigStore() = runBlocking {
        // a directory of its own: the search sidecar is created BESIDE the store (ChatSearchIndex.sidecarFile)
        val dir = File(ctx.cacheDir, "bench-big").also { it.deleteRecursively(); it.mkdirs() }
        val file = File(dir, "bench-big.db")
        fun files() = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = ChatDatabase.builder(ctx, file.path).build()   // the app's builder: secure_delete on, as shipped
        val search = ChatSearchService(scope, log = { log("search: $it") })
        search.configure(file, isStoredInMemoryOnly = false) { }
        try {
            val dao = db.chatDao(); val repo = ChatRepository(dao, search, maintenance = ChatDatabase.maintenance(db, scope))
            val r = Random(19)
            val threads = 1_000; val perThread = 50; val base = 1_770_000_000_000L
            val seedMs = ms {
                for (t in 0 until threads) db.withTransaction {
                    val at = base - t * 3_600_000L
                    dao.upsertThread(ThreadEntity(id = "big-$t", title = "thread $t: ${prose(r, 6)}", timestamp = at))
                    for (i in 0 until perThread) {
                        val user = i % 2 == 0
                        val planted = !user && t % 200 == 7 && i == 1
                        dao.upsertMessage(MessageEntity(id = "big-$t-$i", threadId = "big-$t", role = if (user) "user" else "assistant",
                            content = if (user) prose(r, 14) + "?" else prose(r, 60) + (if (planted) " the lighthouse keeper" else "") + ".", timestamp = at + i * 30_000L))
                    }
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            log("SUMMARY seed ${threads * perThread} messages in ${"%.0f".format(seedMs)} ms · store ${mb(*files().toTypedArray())} MB")

            // the launch reconcile: once from nothing (the first launch after an update that adds the index), once converged (every launch)
            val reconcileCold = ms { search.reconcile(RoomChatSearchSource(dao)) }
            val reconcileWarm = ms { search.reconcile(RoomChatSearchSource(dao)) }
            val indexFiles = dir.listFiles().orEmpty().filter { it !in files() }
            log("SUMMARY reconcile cold ${"%.0f".format(reconcileCold)} ms · converged ${"%.0f".format(reconcileWarm)} ms · index ${mb(*indexFiles.toTypedArray())} MB (${indexFiles.joinToString { it.name }})")

            // row 13: query to results, THREADS granularity as the chats list asks
            for (q in listOf("lighthouse", "enclave", "attestation quote", "verif", "zzzzqx")) {
                var hits = 0
                val runs = (1..7).map { ms { hits = search.search(q)?.size ?: -1 } }
                val fallback = ms { repo.fallbackThreadsMatching(q) }
                log("SUMMARY search \"$q\": median ${"%.1f".format(runs.median())} ms, max ${"%.1f".format(runs.max())} ms, $hits threads · LIKE fallback ${"%.0f".format(fallback)} ms")
            }

            // the list and a chat, as the screens read them (Room flows, first emission)
            val list = (1..5).map { ms { repo.threads().first() } }
            val visible = (1..5).map { ms { repo.visibleThreads().first() } }
            val previews = (1..5).map { ms { repo.previews().first() } }
            val chat = (1..5).map { ms { repo.messages("big-500").first() } }
            log("SUMMARY reads (median of 5): threads ${"%.1f".format(list.median())} ms · visibleThreads ${"%.1f".format(visible.median())} ms · previews ${"%.1f".format(previews.median())} ms · one 50-message chat ${"%.1f".format(chat.median())} ms")

            // row 14: a message saved as the app saves one (the store, then the index's incremental hook)
            repo.createThread("big-new", base + 1)
            val adds = (0 until 100).map { i -> ms { repo.addMessage("big-new-$i", "big-new", if (i % 2 == 0) "user" else "assistant", prose(r, 60), base + 1 + i) } }
            val drain = ms { search.drainPendingWork() }
            log("SUMMARY addMessage x100: median ${"%.2f".format(adds.median())} ms, p95 ${"%.2f".format(adds.p95())} ms · index catch-up ${"%.0f".format(drain)} ms")
            val deleteOne = ms { repo.delete("big-500"); search.drainPendingWork(); settle(scope) }
            val deleteAll = ms { repo.deleteAll(); search.drainPendingWork(); settle(scope) }
            log("SUMMARY delete one thread (50 messages) ${"%.0f".format(deleteOne)} ms · delete all ${"%.0f".format(deleteAll)} ms (secure_delete, checkpoints, VACUUM) · store after ${mb(*files().toTypedArray())} MB · index after ${mb(*dir.listFiles().orEmpty().filter { it !in files() }.toTypedArray())} MB")
        } finally {
            scope.cancel(); db.close()
            dir.deleteRecursively()
        }
    }
}
