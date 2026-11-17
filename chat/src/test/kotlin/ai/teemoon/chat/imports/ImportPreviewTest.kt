// ports: teemoonTests/ImportPreviewTests.swift @ cdea210c (the engine half; the page's state walk is in the app's
// ChatImportRunnerTest)
//
// The import page's confirm step (1.1.0 import redesign, b and c): a dry run counts exactly what the import will write
// and writes nothing, and its element count is the progress bar's total.
package ai.teemoon.chat.imports

import ai.teemoon.chat.imports.ImportTestSupport.full
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportPreviewTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Decodes every data: image into a pending file, as the isolated decoder would. */
    private inner class Ingest : ImageIngest {
        var n = 0
        override suspend fun ingest(payload: String, attachmentId: String): IngestedImage? {
            if (!payload.startsWith("data:image/")) return null
            val f = File(tmp.root, "pending-${++n}.jpg").apply { writeBytes(byteArrayOf(1)) }
            return IngestedImage("image/jpeg", 4, 3, byteArrayOf(9), f, "$n.jpg")
        }
    }

    private fun writer(store: ListImportStore, dryRun: Boolean, ingest: ImageIngest? = null, onElements: (Int) -> Unit = {}) =
        ChatImportWriter(store, ImportTestSupport.encode, images = ingest, dryRun = dryRun, onElements = onElements)

    @Test fun aDryRunCountsWhatTheImportWritesAndWritesNothing() = runTest {
        val store = ListImportStore(); val ingest = Ingest()
        val preview = ChatImport.run(ByteArrayInputStream(full), writer(store, dryRun = true, ingest), spillDir = tmp.newFolder())
        assertTrue("the dry run wrote chats", store.threads.isEmpty() && store.messages.isEmpty() && store.attachments.isEmpty())
        assertTrue(preview.chatsImported > 0)
        assertEquals("the dry run left decoded files behind", emptyList<String>(), tmp.root.listFiles()!!.filter { it.name.startsWith("pending-") }.map { it.name })
        val real = ChatImport.run(ByteArrayInputStream(full), writer(store, dryRun = false, ingest), spillDir = tmp.newFolder())
        assertEquals("the confirm step promised $preview, the import wrote $real", preview, real)
    }

    @Test fun aDryRunAfterAnImportSaysEverythingIsAlreadyHere() = runTest {
        val store = ListImportStore()
        val first = ChatImport.run(ByteArrayInputStream(full), writer(store, dryRun = false))
        val again = ChatImport.run(ByteArrayInputStream(full), writer(store, dryRun = true))
        assertEquals(0, again.chatsImported)
        assertEquals(first.chatsImported, again.chatsAlreadyHere)
    }

    /** A chat twice in one file counts as the import treats it: once, then "already here". */
    @Test fun aChatTwiceInOneFileCountsAsTheImportTreatsIt() = runTest {
        val doubled = ImportTestSupport.parse(full).let { kotlinx.serialization.json.JsonArray(it + it) }.toString().toByteArray()
        val preview = ChatImport.run(ByteArrayInputStream(doubled), writer(ListImportStore(), dryRun = true))
        val real = ChatImport.run(ByteArrayInputStream(doubled), writer(ListImportStore(), dryRun = false))
        assertEquals(real, preview)
        assertTrue(preview.chatsAlreadyHere > 0)
    }

    @Test fun theElementCountIsTheLastProgressValue() = runTest {
        var last = 0
        val w = writer(ListImportStore(), dryRun = true) { last = it }
        ChatImport.run(ByteArrayInputStream(full), w)
        assertTrue(last > 0)
        assertEquals(last, w.elementsRead)
        assertEquals("one element per top-level item of the file", ImportTestSupport.parse(full).size, w.elementsRead)
    }

    @Test fun aSingleChatFileIsOneElement() = runTest {
        val w = writer(ListImportStore(), dryRun = true)
        ChatImport.run(ByteArrayInputStream(ImportTestSupport.single), w)
        assertEquals(1, w.elementsRead)
    }
}
