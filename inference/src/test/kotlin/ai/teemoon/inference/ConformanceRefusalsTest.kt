// ports: teemoonTests/ConformanceRefusalVectors.swift @ v1.1.0 (the consuming half, against conformance/refusals.json)
//
// Which error bodies mean "this model does not take the effort setting": a refusal is retried once without the field,
// anything else fails the send as usual. 3917.8 (and iOS 58) matched snake case only, so xAI's camel-case 400 failed
// every send to grok-build-0.1. Each verdict in the fixture is declared, not computed, so a matcher change that drops a
// row fails here — and iOS runs the same rows.
package ai.teemoon.inference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConformanceRefusalsTest {
    private val rows = Json.parseToJsonElement(javaClass.getResourceAsStream("/refusals.json")!!.reader().readText())
        .jsonObject["vectors"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonObject }

    @Test fun everyRowGetsItsVerdict() {
        assertTrue("refusals.json has no rows", rows.isNotEmpty())
        val wrong = rows.filter { row ->
            val status = row["status"]!!.jsonPrimitive.int
            val body = row["body"]!!.jsonPrimitive.content
            ReasoningEffort.isRefusal(status, body) != row["refusal"]!!.jsonPrimitive.boolean
        }.map { "${it["note"]!!.jsonPrimitive.content}: ${it["body"]!!.jsonPrimitive.content}" }
        assertEquals("rows the matcher gets wrong", emptyList<String>(), wrong)
    }
}
