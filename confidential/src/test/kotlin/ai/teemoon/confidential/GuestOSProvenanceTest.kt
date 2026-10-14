// ports: teemoonTests/GuestOSProvenanceTests.swift @ v1.0.1 (the hash→source map suite; the three
//        os_image_hash wire-shape extraction tests exercise AttestationService and move with that row)
package ai.teemoon.confidential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestOSProvenanceTest {
    private val modelOSHash = "9b69bb1698bacbb6985409a2c272bcb892e09cdcea63d5399c6768b67d3ff677"

    @Test fun knownHash_mapsToPublishedRelease() {
        val src = GuestOSProvenance.source(modelOSHash)!!
        assertEquals("v0.5.5", src.tag)
        assertEquals("25c25025c556ab2f797eeda3bab433f38a8ffb7a", src.commit)
        assertEquals("https://github.com/nearai/private-ml-sdk/releases/tag/v0.5.5", src.releaseURL)
    }

    @Test fun sha256PrefixAndCaseAreNormalized() {
        assertEquals("v0.5.5", GuestOSProvenance.source("sha256:$modelOSHash")?.tag)
        assertEquals("v0.5.5", GuestOSProvenance.source(modelOSHash.uppercase())?.tag)
    }

    /** The gateway node's hash is NOT a dstack-nvidia release — it must not resolve to a source link. */
    @Test fun unknownHash_yieldsNoSource() {
        assertNull(GuestOSProvenance.source("da9a3d5cc196a1a76d953fb27069be428ddf60a1ce10b0534c3cf968d3053fde"))
        assertNull(GuestOSProvenance.source("deadbeef"))
    }
}
