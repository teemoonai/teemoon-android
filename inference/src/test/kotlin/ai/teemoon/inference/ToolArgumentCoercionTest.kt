// ports: teemoonTests/ToolArgumentCoercionTests.swift @ v1.0.1
package ai.teemoon.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for GenerationEngine.coerceArgsToSchema — models such as
 * GLM-5.2 on near.ai emit numeric tool arguments as JSON strings
 * (`"count": "15"`), which used to fail strict decoding with a type mismatch
 * and surface as "[Tool call failed: typeMismatch]".
 */
class ToolArgumentCoercionTest {

    // The exact generated schema teemoon sends the model (with $ref/$defs
    // indirection) — same value production coercion keys off.
    private val braveSchema: JsonObject get() = GenerationEngine.toolSchemaJson(SchemaFixtures.braveTool)!!

    private fun value(json: String, key: String): JsonElement? =
        (Json.parseToJsonElement(json) as JsonObject)[key]

    /** A JSON number (not a quoted string) equal to [n]. */
    private fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    // MARK: - coerceArgsToSchema

    @Test fun coercesStringIntegerToNumber() {
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "bonsai reviews", "count": "15"}""", braveSchema)
        assertEquals(15L, value(out, "count").asLong())
        // The query is declared as a string in the schema and must stay a string.
        assertEquals("bonsai reviews", value(out, "query").asString())
    }

    @Test fun leavesNumericStringQueryUntouched() {
        // A query that happens to be all digits must NOT become a number — the
        // schema declares `query` as a string, so coercion is schema-driven.
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "15", "count": "15"}""", braveSchema)
        assertEquals("15", value(out, "query").asString())
        assertEquals(15L, value(out, "count").asLong())
    }

    @Test fun leavesWellFormedArgumentsUnchanged() {
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "hello", "count": 20}""", braveSchema)
        assertEquals(20L, value(out, "count").asLong())
        assertEquals("hello", value(out, "query").asString())
    }

    @Test fun ignoresNonNumericStringForNumericField() {
        // Garbage that isn't parseable as an Int is left as-is (decoding will
        // then fail loudly rather than silently coercing to a wrong value).
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "x", "count": "lots"}""", braveSchema)
        assertEquals("lots", value(out, "count").asString())
    }

    @Test fun returnsInputUnchangedWhenNotJSON() {
        val input = "not json at all"
        assertEquals(input, GenerationEngine.coerceArgsToSchema(input, braveSchema))
    }

    @Test fun acceptsIntegralDoubleStringForIntegerField() {
        // Models sometimes emit whole numbers with a trailing ".0".
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "x", "count": "20.0"}""", braveSchema)
        assertEquals(20L, value(out, "count").asLong())
    }

    @Test fun leavesNonIntegralDoubleForIntegerField() {
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "x", "count": "20.5"}""", braveSchema)
        assertEquals("20.5", value(out, "count").asString())
    }

    @Test fun leavesOverflowingIntegerAsString() {
        // Int.max + 1 — not representable, must not be silently truncated.
        val out = GenerationEngine.coerceArgsToSchema("""{"query": "x", "count": "9223372036854775808"}""", braveSchema)
        assertEquals("9223372036854775808", value(out, "count").asString())
    }

    private val numberSchema = Json.parseToJsonElement(
        """{"type": "object", "properties": {"x": {"type": "number"}}}""") as JsonObject

    @Test fun nonFiniteNumberIsLeftAsStringAndDoesNotCrash() {
        // Guards the serializer crash: NaN/Infinity parse as a Double but must
        // not reach serialization.
        for (bad in listOf("NaN", "inf", "Infinity", "-Infinity")) {
            val out = GenerationEngine.coerceArgsToSchema("""{"x": "$bad"}""", numberSchema)
            assertEquals("$bad should be left as a string", bad, value(out, "x").asString())
        }
    }

    @Test fun coercesWellFormedNumberString() {
        val out = GenerationEngine.coerceArgsToSchema("""{"x": "3.14"}""", numberSchema)
        assertEquals(3.14, value(out, "x").asDouble()!!, 0.0)
    }

    /** Android-only: Java's number parser takes forms Swift's does not; they must stay strings. */
    @Test fun javaOnlyNumberSpellingsStayStrings() {
        for (bad in listOf("15d", "1.5f", "0x1p3")) {
            val out = GenerationEngine.coerceArgsToSchema("""{"x": "$bad"}""", numberSchema)
            assertEquals("$bad should be left as a string", bad, value(out, "x").asString())
        }
    }

    /** Android-only: booleans coerce case-insensitively; anything else is left alone. */
    @Test fun coercesStringBooleans() {
        val schema = Json.parseToJsonElement(
            """{"type": "object", "properties": {"b": {"type": "boolean"}}}""") as JsonObject
        assertEquals(true, value(GenerationEngine.coerceArgsToSchema("""{"b": " True "}""", schema), "b")!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, value(GenerationEngine.coerceArgsToSchema("""{"b": "false"}""", schema), "b")!!.jsonPrimitive.booleanOrNull)
        assertEquals("yes", value(GenerationEngine.coerceArgsToSchema("""{"b": "yes"}""", schema), "b").asString())
    }

    // MARK: - Full decode path (the exact GLM-5.2 failure)

    @Serializable
    private data class BraveArgs(
        val query: String,
        val freshness: String? = null,
        val contextThresholdMode: String? = null,
        val count: Int? = null,
    )

    @Test fun stringCountDecodesIntoArgumentsAfterCoercion() {
        // Reproduces the screenshot: GLM-5.2 emitted count as a string.
        val raw = """{"query": "Bonsai 27B PrismML review quality criticism", "count": "15"}"""

        val coerced = GenerationEngine.coerceArgsToSchema(raw, braveSchema)
        val args = Json.decodeFromString(BraveArgs.serializer(), coerced)

        assertEquals(15, args.count)
        assertEquals("Bonsai 27B PrismML review quality criticism", args.query)
    }

    // MARK: - $ref / $defs and nested arrays

    /**
     * The generated schema uses a top-level `$ref` into `$defs`; coercion must
     * resolve it (proven implicitly above) and recurse through array `items`
     * without corrupting nested string values.
     */
    @Test fun nestedArrayOfObjectsPassesThroughUnchanged() {
        val schema = GenerationEngine.toolSchemaJson(SchemaFixtures.nestedArrayTool)!!

        val raw = """{"places": [{"name": "Sushi Noz", "address": "181 E 78th St", "notes": "12 seats"}]}"""
        val out = GenerationEngine.coerceArgsToSchema(raw, schema)

        val places = value(out, "places") as JsonArray
        assertEquals(1, places.size)
        val first = places[0] as JsonObject
        assertEquals("Sushi Noz", first["name"].asString())
        // "12 seats" is a string field and must remain a string — not mangled by
        // recursion through the resolved PlaceInput ref.
        assertEquals("12 seats", first["notes"].asString())
        assertNull(first["notes"].asLong())
    }

    /** Android-only: the `{"parameters": "<json string>"}` wrapper some models emit is unwrapped. */
    @Test fun parametersWrapperIsUnwrapped() {
        val wrapped = """{"parameters": "{\"query\": \"x\", \"count\": \"2\"}"}"""
        val out = GenerationEngine.normalizeToolArgsJson(wrapped)
        assertEquals("x", value(out, "query").asString())
        assertTrue(GenerationEngine.normalizeToolArgsJson("""{"query": "x"}""").contains("query"))
    }
}
