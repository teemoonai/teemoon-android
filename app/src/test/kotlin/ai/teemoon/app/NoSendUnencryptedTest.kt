// ports: teemoonTests/TierDowngradeTests.swift @ 073489a1 (noShippingSourceSaysSendUnencrypted; 1.1.1 audit B1)
//
// The label is "send without encryption" everywhere: no shipping source (app/ and confidential/ main — Kotlin, resources,
// manifests) says "send unencrypted", in a string or a comment, so the old words cannot come back in one place.
package ai.teemoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoSendUnencryptedTest {
    @Test fun noShippingSourceSaysSendUnencrypted() {
        val roots = listOf(File("src/main"), File("../confidential/src/main")).filter { it.isDirectory }
        assertEquals("app and confidential main both found from the test's working directory", 2, roots.size)
        val exts = setOf("kt", "xml", "json", "txt", "properties")
        var scanned = 0
        for (root in roots) for (f in root.walkTopDown().filter { it.isFile && it.extension in exts }) {
            scanned++
            assertTrue("${f.path} says \"send unencrypted\"", !f.readText().contains("send unencrypted", ignoreCase = true))
        }
        assertTrue("scanned $scanned files", scanned > 100)
        assertEquals("send without encryption", E2EESendChoice.SendAnyway(stillEncrypted = false).label)
    }
}
