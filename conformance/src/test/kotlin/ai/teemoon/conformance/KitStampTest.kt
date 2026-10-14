package ai.teemoon.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * §13.6: the kit stamp, the ports-map baseline and the followed tag fold into
 * one ref. Every vendored class must carry the kitVersion ports.json pins, and
 * every class the port consumes must be present — a missing file is a module
 * certifying itself.
 */
class KitStampTest {
    private val classes = listOf("crypto", "quotes", "collateral", "sigstore", "nras", "tls", "streams", "catalogs", "brave")

    private fun portsKitVersion(): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "ports.json").exists()) dir = dir.parentFile
        val ports = Json.parseToJsonElement(File(dir!!, "ports.json").readText()).jsonObject
        return ports["kitVersion"]?.jsonPrimitive?.content?.takeIf { it != "null" }
    }

    @Test fun everyVendoredClassCarriesThePinnedKitVersion() {
        val pinned = portsKitVersion()
        assertNotNull("ports.json pins no kitVersion — run tools/sync_conformance.py", pinned)
        for (cls in classes) {
            val stream = javaClass.getResourceAsStream("/$cls.json")
            assertNotNull("conformance/vendor/$cls.json is not vendored", stream)
            val doc = Json.parseToJsonElement(stream!!.reader().readText()).jsonObject
            assertEquals(cls, doc["fixtureClass"]!!.jsonPrimitive.content)
            assertEquals("teemoon-ios", doc["sourceOfTruth"]!!.jsonPrimitive.content)
            assertEquals("$cls.json kitVersion", pinned, doc["kitVersion"]!!.jsonPrimitive.content)
        }
    }
}
