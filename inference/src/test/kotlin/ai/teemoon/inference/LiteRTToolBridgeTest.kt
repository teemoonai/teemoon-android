// ports: teemoonTests/LiteRTToolBridgeTests.swift @ v1.0.1 (all 9; shimDecodesArgumentsThatDoNotMatchTheSchema becomes
//        "arbitrary json reaches the tool": the Kotlin binding hands the raw argument string straight through)
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRTToolBridgeTest {
    private class PlantedTool(private val planted: String) : Tool {
        var ran = false
        override val name = "web_search"; override val description = "Search the web."
        override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query."},"count":{"type":"integer","description":"How many results."}},"required":["query","count"]}"""
        override suspend fun execute(argumentsJson: String): String {
            ran = true
            val o = Json.parseToJsonElement(argumentsJson).jsonObject
            return "RESULT[${o["query"]?.jsonPrimitive?.content}|${o["count"]?.jsonPrimitive?.content}] $planted"
        }
    }
    private class UnbridgedTool : Tool {
        override val name = "saved_places"; override val description = "Look up saved places."
        override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
        override suspend fun execute(argumentsJson: String) = "unused"
    }
    private fun bridge() = LiteRTToolBridge(LiteRTToolRegistry())

    @Test fun executesTheRegisteredTeemoonTool() = runTest {
        val tool = PlantedTool("ZX9"); val reported = ArrayList<Triple<String, String, String>>()
        val b = bridge(); val shims = b.bridged(listOf(tool), onExecuted = { n, a, r -> reported += Triple(n, a, r) })
        assertEquals("web_search should have produced one LiteRT shim", 1, shims.size)
        val result = b.execute("web_search", """{"query":"oil price","count":5}""")
        assertTrue("the teemoon tool never ran", tool.ran); assertTrue(result.contains("ZX9")); assertTrue(result.contains("oil price"))
        assertEquals(1, reported.size); assertEquals("web_search", reported.first().first)
    }
    @Test fun coercesStringifiedScalarsLikeTheSharedEngine() = runTest {
        val tool = PlantedTool("ZX9"); val b = bridge(); b.bridged(listOf(tool), onExecuted = { _, _, _ -> })
        val result = b.execute("web_search", """{"query":"oil","count":"5"}""")   // string!
        assertTrue("coercion failed, so the tool never ran: $result", tool.ran); assertTrue("count did not coerce to an Int: $result", result.contains("|5]"))
    }
    @Test fun exposesTeemoonsFlattenedSchemaInLiteRTsEnvelope() = runTest {
        val b = bridge(); val shim = b.bridged(listOf(PlantedTool("ZX9")), onExecuted = { _, _, _ -> }).single()
        val schema = Json.parseToJsonElement(shim.descriptionJson).jsonObject
        assertEquals("missing the type:function envelope — the native layer will not find the parameters", "function", schema["type"]?.jsonPrimitive?.content)
        val function = schema["function"] as? JsonObject; assertNotNull("no function object — the flat shape silently drops parameters", function)
        assertEquals("web_search", function!!["name"]?.jsonPrimitive?.content)
        val parameters = function["parameters"] as? JsonObject; assertNotNull("no parameters — the model would see no arguments at all", parameters)
        assertNull(parameters!!["\$ref"]); assertNull(parameters["\$defs"])
        val properties = parameters["properties"] as JsonObject; assertNotNull(properties["query"]); assertNotNull(properties["count"])
    }
    @Test fun shimHandsArbitraryJsonThroughRatherThanQuietlyDroppingIt() = runTest {
        val tool = PlantedTool("ZX9"); val b = bridge(); val shim = b.bridged(listOf(tool), onExecuted = { _, _, _ -> }).single()
        val text = shim.execute("""{"query": "oil price", "count": 5, "extra": [1, 2], "nested": {"a": null}}""")
        assertTrue("the query never reached the tool: $text", text.contains("oil price")); assertTrue("count never reached the tool: $text", text.contains("|5]")); assertTrue(tool.ran)
    }
    @Test fun toolRoundsAreBoundedWithoutKillingTheTurn() = runTest {
        val b = bridge(); b.bridged(listOf(PlantedTool("ZX9")), onExecuted = { _, _, _ -> })
        val results = (1..b.maxToolRounds + 2).map { b.execute("web_search", """{"query":"oil","count":5}""") }
        assertEquals("expected exactly ${b.maxToolRounds} rounds to run", b.maxToolRounds, results.count { it.contains("ZX9") })
        val refused = results.filter { it.contains("Search limit reached") }
        assertEquals("calls past the limit must be refused, not run", 2, refused.size); assertTrue(refused.none { it.lowercase().contains("error") })
    }
    @Test fun theRoundBudgetResetsPerTurn() = runTest {
        val b = bridge(); val tool = PlantedTool("ZX9")
        for (turn in 1..3) {
            b.bridged(listOf(tool), onExecuted = { _, _, _ -> })
            assertTrue("turn $turn began already out of budget — the reset is not per-turn", b.execute("web_search", """{"query":"oil","count":5}""").contains("ZX9"))
            b.execute("web_search", """{"query":"oil","count":5}"""); b.execute("web_search", """{"query":"oil","count":5}""")
        }
    }
    @Test fun toolsWithoutABridgeAreNotOffered() = runTest {
        var warned = false
        assertTrue("a tool with no shim must not be offered to the model", bridge().bridged(listOf(UnbridgedTool()), onExecuted = { _, _, _ -> }, warn = { warned = true }).isEmpty())
        assertTrue("silently dropping it would make the tool look broken", warned)
    }
    @Test fun unknownToolReturnsAMessageRatherThanThrowing() = runTest {
        assertTrue(bridge().execute("nope", "{}").contains("not found"))
    }
    @Test fun onStartedFiresBeforeTheToolRuns() = runTest {
        val order = ArrayList<String>(); val tool = object : Tool {
            override val name = "web_search"; override val description = "x"; override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
            override suspend fun execute(argumentsJson: String): String { order += "ran"; return "ok" }
        }
        val b = bridge(); b.bridged(listOf(tool), onStarted = { _, _ -> order += "started" }, onExecuted = { _, _, _ -> order += "executed" })
        b.execute("web_search", """{"query":"q"}""")
        assertEquals(listOf("started", "ran", "executed"), order)
    }
    @Test fun aThrowingToolBecomesAStringNotAThrow() = runTest {
        val tool = object : Tool {
            override val name = "web_search"; override val description = "x"; override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
            override suspend fun execute(argumentsJson: String): String = throw IllegalStateException("boom")
        }
        val b = bridge(); b.bridged(listOf(tool), onExecuted = { _, _, _ -> })
        assertTrue(b.execute("web_search", """{"query":"q"}""").contains("Tool call failed")); assertFalse(b.execute("web_search", "{}").isEmpty())
    }

    @Test
    fun flatDescription_unwrapsTheEnvelopeForTheKotlinBinding() {
        val envelope = """{"type":"function","function":{"name":"web_search","description":"d","parameters":{"type":"object"}}}"""
        val flat = Json.parseToJsonElement(LiteRTToolBridge.flatDescription(envelope)).jsonObject
        assertEquals("web_search", flat["name"]!!.jsonPrimitive.content)
        assertEquals("object", flat["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(null, flat["function"])
        // Already flat stays flat.
        assertEquals("x", Json.parseToJsonElement(LiteRTToolBridge.flatDescription("""{"name":"x"}""")).jsonObject["name"]!!.jsonPrimitive.content)
    }
}
