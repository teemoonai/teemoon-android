// ports: teemoonTests/ToolSchemaFlattenTests.swift @ v1.0.1
package ai.teemoon.inference

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * teemoon flattens the generated `$ref`/`$defs` schema into a plain inline object
 * before sending tools on the wire. With the raw `$ref` form, a small model can't see
 * the real parameters and hallucinates the argument shape — verified live: gemma4:e2b
 * emitted `{"queries":[...]}` 5/5 (temp 0) with `$ref`, and the declared `{"query":...}`
 * 5/5 once flattened. These tests pin that the outbound schema is fully inlined.
 *
 * iOS drives these against the `@Generable`-derived Brave schema. Android has no
 * macro, so [SchemaFixtures.braveTool] is that schema hand-written in the exact
 * `{"$defs": {…}, "$ref": "#/$defs/…"}` shape the macro emits.
 */
class ToolSchemaFlattenTest {

    private val schema: JsonObject get() = GenerationEngine.flattenedToolSchema(SchemaFixtures.braveTool)

    /** Guards the premise: the fixture really is `$ref`/`$defs`-shaped, so the flatten step is doing something. */
    @Test fun rawSchemaHasTheRefIndirection() {
        val raw = GenerationEngine.toolSchemaJson(SchemaFixtures.braveTool)!!
        assertNotNull(raw["\$ref"])
        assertNotNull(raw["\$defs"])
    }

    @Test fun flattenedSchemaHasNoRefOrDefs() {
        assertNull(schema["\$ref"])
        assertNull(schema["\$defs"])
    }

    /** The whole point: the model must see `query` (string, required) at the top level. */
    @Test fun flattenedSchemaExposesQueryDirectly() {
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"] as JsonObject
        val query = props["query"] as JsonObject
        assertEquals("string", query["type"]!!.jsonPrimitive.content)
        val required = (schema["required"] as JsonArray).map { it.jsonPrimitive.content }
        assertTrue(required.contains("query"))
    }

    /** Android-only: a `$ref` under array `items` is inlined too (the nested-array shape). */
    @Test fun nestedItemRefsAreInlined() {
        val flat = GenerationEngine.flattenedToolSchema(SchemaFixtures.nestedArrayTool)
        val places = (flat["properties"] as JsonObject)["places"] as JsonObject
        val items = places["items"] as JsonObject
        assertNull(items["\$ref"])
        assertEquals("object", items["type"]!!.jsonPrimitive.content)
        assertNotNull((items["properties"] as JsonObject)["name"])
    }

    /** A tool whose schema is not a JSON object still sends a valid (empty) object schema. */
    @Test fun unparseableSchemaFallsBackToAnEmptyObject() {
        val broken = object : Tool {
            override val name = "broken"
            override val description = ""
            override val parametersSchema = "not json"
            override suspend fun execute(argumentsJson: String) = ""
        }
        assertEquals("object", GenerationEngine.flattenedToolSchema(broken)["type"]!!.jsonPrimitive.content)
    }
}

/** Schemas in the exact shape iOS's `@Generable` macro emits, for the coercion and flatten suites. */
object SchemaFixtures {
    val braveTool = object : Tool {
        override val name = "web_search"
        override val description = "Search the web."
        override val parametersSchema = """
            {"${'$'}defs":{"Arguments":{"type":"object","properties":{
               "query":{"type":"string","description":"The search query."},
               "freshness":{"type":"string"},
               "contextThresholdMode":{"type":"string"},
               "count":{"type":"integer"}},
               "required":["query"]}},
             "${'$'}ref":"#/${'$'}defs/Arguments"}
        """.trimIndent()
        override suspend fun execute(argumentsJson: String) = ""
    }

    /** Same `$ref` / nested-array shape the parked save-places tool provided. */
    val nestedArrayTool = object : Tool {
        override val name = "nested_array_fixture"
        override val description = "test fixture"
        override val parametersSchema = """
            {"${'$'}defs":{
               "Arguments":{"type":"object","properties":{
                  "places":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/PlaceInput"}}},
                  "required":["places"]},
               "PlaceInput":{"type":"object","properties":{
                  "name":{"type":"string"},"address":{"type":"string"},"notes":{"type":"string"}},
                  "required":["name","address"]}},
             "${'$'}ref":"#/${'$'}defs/Arguments"}
        """.trimIndent()
        override suspend fun execute(argumentsJson: String) = ""
    }
}
