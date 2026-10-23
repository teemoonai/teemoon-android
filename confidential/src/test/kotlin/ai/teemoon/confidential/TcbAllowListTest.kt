// ports: teemoonTests/TcbAllowListTests.swift @ 9722921
//
// near.ai inference-sdk (core/dstack_attestation.py) accepts UpToDate and
// OutOfDate only; the maintainer adopted that 2026-10-19. Before, only Revoked failed.
// Live 2026-10-19: glm-5.3-flash gateway OutOfDate, model + GPU UpToDate;
// Qwen3.6-35B OutOfDate on all three — all pass.
package ai.teemoon.confidential

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcbAllowListTest {

    private fun verdict(raw: String): DcapVerification =
        DcapVerification.Verified(tcbStatus = DcapTcbStatus.fromRaw(raw), mrConfigIdHex = "", reportDataHex = "")

    @Test fun accepted() {
        for (raw in listOf("UpToDate", "OutOfDate")) {
            assertFalse(raw, verdict(raw).isHardFailure)
            assertNull(raw, RecordDcapVerification(gateway = verdict("OutOfDate"), model = verdict(raw), gpu = verdict("UpToDate")).failureReason)
        }
    }

    @Test fun rejected() {
        for (raw in listOf("Revoked", "ConfigurationNeeded", "SWHardeningNeeded", "ConfigurationAndSWHardeningNeeded",
                           "OutOfDateConfigurationNeeded", "Unknown", "")) {
            assertTrue(raw, verdict(raw).isHardFailure)
            val record = RecordDcapVerification(gateway = verdict("UpToDate"), model = verdict(raw), gpu = null)
            assertTrue(raw, record.hasHardFailure)
            assertTrue(raw, record.failureReason?.startsWith("model quote") == true)
        }
    }

    @Test fun liveStatusesPass() {
        val glm = RecordDcapVerification(gateway = verdict("OutOfDate"), model = verdict("UpToDate"), gpu = verdict("UpToDate"))
        val qwen = RecordDcapVerification(gateway = verdict("OutOfDate"), model = verdict("OutOfDate"), gpu = verdict("OutOfDate"))
        assertTrue(!glm.hasHardFailure && !qwen.hasHardFailure)
    }
}
