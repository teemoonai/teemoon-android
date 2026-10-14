// ports: teemoonTests/DataModelTests.swift @ v1.0.1 (the four message_groundingSources cases — Message.groundingSources
//        decodes Message.sourcesJSON; here the stored column is MessageEntity.sourcesJson and GroundingSource.decodeList
//        is its reader). The Room half of the suite is app/androidTest ChatDataModelTest.
package ai.teemoon.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingSourceTest {
    private val one = listOf(GroundingSource(url = "https://test.com", domain = "test.com", title = "Test"))

    @Test fun noStoredSourcesIsEmpty() {
        assertTrue(GroundingSource.decodeList(null).isEmpty())
    }

    @Test fun storedSourcesDecode() {
        val back = GroundingSource.decodeList(GroundingSource.encodeList(one))
        assertEquals(1, back.size); assertEquals("https://test.com", back[0].url)
    }

    /** A corrupt column is an empty rail, not a crash: the reply above it still renders. */
    @Test fun invalidJsonIsEmpty() {
        assertTrue(GroundingSource.decodeList("not json").isEmpty())
    }

    /** iOS pins that a second read agrees with the first (its getter caches); here the decode is pure, so it must. */
    @Test fun decodingIsStable() {
        val stored = GroundingSource.encodeList(one)
        assertEquals(GroundingSource.decodeList(stored), GroundingSource.decodeList(stored))
    }
}
