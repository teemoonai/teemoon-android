// ports: teemoonTests/MessageImageGridTests.swift @ 760ba58f, teemoon/Views/Chat/MessageImages.swift @ 85451a58 (the thumbnail row's tiles and the viewer's page indicator)
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGridTest {
    /**
     * At most 4 tiles, a 2×2 grid; from the 5th photo the 4th tile carries "+(count − 3)" — every photo not shown
     * clear, itself included — and opens at its own index (the maintainer 2026-11-14, both apps, 1.1.0; before it: 6 tiles, 3 to
     * a row, "+(count − 6)" on the 6th). Each is "image n of count"; the "+N" tile is "images 4 to count of count" (iOS).
     */
    @Test fun theTilesAreFourTwoToARow() {
        val ten = ImageGrid.tiles(10)
        assertEquals(listOf(0, 1, 2, 3), ten.map { it.index })
        assertEquals(listOf(null, null, null, "+7"), ten.map { it.overlay })
        assertEquals(listOf("image 1 of 10", "image 2 of 10", "image 3 of 10", "images 4 to 10 of 10"), ten.map { it.label })
        assertEquals("image 4 of 4", ImageGrid.tiles(4)[3].label)
        assertEquals("+2", ImageGrid.tiles(5)[3].overlay)
        assertEquals("exactly 4: no overlay", List<String?>(4) { null }, ImageGrid.tiles(4).map { it.overlay })
        assertEquals(listOf<String?>(null, null), ImageGrid.tiles(2).map { it.overlay })
        assertEquals(listOf("image 1 of 1"), ImageGrid.tiles(1).map { it.label })
        assertEquals(listOf(listOf(0, 1), listOf(2)), ImageGrid.rows(3).map { r -> r.map { it.index } })
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), ImageGrid.rows(10).map { r -> r.map { it.index } })
    }

    /** One image 240 × 180, two as 136 squares, three or more as 100 squares (§4d, in dp). */
    @Test fun theTileSizesFollowTheCount() {
        assertEquals(240 to 180, ImageGrid.tileSize(1))
        assertEquals(136 to 136, ImageGrid.tileSize(2))
        assertEquals(100 to 100, ImageGrid.tileSize(3))
        assertEquals(100 to 100, ImageGrid.tileSize(9))
    }

    /** The viewer pages through every image with page dots (iOS TabView .page, shown when count > 1), and no "n / N" text. */
    @Test fun theViewerShowsPageDotsNotACounter() {
        assertTrue(ImageGrid.viewerShowsDots(8)); assertTrue(ImageGrid.viewerShowsDots(2)); assertFalse(ImageGrid.viewerShowsDots(1))
        assertNull(ImageGrid.viewerCounterText(3, 8))
    }
}
