// spec: teemoon-ios docs/IMAGES.md §4d @ ab914c3e — the can't-see state's words. What each route does is held by
// ComposerImagesKitTest, cell by cell.
package ai.teemoon.app

import ai.teemoon.app.imaging.CantSeeRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class CantSeeRouteTest {
    @Test fun theAttachSheetSaysWhichModelCantSee() {
        assertEquals("glm 5.3 can't see images.", CantSeeRoute.sheetLine("glm 5.3"))
        assertEquals("this model can't see images.", CantSeeRoute.sheetLine(null))
    }

    /** IMAGES.md §4b: a phone model this phone can never fit says so — in the attach sheet, at send and on its where row. */
    @Test fun aPhoneThatCanNeverFitSaysSo() {
        assertEquals("this phone doesn't have enough memory for images with gemma 4 e4b.", CantSeeRoute.permanent("gemma 4 e4b"))
        assertEquals("use gemma 4 e2b", CantSeeRoute.useSmaller("gemma 4 e2b"))
    }

    /**
     * The maintainer 2026-11-12 (via pm): the send-refused alert goes short, all DRAFT, same behaviour — the title names the
     * model, the message says the message wasn't sent, and the buttons are "choose model", "send text only" (only with
     * text), "cancel". The attach sheet keeps "choose an image-capable model".
     */
    @Test fun theRefusedAlertIsShort() {
        assertEquals("glm-5p3 can't see images", CantSeeRoute.alertTitle("glm-5p3"))
        assertEquals("your message wasn't sent. pick a model that can, or send just the text.", CantSeeRoute.alertMessage(hasText = true))
        assertEquals("your message wasn't sent. pick a model that can.", CantSeeRoute.alertMessage(hasText = false))
        assertEquals(listOf("choose model", "send text only", "cancel"), CantSeeRoute.alertButtons(hasText = true))
        assertEquals(listOf("choose model", "cancel"), CantSeeRoute.alertButtons(hasText = false))
        assertEquals("choose an image-capable model", CantSeeRoute.CHOOSE)
    }
}
