// ports: teemoonTests/ConformanceRevealVectors.swift @ 0598bce1 (the consuming half, against conformance/reveal.json)
//
// "Whole if quick" (the maintainer 2026-10-25) held to iOS's hand-worked vectors. The harness is the vector's own rule: arrivals
// (and finish) at their event times, frame() only at the probe times, an event before a probe at the same time. Since kit
// 0.5.4 every case names its "source": a StreamReveal for that origin (onDevice: no first hold, cases g–i).
package ai.teemoon.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformanceRevealTest {
    private val cases = Json.parseToJsonElement(javaClass.getResourceAsStream("/reveal.json")!!.reader().readText())
        .jsonObject["vectors"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

    @Test fun everyProbeOfEveryCase() {
        assertTrue("reveal.json has no cases", cases.isNotEmpty())
        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val events = c["events"]!!.jsonArray.map { it.jsonObject }
            val probes = c["probes"]!!.jsonArray.map { it.jsonObject }
            val reveal = StreamReveal(when (val src = c.str("source")) {
                "cloud" -> StreamReveal.Origin.CLOUD
                "onDevice" -> StreamReveal.Origin.ON_DEVICE
                else -> error("$name: unknown source $src")
            })
            var arrived = ""
            var e = 0
            for (p in probes) {
                val at = p["atMs"]!!.jsonPrimitive.long
                while (e < events.size && events[e]["atMs"]!!.jsonPrimitive.long <= at) {
                    val ev = events[e]; val t = ev["atMs"]!!.jsonPrimitive.long
                    // an event may only finish (no arrival)
                    ev.str("arrive")?.let { arrived = it; reveal.arrive(it, t) }
                    if (ev["finish"]?.jsonPrimitive?.boolean == true) reveal.finish()
                    e++
                }
                val shown = reveal.frame(at)
                val wantText = p["shown"]!!.jsonPrimitive.content
                val wantFade = (p["fadeFrom"] as? kotlinx.serialization.json.JsonPrimitive)?.int
                val gotText = arrived.take(shown.length)
                if (gotText != wantText || shown.fadeFrom != wantFade)
                    failures += "$name @${at}ms: want <${wantText.replace("\n", "\\n")}> fade=$wantFade, got <${gotText.replace("\n", "\\n")}> fade=${shown.fadeFrom}"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}
