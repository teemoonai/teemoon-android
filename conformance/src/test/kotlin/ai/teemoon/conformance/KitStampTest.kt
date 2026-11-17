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
    /**
     * iOS ConformanceVectors.kitVersionByClass @ e4546ef1: "A file whose bytes change gets its own bump, so a vendored
     * copy can't drift under an unchanged number." A class named here carries its own stamp; the rest carry the pin.
     */
    private val kitVersionByClass = mapOf("catalogs" to "0.5.7")
    private val classes = listOf("crypto", "quotes", "collateral", "sigstore", "nras", "tls", "streams", "catalogs", "brave", "signatures", "requests", "refusals", "redaction", "errorcopy")

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
            assertEquals("$cls.json kitVersion", kitVersionByClass[cls] ?: pinned, doc["kitVersion"]!!.jsonPrimitive.content)
        }
    }
}
