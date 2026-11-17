package ai.teemoon.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone models read images in 1.1.0, E2B only (the maintainer 2026-11-12; 2026-10-28, E4B's images stay off). */
class LocalModelReadsImagesTest {
    private fun id(name: String) = LocalModelCatalog.all.first { it.displayName == name }.id

    /**
     * The maintainer 2026-11-12 (run time, "i dont like statically fixing requirements"): E4B is no longer off — both read
     * images where this phone's memory fits their need (PhoneImageMemory); each carries the figure the check asks for.
     */
    @Test fun bothPhoneModelsReadImagesAndCarryTheirNeed() {
        assertTrue(LocalModelCatalog.readsImages(id("Gemma 4 E2B")))
        assertTrue(LocalModelCatalog.readsImages(id("Gemma 4 E4B")))
        assertTrue(LocalModelCatalog.all.all { it.imageNeedMB.isNotEmpty() })
        assertFalse("a model the catalog doesn't list", LocalModelCatalog.readsImages("someone/else"))
        assertFalse(LocalModelCatalog.readsImages(null))
    }

    /**
     * IMAGES.md §4e: "no model on this phone reads images yet." shows while the phone's model still needs its second
     * download (a Tensor phone with the NPU bundle alone); the where sheet is told the phone can't read images yet.
     */
    @Test fun aPhoneWaitingOnTheSecondDownloadDoesNotReadImagesYet() {
        assertTrue(LocalModelCatalog.phoneReadsImages(waitingOnSecondDownload = false))
        assertFalse(LocalModelCatalog.phoneReadsImages(waitingOnSecondDownload = true))
    }

    /**
     * The image turn's memory check compares availMem, and a load costs availMem about its file size, not its PSS. Each
     * path's figure covers the worst drop the device pass measured on it (docs/bench/2026-11-12-images): E2B on the NPU
     * bundle 2.4 GB (Pixel 10), on the GPU 3.7 (Pixel 10), on the CPU 2.1 (Pixel 10); and the CPU path is still the
     * cheapest, so a phone that only runs the CPU (the Galaxy A25: 3044 MB free at its best) is not refused by the GPU's.
     */
    @Test fun e2bsImageNeedCoversEachPathsWorstMeasuredDrop() {
        val e2b = LocalModelCatalog.all.first { it.displayName == "Gemma 4 E2B" }
        assertTrue(e2b.imageNeedMB(ImagePath.NPU)!! >= 2380)
        assertTrue(e2b.imageNeedMB(ImagePath.GPU)!! >= 3734 - 50)
        assertTrue(e2b.imageNeedMB(ImagePath.CPU)!! >= 2117 - 50)
        assertTrue("the A25's best free memory holds the CPU path's need", e2b.imageNeedMB(ImagePath.CPU)!! <= 3044)
    }
}
