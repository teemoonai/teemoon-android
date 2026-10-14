// ports: teemoonTests/PlaceLabelTests.swift @ v1.0.1
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLabelTest {

    @Test fun emptyNameTakesPreset() {
        val next = PlaceLabel.proposed(
            currentName = "", lastAuto = "", presetName = "near.ai", host = "example.com")
        assertEquals("near.ai", next)
    }

    @Test fun emptyNameTakesFriendlyHostWhenNoPreset() {
        val next = PlaceLabel.proposed(
            currentName = "", lastAuto = "", presetName = "", host = "ringzero.tailnet.ts.net")
        assertEquals("ringzero", next)
    }

    @Test fun matchingAutoLabelRefreshes() {
        val next = PlaceLabel.proposed(
            currentName = "near.ai", lastAuto = "near.ai", presetName = "grok", host = null)
        assertEquals("grok", next)
    }

    @Test fun typedNameIsNeverOverwritten() {
        val next = PlaceLabel.proposed(
            currentName = "work box", lastAuto = "near.ai", presetName = "grok", host = "x.com")
        assertNull(next)
    }

    @Test fun nothingToNameReturnsNil() {
        val next = PlaceLabel.proposed(
            currentName = "", lastAuto = "", presetName = "", host = null)
        assertNull(next)
    }
}
