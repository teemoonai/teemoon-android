// ports: teemoonTests/LogBodyPreviewTests.swift @ v1.0.1
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPreviewTest {
    @Test fun shortBody_isVerbatim() { assertEquals("{\"error\":\"nope\"}", "{\"error\":\"nope\"}".toByteArray().previewForLog()) }
    @Test fun emptyBody_isEmpty() { assertEquals("", ByteArray(0).previewForLog()) }
    @Test fun bodyAtTheLimit_isNotAnnotated() {
        val preview = "a".repeat(LOG_PREVIEW_LIMIT).toByteArray().previewForLog()
        assertEquals(LOG_PREVIEW_LIMIT, preview.length); assertFalse(preview.contains("truncated"))
    }
    @Test fun oversizeBody_isCappedAndSaysSo() {
        val preview = "a".repeat(10_000).toByteArray().previewForLog()
        assertTrue(preview.startsWith("a".repeat(LOG_PREVIEW_LIMIT)))
        assertFalse(preview.startsWith("a".repeat(LOG_PREVIEW_LIMIT + 1)))
        assertTrue(preview.endsWith("… (+${10_000 - LOG_PREVIEW_LIMIT} bytes truncated)"))
    }
    @Test fun customLimit_isHonoured() {
        val body = "abcdefghij".toByteArray()
        assertEquals("abcd… (+6 bytes truncated)", body.previewForLog(limit = 4))
        assertEquals("… (+10 bytes truncated)", body.previewForLog(limit = 0))
    }
    @Test fun cutMidCodepoint_stillDecodes() {
        val preview = "aé漢字".toByteArray().previewForLog(limit = 4)   // 1 + 2 + 3 + 3 bytes; cuts inside 漢
        assertTrue(preview.startsWith("aé")); assertTrue(preview.contains("truncated"))
    }
    @Test fun binaryBody_isDescribed_notDumped() {
        assertEquals("<binary, 8 bytes>", byteArrayOf(-1, -2, -1, -2, -1, -2, -1, -2).previewForLog())
    }
}
