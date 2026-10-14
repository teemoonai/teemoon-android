// ports: MessageView.swift's TimeInterval.formatted and DisclosureState — no Swift tests exist for either; these pin the port.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageViewsTest {
    @Test fun secondsOnly() { assertEquals("4s", formattedDuration(4.7)); assertEquals("0s", formattedDuration(0.2)) }
    @Test fun wholeMinutes() { assertEquals("1m", formattedDuration(60.0)); assertEquals("2m", formattedDuration(120.9)) }
    @Test fun minutesAndSeconds() { assertEquals("1m 5s", formattedDuration(65.0)) }
    @Test fun disclosureIsAPropertyOfTheMessage() {
        DisclosureState.setExpanded("m1", true); assertTrue(DisclosureState.isExpanded("m1")); assertFalse(DisclosureState.isExpanded("m2"))
        DisclosureState.setExpanded("m1", false); assertFalse(DisclosureState.isExpanded("m1"))
    }
}
