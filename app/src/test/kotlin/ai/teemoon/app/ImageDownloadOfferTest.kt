// spec: IMAGES.md §4b (the maintainer 2026-10-26, (a)) — a Tensor phone's images need the general file as a second download.
package ai.teemoon.app

import ai.teemoon.app.imaging.ImageCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDownloadOfferTest {
    @Test fun thePaperclipOffersTheSecondDownloadOnlyWhenItIsNeededAndNotRunning() {
        assertTrue(imageDownloadOffered(needsSecondDownload = true, downloading = false))
        // while it downloads the chip shows the progress; asking again would start nothing
        assertFalse(imageDownloadOffered(needsSecondDownload = true, downloading = true))
        assertFalse(imageDownloadOffered(needsSecondDownload = false, downloading = false))
    }

    /** The review page's wording (entry ondevice-image-download), lowercase as every string here. */
    @Test fun theOfferSaysWhatItCostsAndWhatStays() {
        assertEquals("use images on this phone?", ImageCopy.OFFER_DOWNLOAD_TITLE)
        assertTrue(ImageCopy.OFFER_DOWNLOAD_BODY.contains("2.6 GB"))
        for (s in listOf(ImageCopy.OFFER_DOWNLOAD_TITLE, ImageCopy.OFFER_DOWNLOAD_BODY, ImageCopy.OFFER_DOWNLOAD, ImageCopy.OFFER_NOT_NOW))
            assertEquals(s, s.lowercase().replace("gb", "GB"))
    }
}
