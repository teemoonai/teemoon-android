// ports: teemoonTests/LocalWarmUpPolicyTests.swift @ v1.0.4 (all 3)
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
}
