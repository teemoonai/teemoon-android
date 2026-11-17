package ai.teemoon.app

// trust-blocked-chain-and-gateway-row (a), the maintainer approved 2026-11-06: when sending is blocked, every everyday step after
// the failing one is grey — nothing green under a block. Same for the stale record. A failing step already dimmed what
// followed it (dimDownstream); a block whose cause isn't an everyday step (NVIDIA, TLS, the scope) left the chain green.

import ai.teemoon.confidential.TrustClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustChainHaltTest {
    private val claims = listOf(
        TrustClaim("msg", TrustClaim.Status.ORIGIN, "your message", "readable only here"),
        TrustClaim("sealed", TrustClaim.Status.OK, "sealed so only m can open it", ""),
        TrustClaim("identity", TrustClaim.Status.OK, "it's the real, published m", ""),
        TrustClaim("signed", TrustClaim.Status.OK, "every reply comes back signed by m", ""),
    )

    @Test fun aBlockGreysEveryStepAfterTheMessage() {
        val nodes = TrustChain.everyday(claims, halted = true)
        assertEquals("the message itself stays", false, nodes.first().dimmed)
        assertTrue("a step stayed green under a block", nodes.drop(1).all { it.dimmed })
    }

    @Test fun aFailingStepStillGreysOnlyWhatFollowsIt() {
        val failing = claims.toMutableList().also { it[2] = it[2].copy(status = TrustClaim.Status.FAIL) }
        val nodes = TrustChain.everyday(failing, halted = true)
        assertEquals(listOf(false, false, false, true), nodes.map { it.dimmed })
    }

    @Test fun nothingIsGreyedWhenSendingIsntHalted() {
        assertTrue(TrustChain.everyday(claims, halted = false).none { it.dimmed })
    }
}
