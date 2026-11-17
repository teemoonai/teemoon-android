package ai.teemoon.app

// spec: docs/IMPORT_NEAR.md draft 8 §4 and §7 tests 4 and 7 (teemoon-ios 9ad6ebd), the halves the JVM cannot settle:
// the real Room store (one transaction per chat, rolled back whole), and the 64 MiB cap and the 5,000-chat file on a
// device's heap — "Android proves the 64 MiB cap on a device; if it can't hold, pm settles a lower Android cap". The
// recipes come from cases.json and are written to a file, then streamed, as a picked file is.

import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.MessageEntity
import ai.teemoon.chat.ThreadEntity
import ai.teemoon.chat.imports.ImportSummary
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatImportDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "import-test").also { it.deleteRecursively(); it.mkdirs() }
    private val db = ChatDatabase.builder(ctx, File(dir, "import-chat.db").path).build()
    private val repo = ChatRepository(db.chatDao())

    @After fun close() { db.close(); dir.deleteRecursively() }

    private fun log(s: String) {
        Log.w("teemoon", "[import] $s")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("import", s) })
    }

    private fun resource(name: String) = javaClass.getResourceAsStream("/import/near/$name")!!
    private val cases by lazy { Json.parseToJsonElement(resource("cases.json").reader().readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject } }

    /** A recipe written to a file part by part, never held whole; returns its sha256. */
    private fun write(recipe: String, to: File): String {
        val c = cases.first { it["name"]!!.jsonPrimitive.content == recipe }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(to.outputStream().buffered(1 shl 20), digest).use { out ->
            for (part in c["recipe"]!!.jsonArray) {
                if (part is JsonPrimitive) { out.write(part.content.toByteArray()); continue }
                val o = part as JsonObject
                if ("repeat" in o) { val r = o["repeat"]!!.jsonPrimitive.content.toByteArray(); repeat(o["count"]!!.jsonPrimitive.int) { out.write(r) } }
                else {
                    val each = o["each"]!!.jsonPrimitive.content; val joiner = o["joiner"]?.jsonPrimitive?.content.orEmpty().toByteArray()
                    repeat(o["count"]!!.jsonPrimitive.int) { i -> if (i > 0) out.write(joiner); out.write(each.replace("{i}", i.toString()).toByteArray()) }
                }
            }
        }
        assertEquals("$recipe sha256", c["sha256"]!!.jsonPrimitive.content, digest.digest().joinToString("") { "%02x".format(it) })
        return c["sha256"]!!.jsonPrimitive.content
    }

    private fun heapMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 }

    @Test fun theFixtureLandsInTheRealStoreAndASecondImportChangesNothing() = runBlocking {
        assertEquals(ChatImportUi.Finished(ImportSummary(6, 25, 0, 4)), ChatImportRunner.run({ resource("near-export-v1.json") }, repo))
        val dao = db.chatDao()
        assertEquals(6, dao.messages("near:conv_lisbon").size)
        assertEquals("Name a prime number.", dao.observeThreads().first().single { it.id == "near:conv_prime" }.title)
        assertEquals(ChatImportUi.Finished(ImportSummary(0, 0, 6, 1)), ChatImportRunner.run({ resource("near-export-v1.json") }, repo))
        assertEquals(25, dao.indexableMessageIds().size)
    }

    /** One transaction per chat: a message that cannot be written takes its thread and every other message with it. */
    @Test fun aChatThatFailsPartWayLeavesNothingBehind() = runBlocking {
        val dao = db.chatDao()
        val thread = ThreadEntity("near:conv_x", "half", 1L)
        val m = MessageEntity("near:conv_x:a", "near:conv_x", "user", "one", 1L)
        val failed = runCatching { dao.insertChat(thread, listOf(m, m.copy(content = "the same id again"))) }
        assertTrue("the duplicate id must abort", failed.isFailure)
        assertFalse(dao.threadExists("near:conv_x"))
        assertTrue(dao.messages("near:conv_x").isEmpty())
    }

    /** §4's cap on this device: a conversation of 64 MiB + 1 is skipped and counted, the next one imports, the heap holds. */
    @Test fun theSixtyFourMebibyteCapHoldsOnTheDevice() = runBlocking {
        val file = File(dir, "oversize.json"); write("oversize", file)
        val before = heapMb(); var peak = before
        val sampler = Thread { while (!Thread.currentThread().isInterrupted) { peak = maxOf(peak, heapMb()); try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread } } }.apply { start() }
        val t0 = System.nanoTime()
        val ui = ChatImportRunner.run({ FileInputStream(file) }, repo)
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()
        log("oversize: ${file.length()} B in $ms ms, heap ${before} → peak $peak MB of max ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, native ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB")
        assertEquals(ChatImportUi.Finished(ImportSummary(1, 4, 0, 1)), ui)
        assertEquals(listOf("near:conv_tie"), db.chatDao().observeThreads().first().map { it.id })
    }

    /** §7 test 7: 5,000 chats in one file, with memory that does not grow with the file. */
    @Test fun fiveThousandChatsImportWithFlatMemory() = runBlocking {
        val file = File(dir, "many.json"); write("many-chats", file)
        System.gc(); val before = heapMb(); var peak = before
        val sampler = Thread { while (!Thread.currentThread().isInterrupted) { peak = maxOf(peak, heapMb()); try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread } } }.apply { start() }
        val t0 = System.nanoTime()
        val ui = ChatImportRunner.run({ FileInputStream(file) }, repo)
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()
        log("many-chats: ${file.length()} B, 5000 chats in $ms ms, heap $before → peak $peak MB")
        assertEquals(ChatImportUi.Finished(ImportSummary(5000, 10000, 0, 0)), ui)
        assertTrue("heap grew ${peak - before} MB for a 2 MB file", peak - before < 100)
        assertEquals(ChatImportUi.Finished(ImportSummary(0, 0, 5000, 0)), ChatImportRunner.run({ FileInputStream(file) }, repo))
    }
}
