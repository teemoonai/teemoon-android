// ports: teemoonTests/OllamaShortlistTests.swift @ v1.0.1 (the "Ollama shortlist" suite; the pull-failure suite
//        is OllamaDownloadCenter's, and the link-validation / installed-matching suites are OllamaAdapter's)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static data, so these tests are about the two things static data gets wrong: numbers that don't come from anywhere, and labels that read badly. */
class OllamaModelShortlistTest {
    private val byTag = OllamaModelShortlist.recommended.associateBy { it.tag }

    /** Exactly the tags the source table has a Q4 line for, and no others. Adding a row means bringing a source for its number. */
    @Test fun onlyTagsWithASourcedQ4FigureAreListed() {
        assertEquals(listOf("gemma4:e4b", "gemma4:26b", "gemma4:31b"), OllamaModelShortlist.recommended.map { it.tag })
    }

    @Test fun vramFiguresMatchTheSourceTable() {
        assertEquals(6, byTag.getValue("gemma4:e4b").vramLowGB); assertEquals(8, byTag.getValue("gemma4:e4b").vramHighGB)
        assertEquals(16, byTag.getValue("gemma4:26b").vramLowGB); assertEquals(24, byTag.getValue("gemma4:26b").vramHighGB)
        assertEquals(24, byTag.getValue("gemma4:31b").vramLowGB); assertEquals(32, byTag.getValue("gemma4:31b").vramHighGB)
        // The MoE badge belongs to 26b alone.
        assertTrue(byTag.getValue("gemma4:26b").isMixtureOfExperts)
        assertFalse(byTag.getValue("gemma4:e4b").isMixtureOfExperts); assertFalse(byTag.getValue("gemma4:31b").isMixtureOfExperts)
    }

    /** The two figures are independent: dense rows want more VRAM than their download; the E-series is the other way round, on purpose. */
    @Test fun downloadSizesComeFromTheManifestsAndAreNotTheVRAMFigure() {
        for (m in OllamaModelShortlist.recommended) assertTrue(m.downloadGB > 0)
        for (tag in listOf("gemma4:26b", "gemma4:31b")) { val m = byTag.getValue(tag); assertTrue(tag, m.vramHighGB.toDouble() > m.downloadGB) }
        assertTrue(byTag.getValue("gemma4:e4b").downloadGB > byTag.getValue("gemma4:e4b").vramHighGB.toDouble())
        assertEquals(9.6, byTag.getValue("gemma4:e4b").downloadGB, 0.0)   // manifest: 9.61 GB
        assertEquals(18.0, byTag.getValue("gemma4:26b").downloadGB, 0.0)  // 17.99
        assertEquals(20.0, byTag.getValue("gemma4:31b").downloadGB, 0.0)  // 19.87
    }

    @Test fun rowsRunSmallestFirst() {
        val sizes = OllamaModelShortlist.recommended.map { it.downloadGB }; assertEquals(sizes.sorted(), sizes)
        val vram = OllamaModelShortlist.recommended.map { it.vramHighGB }; assertEquals(vram.sorted(), vram)
    }

    /** Every tag must be pullable: the `cloud` tags are hosted by Ollama rather than downloaded. */
    @Test fun noHostedTagsAreOffered() {
        for (m in OllamaModelShortlist.recommended) {
            assertFalse(m.tag.contains("cloud"))
            assertTrue("a bare name pulls whatever :latest is today", m.tag.contains(":"))
        }
    }

    @Test fun labelsReadAsRowsNotAsData() {
        assertEquals("9.6 gb", byTag.getValue("gemma4:e4b").downloadLabel); assertEquals("18 gb", byTag.getValue("gemma4:26b").downloadLabel)
        assertEquals("6–8 gb vram", byTag.getValue("gemma4:e4b").vramLabel)     // en dash
        assertEquals("24–32 gb vram", byTag.getValue("gemma4:31b").vramLabel)
        // One figure rather than two collapses, instead of printing "8–8".
        val single = OllamaShortlistModel(tag = "x:1b", downloadGB = 1.0, vramLowGB = 8, vramHighGB = 8, isMixtureOfExperts = false)
        assertEquals("8 gb vram", single.vramLabel); assertEquals("1 gb", single.downloadLabel)
    }

    /** Tapping a row must produce a ref the pull path accepts unchanged. */
    @Test fun tagsSurviveNormalizationUntouched() {
        for (m in OllamaModelShortlist.recommended) assertEquals(m.tag, OllamaAdapter.normalizePullRef(m.tag))
    }
}
