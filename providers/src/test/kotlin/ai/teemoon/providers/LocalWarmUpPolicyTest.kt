// ports: teemoonTests/LocalWarmUpPolicyTests.swift @ v1.1.0 (all 3)
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWarmUpPolicyTest {
    private val model = LocalModelCatalog.all[0]
    private val ref = "/tmp/m/model.litertlm"

    @Test fun anInstalledOnDeviceModelIsWarmed() {
        assertEquals(ref, LocalWarmUpPolicy.target(Provider.local(model)) { id -> if (id == model.id) ref else null })
    }

    @Test fun aModelNotOnDiskIsNot() {
        assertNull(LocalWarmUpPolicy.target<String>(Provider.local(model)) { null })
    }

    @Test fun cloudAndNothingAreNot() {
        assertNull(LocalWarmUpPolicy.target(Provider.nearAI) { ref })
        assertNull(LocalWarmUpPolicy.target(null) { ref })
    }

    /**
     * Android-only (the maintainer 2026-11-12, "yes"): a phone's warm-up loads the image reader too when the model runs
     * on an NPU bundle that reads images — there it is cheap (the Pixel 10 Pro: ready in 1.1 s, 189 MB at the load's peak)
     * and the first picture then needs no rebuild. On the GPU or CPU it stays text-only: the reader costs ~650 MB there
     * (S26 Ultra, Galaxy A25), paid by every text chat.
     */
    @Test fun theWarmUpLoadsTheImageReaderOnlyOnAnNpuBundleThatReadsImages() {
        fun ref(npu: Boolean, sees: Boolean) = LocalModelStorage.LocalRef("m", "/d", 1, "/d/m.litertlm", requiresNpu = npu, npuSeesImages = sees)
        assertEquals(true, LocalWarmUpPolicy.withImageReader(ref(npu = true, sees = true)))
        assertEquals(false, LocalWarmUpPolicy.withImageReader(ref(npu = true, sees = false)))
        assertEquals("the general file on the GPU or CPU", false, LocalWarmUpPolicy.withImageReader(ref(npu = false, sees = false)))
    }
}
