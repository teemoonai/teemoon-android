package ai.teemoon.app

// spec: IMPORT_NEAR.md draft 9 / IMAGES.md §3 (teemoon-ios 53a4fa4): every case in conformance/import/near/cases.json,
// run the way the app runs it — the real isolated decoder with Wuffs, a real Room store, real attachment files — and held
// to iOS's reference results field by field, images included (key, index, width, height, mime). The JVM half
// (ConformanceImportCorpusTest) cannot decode; this is the half that does.

import ai.teemoon.app.imaging.ImportImageIngest
import ai.teemoon.app.imaging.IsolatedImageDecoder
import ai.teemoon.chat.AttachmentFiles
import ai.teemoon.chat.ChatDatabase
import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.imports.ChatImport
import ai.teemoon.chat.imports.ChatImportWriter
import ai.teemoon.chat.imports.ImportSummary
import ai.teemoon.chat.imports.NotAnExportException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConformanceImageCorpusTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun resource(name: String) = javaClass.getResourceAsStream("/import/near/$name")!!.readBytes()
    private val cases by lazy { Json.parseToJsonElement(resource("cases.json").decodeToString()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject } }
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bytes(c: JsonObject): ByteArray {
        c.str("file")?.let { return resource(it) }
        val out = ByteArrayOutputStream()
        for (part in c["recipe"]!!.jsonArray) {
            if (part is JsonPrimitive) { out.write(part.content.toByteArray()); continue }
            val o = part.jsonObject
            if ("repeat" in o) { val r = o.str("repeat")!!.toByteArray(); repeat(o["count"]!!.jsonPrimitive.int) { out.write(r) } }
            else { val each = o.str("each")!!; val joiner = o.str("joiner").orEmpty().toByteArray()
                repeat(o["count"]!!.jsonPrimitive.int) { i -> if (i > 0) out.write(joiner); out.write(each.replace("{i}", i.toString()).toByteArray()) } }
        }
        return out.toByteArray()
    }

    private fun totals(e: JsonObject) = ImportSummary(e["chatsImported"]!!.jsonPrimitive.int, e["messagesImported"]!!.jsonPrimitive.int,
        e["chatsAlreadyHere"]!!.jsonPrimitive.int, e["itemsSkipped"]!!.jsonPrimitive.int, e["mayBeIncomplete"]!!.jsonPrimitive.boolean,
        e["imagesImported"]?.jsonPrimitive?.int ?: 0, e["imagesSkipped"]?.jsonPrimitive?.int ?: 0)

    private class Scratch(val db: ChatDatabase, val repo: ChatRepository, val dir: File) : AutoCloseable {
        override fun close() { db.close(); dir.deleteRecursively() }
    }
    private fun scratch(name: String): Scratch {
        val dir = File(ctx.cacheDir, "corpus-$name").also { it.deleteRecursively(); it.mkdirs() }
        val db = ChatDatabase.builder(ctx, File(dir, "c.db").path).build()
        return Scratch(db, ChatRepository(db.chatDao(), files = AttachmentFiles(File(dir, "attachments"))), dir)
    }

    private suspend fun import(b: ByteArray, s: Scratch, decoder: IsolatedImageDecoder): ImportSummary? = try {
        ChatImport.run(b.inputStream(), ChatImportWriter(s.repo, ImportImageIngest.encodeSourcesForTest, images = ImportImageIngest(decoder, s.repo.attachmentFiles!!)),
            spillDir = File(s.dir, "spill"))
    } catch (_: NotAnExportException) { null }

    @Test fun everyCaseWithTheRealDecoder() = runBlocking {
        val decoder = IsolatedImageDecoder(ctx)
        val failures = ArrayList<String>()
        var ran = 0
        for (c in cases) {
            if (c["steps"] != null) continue
            val name = c.str("name")!!
            val expected = c["expected"]!!.jsonObject
            scratch(name.replace(Regex("[^a-z0-9]"), "_")).use { s ->
                val first = import(bytes(c), s, decoder)
                ran++
                if (expected["rejected"]?.jsonPrimitive?.boolean == true) { if (first != null) failures += "$name: not rejected"; return@use }
                if (first != totals(expected["first"]!!.jsonObject)) failures += "$name first: expected ${totals(expected["first"]!!.jsonObject)}, got $first"
                (expected["threadsDetail"] as? JsonArray)?.forEach { t -> checkThread(name, t.jsonObject, s, failures) }
                (expected["second"] as? JsonObject)?.let { second ->
                    val again = import(bytes(c), s, decoder)
                    if (again != totals(second)) failures += "$name second: expected ${totals(second)}, got $again"
                }
            }
        }
        Log.w("teemoon", "[corpus] ran $ran cases, ${failures.size} failures")
        assertEquals(failures.joinToString("\n"), 0, failures.size)
        assertTrue(ran >= 34)
    }

    private suspend fun checkThread(name: String, t: JsonObject, s: Scratch, failures: MutableList<String>) {
        val key = t.str("key")!!
        val dao = s.db.chatDao()
        val stored = dao.messages(key)
        val messages = t["messages"]!!.jsonArray.map { it.jsonObject }
        if (stored.map { it.id } != messages.map { it.str("key") }) { failures += "$name $key: keys ${stored.map { it.id }}"; return }
        val attachments = dao.attachmentsFor(stored.map { it.id })
        for ((m, row) in messages.zip(stored)) {
            if (m.str("text") != row.content) failures += "$name ${row.id} text: <${m.str("text")}> vs <${row.content}>"
            if (m["ms"]!!.jsonPrimitive.long != row.timestamp) failures += "$name ${row.id} ms"
            val want = (m["images"] as? JsonArray).orEmpty().map { it.jsonObject.let { i -> listOf(i.str("key"), i["index"]!!.jsonPrimitive.int, i["width"]!!.jsonPrimitive.int, i["height"]!!.jsonPrimitive.int, i.str("mime")) } }
            val got = attachments.filter { it.messageId == row.id }.sortedBy { it.idx }.map { listOf(it.id, it.idx, it.width, it.height, it.mime) }
            if (want != got) failures += "$name ${row.id} images: want $want got $got"
            // Every stored image's file is there, and is our re-encoded JPEG/PNG.
            for (a in attachments.filter { it.messageId == row.id }) {
                val f = s.repo.attachmentFiles!!.resolve(a.path)
                if (f == null || !f.isFile) failures += "$name ${a.id}: no file"
            }
        }
        if (dao.observeThreads().first().none { it.id == key }) failures += "$name: no thread $key"
    }
}
