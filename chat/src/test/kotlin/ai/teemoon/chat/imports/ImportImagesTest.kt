// spec: IMAGES.md draft 2 §3 (teemoon-ios fa3c46c): an export's data: images become attachments of their message; a part
// that does not decode stays "[image not imported]" and is counted; at most 10 per message; nothing remote is fetched;
// a chat is never skipped for its images. The decoder here is a fake — the real one is the isolated service
// (ImageDecodeServiceTest); this holds what the import does with its answers.
package ai.teemoon.chat.imports

import ai.teemoon.chat.imports.ImportTestSupport.full
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ImportImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Decodes any data: payload whose text is in [good]; records every payload it was asked about. */
    private inner class FakeIngest(val good: (String) -> Boolean = { it.startsWith("data:image/") }) : ImageIngest {
        val asked = ArrayList<String>()
        override suspend fun ingest(payload: String, attachmentId: String): IngestedImage? {
            asked += payload.take(40)
            if (!good(payload)) return null
            val f = File(tmp.root, "pending-${asked.size}.jpg").apply { writeBytes(byteArrayOf(1)) }
            return IngestedImage("image/jpeg", 4, 3, byteArrayOf(9), f, "${asked.size}.jpg")
        }
    }

    private suspend fun import(bytes: ByteArray, store: ListImportStore, ingest: ImageIngest?, spill: File? = tmp.newFolder()) =
        ChatImport.run(ByteArrayInputStream(bytes), ChatImportWriter(store, ImportTestSupport.encode, images = ingest), spillDir = spill)

    @Test fun theReceiptsInlineImageBecomesAnAttachmentAndNoText() = runTest {
        val store = ListImportStore(); val ingest = FakeIngest()
        val s = import(full, store, ingest)
        assertEquals(1, s.imagesImported); assertEquals(0, s.imagesSkipped)
        assertEquals(ImportSummary(6, 25, 0, 4, false, 1, 0), s)
        val user = store.transcript("near:conv_receipt")[0]
        assertEquals("What's the total on this?\n\n[file not imported]\n\n[file not imported]", user.content)
        val a = store.attachments.values.single()
        assertEquals("near:conv_receipt:msg_u1#0", a.id); assertEquals(user.id, a.messageId); assertEquals(0, a.idx)
        // A second run decodes nothing: the chat is already here.
        ingest.asked.clear()
        assertEquals(ImportSummary(0, 0, 6, 1), import(full, store, ingest))
        assertTrue(ingest.asked.isEmpty())
    }

    @Test fun anImageThatDoesNotDecodeStaysAPlaceholderAndIsCounted() = runTest {
        val store = ListImportStore()
        val s = import(full, store, FakeIngest(good = { false }))
        assertEquals(0, s.imagesImported); assertEquals(1, s.imagesSkipped)
        assertTrue(store.transcript("near:conv_receipt")[0].content.contains("[image not imported]"))
    }

    @Test fun withoutAnImageStepTheImportIsWhatItWas() = runTest {
        assertEquals(ImportSummary(6, 25, 0, 4), import(full, ListImportStore(), ingest = null))
    }

    private fun chatWith(parts: String) = """[{"id":"c","metadata":{"title":"Pictures"},"data":[
        {"type":"message","id":"u","response_id":"r","created_at":1761100000,"role":"user","content":[$parts]}]}]""".toByteArray()
    private fun image(url: String) = """{"type":"input_image","image_url":"$url"}"""

    @Test fun aRemoteImageIsNeverDecodedOrFetched() = runTest {
        val store = ListImportStore(); val ingest = FakeIngest()
        val s = import(chatWith("""{"type":"input_text","text":"look"},${image("https://example.com/cat.png")},${image("http://example.com/dog.png")}"""), store, ingest)
        assertTrue("a remote URL reached the decoder: ${ingest.asked}", ingest.asked.isEmpty())
        assertEquals(2, s.imagesSkipped)
        assertEquals("look\n\n[image not imported]\n\n[image not imported]", store.transcript("near:c")[0].content)
    }

    /** Up to 10, as a sent message (the maintainer 2026-11-07; iOS 8952827c): the 11th is a placeholder, counted. */
    @Test fun atMostTenImagesPerMessageCarryTheirPayload() = runTest {
        val store = ListImportStore(); val ingest = FakeIngest()
        val s = import(chatWith((0 until 11).joinToString(",") { image("data:image/png;base64,AAAA$it") }), store, ingest)
        assertEquals(10, ingest.asked.size); assertEquals(10, s.imagesImported); assertEquals(1, s.imagesSkipped)
        assertEquals((0 until 10).map { "near:c:u#$it" }, store.attachments.keys.toList())
        assertEquals("an images-only message is kept", "[image not imported]", store.transcript("near:c")[0].content)
    }

    /**
     * IMAGES.md §3 (the maintainer, 2026-10-25): a chat is never skipped for its images. A 70 MB payload is set aside as it
     * streams — outside the 64 MiB conversation cap — and, over the per-image cap, is a placeholder; the text imports.
     */
    @Test fun aChatWithAHugeImageStillImportsItsText() = runTest {
        val store = ListImportStore(); val ingest = FakeIngest()
        val huge = "data:image/png;base64," + "A".repeat(70 * 1024 * 1024)
        val s = import(chatWith("""{"type":"input_text","text":"here it is"},${image(huge)}"""), store, ingest)
        assertEquals(1, s.chatsImported); assertEquals(1, s.imagesSkipped)
        assertTrue("the over-cap payload was not handed on", ingest.asked.isEmpty())
        assertEquals("here it is\n\n[image not imported]", store.transcript("near:c")[0].content)
    }

    @Test fun theSpillFolderIsEmptiedWhenTheRunEnds() = runTest {
        val spill = tmp.newFolder("spill")
        import(full, ListImportStore(), FakeIngest(), spill)
        assertFalse(spill.exists() && spill.listFiles().orEmpty().isNotEmpty())
    }
}
