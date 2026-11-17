// ports: teemoonTests/ConformanceLoadingVectors.swift @ 2687859 (the consuming half, against conformance/loading.json)
//
// The on-device load line (strings approved by the maintainer 2026-10-25/26 via pm; first-load rule agreed with iOS
// 2026-10-26). The expected lines are worked by hand in iOS from the rule; the vector is the contract.
package ai.teemoon.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformanceLoadingTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResourceAsStream("/loading.json")!!.reader().readText())
        .jsonObject["vectors"]!!.jsonObject
    private val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }

    @Test fun everyProbeOfEveryCase() {
        assertTrue("loading.json has no cases", cases.isNotEmpty())
        val failures = ArrayList<String>()
        for (c in cases) {
            fun num(k: String) = (c[k] as? JsonPrimitive)?.doubleOrNull
            val load = EngineLoadLine.Load(num("loadStartMs"), num("loadEndsAtMs"), c["imageCapable"]!!.jsonPrimitive.boolean,
                c["cachePresent"]!!.jsonPrimitive.boolean, num("rebuildSeenAtMs"))
            for (p in c["probes"]!!.jsonArray.map { it.jsonObject }) {
                val at = p["atMs"]!!.jsonPrimitive.double
                val want = p["line"]!!.jsonPrimitive.content
                val got = EngineLoadLine.line(at, load)
                if (got != want) failures += "${c["name"]!!.jsonPrimitive.content} @${at}ms: want <$want>, got <$got>"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun theApprovedWordsAreExact() {
        assertEquals("preparing the model for images… this happens once", EngineLoadLine.PREPARING_FOR_IMAGES)
        assertEquals("loading the model…", EngineLoadLine.LOADING)
        assertEquals(EngineLoadLine.QUIET_MS, vectors["quietMs"]!!.jsonPrimitive.double, 0.0)
    }
}
