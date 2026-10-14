// ports: teemoonTests/ProviderCatalogAdapterTests.swift @ v1.0.4 (realAnswerSplitsIntoProseAndSources) and
//        teemoonTests/BraveWebSearchToolTests.swift @ v1.0.1 (the context-XML shape) — over the kit's `brave` class (kit 0.4.0)
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BraveKitTest {
    private val v by lazy { Json.parseToJsonElement(BraveKitTest::class.java.getResourceAsStream("/brave.json")!!.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonObject }
    private fun s(k: String) = v[k]!!.jsonPrimitive.content

    @Test fun realAnswerSplitsIntoProseAndSources_byteForByteWithIOS() {
        val raw = s("answersContent")
        assertTrue(raw.contains("<citation>")); assertTrue(raw.contains("<usage>"))
        val split = BraveAnswersFormat.split(raw)
        assertFalse(split.visible.contains("<citation>")); assertFalse(split.visible.contains("<usage>")); assertFalse(split.visible.contains("imgs.search.brave.com"))
        assertTrue(split.visible.startsWith("**Paris** is the capital")); assertTrue(split.visible.length < raw.length / 4)
        assertEquals(s("visible"), split.visible)
        val expected = v["sources"]!!.jsonArray.map { it.jsonObject }.map { listOf(it["url"]!!.jsonPrimitive.content, it["domain"]!!.jsonPrimitive.content, it["title"]!!.jsonPrimitive.content, it["snippet"]!!.jsonPrimitive.content) }
        assertEquals(expected, split.sources.map { listOf(it.url, it.domain, it.title, it.snippet) })
        assertTrue(split.sources.size >= 5); assertEquals(split.sources.size, split.sources.map { it.url }.toSet().size)
    }
    /** The result the model reads: the kit's pinned XML, then the citation ask — and the same sources parse out of it. */
    @Test fun theSearchResultCarriesTheCitationAskAfterThePinnedXml() = runTest {
        val tool = BraveWebSearchTool(apiKey = "k", fetch = { _, _, _ -> 200 to s("llmContext").toByteArray() })
        val result = tool.call("paris")
        assertEquals(s("llmContextXML") + BraveWebSearchTool.CITATION_ASK, result)
        assertEquals(BraveWebSearchTool.parseSources(from = s("llmContextXML")), BraveWebSearchTool.parseSources(from = result))
    }
    @Test fun contextXMLMatchesIOSForBothRecordedResponses() {
        assertEquals(s("llmContextXML"), BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(s("llmContext"))))
        val newsActual = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(s("llmContextNews")))
        assertEquals(s("llmContextNewsXML"), newsActual)
        assertEquals(s("llmContextNewsXMLWithoutPublished"), BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(s("llmContextNews")), includePublished = false))
    }
}
