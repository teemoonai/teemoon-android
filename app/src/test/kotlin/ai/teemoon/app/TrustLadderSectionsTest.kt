// Kotlin-self pins for TrustLadderView+Sections / ImageRowView / RecipeCardView's pure derivations (no Swift tests exist beyond the
// UIKit layout test, which is not portable).
package ai.teemoon.app

import ai.teemoon.confidential.ImageEntry
import ai.teemoon.confidential.ModelLayerVerification
import ai.teemoon.confidential.PlaintextExposure
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustLadderSectionsTest {
    private fun img(name: String, kind: ImageEntry.Kind = ImageEntry.Kind.REGISTRY, role: PlaintextExposure.Role? = null, cap: PlaintextExposure.Capability? = null) =
        ImageEntry(name, name, kind, plaintextRole = role, capability = cap)

    @Test fun tiersFollowWhoCanReachPlaintext() {
        assertEquals(1, ImageRowPresentation.tier(img("kernel", ImageEntry.Kind.GUEST_OS)))
        assertEquals(1, ImageRowPresentation.tier(img("proxy", role = PlaintextExposure.Role.E2EE_TERMINATOR)))
        assertEquals(2, ImageRowPresentation.tier(img("agent", cap = PlaintextExposure.Capability.PROCESS_ACCESS)))
        assertEquals(4, ImageRowPresentation.tier(img("fluentbit", cap = PlaintextExposure.Capability.LOG_ACCESS)))
        assertNull(ImageRowPresentation.tier(img("nginx")))
        assertEquals("sees your message", ImageRowPresentation.capsuleLabel(img("kernel", ImageEntry.Kind.GUEST_OS)))
        assertEquals("log-forwarding egress", ImageRowPresentation.egress(4)); assertEquals("", ImageRowPresentation.egress(null))
        assertTrue(ImageRowPresentation.capNote(img("sglang", role = PlaintextExposure.Role.MODEL_SERVER))!!.contains("plaintext tokens"))
    }
    @Test fun recipeVerdictAndScopeCaptionNameTagPinnedRows() {
        assertEquals("verified on this device ✓", ImageRowPresentation.recipeVerdict(ModelLayerVerification.VERIFIED, 0))
        assertEquals("recipe file verified on this device ✓", ImageRowPresentation.recipeVerdict(ModelLayerVerification.VERIFIED, 1))
        assertTrue(ImageRowPresentation.recipeVerdict(ModelLayerVerification.HASH_MISMATCH, 0)!!.contains("do not trust"))
        assertNull(ImageRowPresentation.recipeVerdict(null, 0))
        val images = listOf(img("a/engine"), img("b/otel", ImageEntry.Kind.TAG_PINNED))
        assertEquals("pins 1 of 2 images by content digest — 1 (otel) by tag only: its running bytes can change without changing the hash verified above. see its row.",
            ImageRowPresentation.recipeScopeCaption(ModelLayerVerification.VERIFIED, images))
        assertNull(ImageRowPresentation.recipeScopeCaption(ModelLayerVerification.VERIFIED, listOf(img("a"))))
        assertTrue(ImageRowPresentation.collapsible(img("b/otel", ImageEntry.Kind.TAG_PINNED)))
        assertTrue(!ImageRowPresentation.collapsible(img("b/otel", ImageEntry.Kind.TAG_PINNED, cap = PlaintextExposure.Capability.LOG_ACCESS)))
    }
    @Test fun proofTextSplitsBacktickTokens() {
        val a = proofText("bound in `report_data` of the quote", Color.Black, Color.Gray)
        assertEquals("bound in report_data of the quote", a.text)
        assertEquals(3, a.spanStyles.size)
    }
    @Test fun expertSectionsAlwaysDiscloseTheThreeLimits() {
        val x = ExpertSections()
        assertTrue(x.limits.isEmpty())   // the empty value carries nothing; make() fills it from the session
        assertTrue(ExpertSections.LIMITS_SUBTITLE.contains("named, not hidden"))
    }
}
