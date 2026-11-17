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

    /** A Mali Pixel (8a: Tensor G3) leads with the GPU and ends at the CPU. */
    @Test fun aPixelLeadsWithTheGpuAndEndsAtTheCpu() {
        assertEquals(listOf(BackendKind.GPU, BackendKind.CPU), BackendLadder.order(socModel = "Tensor G3", hardware = "akita"))
    }

    /**
     * The Pixel 10's PowerVR (Tensor G5) runs the general file on the CPU (the maintainer 2026-10-26): cached GPU kernels decode
     * at half speed there and GPU loads take 13.7 s cached / 56.5 s cold, against the CPU's 5.3 / 13.2 s and ~13.5 tok/s.
     */
    @Test fun thePixel10sPowerVrRunsTheGeneralFileOnTheCpu() {
        assertEquals(listOf(BackendKind.CPU), BackendLadder.order(socModel = "Tensor G5", hardware = "blazer"))
        assertEquals(listOf(BackendKind.CPU), BackendLadder.order(socModel = " tensor g5 ", hardware = "frankel"))
        assertEquals(false, BackendLadder.isPowerVr("Tensor G4"))
    }

    /**
     * The CPU rung runs a 4096 window whatever is asked (the maintainer 2026-10-26, "processor only": long reply 7.8 s against
     * 10.4 s on the Pixel 10 Pro's CPU); the GPU keeps what it was asked until measured, and nothing is ever raised.
     */
    @Test fun onlyTheCpuRungTakesTheSmallWindow() {
        assertEquals(4096, BackendLadder.contextTokens(BackendKind.CPU, 8192))
        assertEquals(2048, BackendLadder.contextTokens(BackendKind.CPU, 2048))
        assertEquals(8192, BackendLadder.contextTokens(BackendKind.GPU, 8192))
        assertEquals(4096, BackendLadder.contextTokens(BackendKind.NPU, 4096))
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
    /** The 2x2 measured on 2026-09-16: only Adreno's GPU rung gains from a drafter; every other cell lost speed or has none. */
    @Test fun speculativeDecodingIsForQualcommGpusOnly() {
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "SM8850", hardware = "qcom"))
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "", hardware = "qcom"))
        assertTrue(BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "SM8750", hardware = ""))
        assertFalse("the CPU lost 17 → 14 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.CPU, socModel = "SM8850", hardware = "qcom"))
        assertFalse("PowerVR lost 9 → 7 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.GPU, socModel = "Tensor G5", hardware = "blazer"))
        assertFalse("the Tensor NPU lost 16 → 14 with it", BackendLadder.wantsSpeculativeDecoding(BackendKind.NPU, socModel = "Tensor G5", hardware = "blazer"))
        assertFalse("the sm8750 NPU bundle carries no drafter (no tf_lite_mtp_drafter section): 37.7 tok/s off and on, medians of 3, 2026-10-15",
            BackendLadder.wantsSpeculativeDecoding(BackendKind.NPU, socModel = "SM8750", hardware = "qcom"))
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

    /**
     * An image turn tries only the rungs that run a vision encoder; on an NPU bundle that carries one (the Snapdragon
     * 8 Elite's, the maintainer 2026-11-12) that is the NPU itself, with the GPU encoder beside it.
     */
    @Test fun anImageTurnKeepsTheNpuOnlyOnABundleThatSeesImages() {
        assertEquals(listOf(BackendKind.NPU), BackendLadder.visionLadder(listOf(BackendKind.NPU), requireNpu = true))
        assertEquals(listOf(BackendKind.GPU, BackendKind.CPU), BackendLadder.visionLadder(listOf(BackendKind.GPU, BackendKind.CPU), requireNpu = false))
        assertEquals(listOf(BackendKind.CPU), BackendLadder.visionLadder(listOf(BackendKind.CPU), requireNpu = false))
    }

    /**
     * The maintainer 2026-11-12 ("yes": image turns on the Pixel 10 move to the GPU if the measurement says so). Pixel 10 Pro,
     * E2B, three cached GPU runs against the CPU, every answer right: first token 0.72 / 1.50 / 4.15 s for 1 / 4 / 10
     * pictures against 2.09 / 6.31 / 14.37 s, turns 3.9 / 11.7 / 26.8 s against 5.4 / 15.1 / 36.5 s, no slowdown from run
     * to run (the 10-26 cached-kernel halving was text decode). Text on the general file stays on the CPU there.
     */
    @Test fun thePixel10sImageTurnsLeadWithTheGpu() {
        assertEquals(listOf(BackendKind.GPU, BackendKind.CPU), BackendLadder.visionOrder("Tensor G5", "blazer"))
        assertEquals("text on the general file stays on the CPU", listOf(BackendKind.CPU), BackendLadder.order("Tensor G5", "blazer"))
        assertEquals("elsewhere, the text ladder's vision rungs", listOf(BackendKind.GPU, BackendKind.CPU), BackendLadder.visionOrder("Tensor G4", "caiman"))
        assertEquals("the emulator stays on the CPU", listOf(BackendKind.CPU), BackendLadder.visionOrder("", "ranchu"))
    }

    /**
     * The maintainer 2026-11-12: "we should have in code a simple way of deciding whether to use gpu or cpu … use the data
     * you gathered to come up with a rule". docs/bench/2026-11-12-images: flagship GPUs named all 10 pictures right
     * (Snapdragon 8 Elite SM8750 and 8 Elite Gen 5 SM8850; Tensor G2 GS201, G4, G5), the mid-range ones did not, with no
     * error (Galaxy A25, Exynos 1280: 2 of 10; Moto g54, Dimensity 7020: 0 of 10), and the CPU named every one right on
     * every phone. So an image turn uses the GPU only on a Google Tensor or a Snapdragon 8-series chip; anywhere else it
     * runs on the CPU. Text keeps its own ladder.
     */
    @Test fun imageTurnsUseTheGpuOnlyOnTensorAndSnapdragon8() {
        val gpu = listOf(BackendKind.GPU, BackendKind.CPU); val cpu = listOf(BackendKind.CPU)
        for (soc in listOf("SM8750", "SM8850", "sm8650", "Tensor G4", "Tensor G5", "Tensor G6", "GS201", "GS101", "Zuma", "zumapro"))
            assertEquals(soc, gpu, BackendLadder.visionOrder(soc, "qcom"))
        for (soc in listOf("s5e8825", "MT6855V/AZA", "MT6789", "SM7435", "SM6375", "s5e9945", ""))
            assertEquals("'$soc': the CPU", cpu, BackendLadder.visionOrder(soc, "x"))
        assertEquals("text keeps its ladder on a mid-range GPU", gpu, BackendLadder.order("s5e8825", "s5e8825"))
        assertEquals("the emulator stays on the CPU", cpu, BackendLadder.visionOrder("SM8750", "ranchu"))
    }
}
