// ports: teemoonTests/ConformanceRedactionVectors.swift @ v1.1.0 (the consuming half, against conformance/redaction.json)
//
// Audit gardener #3 (the maintainer 2026-10-25): the copy path of a debug card. iOS took Android's rule — a credential is a
// standard name, a provider's configured auth header name, or a value holding a stored key — and both suites now run
// the same hand-declared cases. A stored key under 8 characters is ignored; header names match whatever their case.
package ai.teemoon.app

import ai.teemoon.providers.CredentialHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ConformanceRedactionTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResourceAsStream("/redaction.json")!!.reader().readText()).jsonObject["vectors"]!!.jsonObject
    private val before = DebugHeaderRedaction.storedSecrets

    @After fun restore() { DebugHeaderRedaction.storedSecrets = before }

    @Test fun everyCaseCopiesAsDeclared() {
        val secrets = vectors["storedSecrets"]!!.jsonArray.map { it.jsonPrimitive.content }
        DebugHeaderRedaction.storedSecrets = { secrets }
        vectors["authHeaderNames"]!!.jsonArray.forEach { CredentialHeaders.register(it.jsonPrimitive.content) }
        val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(8, cases.size)
        val wrong = cases.mapNotNull { c ->
            val input = c["input"]!!.jsonPrimitive.content
            val got = when (val kind = c["kind"]!!.jsonPrimitive.content) {
                "header" -> DebugHeaderRedaction.copyValue(input, c["name"]!!.jsonPrimitive.content)
                "url" -> DebugHeaderRedaction.copyURLString(input)
                "text" -> DebugHeaderRedaction.scrubStoredSecrets(input)
                else -> error("unknown kind $kind")
            }
            val expected = c["expected"]!!.jsonPrimitive.content
            if (got == expected) null else "${c["note"]!!.jsonPrimitive.content}: expected <$expected>, got <$got>"
        }
        assertEquals("cases the copy path gets wrong", emptyList<String>(), wrong)
    }
}
