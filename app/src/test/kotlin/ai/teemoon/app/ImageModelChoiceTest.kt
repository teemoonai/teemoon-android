// ports: teemoonTests/ImageModelChoiceTests.swift @ b8457654 (the marker words and a home row's ability; the note's
//        cases went with the note — IMAGES.md §4d @ teemoon-ios f04a55d2, held by CantSeeRouteTest instead)
//
// images-model-marker (the maintainer, review page 2026-11-01): which models read images, and the words for it.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageModelChoice
import ai.teemoon.providers.ModelBrowserQuery
import ai.teemoon.providers.WhereProviderPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageModelChoiceTest {
    /** iOS a15f0cec: a home machine's model is known to read images from its own row, not from a catalogue. */
    @Test fun aHomeModelReadsImagesOnceItsRowKnows() {
        assertEquals(false, ImageModelChoice.serverReads(null, null))
        assertEquals(true, ImageModelChoice.serverReads(null, ai.teemoon.providers.ModelCapabilities.VISION))
        assertEquals(true, ImageModelChoice.serverReads(setOf(ai.teemoon.providers.ModelCapability.VISION), null))
    }

    @Test fun theMarkerWordsAreTheApprovedOnes() {
        assertEquals("reads images", ImageModelChoice.MARKER)
        assertEquals(ImageModelChoice.MARKER, ModelBrowserQuery.Filter.VISION.label)
        assertEquals("on this device · warm · images",
            WhereProviderPresentation.metadataRun(WhereProviderPresentation.metadataRun("on this device", "warm"), ImageModelChoice.CAPTION_TAG))
        assertEquals("fireworks · images", WhereProviderPresentation.metadataRun("fireworks", ImageModelChoice.CAPTION_TAG))
    }
}
