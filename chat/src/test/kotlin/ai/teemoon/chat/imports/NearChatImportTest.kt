// ports: teemoonTests/ImportBehaviorTests.swift @ abe841b — written to docs/IMPORT_NEAR.md draft 8 and held to the shared corpus
// spec: docs/IMPORT_NEAR.md draft 8 §7 (teemoon-ios 9ad6ebd): the fixture table read the Android way (titles, row
// ids, no PII), tests 2–5 and 10, and Android's own hostile cases (a deep stack, a cut, wrong types). The corpus in
// cases.json is ConformanceImportCorpusTest; test 8 (no network) and 9 (search) are ChatImportNoNetworkTest and
// ChatImportSearchTest.
package ai.teemoon.chat.imports

import ai.teemoon.chat.imports.ImportTestSupport.compact
import ai.teemoon.chat.imports.ImportTestSupport.decode
import ai.teemoon.chat.imports.ImportTestSupport.full
import ai.teemoon.chat.imports.ImportTestSupport.import
import ai.teemoon.chat.imports.ImportTestSupport.parse
import ai.teemoon.chat.imports.ImportTestSupport.single
import ai.teemoon.chat.imports.ImportTestSupport.summary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class NearChatImportTest {

    private fun contents(store: ListImportStore, thread: String) = store.transcript(thread).map { it.content }
    private fun times(store: ListImportStore, thread: String) = store.transcript(thread).map { it.timestamp }

    // 2
    @Test fun theFullExportImportsAsTheTableSays() = runTest {
        val store = ListImportStore()
        assertEquals(summary(6, 25, 0, 4), import(full, store))
        assertEquals(listOf("near:conv_lisbon", "near:conv_prime", "near:conv_tie", "near:conv_receipt", "near:conv_damaged", "near:conv_legacy"), store.threads.keys.toList())

        val lisbon = store.transcript("near:conv_lisbon")
        assertEquals("Two days in Lisbon", store.threads["near:conv_lisbon"]!!.title)
        assertEquals(listOf("What should I see in Lisbon in two days?", "Day one: Alfama and the castle.\n\nDay two: Belém — the tower and the pastéis.",
            "And where to eat?", "Time Out Market for variety.", "Anything open late?", "Bairro Alto stays busy past midnight."), lisbon.map { it.content })
        assertEquals(listOf("user", "assistant", "user", "assistant", "user", "assistant"), lisbon.map { it.role })
        // The same-second reply and question: the question goes 1 ms after.
        assertEquals(listOf(1760000000000, 1760000003000, 1760000003001, 1760000010000, 1760000020000, 1760000025000), lisbon.map { it.timestamp })
        assertEquals(1760000025000, store.threads["near:conv_lisbon"]!!.timestamp)
        assertEquals(listOf(StoredSource("https://www.visitlisboa.com/en/itineraries", "www.visitlisboa.com", "Lisbon itineraries"),
            StoredSource("https://en.wikipedia.org/wiki/Bel%C3%A9m_Tower", "en.wikipedia.org", "Belém Tower")), decode(lisbon[1].sourcesJson))
        assertEquals(listOf(StoredSource("https://www.timeout.com/lisbon", "www.timeout.com", "www.timeout.com")), decode(lisbon[3].sourcesJson))
        assertNull("several searches, no sources: nothing stored", lisbon[5].sourcesJson)
        assertTrue(lisbon.filter { it.role == "user" }.all { it.sourcesJson == null })

        // The deeper branch wins; no title, so Android's own rule names it.
        assertEquals(listOf("Name a prime number.", "7", "A bigger one?", "101", "Bigger still?", "1009"), contents(store, "near:conv_prime"))
        assertEquals("Name a prime number.", store.threads["near:conv_prime"]!!.title)
        assertEquals(1760100101000, store.threads["near:conv_prime"]!!.timestamp)
        // Same depth: the first-listed sibling wins. "New Conversation" is near's default, so no title.
        assertEquals(listOf("Pick a colour.", "Blue.", "Another?", "Green."), contents(store, "near:conv_tie"))
        assertEquals("Pick a colour.", store.threads["near:conv_tie"]!!.title)

        assertEquals(listOf("What's the total on this?\n\n[image not imported]\n\n[file not imported]\n\n[file not imported]", "The total is €42.10."), contents(store, "near:conv_receipt"))
        assertEquals("Receipt question", store.threads["near:conv_receipt"]!!.title)

        assertEquals(listOf("Hello", "Hi there.", "Still there?"), contents(store, "near:conv_damaged"))
        assertTrue("the FIRST msg_a1 is the one kept", "near:conv_damaged:msg_a1" in store.messages)

        assertFalse("an empty conversation makes no thread", "near:conv_empty" in store.threads)

        // Legacy: data order, no walk; three messages in one second, a mixed-case host kept as given.
        assertEquals(listOf("First old question.", "First old answer.", "Second old question.", "Second old answer."), contents(store, "near:conv_legacy"))
        assertEquals(listOf(1760400000000, 1760400000001, 1760400000002, 1760400001000), times(store, "near:conv_legacy"))
        assertEquals(listOf(StoredSource("https://WWW.Example.com/x", "WWW.Example.com", "WWW.Example.com")), decode(store.transcript("near:conv_legacy")[1].sourcesJson))

        // Keys are the shared contract: the strings themselves are Android's row ids.
        assertTrue("near:conv_prime:msg_u1" in store.messages)
        for (m in store.messages.values) {
            assertTrue(m.id.startsWith(m.threadId + ":"))
            assertFalse(m.isE2EE)
            assertNull(m.generatingTimeMs)
            // No PII beyond the text, and no model id, in any stored field.
            for (field in listOf(m.content, m.sourcesJson.orEmpty(), m.id)) {
                for (never in listOf("Sample Person", "user_0001", "GLM", "zai-org", "data:image", "iVBORw0")) assertFalse("$never in ${m.id}", never in field)
            }
        }
        assertTrue(store.threads.values.none { (it.title ?: "").contains("Sample Person") })
    }

    // 3
    @Test fun aSecondImportChangesNothing() = runTest {
        val store = ListImportStore()
        import(full, store)
        val before = store.snapshot()
        assertEquals(summary(0, 0, 6, 1), import(full, store))
        assertEquals(before, store.snapshot())
    }

    // 4
    @Test fun anInterruptedImportIsFinishedByRunningItAgain() = runTest {
        val clean = ListImportStore().also { import(full, it) }
        val store = ListImportStore().apply { failOnInsert = 3 }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { import(full, store) } }
        assertEquals("two whole chats, nothing of the third", listOf("near:conv_lisbon", "near:conv_prime"), store.threads.keys.toList())
        store.failOnInsert = null
        assertEquals(summary(4, 13, 2, 4), import(full, store))
        assertEquals(clean.threads.mapValues { it.value }, store.threads.toMap())
        assertEquals(clean.messages, store.messages)
    }

    // 5
    @Test fun aDeletedChatComesBackOnReimport() = runTest {
        val store = ListImportStore()
        import(full, store)
        store.delete("near:conv_prime")
        assertEquals(summary(1, 6, 5, 1), import(full, store))
        assertEquals(listOf("Name a prime number.", "7", "A bigger one?", "101", "Bigger still?", "1009"), contents(store, "near:conv_prime"))
    }

    // 6: the per-chat file
    @Test fun thePerChatFileImportsAsItsTableSays() = runTest {
        val store = ListImportStore()
        assertEquals(summary(1, 6, 0, 0), import(single, store))
        assertEquals(listOf("near:chat:resp_c1"), store.threads.keys.toList())
        assertTrue("near:chat:resp_c1:msg_c1" in store.messages)
        assertEquals(listOf("Suggest a name for a cat.", "Miso.", "Something longer?", "Sir Pounce-a-lot.", "Perfect, thanks.", "Enjoy the new name."), contents(store, "near:chat:resp_c1"))
        assertEquals(listOf(1760500000000, 1760500002000, 1760500020000, 1760500022000, 1760500030000, 1760500031000), times(store, "near:chat:resp_c1"))
        assertEquals(listOf(StoredSource("https://www.example.org/cat-names", "www.example.org", "Cat names")), decode(store.transcript("near:chat:resp_c1")[3].sourcesJson))
        assertEquals("Suggest a name for a cat.", store.threads["near:chat:resp_c1"]!!.title)
        assertEquals(summary(0, 0, 1, 0), import(single, store))
    }

    @Test fun theSameChatFromBothFilesIsTwoThreadsByDesign() = runTest {
        val store = ListImportStore()
        import(full, store)
        val prime = parse(full).first { it.jsonObject["id"]!!.jsonPrimitive.content == "conv_prime" }.jsonObject["data"]!!
        assertEquals(summary(1, 6, 0, 0), import(compact(prime), store))
        assertEquals(contents(store, "near:conv_prime"), contents(store, "near:chat:resp_p1"))
    }

    @Test fun aFileOfNoKnownShapeIsRejectedWhole() = runTest {
        for (bad in listOf("hello", "{}", "[1, 2]", "", "[\"text\"]", "[{\"neither\": 1}]")) {
            assertThrows(bad, NotAnExportException::class.java) { kotlinx.coroutines.runBlocking { import(bad.toByteArray(), ListImportStore()) } }
        }
        assertEquals(summary(0, 0, 0, 0), import("[]".toByteArray(), ListImportStore()))
        assertEquals(summary(0, 0, 0, 0), import("  [ ]\n".toByteArray(), ListImportStore()))
    }

    // 7: hostile input (the corpus has the rest: ConformanceImportCorpusTest)
    private fun conversation(id: String, title: String, metadataExtra: String = "", text: String = "hello") =
        """{"id":"$id","metadata":{"title":"$title"$metadataExtra},"data":[{"type":"message","id":"u","response_id":"r","created_at":1760700000,"status":"completed","role":"user","content":[{"type":"input_text","text":"$text"}]}]}"""

    private fun tie(): String = parse(full).first { it.jsonObject["id"]!!.jsonPrimitive.content == "conv_tie" }.toString()

    @Test fun aHugelyNestedElementCannotOverflowTheStack() = runTest {
        val file = ("[" + conversation("ok", "Ok") + ",{\"data\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}]").toByteArray()
        assertEquals(summary(1, 1, 0, 1), import(file, ListImportStore()))
    }

    @Test fun aCutOffFileKeepsTheChatsBeforeTheCut() = runTest {
        val cut = full.copyOf(full.decodeToString().indexOf("\"conv_receipt\"").let { full.decodeToString().substring(0, it).toByteArray().size })
        val store = ListImportStore()
        val s = import(cut, store)
        assertEquals(listOf("near:conv_lisbon", "near:conv_prime", "near:conv_tie"), store.threads.keys.toList())
        assertEquals(summary(3, 16, 0, 1), s)
    }

    @Test fun aWrongTypedFieldMakesOnlyThatItemUnreadable() = runTest {
        val file = """[{"id":"c","metadata":{"title":7},"data":[
            {"type":"message","id":"u","response_id":"r","created_at":1760700000,"role":"user","content":[{"type":"input_text","text":"kept"}]},
            {"type":"message","id":"a","response_id":"r","created_at":"1760700001","role":"assistant","content":[{"type":"output_text","text":"string time"}]},
            {"type":"message","id":"b","response_id":"r","created_at":1760700002.5,"role":"assistant","content":[{"type":"output_text","text":"float time"}]},
            {"type":"message","id":"d","response_id":"r","created_at":1760700003,"role":"robot","content":[{"type":"output_text","text":"odd role"}]},
            {"type":"message","id":"e","response_id":["r"],"created_at":1760700004,"role":"assistant","content":[]},
            {"type":"message","id":"f","response_id":"r","created_at":1760700005,"role":"assistant","content":{"text":"not a list"}},
            {"type":"message","id":"g","response_id":"r","created_at":1760700006,"role":"assistant","status":"pending","content":[{"type":"output_text","text":"pending"}]}
        ]}]""".toByteArray()
        val store = ListImportStore()
        assertEquals(summary(1, 1, 0, 6), import(file, store))
        assertEquals(listOf("kept"), contents(store, "near:c"))
        assertEquals("a non-string title is no title", "kept", store.threads["near:c"]!!.title)
    }

    @Test fun aCycleOrADanglingLinkStopsTheWalk() = runTest {
        // r2 → r3 → r2: the root r1 leads into a cycle, which is walked once.
        val file = """[{"id":"c","metadata":{"title":"Loop"},"data":[
            {"type":"message","id":"u1","response_id":"r1","previous_response_id":"gone","next_response_ids":["r2"],"created_at":1,"role":"user","content":[{"type":"input_text","text":"one"}]},
            {"type":"message","id":"u2","response_id":"r2","previous_response_id":"r3","created_at":2,"role":"user","content":[{"type":"input_text","text":"two"}]},
            {"type":"message","id":"u3","response_id":"r3","previous_response_id":"r2","next_response_ids":["r2","missing"],"created_at":3,"role":"user","content":[{"type":"input_text","text":"three"}]}
        ]}]""".toByteArray()
        val store = ListImportStore()
        assertEquals(1, import(file, store).chatsImported)
        assertTrue(contents(store, "near:c").isNotEmpty())
    }

    // 10: pm's readable sample, built on the structure of the maintainer's own export; committed in the corpus on the maintainer's OK.
    @Test fun theReadableSampleImportsAsItsTableSays() = runTest {
        val bytes = ImportTestSupport.resource("sample/near-export-sample.json")
        assertEquals("d1cd77c4c2c8c31a80a46b0755fb94712db0a329d78bea830bdbb53953b8a1f1", ImportTestSupport.sha256(bytes))
        val store = ListImportStore()
        assertEquals(summary(5, 28, 0, 0), import(bytes, store))
        assertEquals(listOf(7, 4, 7), store.messages.values.map { decode(it.sourcesJson).size }.filter { it > 0 })
        assertEquals(summary(0, 0, 5, 0), import(bytes, store))
    }

    @Test fun theWriterReportsProgressPerConversation() = runTest {
        val seen = ArrayList<Int>()
        ChatImport.run(ByteArrayInputStream(full), ChatImportWriter(ListImportStore(), ImportTestSupport.encode, onProgress = { seen += it.chatsImported }))
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 6), seen)
    }
}
