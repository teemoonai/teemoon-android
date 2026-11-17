// IMAGES.md §4b (the maintainer 2026-11-12, run time): an image turn's load asks the memory gate for the model's need
// (what a vision load takes from availMem) + the load headroom. Short of it right now, the turn is refused with the
// "now" line and its numbers. The turn is bracketed by the in-flight mark (PhoneImageLedger), so a kill mid-turn is
// found at the next launch.
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageMemoryCheckTest {
    companion object { val specs = ArrayList<LocalConversationSpec>() }
    private class Engine(override val sees: Boolean) : LocalEngineHandle {
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) { specs += spec; onPiece("ok") }
        override fun benchmark(): LocalBenchmark? = null
        override fun close() {}
    }
    private class Factory : LocalEngineFactory {
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = Engine(false)
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean, vision: Boolean): LocalEngineHandle = Engine(vision)
        override val backendLabel = "test"
    }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)
    private val asked = ArrayList<Int>()
    private val marks = ArrayList<String>()
    /** A phone with [freeMB] available: the app's gate (LoadMemoryGate) throws InsufficientMemory below the need. */
    private fun transport(freeMB: Int) = LiteRTTransport("/models/gemma-4-E2B-it.litertlm", 2468, headroomMB = 512, visionLoadMB = 2500, modelName = "gemma 4 e2b",
        onImageTurn = { begins -> marks += if (begins) "begin" else "end" },
        cache = LocalEngineCache(Factory(), residency = LocalEngineResidency(), gate = LocalGenerationGate(), fileExists = { true },
            memoryCheck = { need -> asked += need; if (freeMB < need) throw LocalInferenceError.InsufficientMemory(need, freeMB) }),
        gate = LocalGenerationGate(), backendLabel = "test")
    private suspend fun image(t: LiteRTTransport) = t.runTurn(listOf(mapOf("role" to "user", "content" to wireContent("what is this?", listOf(WireImage("image/jpeg", jpeg))))), includeTools = false, tools = emptyList()) {}
    private suspend fun text(t: LiteRTTransport) = t.runTurn(listOf(mapOf("role" to "user", "content" to "hi")), includeTools = false, tools = emptyList()) {}

    @Test fun anImageTurnAsksForTheModelsNeedPlusHeadroom() = runTest {
        val t = transport(freeMB = 8000)
        text(t); image(t)
        assertEquals("text: the file + headroom; image: the need + headroom", listOf(2468 + 512, 2500 + 512), asked)
        assertEquals("the image turn is bracketed; a text turn is not", listOf("begin", "end"), marks)
    }

    @Test fun shortRightNowTheImageTurnIsRefusedWithTheNowLineAndItsNumbers() = runTest {
        val t = transport(freeMB = 2700)
        try { image(t); fail("the image turn ran") } catch (e: LocalInferenceError.ImagesShortOfMemoryNow) {
            assertEquals("gemma 4 e2b needs about 2.9 GB free for images; this phone has 2.6 GB free right now. close some apps and try again.", e.message)
            assertEquals(e.message, NetworkFailure.cardMessage(e, "{provider}", false, false))
        }
        assertTrue("a refused turn ends its mark", marks.lastOrNull() == "end")
    }

    @Test fun aTextTurnShortOfMemoryKeepsItsOwnMessage() = runTest {
        val t = transport(freeMB = 1000)
        try { text(t); fail("the text turn ran") } catch (e: LocalInferenceError) {
            assertTrue(e is LocalInferenceError.InsufficientMemory)
        }
    }

    /** The "now" line's numbers (phoneImages.gb): GB = MB / 1024, one decimal, half up — the kit's cases. */
    @Test fun gigabytesAreTheKits() {
        val kit = kotlinx.serialization.json.Json.parseToJsonElement(ImageMemoryCheckTest::class.java.getResourceAsStream("/wherefilter.json")!!.readBytes().decodeToString())
        val g = (kit as kotlinx.serialization.json.JsonObject)["phoneImages"]!!.let { it as kotlinx.serialization.json.JsonObject }["gb"] as kotlinx.serialization.json.JsonObject
        val cases = (g["cases"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val mb = (c["mb"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            assertEquals("$mb MB", (c["gb"] as kotlinx.serialization.json.JsonPrimitive).content, gb(mb))
        }
    }

    /**
     * IMAGES.md §4b @ 00f72739 (the maintainer's Pixel 2026-11-12: a photo, a photo, then a question about both — E2B had
     * neither): a text question after image turns still asks for the vision engine, the memory check and the mark, and
     * the earlier turns reach the engine WITH their images — they were dropped whole (a content array is not a String).
     */
    @Test fun aTextQuestionAfterTwoPhotosSeedsBothWithTheirTurns() = runTest {
        specs.clear()
        val t = transport(freeMB = 8000)
        val photo = { tag: Byte -> WireImage("image/jpeg", jpeg + tag) }
        t.runTurn(listOf(
            mapOf("role" to "user", "content" to wireContent("what is this?", listOf(photo(1)))), mapOf("role" to "assistant", "content" to "a red circle"),
            mapOf("role" to "user", "content" to wireContent("and this?", listOf(photo(2)))), mapOf("role" to "assistant", "content" to "a blue square"),
            mapOf("role" to "user", "content" to "what do the two have in common?")), includeTools = false, tools = emptyList()) {}
        val spec = specs.single()
        assertEquals(listOf("what is this?", "a red circle", "and this?", "a blue square"), spec.history.map { it.second })
        assertEquals(listOf(1, 0, 1, 0), spec.historyImages.map { it.size })
        assertEquals(2, spec.requestImages)
        assertEquals("the vision need, not the file's", listOf(2500 + 512), asked)
        assertEquals(listOf("begin", "end"), marks)
        assertTrue("never kept for reuse", !ConversationReuse.reusable(spec.requestImages))
    }
}
