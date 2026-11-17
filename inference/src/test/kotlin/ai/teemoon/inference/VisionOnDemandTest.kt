// ports: teemoon/Inference/LiteRTTransport.swift @ afc4327 (residentServes; EngineCache.engine(vision:) and build's eviction)
//
// IMAGES.md §4b (pm, 2026-10-26: align Android to it): the vision executor is created only for a turn with an image, so
// text-only turns keep today's memory. A vision engine then serves text turns too; only an image turn on a text-only
// engine rebuilds it.
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionOnDemandTest {
    private class Engine(override val sees: Boolean) : LocalEngineHandle {
        var closed = false; val images = ArrayList<ByteArray?>(); val turns = ArrayList<List<ByteArray>>()
        override suspend fun stream(spec: LocalConversationSpec, onPiece: suspend (String) -> Unit) { images += spec.userImages.firstOrNull(); turns += spec.userImages; onPiece("ok") }
        override fun benchmark(): LocalBenchmark? = null
        override fun close() { closed = true }
    }
    private class Factory : LocalEngineFactory {
        val opens = ArrayList<Boolean>(); val engines = ArrayList<Engine>(); val paths = ArrayList<String>(); val npu = ArrayList<Boolean>(); val contexts = ArrayList<Int>()
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean): LocalEngineHandle = open(modelPath, contextTokens, requireNpu, vision = false)
        override suspend fun open(modelPath: String, contextTokens: Int, requireNpu: Boolean, vision: Boolean): LocalEngineHandle {
            opens += vision; paths += modelPath; npu += requireNpu; contexts += contextTokens; return Engine(vision).also { engines += it }
        }
        override val backendLabel = "test"
    }
    private fun transport(cache: LocalEngineCache) =
        LiteRTTransport("/models/gemma-4-E2B-it.litertlm", 2468, cache = cache, gate = LocalGenerationGate(), backendLabel = "test")
    private fun cache(f: Factory) = LocalEngineCache(f, residency = LocalEngineResidency(), gate = LocalGenerationGate(), fileExists = { true })
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)
    private suspend fun text(t: LiteRTTransport) = t.runTurn(listOf(mapOf("role" to "user", "content" to "hi")), includeTools = false, tools = emptyList()) {}
    private suspend fun image(t: LiteRTTransport) = t.runTurn(listOf(mapOf("role" to "user", "content" to wireContent("what is this?", listOf(WireImage("image/jpeg", jpeg))))), includeTools = false, tools = emptyList()) {}

    @Test fun theRule() {
        assertTrue(residentServes(residentVision = false, wantsVision = false))
        assertFalse("an image turn on a text-only engine rebuilds", residentServes(residentVision = false, wantsVision = true))
        assertTrue("a vision engine serves text too", residentServes(residentVision = true, wantsVision = false))
        assertTrue(residentServes(residentVision = true, wantsVision = true))
    }

    @Test fun textTurnsNeverBuildTheVisionExecutor() = runTest {
        val f = Factory(); val t = transport(cache(f))
        text(t); text(t)
        assertEquals(listOf(false), f.opens)
    }

    @Test fun theFirstImageRebuildsWithVisionAndTextThenStaysOnIt() = runTest {
        val f = Factory(); val t = transport(cache(f))
        text(t); image(t); text(t); image(t)
        assertEquals("one text-only build, one vision build, then reuse", listOf(false, true), f.opens)
        assertTrue("the text-only engine is closed before the vision one loads (one resident at a time)", f.engines[0].closed)
        assertFalse(f.engines[1].closed)
        assertTrue("the image reached the vision engine", f.engines[1].images[0]!!.contentEquals(jpeg))
    }

    @Test fun aWarmUpStaysTextOnly() = runTest {
        val f = Factory(); val c = cache(f)
        c.engine("/models/gemma-4-E2B-it.litertlm", 8192, 2468, 512)
        assertEquals(listOf(false), f.opens)
    }

    /** the maintainer 2026-10-26 (a): on a Tensor phone text runs the NPU bundle and an image turn the general file beside it. */
    @Test fun aTensorPhonesImageTurnRunsTheGeneralFileAndTextStaysOnTheNpu() = runTest {
        val f = Factory()
        val t = LiteRTTransport("/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm", 2970, contextTokens = 4096, cache = cache(f), gate = LocalGenerationGate(),
            backendLabel = "test", requireNpu = true, imageModelPath = "/models/gemma-4-E2B-it.litertlm", imageSizeMB = 2468)
        text(t); image(t); text(t)
        assertEquals(listOf("/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm", "/models/gemma-4-E2B-it.litertlm", "/models/gemma-4-E2B-it_Google_Tensor_G5.litertlm"), f.paths)
        assertEquals(listOf(true, false, true), f.npu)
        assertEquals("the image turn asks for vision on the general file", listOf(false, true, false), f.opens)
        assertEquals("the general file takes the transport's window, not the NPU bundle's compiled 4096", listOf(4096, LiteRTTransport.DEFAULT_CONTEXT_TOKENS, 4096), f.contexts)
        assertTrue("one engine resident: the NPU engine closed for the image turn", f.engines[0].closed)
        assertTrue(f.engines[1].images[0]!!.contentEquals(jpeg))
    }

    /**
     * IMAGES.md §4b: up to 10 images in one phone message (the maintainer 2026-11-12, 10-or-off) — every one reaches the
     * model, in the order attached. Only the first did: the rest were dropped without a word.
     */
    @Test fun everyAttachedImageReachesTheModelInOrder() = runTest {
        val f = Factory(); val t = transport(cache(f))
        val three = (1..3).map { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), it.toByte()) }
        t.runTurn(listOf(mapOf("role" to "user", "content" to wireContent("what are these?", three.map { WireImage("image/jpeg", it) }))), includeTools = false, tools = emptyList()) {}
        val sent = f.engines.last().turns.last()
        assertEquals(3, sent.size)
        assertTrue(sent.zip(three).all { (a, b) -> a.contentEquals(b) })
    }
}
