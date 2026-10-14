// ports: android-only. iOS has no standalone ModelOffer suite — its labels are
//        asserted through OpenRouterModelCardTests @ 835d7c8, which needs the
//        adapter and its fixture. The adapter is not ported yet, so the pure
//        label logic is pinned here and the card's own cases come with it.
//        The numbers below are the Swift suite's: 2.1 s, 37 tok/s, 91.6%.
package ai.teemoon.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelOfferTest {

    private fun offer(
        provider: String = "OpenAI", tag: String = "openai",
        uptime: Double? = null, latency: Double? = null, throughput: Double? = null,
    ) = ModelOffer(providerName = provider, tag = tag, uptimePercent = uptime,
        latencyMilliseconds = latency, throughputTokensPerSecond = throughput)

    /** The title never invents a variant the tag did not carry. */
    @Test fun titleNamesAVariantOnlyWhenTheTagHasOne() {
        assertEquals("OpenAI", offer(tag = "openai").title)
        assertEquals("OpenAI · flex", offer(tag = "openai/flex").title)
        // Only the FIRST slash splits: a variant may contain one.
        assertEquals("OpenAI · flex/eu", offer(tag = "openai/flex/eu").title)
    }

    /**
     * Milliseconds under a second, seconds above it — 2085 ms reads as "2.1 s",
     * which is how long it actually feels.
     */
    @Test fun latencyReadsInTheUnitThatMatches() {
        assertEquals("2.1 s", offer(latency = 2085.0).latencyLabel)
        assertEquals("850 ms", offer(latency = 850.0).latencyLabel)
        assertEquals("1.0 s", offer(latency = 1000.0).latencyLabel)
        // The public list answers null, and zero is not a measurement.
        assertNull(offer(latency = null).latencyLabel)
        assertNull(offer(latency = 0.0).latencyLabel)
    }

    @Test fun throughputAndUptimeReadAsReported() {
        assertEquals("37 tok/s", offer(throughput = 37.4).throughputLabel)
        assertNull(offer(throughput = null).throughputLabel)
        assertNull(offer(throughput = 0.0).throughputLabel)
        assertEquals("91.6%", offer(uptime = 91.61).uptimeLabel)
        assertNull(offer(uptime = null).uptimeLabel)
    }

    /**
     * 99.95 and up prints a flat "100%" rather than "100.0%", which would be a
     * rounded number wearing a precision it does not have.
     */
    @Test fun nearPerfectUptimeReadsAsWhole() {
        assertEquals("100%", offer(uptime = 100.0).uptimeLabel)
        assertEquals("100%", offer(uptime = 99.95).uptimeLabel)
        assertEquals("99.9%", offer(uptime = 99.94).uptimeLabel)
    }

    /** Identity is provider + tag, so two variants from one provider stay distinct. */
    @Test fun identityDistinguishesVariantsOfOneProvider() {
        assertEquals(offer(tag = "openai/flex").id, offer(tag = "openai/flex").id)
        org.junit.Assert.assertNotEquals(offer(tag = "openai").id, offer(tag = "openai/flex").id)
    }
}
