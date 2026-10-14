// ports: teemoonTests/ToolCallDebugRowFormatTests.swift @ v1.0.1, teemoonTests/ToolCallDisplayTruncationTests.swift @ v1.0.1
package ai.teemoon.inference

import ai.teemoon.inference.ToolCallDebugFormat.toolCallSignature
import ai.teemoon.inference.ToolCallDebugFormat.toolCallSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallDebugRowFormatTest {
    private val oneSource = """<source index="1"><url>https://example.com/a</url><title>A</title><content>body</content></source>"""

    @Test fun signature_webSearch_sameFormatWithAndWithoutOptionalArgs() {
        val bare = toolCallSignature("web_search", """{"query":"what is an nyc city bodega"}""", oneSource)
        val withCount = toolCallSignature("web_search", """{"query":"what is an nyc city bodega","count":5}""", oneSource)
        assertEquals("web_search -> 1", bare)
        assertEquals("web_search · n:5 -> 1", withCount)
        assertFalse(bare.contains("bodega"))
    }
    @Test fun signature_webSearch_queryOnly_reportsFetchedCount() {
        assertTrue(toolCallSignature("web_search", """{"query":"tokyo population"}""", oneSource).endsWith("-> 1"))
    }
    @Test fun signature_otherTool_keepsSingleFieldShorthand() {
        assertEquals("""lookup("bodega")""", toolCallSignature("lookup", """{"term":"bodega"}"""))
    }
    @Test fun subtitle_webSearch_showsQuery() {
        assertEquals("\"nyc bodega\"", toolCallSubtitle("web_search", """{"query":"nyc bodega","count":5}"""))
    }
    @Test fun subtitle_webSearch_noQuery_fallsBackToRawArguments() {
        assertEquals("""{"count":5}""", toolCallSubtitle("web_search", """{"count":5}"""))
    }
    @Test fun subtitle_webSearch_unparseableArguments_fallsBackToRawArguments() {
        assertEquals("{not json", toolCallSubtitle("web_search", "{not json"))
    }
    @Test fun subtitle_webSearch_emptyArguments_isNull() { assertNull(toolCallSubtitle("web_search", "")) }
    @Test fun subtitle_otherTool_isNull() { assertNull(toolCallSubtitle("lookup", """{"query":"x"}""")) }

    @Test fun compactRequestBody_replacesHexBlobsWithEdgesAndSize() {
        val hex = "ab".repeat(40)
        assertEquals("{\"content\":\"abababab…abababab\" (40 bytes, encrypted)}", ToolCallDebugFormat.compactRequestBody("{\"content\":\"$hex\"}"))
        assertEquals("{\"id\":\"abcdef\"}", ToolCallDebugFormat.compactRequestBody("{\"id\":\"abcdef\"}"))
    }
    @Test fun sourcesJSONString_isNullWithoutSources_andBraveShapedWithThem() {
        assertNull(ToolCallDebugFormat.sourcesJSONString("no sources here"))
        val s = ToolCallDebugFormat.sourcesJSONString(oneSource)!!
        assertTrue(s.contains("\"grounding\"")); assertTrue(s.contains("\"generic\"")); assertTrue(s.contains("https://example.com/a"))
    }
}

class ToolCallDisplayTruncationTest {
    @Test fun shortResultsPassThroughUntouched() {
        assertEquals("three sources", ToolCallRecord("web_search", "{}", "three sources").displayResult)
    }
    @Test fun longResultsAreBoundedWithAnHonestTrailer() {
        val shown = ToolCallRecord("web_search", "{}", "x".repeat(40_000)).displayResult
        assertTrue("the mounted row must stay bounded — an unbounded row is the blank screen", shown.length < ToolCallRecord.DISPLAY_CAP + 200)
        assertTrue(shown.contains("display truncated"))
        assertTrue("the trailer must say how much exists", shown.contains("40000"))
    }
    @Test fun theBoundaryItselfIsNotTruncated() {
        val exact = "y".repeat(ToolCallRecord.DISPLAY_CAP)
        assertEquals("at the cap is short enough — no trailer noise", exact, ToolCallRecord("web_search", "{}", exact).displayResult)
    }
}

class ApiErrorMessageTest {
    @Test fun providerBodyMessageIsSurfaced() {
        assertEquals("near.ai (HTTP 404): model not found — try another", LlmError.apiErrorMessage("""{"error":{"message":"model not found","detail":"try another"}}""", 404, "near.ai"))
    }
    @Test fun metaErrorsAreJoined() {
        assertEquals("x (HTTP 422): bad — worse", LlmError.apiErrorMessage("""{"error":{"meta":{"errors":[{"msg":"bad"},{"msg":"worse"}]}}}""", 422, "x"))
    }
    @Test fun noBodyFallsBackToTheStatusLine() {
        assertEquals("x rejected the request (HTTP 401). Your API key may be invalid or missing.", LlmError.apiErrorMessage("", 401, "x"))
        assertEquals("x server error (HTTP 503). The service may be temporarily unavailable.", LlmError.apiErrorMessage("<html>", 503, "x"))
    }

    // LLMErrorTests @ v1.0.4 — the problem-details body.

    /** NVIDIA lists models its chat endpoint no longer serves; the 404 is the model, not the URL — and the ids stay out. */
    @Test fun nvidiaUnservedModel404NamesTheModelNotTheURL() {
        val body = """{"status":404,"title":"Not Found","detail":"Function 'b0fcd392-e905-4ab4-8eb9-aeae95c30b37': Not found for account 'iqVNM_S9wj9BaKVMYqRKW'"}"""
        val msg = LlmError.apiErrorMessage(body, 404, "nvidia")
        assertTrue(msg.contains("does not serve this model")); assertTrue(msg.contains("404"))
        assertFalse(msg.contains("URL")); assertFalse(msg.contains("b0fcd392")); assertFalse(msg.contains("iqVNM"))
    }
    @Test fun problemDetailsBodyShowsItsDetail() {
        assertEquals("nvidia (HTTP 429): Rate limit exceeded for this model.",
            LlmError.apiErrorMessage("""{"status":429,"title":"Too Many Requests","detail":"Rate limit exceeded for this model."}""", 429, "nvidia"))
    }
    @Test fun anErrorEnvelopeStillWinsOverProblemDetails() {
        assertEquals("x (HTTP 400): bad request", LlmError.apiErrorMessage("""{"error":{"message":"bad request"},"detail":"ignored"}""", 400, "x"))
    }
}
