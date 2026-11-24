// ports: teemoonTests/ImportCorpusTests.swift @ 8d0b76c, @ 3258473a (37 cases: the image shapes and the ~10 MB recipe) — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// spec: docs/IMPORT_NEAR.md draft 8 §7 tests 1, 5, 6 and 10 (teemoon-ios 8d0b76c, sha256 86fdcb37…ff7b): every case in
// conformance/import/near/cases.json, vendored byte for byte under conformance/vendor/import/near/. The expected
// results are iOS's reference_import.py output; Android never certifies its importer against vectors it wrote.
//
// The one Android rule the cases cannot carry is the title: the reference stores none for an untitled chat (iOS's
// rule), and Android stores its first user message's first line (§3.9). So an expected null title is checked against
// that rule, applied to the expected messages.
//
// IMAGES (draft 9): decoding needs the isolated service and Wuffs, which only a device has. Here the import runs WITHOUT
// an image step, so every image part stays its placeholder line: the image totals, a decoded image's attachment, and
// the text of a message whose image decoded (it loses its line) are left to ConformanceImageCorpusTest on the device.
// Everything else — every other count, key, field and text — is checked here.
package ai.teemoon.chat.imports

import ai.teemoon.chat.ChatRepository
import ai.teemoon.chat.imports.ImportTestSupport.decode
import ai.teemoon.chat.imports.ImportTestSupport.sha256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ConformanceImportCorpusTest {
    private val manifestBytes = ImportTestSupport.resource("cases.json")
    private val cases: List<JsonObject> = (Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject["cases"] as JsonArray).map { it.jsonObject }
    private fun case(name: String) = cases.first { it.str("name") == name }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A committed file, or a recipe built in memory: strings as is, `{repeat, count}`, `{each, count, joiner}` with `{i}`. */
    private fun bytes(c: JsonObject): ByteArray {
        c.str("file")?.let { return ImportTestSupport.resource(it) }
        val out = ByteArrayOutputStream()
        for (part in c["recipe"]!!.jsonArray) {
            if (part is JsonPrimitive) { out.write(part.content.toByteArray()); continue }
            val o = part.jsonObject
            if ("repeat" in o) { val r = o.str("repeat")!!.toByteArray(); repeat(o["count"]!!.jsonPrimitive.int) { out.write(r) } }
            else {
                val each = o.str("each")!!; val joiner = o.str("joiner").orEmpty().toByteArray()
                repeat(o["count"]!!.jsonPrimitive.int) { i -> if (i > 0) out.write(joiner); out.write(each.replace("{i}", i.toString()).toByteArray()) }
            }
        }
        return out.toByteArray()
    }

    private suspend fun run(bytes: ByteArray, store: ListImportStore): ImportSummary? = try {
        ChatImport.run(ByteArrayInputStream(bytes), ChatImportWriter(store, ImportTestSupport.encode))
    } catch (_: NotAnExportException) { null }

    /** The expected totals without the image fields, which need the device (see the header). */
    private fun totals(e: JsonElement) = e.jsonObject.let {
        ImportSummary(it["chatsImported"]!!.jsonPrimitive.int, it["messagesImported"]!!.jsonPrimitive.int,
            it["chatsAlreadyHere"]!!.jsonPrimitive.int, it["itemsSkipped"]!!.jsonPrimitive.int, it["mayBeIncomplete"]!!.jsonPrimitive.boolean)
    }

    /** Every stored thread field by field: key, title (Android's rule for none), time, and each message. */
    private fun assertDetail(name: String, detail: JsonArray, store: ListImportStore) {
        for (t in detail.map { it.jsonObject }) {
            val key = t.str("key")!!
            val thread = store.threads[key] ?: return fail("$name: no thread $key")
            val messages = t["messages"]!!.jsonArray.map { it.jsonObject }
            val expectedTitle = t.str("title") ?: (messages.firstOrNull { it.str("role") == "user" } ?: messages.first()).str("text")!!.let(ChatRepository::titleFrom)
            assertEquals("$name $key title", expectedTitle, thread.title)
            assertEquals("$name $key updated", t["updatedMs"]!!.jsonPrimitive.long, thread.timestamp)
            val stored = store.transcript(key)
            assertEquals("$name $key keys", messages.map { it.str("key") }, stored.map { it.id })
            for ((m, row) in messages.zip(stored)) {
                assertEquals("$name ${row.id} role", m.str("role"), row.role)
                // A message whose image decoded loses that image's line; without the decoder it keeps it (see the header).
                if ((m["images"] as? JsonArray).isNullOrEmpty()) assertEquals("$name ${row.id} text", m.str("text"), row.content)
                assertEquals("$name ${row.id} ms", m["ms"]!!.jsonPrimitive.long, row.timestamp)
                val sources = m["sources"]!!.jsonArray.map { s -> s.jsonObject.let { StoredSource(it.str("url")!!, it.str("domain")!!, it.str("title")!!) } }
                assertEquals("$name ${row.id} sources", sources, decode(row.sourcesJson))
                assertFalse(row.isE2EE); assertNull(row.generatingTimeMs)
            }
        }
    }

    // 1
    @Test fun theManifestAndEveryFileAreTheSpecsBytes() {
        assertEquals("38022da54369439bac3a393de4b3deb3ffb80ebbde9bb39f74d47430dab8ddd7", sha256(manifestBytes))
        assertEquals("near", Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject.str("source"))
        assertEquals("6b20405d85fb80d3d9106614b9a9146ebd0b2a10d03c445f508cc9c0fbe63e6f", sha256(ImportTestSupport.full))
        assertEquals("6d2f62d1185eaae15f089a63a5331e47d43816aadff2df9f8573407344d03a03", sha256(ImportTestSupport.single))
        for (c in cases) {
            if (c["sha256"] == null) continue
            val b = bytes(c)
            assertEquals("${c.str("name")} sha256", c.str("sha256"), sha256(b))
            assertEquals("${c.str("name")} bytes", c["bytes"]!!.jsonPrimitive.long, b.size.toLong())
        }
    }

    // 6: every file case, first and second run
    @Test fun everyCaseGivesItsExpectedResult() = runTest {
        var ran = 0
        for (c in cases) {
            if (c["steps"] != null) continue
            val name = c.str("name")!!
            val expected = c["expected"]!!.jsonObject
            val b = bytes(c)
            val store = ListImportStore()
            val first = run(b, store)
            ran++
            if (expected["rejected"]?.jsonPrimitive?.boolean == true) { assertNull("$name should be rejected", first); assertTrue(store.threads.isEmpty()); continue }
            assertEquals("$name first run", totals(expected["first"]!!), first)
            when (val threads = expected["threads"]) {
                is JsonArray -> assertEquals("$name threads", threads.map { it.jsonPrimitive.content }.sorted(), store.threads.keys.sorted())
                is JsonPrimitive -> assertEquals("$name threads", threads.int, store.threads.size)
                else -> Unit
            }
            (expected["threadsDetail"] as? JsonArray)?.let { assertDetail(name, it, store) }
            expected["second"]?.let { second ->
                val before = store.snapshot()
                assertEquals("$name second run", totals(second), run(b, store))
                assertEquals("$name: the second run changed rows", before, store.snapshot())
            }
        }
        assertEquals(cases.count { it["steps"] == null }, ran)
    }

    @Test fun theSameChatFromBothFilesIsTwoThreadsByDesign() = runTest {
        val c = case("same-chat-twice")["expected"]!!.jsonObject
        val store = ListImportStore()
        run(ImportTestSupport.full, store)
        assertEquals(totals(c["second"]!!), run(ImportTestSupport.resource("derived/prime-as-chat-file.json"), store))
        assertTrue(store.threads.keys.containsAll(c["threads"]!!.jsonArray.map { it.jsonPrimitive.content }))
        assertEquals(store.transcript("near:conv_prime").map { it.content }, store.transcript("near:chat:resp_p1").map { it.content })
    }

    // 5
    @Test fun aDeletedChatComesBackIdentical() = runTest {
        val c = case("deleted-chat-returns")["expected"]!!.jsonObject
        val store = ListImportStore()
        run(ImportTestSupport.full, store)
        val thread = store.threads["near:conv_prime"]; val messages = store.transcript("near:conv_prime")
        store.delete("near:conv_prime")
        assertEquals(totals(c["third"]!!), run(ImportTestSupport.full, store))
        assertTrue(c["restoredThreadIdenticalToFirstImport"]!!.jsonPrimitive.boolean)
        assertEquals(thread, store.threads["near:conv_prime"])
        assertEquals(messages, store.transcript("near:conv_prime"))
    }

    @Test fun noCaseIsLeftUnrun() {
        val known = setOf("same-chat-twice", "deleted-chat-returns")
        assertEquals(known, cases.filter { it["steps"] != null }.map { it.str("name") }.toSet())
        assertTrue(cases.none { it["expected"] is JsonNull })
    }
}
