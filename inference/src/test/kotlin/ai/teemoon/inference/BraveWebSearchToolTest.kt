// ports: teemoonTests/BraveWebSearchToolTests.swift @ v1.0.1
//        + UnconfiguredSearchTests.swift (the keyless-tool suite only)
//        + HTTPSeamTests.swift (BraveKeyCheckTests)
//        + LLMErrorTests.swift (groundingMessage)
//        + FaultTests.swift (GroundingSource structured-data)
package ai.teemoon.inference

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BraveWebSearchToolTest {

    // MARK: - parseSources

    @Test fun parseSourcesValidXML() {
        val xml = """
        <source index="1">
        <url>https://example.com/page1</url>
        <title>Example Page</title>
        <content>Some content about the topic.</content>
        </source>
        <source index="2">
        <url>https://other.com/page2</url>
        <title>Other Page</title>
        <content>More content here.</content>
        </source>
        """.trimIndent()
        val sources = BraveWebSearchTool.parseSources(xml)
        assertEquals(2, sources.size)
        assertEquals("https://example.com/page1", sources[0].url)
        assertEquals("Example Page", sources[0].title)
        assertEquals("Some content about the topic.", sources[0].snippet)
        assertEquals("example.com", sources[0].domain)
        assertEquals("https://other.com/page2", sources[1].url)
    }

    @Test fun parseSourcesEmptyString() {
        assertTrue(BraveWebSearchTool.parseSources("").isEmpty())
    }

    @Test fun parseSourcesNoMatchingTags() {
        assertTrue(BraveWebSearchTool.parseSources("Just some plain text.").isEmpty())
    }

    @Test fun parseSourcesToleratesPublishedElement() {
        val xml = """
        <source index="1">
        <url>https://example.com/news</url>
        <title>Some News</title>
        <published>Saturday, July 25, 2026</published>
        <content>The news itself.</content>
        </source>
        """.trimIndent()
        val sources = BraveWebSearchTool.parseSources(xml)
        assertEquals(1, sources.size)
        assertEquals("Some News", sources[0].title)
        assertEquals("The news itself.", sources[0].snippet)
    }

    @Test fun parseSourcesMultilineContent() {
        val xml = """
        <source index="1">
        <url>https://example.com</url>
        <title>Test</title>
        <content>Line one.
        Line two.
        Line three.</content>
        </source>
        """.trimIndent()
        val sources = BraveWebSearchTool.parseSources(xml)
        assertEquals(1, sources.size)
        assertTrue(sources[0].snippet.contains("Line one."))
        assertTrue(sources[0].snippet.contains("Line three."))
    }

    // MARK: - parseMarkdownLinks

    @Test fun parseMarkdownLinksValidLinks() {
        val text = """
        According to [Example](https://example.com/page), the answer is yes.
        Also see [Other Site](https://other.com/info).
        """.trimIndent()
        val sources = BraveWebSearchTool.parseMarkdownLinks(text)
        assertEquals(2, sources.size)
        assertEquals("https://example.com/page", sources[0].url)
        assertEquals("Example", sources[0].title)
        assertEquals("https://other.com/info", sources[1].url)
    }

    @Test fun parseMarkdownLinksDeduplicatesURLs() {
        val sources = BraveWebSearchTool.parseMarkdownLinks(
            "See [First](https://example.com) and [Second](https://example.com).",
        )
        assertEquals(1, sources.size)
        assertEquals("First", sources[0].title)
    }

    @Test fun parseMarkdownLinksNoLinks() {
        assertTrue(BraveWebSearchTool.parseMarkdownLinks("No links here.").isEmpty())
    }

    @Test fun parseMarkdownLinksHttpAndHttps() {
        val sources = BraveWebSearchTool.parseMarkdownLinks(
            "[Secure](https://s.com) and [Insecure](http://i.com)",
        )
        assertEquals(2, sources.size)
    }

    // MARK: - GroundingSource Codable

    @Test fun groundingSourceCodableRoundtrip() {
        val source = GroundingSource("https://test.com", "test.com", "Test", "A snippet")
        val encoded = GroundingSource.encodedJSON(listOf(source))!!
        val decoded = Json.decodeFromString<List<GroundingSource>>(encoded)
        assertEquals(source, decoded.single())
    }

    @Test fun groundingSourceDecodableMissingSnippetDefaultsToEmpty() {
        val json = """{"url": "https://test.com", "domain": "test.com", "title": "Test"}"""
        val decoded = Json.decodeFromString<GroundingSource>(json)
        assertEquals("", decoded.snippet)
    }

    @Test fun groundingSourceIdIsURL() {
        assertEquals(
            "https://unique.com",
            GroundingSource("https://unique.com", "unique.com", "T").id,
        )
    }

    // MARK: - Live response decoding
    // Fixtures reconstructed from the Swift assertions, not copied from
    // teemoonTests/Fixtures/brave_llm_context*.json (no kit class).

    @Test fun decodesLiveResponseGroundingAndSources() {
        val r = BraveWebSearchTool.decode(WEATHER)
        assertEquals(3, r.grounding.generic.size)
        assertTrue(r.grounding.map.isNullOrEmpty())
        for (page in r.grounding.generic) {
            assertNotNull(r.sources?.get(page.url))
        }
        val first = r.sources!![r.grounding.generic[0].url]!!
        assertEquals("www.weatherusa.net", first.hostname)
        assertNull(first.displayDate)
    }

    @Test fun decodesLiveResponseAgeIsThreeRenderings() {
        val r = BraveWebSearchTool.decode(NEWS)
        val dated = r.sources!!.values.first { (it.age?.size ?: 0) == 3 }
        val age = dated.age!!
        assertTrue(age[1].startsWith("2026-"))
        assertTrue(age[2].endsWith("days ago"))
        assertEquals(age[0], dated.displayDate)
    }

    @Test fun contextXMLCarriesPublishedDateFromTopLevelSources() {
        val xml = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(NEWS))
        assertTrue(xml.contains("<published>"))
        assertEquals(3, BraveWebSearchTool.parseSources(xml).size)
    }

    // MARK: - JSON-LD passages reaching the model

    @Test fun readableSnippetUnpacksTheWeatherTableIntoProse() {
        val blob = """{"title":"Forecast for New York","table":[{"DEWPOINT":"40°"},{"HUMIDITY":"93%"}]}"""
        val out = GroundingSource.readableSnippet(blob)
        assertTrue(out.contains("DEWPOINT: 40°"))
        assertTrue(out.contains("HUMIDITY: 93%"))
        assertFalse(out.contains("{"))
        assertFalse(out.contains("\""))
    }

    @Test fun readableSnippetLeavesProseAlone() {
        val prose = "New York will be cloudy with a high near 52."
        assertEquals(prose, GroundingSource.readableSnippet(prose))
    }

    @Test fun readableSnippetIsDeterministicAcrossRuns() {
        val blob = """{"a":"1","b":"2","c":"3","d":"4","e":"5"}"""
        val renders = (0 until 25).map { GroundingSource.readableSnippet(blob) }.toSet()
        assertEquals(1, renders.size)
    }

    @Test fun readableSnippetSalvagesTruncatedJSON() {
        val cut = """{"@type":"NewsArticle","headline":"Nvidia unveils Korea AI push","articleBody":"The chipmaker said"""
        val out = GroundingSource.readableSnippet(cut)
        assertTrue(out.contains("Nvidia unveils Korea AI push"))
        assertFalse(out.contains("@type"))
    }

    @Test fun readableSnippetEmptyWhenNothingSurvives() {
        assertTrue(GroundingSource.readableSnippet("{}").isEmpty())
        assertTrue(GroundingSource.readableSnippet("   ").isEmpty())
    }

    @Test fun contextXMLSendsTheModelFactsNotJSON() {
        val xml = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(WEATHER))
        assertTrue(xml.contains("DEWPOINT: 40°"))
        for (source in BraveWebSearchTool.parseSources(xml)) {
            assertFalse(
                "a JSON-LD blob reached the model: ${source.snippet.take(80)}",
                GroundingSource.isStructuredData(source.snippet),
            )
        }
    }

    @Test fun contextXMLOmitsPublishedWhenBraveHasNoDate() {
        val xml = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(WEATHER))
        assertFalse(xml.contains("<published>"))
        val sources = BraveWebSearchTool.parseSources(xml)
        assertEquals(3, sources.size)
        assertEquals("www.weatherusa.net", sources[0].domain)
        assertTrue(sources.all { it.snippet.isNotEmpty() })
    }

    @Test fun contextXMLFallsBackToTopLevelSnippetWhenNoPassages() {
        val json = """
        {"grounding": {"generic": [{"url": "https://a.com/p", "title": "", "snippets": []}], "map": []},
         "sources": {"https://a.com/p": {"title": "Page A", "hostname": "a.com", "age": [], "snippet": "Summary of page A."}}}
        """.trimIndent()
        val xml = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(json))
        val sources = BraveWebSearchTool.parseSources(xml)
        assertEquals(1, sources.size)
        assertEquals("Page A", sources[0].title)
        assertEquals("Summary of page A.", sources[0].snippet)
    }

    @Test fun contextXMLDropsPagesWithNoContentAnywhere() {
        val json = """
        {"grounding": {"generic": [{"url": "https://a.com/p", "title": "A", "snippets": []}], "map": []},
         "sources": {}}
        """.trimIndent()
        val xml = BraveWebSearchTool.contextXML(BraveWebSearchTool.decode(json))
        assertTrue(xml.isEmpty())
    }

    // MARK: - GroundingBudget

    @Test fun theBudgetScalesWithQueryBreadth() {
        assertTrue(GroundingBudget.maxTokens(3) < GroundingBudget.maxTokens(50))
        assertEquals(2048, GroundingBudget.maxTokens(3))
        assertEquals(4096, GroundingBudget.maxTokens(10))
        assertEquals(10, GroundingBudget.urlCap(10))
    }

    @Test fun theBudgetNeverExceedsTheModelsRequestedCount() {
        for (count in listOf(1, 2, 5, 20, 50)) {
            assertTrue(GroundingBudget.urlCap(count) <= count)
        }
        assertEquals(10, GroundingBudget.urlCap(50))
    }

    @Test fun everyProviderGetsTheFullBudget() {
        // There is no per-provider budget left to assert: BraveWebSearchTool
        // takes no budget argument at all. The name is the construction pin.
        assertEquals("web_search", BraveWebSearchTool(apiKey = "k").name)
    }

    /** Android-only: the on-device cap is the measured phone-prefill number. */
    @Test fun onDeviceBudgetIsThePhonePrefillCap() {
        assertEquals(5, GroundingBudget.urlCap(50, onDevice = true))
        assertEquals(5, GroundingBudget.urlCap(5, onDevice = true))
        assertEquals(3, GroundingBudget.urlCap(3, onDevice = true))
        assertEquals(2048, GroundingBudget.maxTokens(50, onDevice = true))
        assertEquals(2048, GroundingBudget.maxTokens(3, onDevice = true))
    }

    /**
     * Android-only: the 2048 cap was measured against the general bundle's 8192 window. A Tensor bundle's window is
     * 4096, compiled in, and there 2048 of sources plus the preface, the question and the model's own answer met the
     * ceiling mid-sentence (Pixel 10 Pro, 2026-10-12: last prefill 2405, decode stopped at 747). A quarter of the window.
     */
    @Test fun onDeviceBudgetScalesToTheBundlesWindow() {
        assertEquals(2048, GroundingBudget.maxTokens(50, onDevice = true, contextTokens = 8192))
        assertEquals(1024, GroundingBudget.maxTokens(50, onDevice = true, contextTokens = 4096))
        assertEquals("Brave rejects an ask under 1024 (HTTP 422), so a tiny window still asks the floor", 1024, GroundingBudget.maxTokens(50, onDevice = true, contextTokens = 2048))
        assertEquals("never above the measured phone cap", 2048, GroundingBudget.maxTokens(50, onDevice = true, contextTokens = 32768))
        assertEquals("a cloud model's budget ignores the window", 4096, GroundingBudget.maxTokens(10, onDevice = false, contextTokens = 4096))
        assertEquals(1024, BraveWebSearchTool(apiKey = "k", onDevice = true, contextTokens = 4096).payloadTokenCap(50))
    }

    // MARK: - GroundingSource structured-data (FaultTests)

    @Test fun groundingSourceIsStructuredData() {
        assertTrue(GroundingSource.isStructuredData("{\"key\": \"value\"}"))
        assertTrue(GroundingSource.isStructuredData("[1, 2, 3]"))
        assertTrue(GroundingSource.isStructuredData("something \"@type\": \"Organization\""))
        assertFalse(GroundingSource.isStructuredData("Just plain text"))
        assertFalse(GroundingSource.isStructuredData(""))
    }

    @Test fun groundingSourceDisplaySnippetHidesStructuredData() {
        val hidden = GroundingSource("https://a.com", "a.com", "A", "{\"@type\": \"WebPage\"}")
        assertEquals("", hidden.displaySnippet)
        val shown = GroundingSource("https://a.com", "a.com", "A", "A useful snippet")
        assertEquals("A useful snippet", shown.displaySnippet)
    }

    @Test fun citedOrAllPrefersMarkdownLinksAndEnrichesSnippets() {
        val grounding = listOf(
            GroundingSource("https://a.com", "a.com", "A", "snippet-a"),
            GroundingSource("https://b.com", "b.com", "B", "snippet-b"),
        )
        val cited = GroundingSource.citedOrAll(
            "See [A](https://a.com) for more.",
            grounding,
        )
        assertEquals(listOf("https://a.com"), cited.map { it.url })
        assertEquals("snippet-a", cited.single().snippet)
        assertEquals(grounding, GroundingSource.citedOrAll("no links", grounding))
    }

    // MARK: - Unconfigured search (the keyless tool suite)

    @Test fun reportsQuery() = runTest {
        val seen = ArrayList<String>()
        val tool = BraveWebSearchTool(apiKey = "", onUnconfiguredSearch = { seen.add(it) })
        tool.call("weather in tokyo today")
        assertEquals(listOf("weather in tokyo today"), seen)
    }

    @Test fun doesNotThrow() = runTest {
        val result = BraveWebSearchTool(apiKey = "", onUnconfiguredSearch = {}).call("current price of gold")
        assertTrue(result.isNotEmpty())
    }

    @Test fun toolResultShape() = runTest {
        val lower = BraveWebSearchTool(apiKey = "", onUnconfiguredSearch = {})
            .call("who won yesterday")
            .lowercase()
        assertTrue(lower.contains("setting, not a failure"))
        assertTrue(lower.contains("own knowledge"))
        assertFalse(lower.contains("turn on web"))
        assertFalse(lower.contains("teemoon"))
        assertTrue(lower.contains("do not recommend other"))
        assertTrue(lower.contains("run a search themselves"))
        for (failureWord in listOf("did not run", "unavailable", "no results", "could not", "failed", "error")) {
            assertFalse("reads as a failed tool call: $failureWord", lower.contains(failureWord))
        }
    }

    @Test fun emptyQueryDoesNotOffer() = runTest {
        val seen = ArrayList<String>()
        BraveWebSearchTool(apiKey = "", onUnconfiguredSearch = { seen.add(it) }).call("")
        assertTrue(seen.isEmpty())
    }

    // MARK: - Key check (HTTPSeamTests)

    @Test fun http200IsValid() = runTest {
        val result = BraveWebSearchTool.checkKey("sk-real", fetch = { _, _, _ -> 200 to ByteArray(0) })
        assertEquals(BraveWebSearchTool.KeyCheck.VALID, result.kind)
    }

    @Test fun http401IsRejected() = runTest {
        val result = BraveWebSearchTool.checkKey("sk-bad", fetch = { _, _, _ -> 401 to ByteArray(0) })
        assertEquals(BraveWebSearchTool.KeyCheck.REJECTED, result.kind)
    }

    @Test fun emptyKeyIsRejectedWithoutARequest() = runTest {
        var called = false
        val result = BraveWebSearchTool.checkKey("   ", fetch = { _, _, _ ->
            called = true
            200 to ByteArray(0)
        })
        assertEquals(BraveWebSearchTool.KeyCheck.REJECTED, result.kind)
        assertFalse(called)
    }

    /** Android-only: 429 is out-of-credit, not rejected — saving the key is correct. */
    @Test fun http429IsOutOfCredit() = runTest {
        val result = BraveWebSearchTool.checkKey("sk", fetch = { _, _, _ -> 429 to ByteArray(0) })
        assertEquals(BraveWebSearchTool.KeyCheck.OUT_OF_CREDIT, result.kind)
    }

    @Test fun transportErrorIsUnreachable() = runTest {
        val result = BraveWebSearchTool.checkKey("sk", fetch = { _, _, _ -> error("offline") })
        assertEquals(BraveWebSearchTool.KeyCheck.UNREACHABLE, result.kind)
    }

    /** Brave's answer to a key it cannot parse (a 16-character paste, measured 2026-09-17) is a 422, not a 401. */
    @Test fun checkKeyTreatsBravesInvalidTokenAsRejected() = runTest {
        val body = """{"error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"The provided subscription token is invalid.","meta":{"component":"authentication"},"status":422},"type":"ErrorResponse"}""".toByteArray()
        val result = BraveWebSearchTool.checkKey("abcdefghijklmnop", fetch = { _, _, _ -> 422 to body })
        assertEquals(BraveWebSearchTool.KeyCheck.REJECTED, result.kind)
        // Any other 422 is still Brave's problem, not the key's.
        val other = BraveWebSearchTool.checkKey("sk", fetch = { _, _, _ -> 422 to """{"error":{"code":"VALIDATION","detail":"count must be <= 50"}}""".toByteArray() })
        assertEquals(BraveWebSearchTool.KeyCheck.BRAVE_UNAVAILABLE, other.kind)
    }

    @Test fun groundingMessageNamesBravesReasonAndAnInvalidTokenPointsAtTheKey() {
        val msg = LlmError.groundingMessage(422, """{"error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"The provided subscription token is invalid.","meta":{"component":"authentication"},"status":422},"type":"ErrorResponse"}""")
        assertTrue(msg, msg.startsWith("Brave web search rejected the API key (HTTP 422): The provided subscription token is invalid."))
        assertTrue(msg, msg.endsWith("Check your Brave API key in Settings → Search."))
        val plan = LlmError.groundingMessage(400, """{"type":"ErrorResponse","error":{"status":400,"detail":"The option is not included in the plan.","code":"OPTION_NOT_IN_PLAN"}}""")
        assertEquals("Brave web search returned an error (HTTP 400): The option is not included in the plan.", plan)
        assertEquals("Brave web search returned an error (HTTP 500).", LlmError.groundingMessage(500, "<html>oops</html>"))
        assertEquals("Brave web search returned an error (HTTP 422).", LlmError.groundingMessage(422))
    }

    // MARK: - groundingMessage

    @Test fun groundingMessage401() {
        val msg = LlmError.groundingMessage(401)
        assertTrue(msg.contains("Brave"))
        assertTrue(msg.contains("API key"))
    }

    @Test fun groundingMessage402() {
        assertTrue(LlmError.groundingMessage(402).contains("credits"))
    }

    @Test fun groundingMessage429() {
        assertTrue(LlmError.groundingMessage(429).contains("rate limit"))
    }

    // MARK: - fetch seam

    @Test fun fetchPassesTheExplicitTimeoutAndOnDeviceCaps() = runTest {
        var timeoutMs = 0L
        var url = ""
        val tool = BraveWebSearchTool(
            apiKey = "sk",
            onDevice = true,
            fetch = { u, _, t ->
                url = u
                timeoutMs = t
                200 to WEATHER.toByteArray()
            },
        )
        tool.call("weather 10001", count = 50)
        assertEquals(BraveWebSearchTool.SEARCH_TIMEOUT_MS, timeoutMs)
        assertTrue(url.contains("maximum_number_of_urls=5"))
        assertTrue(url.contains("maximum_number_of_tokens=2048"))
        assertTrue(url.contains("count=50"))
    }

    @Test fun http401ThrowsTheGroundingMessage() = runTest {
        val tool = BraveWebSearchTool(
            apiKey = "sk",
            fetch = { _, _, _ -> 401 to "nope".toByteArray() },
        )
        try {
            tool.call("q")
            fail("401 must throw")
        } catch (e: LlmError) {
            assertEquals(401, e.httpStatus)
            assertEquals(LlmError.groundingMessage(401), e.userMessage)
        }
    }

    @Test fun queryIsTruncatedTo400() = runTest {
        val seen = ArrayList<String>()
        val long = "x".repeat(500)
        BraveWebSearchTool(apiKey = "", onUnconfiguredSearch = { seen.add(it) }).call(long)
        assertEquals(400, seen.single().length)
    }

    companion object {
        /**
         * Minimal weather-shaped payload that satisfies the live-fixture
         * assertions: 3 generic pages keyed into `sources`, first hostname
         * weatherusa, empty age, a JSON-LD forecast table that unpacks to
         * `DEWPOINT: 40°`.
         */
        private val WEATHER = """
        {"grounding":{"generic":[
          {"url":"https://www.weatherusa.net/forecast","title":"NY","snippets":["{\"title\":\"Forecast for New York\",\"table\":[{\"DEWPOINT\":\"40°\"},{\"HUMIDITY\":\"93%\"}]}"]},
          {"url":"https://b.example/2","title":"B","snippets":["plain two"]},
          {"url":"https://c.example/3","title":"C","snippets":["plain three"]}
        ],"map":[]},
        "sources":{
          "https://www.weatherusa.net/forecast":{"title":"NY","hostname":"www.weatherusa.net","age":[],"snippet":"s"},
          "https://b.example/2":{"title":"B","hostname":"b.example","age":[],"snippet":"s"},
          "https://c.example/3":{"title":"C","hostname":"c.example","age":[],"snippet":"s"}
        }}
        """.trimIndent()

        private val NEWS = """
        {"grounding":{"generic":[
          {"url":"https://news.example/a","title":"A","snippets":["alpha"]},
          {"url":"https://news.example/b","title":"B","snippets":["beta"]},
          {"url":"https://news.example/c","title":"C","snippets":["gamma"]}
        ],"map":[]},
        "sources":{
          "https://news.example/a":{"title":"A","hostname":"news.example","age":["Saturday, July 25, 2026","2026-07-25","0 days ago"],"snippet":"s"},
          "https://news.example/b":{"title":"B","hostname":"news.example","age":[],"snippet":"s"},
          "https://news.example/c":{"title":"C","hostname":"news.example","age":[],"snippet":"s"}
        }}
        """.trimIndent()
    }
}
