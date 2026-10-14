package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The catalogue's capability SET and the persisted BITSET are the same fact in two shapes; a flag either converter
 * forgets is a capability that silently disappears when a model is equipped (INSERT did, through a second converter
 * that mapped seven of the eight flags — found by review 2026-10-06).
 */
class ModelCapabilitiesTest {
    @Test fun everyFlagSurvivesTheRoundTrip() {
        for (c in ModelCapability.entries) {
            assertEquals(setOf(c), ModelCapabilities.fromCapabilitySet(setOf(c))!!.toCapabilitySet())
        }
        val all = ModelCapability.entries.toSet()
        assertEquals(all, ModelCapabilities.fromCapabilitySet(all)!!.toCapabilitySet())
    }
    @Test fun anUnknownSetStaysUnknown() { assertNull(ModelCapabilities.fromCapabilitySet(null)) }
}
