package ai.teemoon.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The ladder and the memo, off-device. What cannot be tested here is the probe itself — it needs the runtime and a
 * 2.4 GB bundle — so the contract pinned here is the ORDER a device is offered and the memory of what worked.
 */
class BackendLadderTest {

    /** Measured on the Pixel 10 Pro: the GPU shows the first word in 3.7 s against the CPU's 17.4 s (see BackendLadder). */
    @Test fun aPixelLeadsWithTheGpuAndEndsAtTheCpu() {
        assertEquals(listOf(BackendKind.GPU, BackendKind.CPU), BackendLadder.order(socModel = "Tensor G5", hardware = "blazer"))
    }

    /**
     * The general ladder NEVER offers the NPU, whatever the chip. A general bundle has no NPU graph, so that rung
     * does not refuse cleanly — it loads, falls through internally, and decodes slower than the real CPU path while
     * claiming to be silicon. The NPU is reached only by installing a bundle compiled for the chip, which routes
     * through `requireNpu` instead of through here.
     */
    @Test fun theGeneralLadderNeverOffersTheNpu() {
        for (soc in listOf("Tensor G5", "Tensor G6", "SM8750", "")) for (hw in listOf("blazer", "pineapple", "")) {
            val order = BackendLadder.order(soc, hw)
            assertFalse("$soc/$hw offers a rung the general bundle cannot really use", BackendKind.NPU in order)
            assertFalse("$soc/$hw offers a backend both runtimes reject", BackendKind.GOOGLE_TENSOR in order)
        }
    }

    /** Build.SOC_MODEL is API 31+ and some devices leave it empty: the Pixel board name is the second source. */
    /** The 2x2 measured on 2026-09-16: only Adreno's GPU rung gains from a drafter; every other cell lost speed. */
    @Test fun speculativeDecodingIsForQualcommGpusAndTheSnapdragon8EliteNpu() {
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "SM8850", hardware = "qcom"))
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "", hardware = "qcom"))
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "SM8750", hardware = ""))
        assertFalse("the CPU lost 17 → 14 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.CPU, socModel = "SM8850", hardware = "qcom"))
        assertFalse("PowerVR lost 9 → 7 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "Tensor G5", hardware = "blazer"))
        assertFalse("the Tensor NPU lost 16 → 14 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.NPU, socModel = "Tensor G5", hardware = "blazer"))
        assertTrue("the Snapdragon 8 Elite NPU gained 33 → 37", BackendLadder.wantsSpeculativeDecoding(BackendKind.NPU, socModel = "SM8750", hardware = "qcom"))
        assertFalse("no other Snapdragon has an NPU bundle", BackendLadder.wantsSpeculativeDecoding(BackendKind.NPU, socModel = "SM8850", hardware = "qcom"))
        assertFalse(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "", hardware = "ranchu"))
        assertFalse("a MediaTek board is unmeasured", BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "MT6989", hardware = "mt6989"))
    }

    @Test fun aPixelIsRecognisedByItsBoardWhenTheSocIsSilent() {
        assertTrue(BackendLadder.isGoogleTensor(socModel = "", hardware = "gs101"))
        assertTrue(BackendLadder.isGoogleTensor(socModel = "Tensor G5", hardware = ""))
        assertFalse(BackendLadder.isGoogleTensor(socModel = "SM8650", hardware = "pineapple"))
    }

    /** The ladder never offers the same rung twice and always keeps a floor. */
    @Test fun theLadderIsAlwaysWellFormed() {
        for (soc in listOf("Tensor G5", "SM8650", "")) for (hw in listOf("blazer", "pineapple", "ranchu", "")) {
            val order = BackendLadder.order(soc, hw)
            assertEquals("$soc/$hw has a rung twice", order.size, order.toSet().size)
            assertTrue("$soc/$hw has no floor", BackendKind.CPU in order)
        }
    }

    /** The emulator's GPU compiles and then dies at decode; the honest probe would catch it only after a full load. */
    @Test fun theEmulatorGoesStraightToTheCpu() {
        assertEquals(listOf(BackendKind.CPU), BackendLadder.order("", "ranchu"))
        assertEquals(listOf(BackendKind.CPU), BackendLadder.order("", "goldfish"))
    }

    /** Every ladder ends at the CPU: the floor it can never fall through. */
    @Test fun everyLadderEndsAtTheCpu() {
        for (soc in listOf("Tensor G5", "SM8650", "")) for (hw in listOf("blazer", "pineapple", "ranchu", "")) {
            val order = BackendLadder.order(soc, hw)
            assertEquals("$soc/$hw", BackendKind.CPU, order.last())
        }
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun theMemoPutsAProvenBackendFirstAndKeepsTheRestBehindIt() {
        val memo = BackendMemo(File(tmp.root, "v.tsv"))
        val ladder = listOf(BackendKind.GOOGLE_TENSOR, BackendKind.GPU, BackendKind.CPU)
        val key = BackendMemo.key("/models/gemma.litertlm", ladder)
        assertEquals(ladder, memo.reorder(ladder, key))          // nothing remembered: the ladder as built
        memo.remember(key, BackendKind.CPU)
        // the proven rung leads, and the others still follow — a memo can be stale, so it reorders and never truncates
        assertEquals(listOf(BackendKind.CPU, BackendKind.GOOGLE_TENSOR, BackendKind.GPU), memo.reorder(ladder, key))
    }

    /** A verdict is about one model on one ladder: a longer ladder offers a rung this device has never tried. */
    @Test fun aNewLadderOrADifferentModelReprobes() {
        val memo = BackendMemo(File(tmp.root, "v.tsv"))
        val short = listOf(BackendKind.GPU, BackendKind.CPU)
        val long = listOf(BackendKind.GOOGLE_TENSOR, BackendKind.GPU, BackendKind.CPU)
        memo.remember(BackendMemo.key("/m/gemma.litertlm", short), BackendKind.CPU)
        assertNull(memo.remembered(BackendMemo.key("/m/gemma.litertlm", long)))
        assertNull(memo.remembered(BackendMemo.key("/m/other.litertlm", short)))
        assertEquals(BackendKind.CPU, memo.remembered(BackendMemo.key("/m/gemma.litertlm", short)))
    }

    /** It lives in cacheDir, which the system may empty: losing it must cost a re-probe, never an error. */
    @Test fun aMissingOrCorruptMemoIsSimplyNothingRemembered() {
        val file = File(tmp.root, "gone/v.tsv")
        val memo = BackendMemo(file)
        assertNull(memo.remembered("k"))
        memo.remember("k", BackendKind.GPU)                       // creates the directory it needs
        assertEquals(BackendKind.GPU, memo.remembered("k"))
        file.writeText("this is not a verdict\nk\tNOT_A_BACKEND")
        assertNull(memo.remembered("k"))
    }

    /** A device that keeps re-probing must not grow the file forever. */
    @Test fun theMemoIsBounded() {
        val file = File(tmp.root, "v.tsv"); val memo = BackendMemo(file)
        repeat(40) { memo.remember("key-$it", BackendKind.CPU) }
        assertTrue("the memo grew to ${file.readLines().size} lines", file.readLines().size <= 16)
        assertEquals(BackendKind.CPU, memo.remembered("key-39"))  // the newest verdict is the one kept
    }
}
