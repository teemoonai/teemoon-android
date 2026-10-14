// ports: teemoonTests/ToolCallFormatTests.swift @ v1.0.1
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallFormatTest {

    /** Decode a call's argument JSON — key order is not stable, so assert values. */
    private fun args(s: ToolCallState): JsonObject =
        Json.parseToJsonElement(s.arguments) as JsonObject

    // MARK: Gemma ```tool_code

    @Test fun gemmaParsesStringAndNumberArgs() {
        val content = """
            Let me look that up.
            ```tool_code
            web_search(query="weather in SF", count=5)
            ```
        """.trimIndent()
        val calls = GemmaToolCode.parse(content)
        assertEquals(1, calls.size)
        val call = calls[0]!!
        assertEquals("web_search", call.name)
        assertEquals("weather in SF", args(call)["query"]!!.jsonPrimitive.content)
        assertEquals(5, args(call)["count"]!!.jsonPrimitive.int)
    }

    @Test fun gemmaStripsPrintWrapper() {
        val call = GemmaToolCode.parse("```tool_code\nprint(get_time(zone='PST'))\n```")[0]!!
        assertEquals("get_time", call.name)
        assertEquals("PST", args(call)["zone"]!!.jsonPrimitive.content)
    }

    @Test fun gemmaKeepsCommasInsideQuotedValues() {
        // The top-level split must not break on the comma inside the quoted string.
        val call = GemmaToolCode.parse("```tool_code\nsend(text=\"hello, world\", urgent=true)\n```")[0]!!
        assertEquals("hello, world", args(call)["text"]!!.jsonPrimitive.content)
        assertEquals(true, args(call)["urgent"]!!.jsonPrimitive.boolean)
    }

    @Test fun gemmaTakesLastSegmentOfDottedName() {
        val call = GemmaToolCode.parse("```tool_code\nfunctions.web_search(query=\"x\")\n```")[0]!!
        assertEquals("web_search", call.name)
    }

    @Test fun gemmaParsesMultipleCalls() {
        val calls = GemmaToolCode.parse("```tool_code\na(x=1)\nb(y=2)\n```")
        assertEquals(2, calls.size)
        assertEquals("a", calls[0]!!.name)
        assertEquals("b", calls[1]!!.name)
    }

    // MARK: dispatch (detect-by-marker)

    @Test fun parseAnyRoutesGemma() {
        val gemma = ToolCallFormat.parseAny("```tool_code\nsearch(q=\"a\")\n```", emptyList())
        assertEquals("search", gemma[0]?.name)

        // No marker → nothing.
        assertTrue(ToolCallFormat.parseAny("just a normal reply", emptyList()).isEmpty())
    }

    /** The angle-bracket half of Swift's `parseAnyRoutesGemmaVsAngleBracket`. */
    @Test fun parseAnyRoutesAngleBracket() {
        val ang = ToolCallFormat.parseAny(
            "<tool_call>{\"name\":\"search\",\"arguments\":{\"q\":\"a\"}}</tool_call>", emptyList())
        assertEquals("search", ang[0]?.name)
    }

    @Test fun registryExposesMarkers() {
        assertTrue(ToolCallFormat.allMarkers.contains("<tool_call>"))
        assertTrue(ToolCallFormat.allMarkers.contains("```tool_code"))
        assertTrue(ToolCallFormat.maxMarkerLength >= "```tool_code".length)
    }

    /** Every format declares how its region closes — the elision depends on it. */
    @Test fun everyFormatDeclaresAClosingMarker() {
        for (fmt in ToolCallFormat.entries) {
            assertTrue("${fmt.name} has markers", fmt.markers.isNotEmpty())
            assertTrue("${fmt.name} closes", fmt.closingMarker.isNotEmpty())
        }
        assertEquals(ToolCallFormat.allMarkers.size, ToolCallFormat.markerPairs.size)
    }

    // MARK: real Gemma output (captured live 2026-07-25, exact bytes)

    @Test fun parsesRealGemmaOutput() {
        val weather = ToolCallFormat.parseAny(
            "```tool_code\nget_weather(city=\"San Francisco\")\n```", emptyList())
        assertEquals("get_weather", weather[0]?.name)
        assertEquals("San Francisco", args(weather[0]!!)["city"]!!.jsonPrimitive.content)

        val search = ToolCallFormat.parseAny(
            "```tool_code\nweb_search(query=\"top 3 Swift programming language results\")\n```",
            emptyList())
        assertEquals("web_search", search[0]?.name)
        assertEquals("top 3 Swift programming language results",
            args(search[0]!!)["query"]!!.jsonPrimitive.content)

        val dice = ToolCallFormat.parseAny("```tool_code\nroll_dice(sides=20, count=3)\n```", emptyList())
        assertEquals("roll_dice", dice[0]?.name)
        assertEquals(20, args(dice[0]!!)["sides"]!!.jsonPrimitive.int)
        assertEquals(3, args(dice[0]!!)["count"]!!.jsonPrimitive.int)
    }
}
